// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Application;

/** Only here so crash reports are recorded whichever part of the app starts first. */
public class LuchApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Reports.install(this);
    }
}
