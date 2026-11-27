// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * "Send to the developers": a made report goes to the project's GlitchTip (Sentry's protocol) as an event, with no
 * account on the person's side. The DSN is baked in at build time (BuildConfig.REPORT_DSN, empty: no button). Two
 * envelopes: the event with what happened, about.txt and the log tails (this one must arrive), then the zip as its
 * attachment, which a GlitchTip may refuse without the report being lost. Plain Java, for the JVM tests.
 */
final class ReportUpload {
    /** Each log in the event: its last 64 KB, whole lines. */
    static final int LOG_TAIL = 64 * 1024;
    /** The event as a whole stays under what a Sentry-like server takes in one go. */
    static final int EVENT_MAX = 800 * 1024;
    /** A zip bigger than this is not attached (the event still goes). */
    static final long ZIP_MAX = 10L << 20;
    private static final int TIMEOUT_MS = 20_000;

    /** Why it did not go, for the person (the screen words it). */
    enum Why { TOO_BIG, TOO_MANY, REFUSED, SERVER, NETWORK }

    static final class Failure extends Exception {
        final Why why;
        final int status;
        final String detail;

        Failure(Why why, int status, String detail) {
            super(why + (status > 0 ? " " + status : "") + (detail.isEmpty() ? "" : ": " + detail));
            this.why = why;
            this.status = status;
            this.detail = detail;
        }
    }

    /** {@code https://<key>@<host>[/<path>]/<project>}: where envelopes go and with which key. */
    static final class Dsn {
        final String key, envelope;

        private Dsn(String key, String envelope) {
            this.key = key;
            this.envelope = envelope;
        }

        /** Null for an empty or unreadable DSN: then there is nowhere to send to, and no button. */
        static Dsn parse(String dsn) {
            if (dsn == null || dsn.trim().isEmpty()) return null;
            try {
                URI u = new URI(dsn.trim());
                String scheme = u.getScheme(), key = u.getRawUserInfo(), host = u.getHost(), path = u.getRawPath();
                if (scheme == null || !(scheme.equals("https") || scheme.equals("http")) || host == null
                        || key == null || key.isEmpty() || path == null) return null;
                key = key.split(":", 2)[0];   // an old DSN's secret is not used
                int slash = path.lastIndexOf('/');
                String project = path.substring(slash + 1), prefix = path.substring(0, Math.max(0, slash));
                if (project.isEmpty() || key.isEmpty()) return null;
                String hostPort = (host.contains(":") ? "[" + host + "]" : host) + (u.getPort() > 0 ? ":" + u.getPort() : "");
                return new Dsn(key, scheme + "://" + hostPort + prefix + "/api/" + project + "/envelope/");
            } catch (URISyntaxException e) {
                return null;
            }
        }
    }

    /** What goes into the event; the texts are already as in the zip (hidden addresses and names stay hidden). */
    static final class Report {
        String what = "", about = "";
        /** File name in the zip → its text: luch.log, receiver-1.log... */
        Map<String, String> logs = new java.util.LinkedHashMap<>();
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        String version = "", osVersion = "", manufacturer = "", model = "", family = "";
        String zipName = "report.zip";
        byte[] zip;
    }

    /** The outcome: the number to tell us, and whether the zip went with it. */
    static final class Sent {
        final String eventId, number;
        /** Null: the zip went too; else why not (too big, or the server would not take it). */
        final String notAttached;

        Sent(String eventId, String notAttached) {
            this.eventId = eventId;
            this.number = number(eventId);
            this.notAttached = notAttached;
        }
    }

    interface Progress {
        void sent(long done, long total);
    }

    private final Dsn dsn;
    private volatile HttpURLConnection current;
    private volatile boolean cancelled;

    ReportUpload(Dsn dsn) {
        this.dsn = dsn;
    }

