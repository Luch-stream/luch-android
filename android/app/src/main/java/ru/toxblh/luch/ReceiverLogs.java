// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The other side of {@link LogShare}: this phone, showing its screen on a receiver, asks it for its log for a problem
 * report (PROTOCOL.md 6a), the way the Mac and Linux senders do. Only over the session the receiver already let in:
 * a new connection from this phone is a plain one, and it would show Allow on the TV, which a report must never do.
 * Plain Java, for the JVM tests.
 */
final class ReceiverLogs {
    static final int MAX_KB = 1024;
    /** How long a receiver may take to start answering, and between two parts. */
    static final long TIMEOUT_MS = 10_000;
    /** More than this unpacked is not a log. */
    static final int MAX_TEXT = 16 << 20;

    private ReceiverLogs() {
    }

    /** One line each way over a control connection that is already open and let in. */
    interface Channel {
        void send(JSONObject message) throws IOException;

        /** The next "logs" or "error" line the receiver sent, null after {@code timeoutMs} without one. */
        JSONObject next(long timeoutMs) throws IOException, InterruptedException;
    }

    /** Why there is no log, in words for about.txt. */
    static final class Failure extends Exception {
        Failure(String why) {
            super(why);
        }
    }

    static JSONObject request(int maxKb) {
        try {
            return new JSONObject().put("t", LogShare.MODE).put("max_kb", maxKb);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the receiver's welcome lists "logs" in its modes: an older one would answer "unknown". */
    static boolean offered(JSONObject welcome) {
        JSONArray modes = welcome == null ? null : welcome.optJSONArray("modes");
        for (int i = 0; modes != null && i < modes.length(); i++) if (LogShare.MODE.equals(modes.optString(i))) return true;
        return false;
    }

    /** The parts as they come, by "part"; whole once "parts" of them are in, one after another. */
    static final class Assembler {
        private final Map<Integer, byte[]> slices = new TreeMap<>();
        private int parts;

        void take(JSONObject m) throws Failure {
            int part = m.optInt("part", -1), n = m.optInt("parts", 0);
            byte[] gz = LogShare.unbase64(m.optString("gz", "~"));
            if (n <= 0 || n > 4096 || gz == null) throw new Failure("sent a log part that cannot be read");
            if (parts != 0 && parts != n) throw new Failure("sent log parts of two different logs");
            // Counted from 0 (PROTOCOL.md); an early sender counted from 1, and the order reads the same.
            if (part < 0 || part > n) throw new Failure("sent log part " + part + " of " + n);
            parts = n;
            slices.put(part, gz);
        }

        boolean complete() {
            if (parts == 0 || slices.size() != parts) return false;
            int lo = ((TreeMap<Integer, byte[]>) slices).firstKey(), hi = ((TreeMap<Integer, byte[]>) slices).lastKey();
            return hi - lo == parts - 1;
        }

        byte[] joined() {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (byte[] s : slices.values()) b.write(s, 0, s.length);
            return b.toByteArray();
        }
    }

    /** Asks and waits for the whole log; the text, or a Failure that says why not. */
    static String fetch(Channel c, int maxKb, long timeoutMs) throws Failure {
        try {
            c.send(request(maxKb));
            Assembler a = new Assembler();
            while (!a.complete()) {
                JSONObject m = c.next(timeoutMs);
                if (m == null) throw new Failure("did not send its log in time");
                if ("error".equals(m.optString("t"))) {
                    if (!LogShare.MODE.equals(m.optString("for"))) continue;   // about something else
                    String code = m.optString("code");
                    if ("not_allowed".equals(code)) throw new Failure("would not hand over its log (not let in)");
                    if ("unknown".equals(code)) throw new Failure("cannot hand over its log: update Luch on it");
                    throw new Failure("refused its log: " + m.optString("detail", code));
                }
                if (LogShare.MODE.equals(m.optString("t"))) a.take(m);
            }
            byte[] text = LogShare.gunzip(a.joined(), MAX_TEXT);
            if (text == null) throw new Failure("sent a log that does not unpack");
            return new String(text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Failure("the connection broke: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted");
        }
    }

    /** A receiver in the report: whether its log is in it (as receiver-N.log), and why not. */
    static final class Part {
        final String name, model, address;
        final boolean inUse;
        String note = "";
        String log;

        Part(String name, String model, String address, boolean inUse) {
            this.name = name;
            this.model = model;
            this.address = address;
            this.inUse = inUse;
        }
    }

    /** The note on a receiver this phone does not show its screen on now: asking it would ask on its TV. */
    static final String NOT_ASKED = "not asked: this phone is not showing its screen there now, and a new connection"
            + " would show Allow on the TV";

    /** The receivers' part of about.txt: one entry each, numbered as their files are; empty when none was seen. */
    static String about(List<Part> parts) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            s.append(i + 1).append(". ").append(p.name).append(p.inUse ? " (in use)" : "").append(": ").append(p.note)
                    .append('\n');
            s.append("   model ").append(p.model == null || p.model.isEmpty() ? "?" : p.model).append(", at ")
                    .append(p.address).append('\n');
            if (p.log != null) s.append("   log: ").append(file(i)).append('\n');
        }
        return s.toString();
    }

    /** The name of the {@code i}-th receiver's log in the zip. */
    static String file(int i) {
        return "receiver-" + (i + 1) + ".log";
    }
}
