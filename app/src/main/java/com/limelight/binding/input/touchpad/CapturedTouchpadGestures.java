package com.limelight.binding.input.touchpad;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Feeds captured touchpad events to {@link TouchpadGestureTracker} and sends
 * what it recognises to the host.
 *
 * Only events from a touchpad the stream has captured are considered. A mouse,
 * a touchscreen, a stylus or a gamepad touchpad all reach the caller through
 * other paths and are left untouched.
 *
 * Everything here runs on the input path, so it allocates nothing per event:
 * the tracker, the handler and the button-release callbacks are built once.
 */
public final class CapturedTouchpadGestures implements TouchpadGestureTracker.MouseOutput {

    /**
     * How long a tap holds the button down. Upstream's own touchscreen taps use
     * the same figure, for applications that poll rather than watch for events.
     */
    private static final long CLICK_HOLD_MS = 100;

    private final NvConnection conn;
    private final Handler handler;
    private final Runnable releaseLeft;
    private final Runnable releaseRight;

    private TouchpadGestureTracker tracker;
    private int trackedDeviceId = -1;
    private long tapClickHeldUntil;

    /**
     * The finger whose position stands for the gesture. It is followed by id
     * rather than by index: when a finger lifts, the pointers below it shift
     * down one index, and reading index 0 blindly would see the gesture jump
     * across the pad to wherever the other finger happens to rest.
     */
    private int trackedPointerId = -1;

    public CapturedTouchpadGestures(NvConnection conn) {
        this.conn = conn;
        this.handler = new Handler(Looper.getMainLooper());
        this.releaseLeft = new Runnable() {
            @Override
            public void run() {
                CapturedTouchpadGestures.this.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            }
        };
        this.releaseRight = new Runnable() {
            @Override
            public void run() {
                CapturedTouchpadGestures.this.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }
        };
    }

    /**
     * @return true while a multi-finger gesture is running, meaning the caller
     *         must not turn this event into pointer motion
     */
    public boolean handleEvent(MotionEvent event) {
        return handleEventForMotion(event) == MOTION_BLOCK;
    }

    /** Send this event's motion, along with anything held back before it. */
    public static final int MOTION_SEND = 0;
    /** Hold this event's motion back: the touch may still be a tap. */
    public static final int MOTION_HOLD = 1;
    /** Drop this event's motion and anything held: a tap just clicked. */
    public static final int MOTION_DISCARD = 2;
    /** No motion from these fingers: a multi-finger gesture is running. */
    public static final int MOTION_BLOCK = 3;

    private boolean clickedThisEvent;

    /**
     * @return what the caller should do with this event's pointer motion,
     *         one of the {@code MOTION_*} values
     */
    public int handleEventForMotion(MotionEvent event) {
        if (event.getSource() != InputDevice.SOURCE_TOUCHPAD) {
            return MOTION_SEND;
        }

        clickedThisEvent = false;
        TouchpadGestureTracker gestures = trackerFor(event);

        // A physical click is reported by the hardware already; recognising a
        // gesture underneath it would send the host a second, phantom click.
        if (event.getButtonState() != 0) {
            gestures.disqualify();
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackedPointerId = event.getPointerId(0);
                gestures.beginGesture(event.getEventTime(), event.getX(), event.getY());
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                gestures.fingerAdded(event.getPointerCount());
                break;

            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(trackedPointerId);
                if (index >= 0) {
                    gestures.fingersMoved(event.getPointerCount(),
                            event.getX(index), event.getY(index));
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (event.getPointerId(event.getActionIndex()) == trackedPointerId) {
                    handOverTrackedFinger(gestures, event);
                }
                gestures.fingerRemoved(event.getPointerCount() - 1);
                break;
            }

            case MotionEvent.ACTION_UP:
                gestures.endGesture(event.getEventTime());
                break;

            case MotionEvent.ACTION_CANCEL:
                gestures.cancelGesture();
                break;

            default:
                break;
        }

        if (gestures.isMultiFingerGestureActive()) {
            return MOTION_BLOCK;
        }
        if (clickedThisEvent) {
            return MOTION_DISCARD;
        }
        if (gestures.isTapCandidate(event.getEventTime())) {
            return MOTION_HOLD;
        }
        return MOTION_SEND;
    }

    /**
     * The finger standing for the gesture has lifted while others are still
     * down: pass the role to one of them before the index shift happens.
     */
    private void handOverTrackedFinger(TouchpadGestureTracker gestures, MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == event.getActionIndex()) {
                continue;
            }

            trackedPointerId = event.getPointerId(i);
            gestures.retargetFinger(event.getX(i), event.getY(i));
            return;
        }
    }

    /**
     * @return true while a tap-to-click is still holding its button down on the host
     */
    public boolean isTapClickHeld() {
        return SystemClock.uptimeMillis() < tapClickHeldUntil;
    }

    @Override
    public void click(byte button) {
        tapClickHeldUntil = SystemClock.uptimeMillis() + CLICK_HOLD_MS;
        clickedThisEvent = true;
        conn.sendMouseButtonDown(button);

        Runnable release = button == MouseButtonPacket.BUTTON_RIGHT ? releaseRight : releaseLeft;
        handler.removeCallbacks(release);
        handler.postDelayed(release, CLICK_HOLD_MS);
    }

    @Override
    public void scroll(short highResolutionUnits) {
        conn.sendMouseHighResScroll(highResolutionUnits);
    }

    /**
     * One tracker per touchpad: thresholds are scaled to the pad that is in
     * use, and a different device means a different coordinate space.
     */
    private TouchpadGestureTracker trackerFor(MotionEvent event) {
        if (tracker != null && trackedDeviceId == event.getDeviceId()) {
            return tracker;
        }

        tracker = new TouchpadGestureTracker(this, verticalRangeOf(event));
        trackedDeviceId = event.getDeviceId();
        return tracker;
    }

    private static float verticalRangeOf(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) {
            return 0;
        }

        InputDevice.MotionRange range =
                device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD);
        return range != null ? range.getRange() : 0;
    }
}
