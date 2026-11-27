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
            {0, N, N, 150, 150},          // nothing reported yet: the floor
            {1, 40, 120, 150, 150},       // needs under the floor
            {2, 40, 210, 212, 212},       // a slow receiver: up at once
            {3, 40, 230.5, 232.5, 232.5},
            {4, 40, 200, 232.5, 232.5},   // 10 ms below for less than 30 s: stays
            {33, 40, 200, 232.5, 232.5},
            {34, 40, 200, 231.5, 231.5},  // 30 s below: down by 1 ms a second
            {35, 40, 200, 230.5, 230.5},
            {36, 40, 229, 230.5, 230.5},  // within 10 ms again: holds
            {37, 40, 240, 242, 242},
    };

    static final double[][] INTERACTIVE = {
            {0, 30, 15, 0, 30},           // the speaker is faster than the picture: it waits for the picture
            {1, 31, 15, 0, 30},           // 1 ms later: not a rise beyond 1 ms, held
            {2, 33, 15, 0, 33},
            {3, 25, 15, 0, 33},           // the picture got faster: 10 s before it follows
            {13, 25, 15, 0, 32},
            {14, 25, 70, 50, 70},         // a Bluetooth speaker: the picture waits only for the excess over 20 ms
            {15, 25, 40, 50, 70},         // and does not jump back at once
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
