// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * "Direct" (PROTOCOL.md 4m): a sender with no network to this TV asks over Bluetooth, and this device makes a Wi-Fi
 * Direct group the sender joins as a plain Wi-Fi network. The request's rules, its answers, the group's name and
 * passphrase and how long it stays; {@link DirectGroup} makes the group, {@link BleLink} carries the request. Plain
 * Java with a JVM test.
 */
final class Direct {
    static final String MODE = "direct";
    /** The group stays this long after the last connection over it ends (or after it was made, if none came). */
    static final long LINGER_MS = 120_000;
    /** After a Deny on the TV, the same sender's next requests are refused without asking for this long. */
    static final long DENIED_MS = 10 * 60_000L;
    /** The first Android with an L2CAP server and a Wi-Fi Direct group of a chosen name and passphrase. */
    static final int MIN_SDK = 29;
    /** Android 13+ asks for "Nearby Wi-Fi devices", before it Wi-Fi Direct needs the location permission. */
    static final int NEARBY_WIFI_SDK = 33;

    /** Android's rule for a group's name: "DIRECT-" and two characters first, at most 32 bytes. */
    static final String SSID_PREFIX = "DIRECT-";
    static final int SSID_MAX = 32;
    /** WPA2: 8 to 63 characters; 16 letters and digits are about 95 bits, and nobody types them. */
    static final int PASS_LEN = 16, PASS_MIN = 8, PASS_MAX = 63;
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Direct() {
    }

    /** The group as handed to the sender. */
    static final class Group {
        final String ssid, pass, addr;
        /** MHz, 0 when the system did not say. */
        final int freq;

        Group(String ssid, String pass, int freq, String addr) {
            this.ssid = ssid;
            this.pass = pass;
            this.freq = freq;
            this.addr = addr;
        }
    }

    /**
     * Why this device cannot make a group for a sender, as the detail of a {@code no_p2p} error; null when it can.
     * {@code permitted}: "Nearby Wi-Fi devices" on Android 13+, the location permission before it.
     */
    static String why(int sdk, boolean wifiDirect, boolean permitted, boolean wifiOn) {
        if (sdk < MIN_SDK) return "Android 10 or newer is needed for a direct connection";
        if (!wifiDirect) return "this device has no Wi-Fi Direct";
        if (!permitted) {
            return sdk >= NEARBY_WIFI_SDK ? "Nearby Wi-Fi devices is not allowed for Luch on this device"
                    : "Location (needed for Wi-Fi Direct before Android 13) is not allowed for Luch on this device";
        }
        if (!wifiOn) return "Wi-Fi is off on this device";
        return null;
    }

    /**
     * The runtime permissions "direct" needs on Android {@code sdk}: Wi-Fi Direct's ("Nearby Wi-Fi devices" on 13+,
     * the location before it: Android 12 grants the fine one only asked with the coarse) and the Bluetooth channel's
     * ("Nearby devices", connect, on 12+). Asked once on the status screen, never because of a request.
     */
    static String[] permissions(int sdk) {
        if (sdk < MIN_SDK) return new String[0];
        java.util.List<String> p = new java.util.ArrayList<>();
        if (sdk >= NEARBY_WIFI_SDK) {
            p.add("android.permission.NEARBY_WIFI_DEVICES");
        } else {
            p.add("android.permission.ACCESS_FINE_LOCATION");
            p.add("android.permission.ACCESS_COARSE_LOCATION");
        }
        if (sdk >= 31) p.add("android.permission.BLUETOOTH_CONNECT");
        return p.toArray(new String[0]);
    }

    /** The welcome's modes, with "direct" when this device can make a group ({@link #why} said nothing). */
    static JSONArray withMode(JSONArray modes, String why) {
        if (why == null) modes.put(MODE);
        return modes;
    }

    /** A fresh name for a group: {@code DIRECT-xy-Luch-abcd}, both parts random. */
    static String ssid(Random r) {
        return SSID_PREFIX + random(r, 2) + "-Luch-" + random(r, 4);
    }

    static String ssid() {
        return ssid(RANDOM);
    }

    /** A fresh WPA2 passphrase. */
    static String passphrase(Random r) {
        return random(r, PASS_LEN);
    }

    static String passphrase() {
        return passphrase(RANDOM);
    }

    private static String random(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        return sb.toString();
    }

    /** Whether Android takes {@code s} as a group's name. */
    static boolean validSsid(String s) {
        return s != null && s.startsWith(SSID_PREFIX) && s.length() >= SSID_PREFIX.length() + 2
                && s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= SSID_MAX;
    }

    /** Whether {@code s} is a WPA2 passphrase: 8 to 63 printable ASCII characters. */
    static boolean validPass(String s) {
        if (s == null || s.length() < PASS_MIN || s.length() > PASS_MAX) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) > 0x7e) return false;
        return true;
    }

    /** {"t":"direct","ssid":..,"pass":..,"freq":..,"addr":..,"port":..,"tls_port":..}: where to go now. */
    static JSONObject answer(Group g, int port, int tlsPort) {
        try {
            return new JSONObject().put("t", MODE).put("ssid", g.ssid).put("pass", g.pass).put("freq", g.freq)
                    .put("addr", g.addr).put("port", port).put("tls_port", tlsPort);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {"t":"error","for":"direct","code":..}: denied, no_p2p, not_allowed, busy or timeout. */
    static JSONObject error(String code, String detail) {
        try {
            return new JSONObject().put("t", "error").put("code", code).put("for", MODE).put("detail", detail);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * How long the group stays: while any connection came over it and is still open, and {@link #LINGER_MS} after
     * the last one ends; a group nobody joins goes {@link #LINGER_MS} after it was made or last handed out.
     */
    static final class Lifetime {
        private final Gate.Clock clock;
        private int open;
        private long until;

        Lifetime(Gate.Clock clock) {
            this.clock = clock;
            until = clock.now() + LINGER_MS;
        }

        /** Handed to another sender: it gets its time to join too. */
        synchronized void handedOut() {
            until = Math.max(until, clock.now() + LINGER_MS);
        }

        synchronized void arrived() {
            open++;
        }

        synchronized void left() {
            if (open > 0 && --open == 0) until = clock.now() + LINGER_MS;
        }

        synchronized boolean over() {
            return open == 0 && clock.now() >= until;
        }

        /** How long until {@link #over} could say yes, for the next look. */
        synchronized long leftMs() {
            return open > 0 ? LINGER_MS : Math.max(0, until - clock.now());
        }
    }

    /**
     * Who may be asked about on the TV: one question at a time, and a sender just refused is not asked again for
     * {@link #DENIED_MS} (it chose its key and its Bluetooth address, so both count).
     */
    static final class Asks {
        private final Gate.Clock clock;
        private final Map<String, Long> denied = new HashMap<>();
        private boolean asking;

        Asks(Gate.Clock clock) {
            this.clock = clock;
        }

        /** Null: ask now, and {@link #answered} must follow; else the error code to answer with. */
        synchronized String take(String fp, String address) {
            long now = clock.now();
            for (java.util.Iterator<Long> i = denied.values().iterator(); i.hasNext(); ) if (i.next() <= now) i.remove();
            for (String k : new String[] {fp, address}) {
                Long until = k == null ? null : denied.get(k);
                if (until != null && now < until) return "denied";
            }
            if (asking) return "busy";
            asking = true;
            return null;
        }

        synchronized void answered(String fp, String address, boolean deny) {
            asking = false;
            if (!deny) return;
            long until = clock.now() + DENIED_MS;
            for (String k : new String[] {fp, address}) if (k != null) denied.put(k, until);
        }
    }
}
