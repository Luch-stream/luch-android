// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Answers a launcher's "is a picture being received?" with the broadcast a session sends on every change. */
public class StateQueryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        MirrorSession s = MirrorSession.current;
        MirrorSession.broadcast(context, s != null && !s.ended(), s != null && s.isAway());
    }
}
