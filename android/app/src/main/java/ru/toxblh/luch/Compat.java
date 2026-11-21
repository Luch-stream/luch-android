// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.nsd.NsdManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The few calls whose API is newer than Android 5.0, the oldest Android TV. On Android 6+ (or 8+) they are the
 * platform's own; below that, the older way to the same result. docs/older-android.md lists what still works worse.
 */
final class Compat {
    private Compat() {
    }

    /** {@code context.getSystemService(Class)} (Android 6) for the services Luch uses. */
    @SuppressWarnings("unchecked")
    static <T> T service(Context context, Class<T> type) {
        if (Build.VERSION.SDK_INT >= 23) return context.getSystemService(type);
        String name;
        if (type == AudioManager.class) name = Context.AUDIO_SERVICE;
        else if (type == ConnectivityManager.class) name = Context.CONNECTIVITY_SERVICE;
        else if (type == DisplayManager.class) name = Context.DISPLAY_SERVICE;
        else if (type == NotificationManager.class) name = Context.NOTIFICATION_SERVICE;
        else if (type == NsdManager.class) name = Context.NSD_SERVICE;
        else if (type == PowerManager.class) name = Context.POWER_SERVICE;
        else if (type == WifiManager.class) name = Context.WIFI_SERVICE;
        else throw new IllegalArgumentException("no service name for " + type);
        return (T) context.getSystemService(name);
    }

    /** A foreground service needs startForegroundService from Android 8; before it, startService did the same. */
    static void startForegroundService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** {@code Files.readAllBytes} is Android 8. */
    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] b = new byte[16 * 1024];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** The first IPv4 address on a non-loopback interface (ConnectivityManager.getActiveNetwork is Android 6). */
    static String ipv4() {
        try {
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!i.isUp() || i.isLoopback()) continue;
                for (InetAddress a : Collections.list(i.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return "?";
    }
}
