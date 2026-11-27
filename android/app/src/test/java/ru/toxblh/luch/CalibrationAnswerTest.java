// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import ru.toxblh.luch.sync.ProbePlay;

/** The receiver's side of a calibration on the wire (PROTOCOL.md 4k): who gets sweeps, and what is said back. */
public class CalibrationAnswerTest {
    static JSONObject probe() throws Exception {
        return new JSONObject("{\"t\":\"probe\",\"id\":\"p1\",\"at\":12000000,\"signal\":\"sweep\",\"f0\":2000,"
                + "\"f1\":6000,\"ms\":200,\"n\":4,\"gap_ms\":500,\"gain\":0.3}");
    }

    @Test
    public void onlyAGroupPlayingSyncedSoundHereGetsSweeps() {
        // Not this sender's group (or none at all): not playing; a group without sound here: off.
        assertEquals("not_playing", Calibration.refusal(false, true, true, true));
        assertEquals("probe_off", Calibration.refusal(true, false, true, true));
        assertEquals("not_synced", Calibration.refusal(true, true, false, true));
        // Synced, but the scheduled sound not running yet, or muted while the screen is away.
        assertEquals("not_playing", Calibration.refusal(true, true, true, false));
        assertNull(Calibration.refusal(true, true, true, true));
    }

    @Test
    public void aProbeBecomesItsSweeps() throws Exception {
        ProbePlay p = Calibration.sweeps(probe(), 48000, 10_000_000);
        assertEquals(4, p.sweeps());
        assertEquals(12_000_000, p.firstPtsUs());
        assertEquals(12_000_000 + 3 * 700_000, p.lastPtsUs());
        assertEquals(9600, p.length());
        // Left out, the fields take the protocol's values.
        ProbePlay q = Calibration.sweeps(new JSONObject().put("id", "p2").put("at", 1), 48000, 0);
        assertEquals(4, q.sweeps());
        assertEquals(1, q.firstPtsUs());
        assertEquals(1 + 3 * 700_000, q.lastPtsUs());
    }

    @Test
    public void aProbeThatCannotBePlayedAsAskedIsABadRequest() throws Exception {
        String[][] bad = {{"signal", "\"noise\""}, {"n", "0"}, {"n", "17"}, {"f1", "30000"}, {"f0", "7000"},
                {"ms", "5"}, {"gain", "0"}, {"gap_ms", "-1"}, {"at", "60000000"}};
        for (String[] b : bad) {
            JSONObject m = probe().put(b[0], new JSONObject("{\"v\":" + b[1] + "}").get("v"));
            try {
                Calibration.sweeps(m, 48000, 10_000_000);
                fail(b[0] + " " + b[1]);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
        JSONObject noAt = probe();
        noAt.remove("at");
        try {
            Calibration.sweeps(noAt, 48000, 0);
            fail("no at");
        } catch (IllegalArgumentException expected) {
            assertEquals("at", expected.getMessage());
        }
    }

    @Test
    public void theAnswersAreTheProtocols() throws Exception {
        JSONObject done = Calibration.done("p1", new long[]{12_107_000, 12_807_012}, "timestamp");
        assertEquals(4, done.length());
        assertEquals("probe_done", done.getString("t"));
        assertEquals("p1", done.getString("id"));
        assertEquals("[12107000,12807012]", done.getJSONArray("played").toString());
        assertEquals("timestamp", done.getString("src"));
        JSONObject err = Calibration.error(probe(), Calibration.NOT_SYNCED, null);
        assertEquals("error", err.getString("t"));
        assertEquals("probe", err.getString("for"));
        assertEquals("not_synced", err.getString("code"));
        assertEquals("p1", err.getString("id"));
        assertFalse(err.has("detail"));
        assertEquals("ms", Calibration.error(probe(), Calibration.BAD, "ms").getString("detail"));
    }
}
