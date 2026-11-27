// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;

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
}
