// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.toxblh.luch.sync.ProbePlay;

/**
 * The acoustic calibration of sync (PROTOCOL.md 4k, docs: sync, 6.7), the receiver's part: a {"t":"probe"} from the
 * sender whose group plays here puts sweeps into the scheduled sound (sync.ProbePlay), and {"t":"probe_done"} says
 * when each was heard by this receiver's reckoning. It only ever plays when someone asks the sender to calibrate, and
 * only into a group already playing sound here. Plain logic with a JVM test; MirrorSession does the playing.
 */
final class Calibration {
    static final int MAX_SWEEPS = 16;
    /** A probe more than this far ahead of the sender's clock is a mistake, not something to keep waiting for. */
    static final long MAX_AHEAD_US = 30_000_000L;
    static final String OFF = "probe_off", NOT_PLAYING = "not_playing", NOT_SYNCED = "not_synced", LATE = "late",
            BAD = "bad_request";

    private Calibration() {
    }

    /**
     * Why a probe cannot be played now, null when it can. {@code group}: a group session of the sender that asks plays
     * here; {@code sound}: this receiver plays its sound (role audio or both, and the session has audio);
     * {@code synced}: the sender's clock is known; {@code playing}: the scheduled sound is running and not muted.
     */
    static String refusal(boolean group, boolean sound, boolean synced, boolean playing) {
        if (!group) return NOT_PLAYING;
        if (!sound) return OFF;
        if (!synced) return NOT_SYNCED;
        if (!playing) return NOT_PLAYING;
        return null;
    }

    /**
     * The sweeps a probe asks for, at {@code rate}; {@code nowSenderUs} is the sender's clock now. Throws for a probe
     * that cannot be played as asked (its "detail" for the error).
     */
    static ProbePlay sweeps(JSONObject probe, int rate, long nowSenderUs) {
        if (!"sweep".equals(probe.optString("signal", "sweep"))) throw new IllegalArgumentException("signal");
        if (!probe.has("at")) throw new IllegalArgumentException("at");
        long at = probe.optLong("at");
        double f0 = probe.optDouble("f0", 2000), f1 = probe.optDouble("f1", 6000), ms = probe.optDouble("ms", 200);
        int n = probe.optInt("n", 4);
        long gap = probe.optLong("gap_ms", 500);
        double gain = probe.optDouble("gain", 0.3);
        if (!(f0 >= 20 && f0 < f1 && f1 < rate / 2.0)) throw new IllegalArgumentException("f0, f1");
        if (!(ms >= 20 && ms <= 2000)) throw new IllegalArgumentException("ms");
        if (n < 1 || n > MAX_SWEEPS) throw new IllegalArgumentException("n");
        if (gap < 0 || gap > 10_000) throw new IllegalArgumentException("gap_ms");
        if (!(gain > 0)) throw new IllegalArgumentException("gain");
        if (at - nowSenderUs > MAX_AHEAD_US) throw new IllegalArgumentException("at: more than 30 s ahead");
        return new ProbePlay(rate, f0, f1, ms, gain, at, n, gap);
    }

    static JSONObject error(JSONObject probe, String code, String detail) {
        try {
            JSONObject e = new JSONObject().put("t", "error").put("for", "probe").put("code", code)
                    .put("id", probe.optString("id"));
            if (detail != null) e.put("detail", detail);
            return e;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The answer once the last sweep has been heard: {@code played}, the sender's µs at which each sweep's first frame
     * left the DAC by this receiver's reckoning, and where that reckoning comes from ("timestamp" or "head").
     */
    static JSONObject done(String id, long[] played, String src) {
        try {
            JSONArray p = new JSONArray();
            for (long t : played) p.put(t);
            return new JSONObject().put("t", "probe_done").put("id", id).put("played", p).put("src", src);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
