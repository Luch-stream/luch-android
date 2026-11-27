// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The policy's test vectors. luchd/src/sync.rs runs the very same table (keep them equal): each row is one second's
 * reports and the {D_v, D_a} expected after it.
 */
public class SenderPolicyTest {
    static final double N = Double.NaN;

    /** {now s, video pts ms, need ms, expected D_v, expected D_a}. */
    static final double[][] WATCHING = {
            {0, N, N, 150, 150},
            {1, 40, 120, 150, 150},
            {2, 40, 210, 220, 220},
            {3, 40, 230.5, 240.5, 240.5},
            {4, 40, 200, 240.5, 240.5},
            {33, 40, 200, 240.5, 240.5},
            {34, 40, 200, 210, 210},
            {35, 40, 200, 210, 210},
            {36, 40, 229, 239, 239},
            {37, 40, 240, 250, 250},
    };

    static final double[][] INTERACTIVE = {
            {0, 30, 15, 0, 30},
            {1, 31, 15, 0, 30},
            {2, 33, 15, 0, 33},
            {3, 25, 15, 0, 33},
            {13, 25, 15, 0, 25},
            {14, 25, 70, 50, 70},
            {15, 25, 40, 50, 70},
    };

    private static void run(SenderPolicy p, double[][] rows) {
        for (double[] r : rows) {
            double[] d = p.update(r[0], new double[]{r[1]}, new double[]{r[2]});
            assertEquals("D_v at " + r[0] + " s", r[3], d[0], 1e-9);
            assertEquals("D_a at " + r[0] + " s", r[4], d[1], 1e-9);
        }
    }

    @Test
    public void watching() {
        run(new SenderPolicy(SenderPolicy.Mode.WATCHING, 0, 150), WATCHING);
    }

    @Test
    public void interactive() {
        run(new SenderPolicy(SenderPolicy.Mode.INTERACTIVE, 20, 0), INTERACTIVE);
    }

    @Test
    public void headerUnitsAreTenthsOfAMillisecond() {
        assertEquals(1505, SenderPolicy.headerUnits(150.5));
        assertEquals(65535, SenderPolicy.headerUnits(9000));
        assertEquals(0, SenderPolicy.headerUnits(-3));
    }
}
