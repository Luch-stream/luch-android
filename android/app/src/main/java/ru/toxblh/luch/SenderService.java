// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/** Keeps the screen capture alive while this device shows its screen on a receiver (Android requires a foreground service). */
public class SenderService extends Service {
    private static final String CHANNEL = "sender";
    private static final String ACTION_STOP = "ru.toxblh.luch.STOP_SENDING";

    static void start(Context context, int resultCode, Intent projectionData, String receiverId, String codec) {
        Intent i = new Intent(context, SenderService.class).putExtra("code", resultCode).putExtra("data", projectionData)
                .putExtra("rx", receiverId).putExtra("codec", codec);
        Compat.startForegroundService(context, i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            Sender.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        Luch.Receiver to = null;
        for (Luch.Receiver r : Luch.receivers()) {
            if (r.id.equals(intent.getStringExtra("rx"))) to = r;
        }
        if (to == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(to.name);
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection p = m.getMediaProjection(intent.getIntExtra("code", 0), intent.getParcelableExtra("data"));
        if (p == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Sender.begin(this, to, intent.getStringExtra("codec"), p);
        Luch.notifyListeners();
        return START_NOT_STICKY;
    }

    private void goForeground(String to) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.sender_channel), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SenderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = b.setSmallIcon(R.drawable.ic_luch).setContentTitle(getString(R.string.sender_notification, to))
                .setContentText(getString(R.string.action_stop_send)).setContentIntent(stop).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(3, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
