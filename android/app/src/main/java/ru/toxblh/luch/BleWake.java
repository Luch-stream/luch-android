// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Waking another TV by Bluetooth for a sender that cannot advertise itself (PROTOCOL.md 4l): a Mac cannot put
 * manufacturer data on the air, this device can. The request, its checks and its answers; {@link Nearby#wake} sends
 * it. Plain Java with a JVM test.
 */
final class BleWake {
    static final String MODE = "ble_wake";
    /** MediaTek's company id, the key of a TV remote's power-key data. Nothing else is relayed. */
    static final int MEDIATEK = 0x0046;
    /** About one press of the remote, as luchd holds it; longer asks are cut to MAX_MS. */
    static final int HOLD_MS = 2000, MIN_MS = 100, MAX_MS = 5000;
    /** One legacy advertisement: flags (3) + the HID service (4) + manufacturer data (4 + n) within 31 bytes. */
    static final int MIN_BYTES = 2, MAX_BYTES = 20;

    /** Only one advertisement for another TV on the air at a time. */
    private static final AtomicBoolean ON_AIR = new AtomicBoolean();

    final byte[] data;
    final int ms;

    private BleWake(byte[] data, int ms) {
        this.data = data;
        this.ms = ms;
    }

    /** A request refused, with the code and detail of its error answer. */
    static final class Refused extends Exception {
        final String code;

        Refused(String code, String detail) {
            super(detail);
            this.code = code;
        }

        JSONObject answer() {
            return error(code, getMessage());
        }
    }

    /** {"t":"ble_wake","company":70,"data":"<hex>","ms":2000}, checked. */
    static BleWake parse(JSONObject msg) throws Refused {
        if (msg.optInt("company", -1) != MEDIATEK) {
            throw new Refused("bad_request", "only a remote's data, company 70 (0x0046)");
        }
        byte[] data = hex(msg.optString("data"));
        if (data == null || data.length < MIN_BYTES || data.length > MAX_BYTES) {
            throw new Refused("bad_request", "data must be " + MIN_BYTES + " to " + MAX_BYTES + " bytes in hex");
        }
        int ms = Math.max(MIN_MS, Math.min(MAX_MS, msg.optInt("ms", HOLD_MS)));
        return new BleWake(data, ms);
    }

    /** "7e8089": the bytes, or null when it is not hex. */
    static byte[] hex(String s) {
        if (s == null || s.isEmpty() || s.length() % 2 != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }

    /** The error code for why this device cannot advertise ({@link Nearby#unavailable}), null when it can. */
    static String code(String unavailable) {
        if (unavailable == null) return null;
        // "Nearby devices" not granted: asked for on the status screen only, never because of a network message.
        return Nearby.NO_PERMISSION.equals(unavailable) ? "not_allowed" : "no_ble";
    }

    /** The detail for an error code of {@link #code}. */
    static String detail(String code) {
        return "not_allowed".equals(code) ? "Nearby devices (Bluetooth) is not allowed for Luch on this device"
                : "no Bluetooth that can advertise here";
    }

    /** The welcome's modes, with "ble_wake" when this device can send it. */
    static JSONArray withMode(JSONArray modes, String unavailable) {
        if (code(unavailable) == null) modes.put(MODE);
        return modes;
    }

    /** Takes the one slot on the air; false while another is there. */
    static boolean take() {
        return ON_AIR.compareAndSet(false, true);
    }

    static void release() {
        ON_AIR.set(false);
    }

    static JSONObject ok() {
        try {
            return new JSONObject().put("t", MODE).put("ok", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject error(String code, String detail) {
        try {
            return new JSONObject().put("t", "error").put("code", code).put("for", MODE).put("detail", detail);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
