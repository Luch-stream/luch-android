// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** The system installer's answer to {@link Updater#install}: brings up its question when it has one. */
public class UpdateReceiver extends BroadcastReceiver {
    static final String BY_USER = "by_user";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                if (!intent.getBooleanExtra(BY_USER, false)) {
                    // Started in the background: no dialog over whatever is on the TV. The status screen still
                    // offers the update.
                    int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                    if (id >= 0) context.getPackageManager().getPackageInstaller().abandonSession(id);
                    Log.i(LuchService.TAG, "update needs a question: left for the status screen");
                    Updater.installing = false;
                    Luch.notifyListeners();
                    break;
                }
                Intent ask = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask != null) context.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Log.i(LuchService.TAG, "update installed");   // the app restarts; BootReceiver starts the service
                break;
            default: {
                String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(LuchService.TAG, "update not installed: " + status + " " + why);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Reports.error(context, "update not installed: " + status + " " + why, null, null);
                }
                Updater.installing = false;
                Luch.notifyListeners();
            }
        }
    }
}
