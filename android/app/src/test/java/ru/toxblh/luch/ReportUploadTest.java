// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** "Send to the developers": the two envelopes a GlitchTip gets, byte for byte, against a local HTTP server. */
public class ReportUploadTest {
    private static final String EVENT_ID = "0123456789abcdef0123456789abcdef";
    private static final Date NOW = new Date(1_800_000_000_123L);

    /** One request as the server saw it. */
    private static final class Seen {
        String method, path, auth, type;
        byte[] body;
    }

    private ServerSocket server;
    private Thread serving;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    /** The answers, one per request in order: status and body. */
    private final List<Object[]> answers = Collections.synchronizedList(new ArrayList<>());

    /**
     * A small HTTP/1.1 server of our own, one request per connection (the JDK's HttpServer is not on the classpath
     * the Android unit tests compile against): enough to see the request as it went out.
     */
    @Before
    public void start() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket c = server.accept()) {
                    serve(c);
                } catch (IOException closed) {
                    // the test is over, or the client went away
                }
            }
        }, "test-http");
        serving.start();
    }

    private void serve(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        Seen s = new Seen();
        String[] request = line(in).split(" ");
        s.method = request[0];
        s.path = request[1];
        int length = 0;
        for (String h; !(h = line(in)).isEmpty(); ) {
            int colon = h.indexOf(':');
            String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = h.substring(colon + 1).trim();
            if (name.equals("x-sentry-auth")) s.auth = value;
            if (name.equals("content-type")) s.type = value;
            if (name.equals("content-length")) length = Integer.parseInt(value);
        }
        s.body = new byte[length];
        for (int off = 0, n; off < length; off += n) {
            n = in.read(s.body, off, length - off);
            if (n < 0) throw new IOException("cut short");
        }
        seen.add(s);
        Object[] a = answers.isEmpty() ? new Object[]{200, "{\"id\":\"" + EVENT_ID + "\"}"} : answers.remove(0);
        byte[] body = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 " + a[0] + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int ch; (ch = in.read()) != '\n'; ) {
            if (ch < 0) throw new IOException("cut short");
            if (ch != '\r') b.append((char) ch);
        }
        return b.toString();
    }

    @After
    public void stop() throws Exception {
        server.close();
        serving.join(2000);
    }

    private String dsn() {
        return "http://abc123@127.0.0.1:" + server.getLocalPort() + "/42";
    }

    /** A zip's worth of bytes, newlines and all: the framing must go by the length, not by lines. */
    private static byte[] zip() {
        byte[] z = new byte[50_000];
        new Random(9).nextBytes(z);
        z[0] = 'P';
        z[1] = 'K';
        z[100] = '\n';
        return z;
    }

    private static ReportUpload.Report report() {
        ReportUpload.Report r = new ReportUpload.Report();
        r.what = "The picture froze\nafter two minutes";
        r.about = "Luch problem report\nLuch: 0.4 (github, debug)\n";
        r.logs.put("luch.log", "2026-01-31 20:15:02.123+0100 I luch-video: started\n");
        r.logs.put("receiver-1.log", "2026-01-31 20:15:03.000+0100 I luch-control: hello from Receiver 1\n");
        r.tags.put("flavor", "github");
        r.tags.put("sdk", "34");
        r.tags.put("device", "Google Pixel 8");
        r.tags.put("role", "sender");
        r.tags.put("codec", "HEVC");
        r.version = "0.4";
        r.osVersion = "14";
        r.manufacturer = "Google";
        r.model = "Pixel 8";
        r.family = "phone";
        r.zipName = "Luch-report-2026-01-31-2015.zip";
        r.zip = zip();
        return r;
    }

    /** An envelope cut as Sentry reads it: two header lines, then exactly "length" bytes, then a newline. */
    private static final class Envelope {
        final JSONObject header, item;
        final byte[] payload;

        Envelope(byte[] b) throws Exception {
            int a = indexOf(b, 0), c = indexOf(b, a + 1);
            header = new JSONObject(new String(b, 0, a, StandardCharsets.UTF_8));
            item = new JSONObject(new String(b, a + 1, c - a - 1, StandardCharsets.UTF_8));
            int len = item.getInt("length");
            assertEquals("payload, then one newline", c + 1 + len + 1, b.length);
            assertEquals('\n', b[b.length - 1]);
            payload = Arrays.copyOfRange(b, c + 1, c + 1 + len);
        }

        private static int indexOf(byte[] b, int from) {
            for (int i = from; i < b.length; i++) if (b[i] == '\n') return i;
            throw new AssertionError("no newline");
        }
    }

    @Test
    public void theEventThenTheZipAsItsAttachment() throws Exception {
        List<long[]> progress = new ArrayList<>();
        ReportUpload.Sent sent = new ReportUpload(ReportUpload.Dsn.parse(dsn()))
                .send(report(), EVENT_ID, NOW, (done, total) -> progress.add(new long[]{done, total}));
        assertEquals("LUCH-01234567", sent.number);
        assertNull(sent.notAttached);
        assertEquals(2, seen.size());

        for (Seen s : seen) {
            assertEquals("POST", s.method);
            assertEquals("/api/42/envelope/", s.path);
            assertEquals("application/x-sentry-envelope", s.type);
            assertEquals("Sentry sentry_version=7, sentry_key=abc123, sentry_client=luch-android/0.4", s.auth);
        }

        Envelope first = new Envelope(seen.get(0).body);
        assertEquals(EVENT_ID, first.header.getString("event_id"));
        assertEquals("2027-01-15T08:00:00.123Z", first.header.getString("sent_at"));
        assertEquals("event", first.item.getString("type"));
        JSONObject e = new JSONObject(new String(first.payload, StandardCharsets.UTF_8));
        assertEquals(EVENT_ID, e.getString("event_id"));
        assertEquals("info", e.getString("level"));
        assertEquals("java", e.getString("platform"));
        assertEquals("luch-android@0.4", e.getString("release"));
        assertEquals("Problem report: The picture froze", e.getJSONObject("message").getString("formatted"));
        JSONObject tags = e.getJSONObject("tags");
        assertEquals("github", tags.getString("flavor"));
        assertEquals("34", tags.getString("sdk"));
        assertEquals("Google Pixel 8", tags.getString("device"));
        assertEquals("sender", tags.getString("role"));
        assertEquals("HEVC", tags.getString("codec"));
        assertEquals("Android", e.getJSONObject("contexts").getJSONObject("os").getString("name"));
        assertEquals("14", e.getJSONObject("contexts").getJSONObject("os").getString("version"));
        assertEquals("Pixel 8", e.getJSONObject("contexts").getJSONObject("device").getString("model"));
        JSONObject extra = e.getJSONObject("extra");
        assertEquals("The picture froze\nafter two minutes", extra.getString("what_happened"));
        assertEquals(report().about, extra.getString("about.txt"));
        assertEquals(report().logs.get("luch.log"), extra.getString("luch.log"));
        assertEquals(report().logs.get("receiver-1.log"), extra.getString("receiver-1.log"));

        Envelope second = new Envelope(seen.get(1).body);
        assertEquals(EVENT_ID, second.header.getString("event_id"));
        assertEquals("attachment", second.item.getString("type"));
        assertEquals("Luch-report-2026-01-31-2015.zip", second.item.getString("filename"));
        assertEquals("application/zip", second.item.getString("content_type"));
        assertEquals("event.attachment", second.item.getString("attachment_type"));
        assertArrayEquals(zip(), second.payload);

        long total = seen.get(0).body.length + seen.get(1).body.length;
        long[] last = progress.get(progress.size() - 1);
        assertEquals(total, last[0]);
        assertEquals(total, last[1]);
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i)[0] >= progress.get(i - 1)[0]);
    }

    @Test
    public void aRefusedAttachmentDoesNotLoseTheReport() throws Exception {
        answers.add(new Object[]{200, "{\"id\":\"" + EVENT_ID + "\"}"});
        answers.add(new Object[]{400, "{\"detail\":\"Invalid envelope item type: attachment\"}"});
        ReportUpload.Sent sent = new ReportUpload(ReportUpload.Dsn.parse(dsn())).send(report(), EVENT_ID, NOW,
                (d, t) -> { });
        assertEquals("LUCH-01234567", sent.number);
        assertNotNull(sent.notAttached);
        assertTrue(sent.notAttached, sent.notAttached.contains("Invalid envelope item type"));
    }

    private ReportUpload.Failure refusal(int status, String body) {
        answers.add(new Object[]{status, body});
        try {
            new ReportUpload(ReportUpload.Dsn.parse(dsn())).send(report(), EVENT_ID, NOW, (d, t) -> { });
        } catch (ReportUpload.Failure f) {
            assertEquals("the attachment is not tried after a refused event", 1, seen.size());
            seen.clear();
            return f;
        }
        fail("sent");
        return null;
    }

    @Test
    public void aRefusedEventSaysWhy() {
        ReportUpload.Failure f = refusal(429, "{\"detail\":\"Too many requests\"}");
        assertEquals(ReportUpload.Why.TOO_MANY, f.why);
        assertEquals(429, f.status);
        f = refusal(413, "");
        assertEquals(ReportUpload.Why.TOO_BIG, f.why);
        f = refusal(403, "{\"detail\":\"Invalid api key\"}");
        assertEquals(ReportUpload.Why.REFUSED, f.why);
        assertEquals("Invalid api key", f.detail);
        f = refusal(400, "{\"error\":\"bad envelope\"}");
        assertEquals("bad envelope", f.detail);
        f = refusal(502, "<html>\n<body>Bad gateway</body>\n</html>");
        assertEquals(ReportUpload.Why.SERVER, f.why);
        assertEquals("<html> <body>Bad gateway</body> </html>", f.detail);
    }

    @Test
    public void nobodyThereIsANetworkFailure() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        try {
            new ReportUpload(ReportUpload.Dsn.parse("http://k@127.0.0.1:" + port + "/1")).send(report(), EVENT_ID, NOW,
                    (d, t) -> { });
            fail("sent");
        } catch (ReportUpload.Failure f) {
            assertEquals(ReportUpload.Why.NETWORK, f.why);
        }
    }

    @Test
    public void bigLogsAreCutToTheirEndsAndTheEventStaysSmall() throws Exception {
        ReportUpload.Report r = report();
        StringBuilder log = new StringBuilder();
        for (int i = 0; log.length() < 1_000_000; i++) log.append("line ").append(i).append(" — ok\n");
        r.logs.clear();
        for (int i = 0; i < 15; i++) r.logs.put("receiver-" + (i + 1) + ".log", log.toString());
        JSONObject e = ReportUpload.event(r, EVENT_ID, NOW);
        byte[] bytes = e.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(String.valueOf(bytes.length), bytes.length < ReportUpload.EVENT_MAX);
        String first = e.getJSONObject("extra").getString("receiver-1.log");
        assertTrue(first.getBytes(StandardCharsets.UTF_8).length <= ReportUpload.LOG_TAIL);
        assertTrue("whole lines", first.startsWith("line "));
        assertTrue("the end of it", log.toString().endsWith(first));
        assertTrue(e.getJSONObject("extra").getString("receiver-15.log").startsWith("(left out"));
    }

    @Test
    public void tailKeepsWholeLinesAndWholeCharacters() {
        assertEquals("c\n", ReportUpload.tail("a\nb\nc\n", 3));
        assertEquals("short", ReportUpload.tail("short", 100));
        // One line longer than the room: its end, not half a letter.
        assertEquals("яя", ReportUpload.tail("яяя", 5));
    }

    @Test
    public void theDsnSaysWhereAndWithWhichKey() {
        ReportUpload.Dsn d = ReportUpload.Dsn.parse("https://0123abcd@reports.example.org/7");
        assertEquals("0123abcd", d.key);
        assertEquals("https://reports.example.org/api/7/envelope/", d.envelope);
        d = ReportUpload.Dsn.parse(" https://key:secret@example.org:8443/glitch/12 ");
        assertEquals("key", d.key);
        assertEquals("https://example.org:8443/glitch/api/12/envelope/", d.envelope);
        assertNull(ReportUpload.Dsn.parse(""));
        assertNull(ReportUpload.Dsn.parse(null));
        assertNull(ReportUpload.Dsn.parse("https://example.org/7"));
        assertNull(ReportUpload.Dsn.parse("https://key@example.org/"));
        assertNull(ReportUpload.Dsn.parse("ftp://key@example.org/7"));
        assertNull(ReportUpload.Dsn.parse("not a dsn"));
    }

    @Test
    public void theNumberAndTheFirstLine() {
        assertEquals("LUCH-ABCDEF01", ReportUpload.number("abcdef0123456789abcdef0123456789"));
        String id = ReportUpload.newEventId();
        assertTrue(id, id.matches("[0-9a-f]{32}"));
        assertEquals("(nothing written)", ReportUpload.firstLine("  "));
        assertEquals("It froze", ReportUpload.firstLine("\n It froze \nthen more"));
    }
}
