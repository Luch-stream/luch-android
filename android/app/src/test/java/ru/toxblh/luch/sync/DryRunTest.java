// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The status screen's dry run measures its made-up recording right, whatever the seed. */
public class DryRunTest {
    @Test
    public void measuresWhatItMadeUp() {
        for (long seed = 0; seed < 20; seed++) {
            DryRun.Outcome o = DryRun.run(seed);
            assertTrue(o.why, o.accepted);
            assertEquals("seed " + seed, o.trueMs, o.measuredMs, 0.1);
            assertTrue(o.psrDb >= Probe.MIN_PSR_DB);
        }
    }
}
