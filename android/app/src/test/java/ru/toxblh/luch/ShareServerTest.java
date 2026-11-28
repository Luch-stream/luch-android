// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ShareServerTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** The same answers as luchd's cast::tests::ranges, so both senders serve a seek alike. */
    @Test
    public void rangesAsLuchd() {
        assertArrayEquals(new long[]{0, 99}, ShareServer.parseRange("bytes=0-", 100));
        assertArrayEquals(new long[]{10, 19}, ShareServer.parseRange("bytes=10-19", 100));
        assertArrayEquals(new long[]{90, 99}, ShareServer.parseRange("bytes=90-200", 100));
        assertArrayEquals(new long[]{90, 99}, ShareServer.parseRange("bytes=-10", 100));
        assertNull(ShareServer.parseRange("bytes=100-", 100));
        assertNull(ShareServer.parseRange("bytes=5-1", 100));
        assertNull(ShareServer.parseRange("items=0-1", 100));
        assertArrayEquals(new long[]{0, 4}, ShareServer.parseRange("bytes=0-4, 10-20", 100));
        assertNull(ShareServer.parseRange("bytes=0-", 0));
    }

    private static byte[] data() {
        byte[] d = new byte[200_000];
        for (int i = 0; i < d.length; i++) d[i] = (byte) (i * 31 + 7);
        return d;
    }

    private static ShareServer.Source source(byte[] d, boolean knownLength) {
        return new ShareServer.Source() {
            @Override
            public long length() {
                return knownLength ? d.length : -1;
            }

            @Override
            public InputStream open(long from) {
                return new ByteArrayInputStream(d, (int) from, d.length - (int) from);
            }
        };
    }

    /** The head and the body of one request. */
    private static final class Answer {
        final String head;
        final byte[] body;

        Answer(String head, byte[] body) {
            this.head = head;
            this.body = body;
        }
    }

    private static Answer get(int port, String method, String path, String range) throws IOException {
        try (Socket s = new Socket(LOOPBACK, port)) {
            OutputStream o = s.getOutputStream();
            o.write((method + " " + path + " HTTP/1.1\r\nHost: x\r\n" + (range == null ? "" : "Range: " + range + "\r\n")
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) all.write(buf, 0, n);
            byte[] b = all.toByteArray();
            int split = 0;
            for (int i = 0; i + 3 < b.length; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                    split = i + 4;
                    break;
                }
            }
            return new Answer(new String(b, 0, split, StandardCharsets.US_ASCII),
                    Arrays.copyOfRange(b, split, b.length));
        }
    }

    private static String path(String url) {
        return url.substring(url.indexOf('/', "http://".length()));
    }

    @Test
    public void servesWholeFilesAndRanges() throws Exception {
        byte[] d = data();
        try (ShareServer srv = new ShareServer(LOOPBACK, LOOPBACK)) {
            String url = srv.share(source(d, true), "video/mp4", "Holiday clip.mp4", "127.0.0.1");
            assertTrue(url, url.startsWith("http://127.0.0.1:" + srv.port() + "/"));
            assertTrue(url, url.endsWith("/0/Holiday_clip.mp4"));

            Answer all = get(srv.port(), "GET", path(url), null);
            assertTrue(all.head, all.head.startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(all.head, all.head.contains("Content-Type: video/mp4\r\n"));
            assertTrue(all.head, all.head.contains("Accept-Ranges: bytes\r\n"));
            assertTrue(all.head, all.head.contains("Content-Length: 200000\r\n"));
            assertArrayEquals(d, all.body);

            Answer part = get(srv.port(), "GET", path(url), "bytes=150000-150009");
            assertTrue(part.head, part.head.startsWith("HTTP/1.1 206 Partial Content\r\n"));
            assertTrue(part.head, part.head.contains("Content-Range: bytes 150000-150009/200000\r\n"));
            assertTrue(part.head, part.head.contains("Content-Length: 10\r\n"));
            assertArrayEquals(Arrays.copyOfRange(d, 150000, 150010), part.body);

            Answer tail = get(srv.port(), "GET", path(url), "bytes=-5");
            assertArrayEquals(Arrays.copyOfRange(d, d.length - 5, d.length), tail.body);

            Answer open = get(srv.port(), "GET", path(url), "bytes=199990-");
            assertTrue(open.head, open.head.contains("Content-Range: bytes 199990-199999/200000\r\n"));
            assertEquals(10, open.body.length);

            Answer bad = get(srv.port(), "GET", path(url), "bytes=300000-");
            assertTrue(bad.head, bad.head.startsWith("HTTP/1.1 416 "));
            assertTrue(bad.head, bad.head.contains("Content-Range: bytes */200000\r\n"));

            Answer head = get(srv.port(), "HEAD", path(url), null);
            assertTrue(head.head, head.head.contains("Content-Length: 200000\r\n"));
            assertEquals(0, head.body.length);
        }
    }

    @Test
    public void wrongTokenOrIndexIsNotFound() throws Exception {
        try (ShareServer srv = new ShareServer(LOOPBACK, LOOPBACK)) {
            String url = srv.share(source(data(), true), "image/jpeg", "a.jpg", "127.0.0.1");
            String token = path(url).split("/")[1];
            assertTrue(get(srv.port(), "GET", "/" + token + "/1", null).head.startsWith("HTTP/1.1 404 "));
            assertTrue(get(srv.port(), "GET", "/0123456789abcdef0123456789abcdef/0", null).head.startsWith("HTTP/1.1 404 "));
            assertTrue(get(srv.port(), "GET", "/", null).head.startsWith("HTTP/1.1 404 "));
            assertTrue(get(srv.port(), "POST", path(url), null).head.startsWith("HTTP/1.1 404 "));
            // the photo receiver's plain GET of /<token>/<n>, without a name
            assertTrue(get(srv.port(), "GET", "/" + token + "/0", null).head.startsWith("HTTP/1.1 200 "));
        }
    }

    /** Only the receiver it was shared with gets anything: anyone else is closed on without an answer. */
    @Test
    public void otherAddressesGetNothing() throws Exception {
        try (ShareServer srv = new ShareServer(LOOPBACK, InetAddress.getByName("192.0.2.7"))) {
            String url = srv.share(source(data(), true), "image/jpeg", "a.jpg", "127.0.0.1");
            try {
                Answer a = get(srv.port(), "GET", path(url), null);
                assertEquals("", a.head);
                assertEquals(0, a.body.length);
            } catch (java.net.SocketException reset) {
                // closed before the request was read: nothing either
            }
        }
    }

    /** A source of unknown length (a relayed link) is sent whole, ranges or not, and ends with the connection. */
    @Test
    public void unknownLengthIsStreamedWhole() throws Exception {
        byte[] d = data();
        try (ShareServer srv = new ShareServer(LOOPBACK, LOOPBACK)) {
            String url = srv.share(source(d, false), "application/pdf", "talk.pdf", "127.0.0.1");
            Answer a = get(srv.port(), "GET", path(url), "bytes=10-20");
            assertTrue(a.head, a.head.startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(a.head, !a.head.contains("Content-Length"));
            assertArrayEquals(d, a.body);
        }
    }

    @Test
    public void namesInLinksAreSafe() {
        assertEquals("IMG_2024.jpg", ShareServer.pathName("IMG_2024.jpg"));
        assertEquals("___.mp4", ShareServer.pathName("?#/.mp4"));
        assertEquals("file", ShareServer.pathName(""));
        assertEquals("_________.pdf", ShareServer.pathName("Презентац.pdf"));
        assertEquals(80, ShareServer.pathName(new String(new char[200]).replace('\0', 'a')).length());
    }

    @Test
    public void typesByName() {
        assertEquals("video/mp4", ShareServer.mimeOf("clip.MOV"));
        assertEquals("audio/mpeg", ShareServer.mimeOf("https://example.com/song.mp3?x=1#t"));
        assertEquals("application/vnd.apple.mpegurl", ShareServer.mimeOf("https://example.com/live/index.m3u8"));
        assertEquals("application/pdf", ShareServer.mimeOf("slides.pdf"));
        assertEquals("", ShareServer.mimeOf("https://example.com/watch"));
        assertEquals("", ShareServer.mimeOf(null));
    }
}
