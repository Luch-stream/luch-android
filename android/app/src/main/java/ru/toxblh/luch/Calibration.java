// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The acoustic calibration of sync (PROTOCOL.md 4h, docs: sync, 6.7), as a dry run only: the receiver never plays a
 * probe and never opens the microphone. With the setting on, a {"t":"probe"} is answered as if the sweeps had been
 * played, and the status screen can run the detector on a recording it synthesises in memory. The real run, which
 * makes sound, is for a daytime test by hand; nothing here starts it.
 */
final class Calibration {
    /** The setting's key: off unless someone turns it on in the status screen. */
    static final String SETTING = "sync_calibration_dry_run";

    private Calibration() {
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("luch", Context.MODE_PRIVATE).getBoolean(SETTING, false);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putBoolean(SETTING, on).apply();
    }

    /**
     * The answer to {"t":"probe"}: with the setting off, a refusal; on, a probe_done as if each sweep had reached the
     * DAC at its scheduled time, marked "dry_run". No sweep is played.
     */
    static JSONObject answer(Context context, JSONObject probe) throws JSONException {
        if (!enabled(context)) {
            return new JSONObject().put("t", "error").put("code", "probe_off").put("for", "probe");
        }
        long at = probe.optLong("at");
        int n = Math.max(1, Math.min(16, probe.optInt("n", 4)));
        long step = (probe.optLong("ms", 200) + probe.optLong("gap_ms", 500)) * 1000;
        JSONArray played = new JSONArray();
        for (int i = 0; i < n; i++) played.put(at + i * step);
        return new JSONObject().put("t", "probe_done").put("id", probe.optString("id")).put("dry_run", true)
                .put("played", played);
    }
}
