// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

/**
 * The touch screen as a laptop's touchpad (PROTOCOL.md, section 4h): one finger moves the pointer, a tap clicks, a
 * two-finger tap right-clicks, two fingers scroll, a tap and then a drag holds the left button. It is fed the middle of
 * the fingers that are down and how many there are, in screen pixels; no Android classes, tested on the JVM.
 */
final class Touchpad {
    interface Out {
        /** Relative motion in the sender's logical pixels. */
        void moveBy(double dx, double dy);

        /** Wheel motion, 120 per notch, positive down / right, smooth. */
        void wheel(double dy, double dx);

        void buttons(int b);

        /** Runs {@code r} on the same thread after {@code ms}. */
        void later(Runnable r, long ms);
    }

    /** A tap is shorter than this and moves less than the slop. */
    static final long TAP_MS = 250;
    /** A finger down this soon after a tap, near it, drags (holds the left button). */
    static final long DRAG_AFTER_TAP_MS = 300;
    /** How long a tap's click is held: long enough for one repeat of the state (50 ms) to carry it. */
    static final long CLICK_MS = 60;
    /** Wheel units per logical pixel of two-finger motion: a notch (120) per 15 px, as libinput counts. */
    static final double WHEEL_PER_PX = 120.0 / 15.0;

    private final Out out;
    private final float slop;
    /** The sender's logical pixels per screen pixel of the picture. */
    private double gain = 1;

    private long downAt, lastTapAt = -1;
    private float downX, downY, lastTapX, lastTapY, lastX, lastY;
    private long lastT;
    private int fingers, maxFingers;
    private boolean moved, dragging, down;

    Touchpad(Out out, float slopPx) {
        this.out = out;
        this.slop = slopPx;
    }

    void setGain(double g) {
        if (g > 0 && !Double.isInfinite(g)) gain = g;
    }

    /**
     * A little acceleration, as touchpads have: slow motion is precise, a flick crosses the screen. {@code v} in
     * screen pixels per millisecond.
     */
    static double accel(double v) {
        return Math.max(0.7, Math.min(2.5, 0.7 + v * 0.6));
    }

    /** The first finger went down. */
    void start(long t, float x, float y) {
        down = true;
        downAt = lastT = t;
        downX = lastX = x;
        downY = lastY = y;
        fingers = maxFingers = 1;
        moved = false;
        dragging = lastTapAt >= 0 && t - lastTapAt < DRAG_AFTER_TAP_MS
                && Math.hypot(x - lastTapX, y - lastTapY) < slop * 4;
        if (dragging) out.buttons(InputWire.LEFT);
    }

    /** A finger more or less: motion goes on from the new middle without a jump. */
    void fingers(long t, int n, float cx, float cy) {
        if (!down) return;
        fingers = n;
        maxFingers = Math.max(maxFingers, n);
        lastX = cx;
        lastY = cy;
        lastT = t;
    }

    void move(long t, float cx, float cy) {
        if (!down) return;
        float dx = cx - lastX, dy = cy - lastY;
        if (!moved && Math.hypot(cx - downX, cy - downY) < slop && fingers == 1) return;   // a tap's wobble
        if (!moved) {
            moved = true;
            if (fingers == 1) {
                dx = cx - downX;   // the slop is not lost: the pointer starts from where the finger did
                dy = cy - downY;
            }
        }
        long dt = Math.max(1, t - lastT);
        lastX = cx;
        lastY = cy;
        lastT = t;
        if (fingers == 1) {
            double k = gain * accel(Math.hypot(dx, dy) / dt);
            out.moveBy(dx * k, dy * k);
        } else if (fingers == 2) {
            // the content follows the fingers, as on a phone: fingers going up scroll down
            out.wheel(-dy * gain * WHEEL_PER_PX, -dx * gain * WHEEL_PER_PX);
        }
    }

    /** The last finger went up (or the gesture was cancelled). */
    void end(long t, boolean cancelled) {
        if (!down) return;
        down = false;
        if (dragging) {
            dragging = false;
            out.buttons(0);
            lastTapAt = -1;
            return;
        }
        boolean tap = !cancelled && !moved && t - downAt < TAP_MS;
        if (!tap) {
            lastTapAt = -1;
            return;
        }
        if (maxFingers == 1) {
            click(InputWire.LEFT);
            lastTapAt = t;
            lastTapX = downX;
            lastTapY = downY;
        } else if (maxFingers == 2) {
            click(InputWire.RIGHT);
            lastTapAt = -1;
        }
    }

    private void click(int button) {
        out.buttons(button);
        out.later(() -> {
            if (!dragging) out.buttons(0);
        }, CLICK_MS);
    }
}
