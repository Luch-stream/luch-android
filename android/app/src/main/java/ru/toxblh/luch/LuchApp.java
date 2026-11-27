// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Application;

/** Only here so the log file and crash reports are kept whichever part of the app starts first. */
public class LuchApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.start(this);
        AudioFeed.restoreVolume(this); // a phone left muted by a session that was cut short
        Reports.install(this);
    }
}
