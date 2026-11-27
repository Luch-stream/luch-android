// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** This receiver's identity and what it currently sees: senders connected to it and senders announced nearby. */
final class Luch {
    static final String TYPE_RECEIVER = "_luch._tcp";
    static final String TYPE_SENDER = "_luchsrc._tcp";
    static final int PORT = 47800;
    static final int VERSION = 0;

    /** A sender, connected or only announced. */
    static final class Peer {
        final String id;
        final String name;
        final String address;

        Peer(String id, String name, String address) {
            this.id = id;
            this.name = name;
            this.address = address;
        }
    }

    /** A receiver announced nearby: where this device can show its screen. */
    static final class Receiver {
        final String id;
        final String name;
        final String model;
        final String address;
        final int port;
        final String codecs;

        Receiver(String id, String name, String model, String address, int port, String codecs) {
            this.id = id;
            this.name = name;
            this.model = model;
            this.address = address;
            this.port = port;
            this.codecs = codecs;
        }
    }

    interface Listener {
        void changed();
    }

    private static final Map<String, Peer> CONNECTED = new LinkedHashMap<>();
    private static final Map<String, Peer> NEARBY = new LinkedHashMap<>();
    private static final Map<String, Receiver> RECEIVERS = new LinkedHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    static volatile String status = "";

    private Luch() {
    }

    static synchronized String id(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("luch", Context.MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) {
            id = newId(android.provider.Settings.Secure.getString(context.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID), context.getPackageName());
            prefs.edit().putString("id", id).apply();
        }
        return id;
    }

    /**
     * The id of a new install. Made from ANDROID_ID, which outlives reinstalling (one per signing key since Android 8,
     * one per device before, hence the package name in it): a reinstalled receiver is the same one to its senders,
     * not another entry in their lists. Random when there is no ANDROID_ID.
     */
    static String newId(String androidId, String pkg) {
        if (androidId == null || androidId.isEmpty()) return UUID.randomUUID().toString();
        return UUID.nameUUIDFromBytes(("luch:" + pkg + ":" + androidId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /** Whether the receiver is switched on (the quick settings tile and the status screen turn it off and on). */
    static boolean enabled(Context context) {
        return context.getSharedPreferences("luch", Context.MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
        if (on) {
            LuchService.start(context);
        } else {
            context.stopService(new android.content.Intent(context, LuchService.class));
        }
        notifyListeners();
    }

    /** Whether a sender's offer to show its screen is put in a notification to tap (on by default). */
    static boolean offerNotification(Context context) {
        return context.getSharedPreferences("luch", Context.MODE_PRIVATE).getBoolean("offer_notification", true);
    }

    static void setOfferNotification(Context context, boolean on) {
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putBoolean("offer_notification", on).apply();
    }

    /** The name people gave the TV in its settings, else the model. */
    static String name(Context context) {
        String own = customName(context);
        String name = own.isEmpty() ? systemName(context) : own;
        // A -PluchDev build next to a release on the same TV: the two must not look alike to a sender.
        return context.getPackageName().endsWith(".dev") ? name + " (dev)" : name;
    }

    /** What the person typed in the app, empty when nothing. */
    static String customName(Context context) {
        return context.getSharedPreferences("luch", Context.MODE_PRIVATE).getString("custom_name", "").trim();
    }

    /** Saves the name and makes the receiver announce itself under it. */
    static void setCustomName(Context context, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.length() > 30) clean = clean.substring(0, 30);
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putString("custom_name", clean).apply();
        if (enabled(context)) {
            context.stopService(new android.content.Intent(context, LuchService.class));
            LuchService.start(context);
        }
    }

    static String systemName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    static String model() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    /** Hardware video decoders worth offering, as MIME types. */
    static List<String> decoders() {
        List<String> out = new ArrayList<>();
        for (String mime : new String[]{"video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9"}) {
            if (hardwareDecoder(mime) != null) out.add(mime);
        }
        return out;
    }

    /** Largest frame the AVC decoder takes, "WxH". */
    static String maxSize() {
        MediaCodecInfo info = hardwareDecoder("video/avc");
        if (info == null) return "1920x1080";
        MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType("video/avc").getVideoCapabilities();
        return v.getSupportedWidths().getUpper() + "x" + v.getSupportedHeights().getUpper();
    }

    private static MediaCodecInfo hardwareDecoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) return info;
            }
        }
        return null;
    }

    static synchronized void connected(Peer peer) {
        CONNECTED.put(peer.id, peer);
        notifyListeners();
    }

    static synchronized void disconnected(String id) {
        if (CONNECTED.remove(id) != null) notifyListeners();
    }

    /** Keyed by the mDNS service name, the only thing a "service lost" event carries. */
    static synchronized void nearby(String serviceName, Peer peer) {
        NEARBY.put(serviceName, peer);
        notifyListeners();
    }

    static synchronized void gone(String serviceName) {
        if (NEARBY.remove(serviceName) != null) notifyListeners();
    }

    /** Keyed by the mDNS service name. */
    static synchronized void receiver(String serviceName, Receiver r) {
        RECEIVERS.put(serviceName, r);
        notifyListeners();
    }

    static synchronized void receiverGone(String serviceName) {
        if (RECEIVERS.remove(serviceName) != null) notifyListeners();
    }

    static synchronized List<Receiver> receivers() {
        return Collections.unmodifiableList(new ArrayList<>(RECEIVERS.values()));
    }

    static synchronized List<Peer> connected() {
        return Collections.unmodifiableList(new ArrayList<>(CONNECTED.values()));
    }

    static synchronized List<Peer> nearby() {
        return Collections.unmodifiableList(new ArrayList<>(NEARBY.values()));
    }

    static void setStatus(String s) {
        status = s;
        notifyListeners();
    }

    static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void notifyListeners() {
        for (Listener l : LISTENERS) l.changed();
    }
}
