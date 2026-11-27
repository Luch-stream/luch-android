// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The card: what this receiver says about itself where there is no room for JSON (PROTOCOL.md 1c-1e). Bluetooth LE
 * and sound carry the same bytes, the QR code the same as a link:
 * <pre>
 * 0..2   "Lu"            magic
 * 2      v &lt;&lt; 4 | kind   protocol version; 1 = a receiver here, 2 = a sender looking for one
 * 3..7   id              the first 4 bytes of the id (its first 8 hex digits)
 * 7..11  IPv4            receiver only
 * 11..13 port            receiver only, big-endian
 * 13..17 token           the one-time pairing token: sound only, never over Bluetooth
 * </pre>
 */
final class Card {
    static final int RECEIVER = 1;
    static final int SENDER = 2;
    /** The Bluetooth manufacturer-data key: the company id kept for tests and projects without one of their own. */
    static final int COMPANY = 0xFFFF;

    final int kind;
    /** 8 lowercase hex digits. */
    final String id;
    /** Dotted IPv4, or null. */
    final String host;
    final int port;
    /** 8 lowercase hex digits, or null. */
    final String token;

    Card(int kind, String id, String host, int port, String token) {
        this.kind = kind;
        this.id = shortId(id);
        this.host = host;
        this.port = port;
        this.token = token;
    }

    static String shortId(String id) {
        String s = id.replace("-", "").toLowerCase(Locale.ROOT);
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    byte[] encode() {
        byte[] ip = host == null ? null : ipv4(host);
        byte[] tok = token == null ? null : hex4(token);
        int len = ip == null ? 7 : tok == null ? 13 : 17;
        byte[] b = new byte[len];
        b[0] = 'L';
        b[1] = 'u';
        b[2] = (byte) (Luch.VERSION << 4 | kind);
        byte[] idb = hex4(id);
        if (idb != null) System.arraycopy(idb, 0, b, 3, 4);
        if (ip != null) {
            System.arraycopy(ip, 0, b, 7, 4);
            b[11] = (byte) (port >> 8);
            b[12] = (byte) port;
            if (tok != null) System.arraycopy(tok, 0, b, 13, 4);
        }
        return b;
    }

    /** Null for anything that is not a card of this version. */
    static Card decode(byte[] b) {
        if (b == null || b.length < 7 || b[0] != 'L' || b[1] != 'u') return null;
        int version = (b[2] & 0xFF) >> 4, kind = b[2] & 15;
        if (version != Luch.VERSION || (kind != RECEIVER && kind != SENDER)) return null;
        String id = hex(b, 3, 4);
        String host = null;
        int port = 0;
        if (b.length >= 13) {
            host = (b[7] & 0xFF) + "." + (b[8] & 0xFF) + "." + (b[9] & 0xFF) + "." + (b[10] & 0xFF);
            port = (b[11] & 0xFF) << 8 | (b[12] & 0xFF);
        }
        if (kind == RECEIVER && (host == null || port == 0 || host.equals("0.0.0.0"))) return null;
        return new Card(kind, id, host, port, b.length >= 17 ? hex(b, 13, 4) : null);
    }

    /**
     * What the QR code says. The phone app reads it (StatusActivity), luchd too; unknown parameters are ignored. No
     * id: only this TV takes the token, which says as much, and the code stays a size a camera reads from afar.
     */
    String link() {
        StringBuilder sb = new StringBuilder("luch://connect?host=").append(host).append("&port=").append(port);
        if (token != null) sb.append("&t=").append(token);
        return sb.toString();
    }

    static byte[] ipv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                out[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static byte[] hex4(String s) {
        if (s == null || s.length() < 8) return null;
        byte[] out = new byte[4];
        try {
            for (int i = 0; i < 4; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static String hex(byte[] b, int from, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + n; i++) sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xFF));
        return sb.toString();
    }

    /**
     * The one-time pairing token (PROTOCOL.md 1e): in the QR code and the sound, never on the air. A sender that
     * gives it back in its hello has seen this TV's screen or heard it; the token is used up then and a new one is
     * shown. It lives in memory only: a restart makes a new one too.
     */
    static final class Pairing {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static String token;

        private Pairing() {
        }

        static synchronized String current() {
            if (token == null) token = String.format(Locale.ROOT, "%08x", RANDOM.nextInt());
            return token;
        }

        /**
         * "ok" or "bad" for a token a sender gave, null when it gave none. A probe (discovery asking who is there)
         * does not use it up.
         */
        static synchronized String check(String given, boolean probe) {
            if (given == null || given.isEmpty()) return null;
            boolean ok = given.equalsIgnoreCase(current());
            if (ok && !probe) {
                token = null;
                Luch.notifyListeners();
            }
            return ok ? "ok" : "bad";
        }
    }
}
