// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackGuardTest {
    @Test
    public void theSecondPressWithinTheWindowStops() {
        BackGuard g = new BackGuard();
        assertFalse("a stray Back only warns", g.press(10_000));
        assertTrue(g.press(10_000 + BackGuard.WINDOW_MS));
    }

    @Test
    public void aLatePressWarnsAgainAndAStopStartsOver() {
        BackGuard g = new BackGuard();
        assertFalse(g.press(0));
        assertFalse("too late: a new first press", g.press(BackGuard.WINDOW_MS + 1));
        assertTrue(g.press(BackGuard.WINDOW_MS + 500));
        assertFalse("after a stop the next one only warns", g.press(BackGuard.WINDOW_MS + 600));
    }
}
