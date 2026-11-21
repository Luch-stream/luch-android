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

    interface Listener {
        void changed();
    }

    private static final Map<String, Peer> CONNECTED = new LinkedHashMap<>();
    private static final Map<String, Peer> NEARBY = new LinkedHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    static volatile String status = "";

    private Luch() {
    }

    static synchronized String id(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("luch", Context.MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("id", id).apply();
        }
        return id;
    }

    /** The name people gave the TV in its settings, else the model. */
    static String name(Context context) {
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
