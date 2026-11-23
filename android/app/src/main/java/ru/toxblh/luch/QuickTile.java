// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A tile in the quick settings shade: switches the receiver on and off, and says whether a picture is being received. */
@TargetApi(24)
public class QuickTile extends TileService {
    private final Luch.Listener listener = () -> new android.os.Handler(android.os.Looper.getMainLooper()).post(this::refresh);

    @Override
    public void onStartListening() {
        Luch.addListener(listener);
        refresh();
    }

    @Override
    public void onStopListening() {
        Luch.removeListener(listener);
    }

    @Override
    public void onClick() {
        MirrorSession s = MirrorSession.current;
        if (s != null && !s.ended()) {
            // While a picture is coming, the tile leads to it.
            Intent show = new Intent(this, MirrorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, show, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(show);
            }
            return;
        }
        Luch.setEnabled(this, !Luch.enabled(this));
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        MirrorSession s = MirrorSession.current;
        boolean live = s != null && !s.ended();
        boolean on = Luch.enabled(this);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_luch));
        tile.setLabel(getString(R.string.app_name));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(live ? R.string.state_live : on ? R.string.state_ready : R.string.state_off));
        }
        tile.updateTile();
    }
}
