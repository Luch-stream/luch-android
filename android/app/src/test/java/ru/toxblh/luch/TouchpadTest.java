// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The touch screen as a touchpad: motion, taps, two-finger taps and scrolls, tap-and-drag. */
public class TouchpadTest {
    private final List<String> out = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private double dx, dy, wy, wx;
    private Touchpad pad;

    @Before
    public void setUp() {
        pad = new Touchpad(new Touchpad.Out() {
            @Override
            public void moveBy(double x, double y) {
                dx += x;
                dy += y;
            }

            @Override
            public void wheel(double y, double x) {
                wy += y;
                wx += x;
            }

            @Override
            public void buttons(int b) {
                out.add("buttons " + b);
            }

            @Override
            public void later(Runnable r, long ms) {
                assertEquals(Touchpad.CLICK_MS, ms);
                later.add(r);
            }
        }, 10);
        pad.setGain(2);
    }

    private void runLater() {
        for (Runnable r : new ArrayList<>(later)) r.run();
        later.clear();
    }

    @Test
    public void oneFingerMovesThePointerWithTheGain() {
        pad.start(0, 100, 100);
        pad.move(100, 105, 100);   // within the slop: nothing yet
        assertEquals(0, dx, 0);
        pad.move(200, 140, 100);   // 40 px in 200 ms: slow, no acceleration beyond the floor
        pad.end(300, false);
        assertEquals(40 * 2 * Touchpad.accel(40 / 200.0), dx, 1e-9);
        assertEquals(0, dy, 1e-9);
        assertTrue("a move is not a click", out.isEmpty());
    }

    @Test
    public void fasterMotionGoesFurther() {
        assertTrue(Touchpad.accel(5) > Touchpad.accel(0.5));
        assertEquals(0.7, Touchpad.accel(0), 1e-9);
        assertEquals(2.5, Touchpad.accel(100), 1e-9);
    }

    @Test
    public void tapClicksAndLetsGoAfterAMoment() {
        pad.start(0, 100, 100);
        pad.move(50, 103, 102);
        pad.end(120, false);
        assertEquals(List.of("buttons 1"), out);
        runLater();
        assertEquals(List.of("buttons 1", "buttons 0"), out);
        assertEquals(0, dx, 0);
    }

    @Test
    public void slowTouchIsNotATap() {
        pad.start(0, 100, 100);
        pad.end(Touchpad.TAP_MS + 1, false);
        assertTrue(out.isEmpty());
    }

    @Test
    public void twoFingerTapRightClicks() {
        pad.start(0, 100, 100);
        pad.fingers(20, 2, 120, 100);
        pad.fingers(100, 1, 100, 100);
        pad.end(120, false);
        assertEquals(List.of("buttons 2"), out);
        runLater();
        assertEquals("buttons 0", out.get(1));
    }

    @Test
    public void twoFingersScrollWithTheContent() {
        pad.start(0, 100, 300);
        pad.fingers(10, 2, 120, 300);
        pad.move(50, 120, 250);   // fingers up by 50 px: the page goes on down
        pad.end(400, false);
        assertEquals(50 * 2 * Touchpad.WHEEL_PER_PX, wy, 1e-9);
        assertEquals(0, wx, 1e-9);
        assertEquals(0, dx, 0);
        assertTrue(out.isEmpty());
    }

    @Test
    public void tapThenDragHoldsTheLeftButton() {
        pad.start(0, 100, 100);
        pad.end(100, false);
        runLater();
        out.clear();
        pad.start(250, 104, 100);   // soon after, near: a drag
        assertEquals(List.of("buttons 1"), out);
        pad.move(300, 200, 100);
        assertTrue(dx > 0);
        pad.end(500, false);
        assertEquals(List.of("buttons 1", "buttons 0"), out);
        assertTrue("no click is queued after a drag", later.isEmpty());
    }

    @Test
    public void aTapLongAfterTheLastIsAClickAgain() {
        pad.start(0, 100, 100);
        pad.end(100, false);
        runLater();
        out.clear();
        pad.start(100 + Touchpad.DRAG_AFTER_TAP_MS + 1, 100, 100);
        assertTrue(out.isEmpty());
        pad.end(100 + Touchpad.DRAG_AFTER_TAP_MS + 50, false);
        assertEquals(List.of("buttons 1"), out);
    }

    @Test
    public void cancelledIsNothing() {
        pad.start(0, 100, 100);
        pad.end(50, true);
        assertTrue(out.isEmpty());
    }
}
