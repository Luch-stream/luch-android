// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * {"t":"logs"} (PROTOCOL.md 6a): the end of this device's log for a sender that asks, gzipped and cut into lines
 * every sender reads (its control lines are at most 64 KiB). Plain Java, for the JVM tests.
 */
final class LogShare {
    static final String MODE = "logs";
    static final int DEFAULT_KB = 1024;
    /** No more than the two files hold. */
    static final int MAX_KB = 2 * LogFile.MAX_BYTES / 1024;
    /** Gzip bytes per part: 36 KiB is exactly 48 KiB of base64. */
    static final int SLICE = 36 * 1024;

    private LogShare() {
    }

    /** How much the sender asked for, in bytes: {@code max_kb}, 1024 when missing or not above 0, at most MAX_KB. */
    static int maxBytes(JSONObject request) {
        int kb = request.optInt("max_kb", DEFAULT_KB);
        if (kb <= 0) kb = DEFAULT_KB;
        return Math.min(kb, MAX_KB) * 1024;
    }

    /** The answer: {"t":"logs","part":i,"parts":n,"gz":"<base64>"} for i = 0..n-1; always at least one part. */
    static List<JSONObject> parts(byte[] text) throws JSONException {
        byte[] gz = gzip(text);
        int n = Math.max(1, (gz.length + SLICE - 1) / SLICE);
        List<JSONObject> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int from = i * SLICE;
            out.add(new JSONObject().put("t", MODE).put("part", i).put("parts", n)
                    .put("gz", base64(gz, from, Math.min(SLICE, gz.length - from))));
        }
        return out;
    }

    static byte[] gzip(byte[] text) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(text.length / 6 + 64);
        try (GZIPOutputStream z = new GZIPOutputStream(b)) {
            z.write(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);   // in memory: does not happen
        }
        return b.toByteArray();
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard base64 with padding; java.util.Base64 is Android 8+, android.util.Base64 is not in the JVM tests. */
    static String base64(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder((len + 2) / 3 * 4);
        int end = off + len;
        for (int i = off; i < end; i += 3) {
            int n = (b[i] & 0xff) << 16;
            if (i + 1 < end) n |= (b[i + 1] & 0xff) << 8;
            if (i + 2 < end) n |= b[i + 2] & 0xff;
            s.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            s.append(i + 1 < end ? B64[n >> 6 & 63] : '=');
            s.append(i + 2 < end ? B64[n & 63] : '=');
        }
        return s.toString();
    }
}