    /** The report's number for the person: "LUCH-" and the event id's first 8 hex digits. */
    static String number(String eventId) {
        return "LUCH-" + eventId.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String auth(Dsn dsn, String version) {
        return "Sentry sentry_version=7, sentry_key=" + dsn.key + ", sentry_client=luch-android/" + version;
    }

    /** Sends both envelopes; the attachment's failure only shows in {@link Sent#notAttached}. */
    Sent send(Report r, String eventId, Date now, Progress progress) throws Failure {
        byte[] first, second = null;
        try {
            byte[] event = event(r, eventId, now).toString().getBytes(StandardCharsets.UTF_8);
            first = envelope(header(eventId, now), "{\"type\":\"event\",\"length\":" + event.length + "}", event);
            if (r.zip != null && r.zip.length <= ZIP_MAX) {
                String item = new JSONObject().put("type", "attachment").put("length", r.zip.length)
                        .put("filename", r.zipName).put("content_type", "application/zip")
                        .put("attachment_type", "event.attachment").toString();
                second = envelope(header(eventId, now), item, r.zip);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only strings and numbers go in
        }
        long total = first.length + (second == null ? 0 : second.length);
        post(first, r.version, done -> progress.sent(done, total));
        if (second == null) return new Sent(eventId, r.zip == null ? "no file" : "over " + (ZIP_MAX >> 20) + " MB");
        String notAttached = null;
        long base = first.length;
        try {
            post(second, r.version, done -> progress.sent(base + done, total));
        } catch (Failure e) {
            // The event is in: the report has its number whether or not the file went with it.
            notAttached = e.getMessage();
        }
        progress.sent(total, total);
        return new Sent(eventId, notAttached);
    }

    /** Stops a send under way (the person pressed Cancel); {@link #send} then fails, unless the event is in. */
    void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
    }

    boolean cancelled() {
        return cancelled;
    }

    static String header(String eventId, Date now) {
        return "{\"event_id\":\"" + eventId + "\",\"sent_at\":\"" + iso(now) + "\"}";
    }

    /** One item: the envelope header, the item header and the payload, each line ended by a newline. */
    static byte[] envelope(String header, String itemHeader, byte[] payload) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + header.length() + itemHeader.length() + 3);
        byte[] h = (header + "\n" + itemHeader + "\n").getBytes(StandardCharsets.UTF_8);
        b.write(h, 0, h.length);
        b.write(payload, 0, payload.length);
        b.write('\n');
        return b.toByteArray();
    }

    /** The event: an "info" message with the report in tags, contexts and extra. */
    static JSONObject event(Report r, String eventId, Date now) throws JSONException {
        JSONObject tags = new JSONObject();
        for (Map.Entry<String, String> t : r.tags.entrySet()) {
            String v = t.getValue() == null ? "" : t.getValue().replace('\n', ' ').trim();
            if (!v.isEmpty()) tags.put(t.getKey(), v.length() > 200 ? v.substring(0, 200) : v);
        }
        JSONObject extra = new JSONObject().put("what_happened", r.what);
        // What the texts take once in JSON (a newline is two bytes there); 16 KB for the rest of the event.
        int room = EVENT_MAX - 16 * 1024 - json(r.what) - tags.toString().length();
        String about = fit(r.about, room);
        extra.put("about.txt", about);
        room -= json(about);
        for (Map.Entry<String, String> l : r.logs.entrySet()) {
            String t = fit(l.getValue(), room);
            extra.put(l.getKey(), t.isEmpty() && !l.getValue().isEmpty() ? "(left out: too much already; see the zip)" : t);
            room -= json(t);
        }
        return new JSONObject()
                .put("event_id", eventId)
                .put("timestamp", iso(now))
                .put("level", "info")
                .put("logger", "luch.report")
                .put("platform", "java")
                .put("release", "luch-android@" + r.version)
                .put("message", new JSONObject().put("formatted", "Problem report: " + firstLine(r.what)))
                .put("tags", tags)
                .put("contexts", new JSONObject()
                        .put("os", new JSONObject().put("name", "Android").put("version", r.osVersion))
                        .put("device", new JSONObject().put("manufacturer", r.manufacturer).put("model", r.model)
                                .put("family", r.family)))
                .put("extra", extra);
    }

    static String firstLine(String what) {
        String t = what == null ? "" : what.trim();
        if (t.isEmpty()) return "(nothing written)";
        int nl = t.indexOf('\n');
        String line = (nl < 0 ? t : t.substring(0, nl)).trim();
        return line.length() > 120 ? line.substring(0, 119) + "…" : line;
    }

    /** The end of {@code text} in at most {@code max} UTF-8 bytes, from the start of a line. */
    static String tail(String text, int max) {
        if (text == null || max <= 0) return "";
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return text;
        int from = b.length - max;
        while (from < b.length && b[from - 1] != '\n') from++;
        if (from == b.length) {
            // One long line: cut it, not in the middle of a character.
            from = b.length - max;
            while (from < b.length && (b[from] & 0xC0) == 0x80) from++;
        }
        return new String(b, from, b.length - from, StandardCharsets.UTF_8);
    }

    /** The end of {@code text}, at most LOG_TAIL bytes, that takes at most {@code room} bytes in JSON. */
    private static String fit(String text, int room) {
        int max = Math.min(LOG_TAIL, room);
        String t = tail(text, max);
        int size = json(t);
        if (size > room) t = tail(text, (int) ((long) max * room / size) - 1);   // escapes took more: once smaller
        return json(t) > room ? "" : t;
    }

    private static int json(String s) {
        return JSONObject.quote(s).getBytes(StandardCharsets.UTF_8).length;
    }

    static String iso(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(d);
    }

    private interface Sending {
        void sent(long done);
    }

    private void post(byte[] body, String version, Sending progress) throws Failure {
        if (cancelled) throw new Failure(Why.NETWORK, 0, "cancelled");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(dsn.envelope).toURL().openConnection();
            current = c;
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/x-sentry-envelope");
            c.setRequestProperty("X-Sentry-Auth", auth(dsn, version));
            c.setRequestProperty("User-Agent", "luch-android/" + version);
            try (OutputStream out = c.getOutputStream()) {
                for (int off = 0; off < body.length; off += 16 * 1024) {
                    int n = Math.min(16 * 1024, body.length - off);
                    out.write(body, off, n);
                    progress.sent(off + n);
                }
            }
            int status = c.getResponseCode();
            if (status >= 200 && status < 300) {
                read(c.getInputStream());
                return;
            }
            String detail = detail(read(c.getErrorStream()));
            if (status == 413) throw new Failure(Why.TOO_BIG, status, detail);
            if (status == 429) throw new Failure(Why.TOO_MANY, status, detail);
            throw new Failure(status >= 500 ? Why.SERVER : Why.REFUSED, status, detail);
        } catch (IOException e) {
            throw new Failure(Why.NETWORK, 0, cancelled ? "cancelled" : String.valueOf(e.getMessage()));
        } finally {
            current = null;
            if (c != null) c.disconnect();
        }
    }

    /** What a refusal says: {"detail":...} (GlitchTip), {"error":...}, else the start of the body. */
    static String detail(String body) {
        String b = body == null ? "" : body.trim();
        try {
            JSONObject o = new JSONObject(b);
            String d = o.optString("detail", o.optString("error", ""));
            if (!d.isEmpty()) b = d;
        } catch (JSONException notJson) {
            // a proxy's page: its start says enough
        }
        b = b.replaceAll("\\s+", " ");
        return b.length() > 200 ? b.substring(0, 200) : b;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (b.size() < 16 * 1024 && (n = s.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
