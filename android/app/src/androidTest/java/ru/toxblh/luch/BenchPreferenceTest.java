// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BenchPreferenceTest {
    private JSONObject results(double avc, double hevc, double vp9, double av1) throws Exception {
        return new JSONObject().put("video/avc", new JSONObject().put("ms", avc))
                .put("video/hevc", new JSONObject().put("ms", hevc))
                .put("video/x-vnd.on2.vp9", new JSONObject().put("ms", vp9))
                .put("video/av01", new JSONObject().put("ms", av1));
    }

    @Test public void latencyWinsOverCompression() throws Exception {
        assertEquals("video/avc", Bench.preferred(results(2, 10, 12, 15)));
    }
    @Test public void equalLatencyFavoursCompression() throws Exception {
        assertEquals("video/av01", Bench.preferred(results(5, 5, 5, 5)));
        JSONObject r = results(5, 5, 5, 5);
        r.put("video/av01", new JSONObject().put("error", "unavailable"));
        assertEquals("video/hevc", Bench.preferred(r));
    }
    @Test public void toleranceIsRelativeToFastestWithoutAccumulating() throws Exception {
        assertEquals("video/hevc", Bench.preferred(results(5, 5.5, 5.8, 6.2)));
        assertEquals("video/avc", Bench.preferred(results(5, 5.51, 6, 7)));
    }
    @Test public void missingAndInvalidMeasurementsAreIgnored() throws Exception {
        assertNull(Bench.preferred(new JSONObject()));
        JSONObject r = new JSONObject().put("video/av01", new JSONObject().put("ms", -1))
                .put("video/avc", new JSONObject().put("ms", 5));
        assertEquals("video/avc", Bench.preferred(r));
    }
}
