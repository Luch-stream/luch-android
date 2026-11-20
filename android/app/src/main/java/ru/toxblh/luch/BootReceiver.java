// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver at boot and after an update, like an AirPlay or Cast receiver would be. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            LuchService.start(context);
        }
    }
}
