// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What this phone shares with a receiver (PROTOCOL.md 4a, 4b, 4g): the files stay where they are and are offered over
 * plain HTTP at {@code /<token>/<n>/<name>}, to the receiver's address only, while the show lasts. HTTP/1.1 with one
 * {@code Range} per request, so a video can be seeked; one request per connection, which every receiver handles.
 * Plain Java (the content:// side is a {@link Source}), so it runs in JVM tests.
 */
final class ShareServer implements Closeable {
    private static final int MAX_CONNECTIONS = 8;

    /** One shared thing: a file behind a content:// link, or another link relayed. */
    interface Source {
        /** Its length in bytes, -1 when unknown (then no ranges). */
        long length();

        /** Its bytes from {@code from} on. */
        InputStream open(long from) throws IOException;
    }

    private static final class Entry {
        final Source source;
        final String mime;

        Entry(Source source, String mime) {
            this.source = source;
            this.mime = mime;
        }
    }

    private final InetAddress client;
    private final String token;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final ServerSocket server;
    private int connections;

    /** Listens on {@code bind} (the address this phone has towards {@code client}), on a port of its own. */
    ShareServer(InetAddress bind, InetAddress client) throws IOException {
        this.client = client;
        byte[] t = new byte[16];
        new SecureRandom().nextBytes(t);
        token = Pairing.hex(t);
        server = new ServerSocket(0, 8, bind);
        Thread accept = new Thread(this::acceptLoop, "luch-share-http");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Offers {@code s}; the link the receiver fetches it at, on this phone's {@code host}. */
    String share(Source s, String mime, String name, String host) {
        entries.add(new Entry(s, mime));
        int n = entries.size() - 1;
        return "http://" + host + ":" + port() + "/" + token + "/" + n + "/" + pathName(name);
    }

    /** The name in a link: what the receiver may show, or guess the type from; nothing that breaks a URL. */
    static String pathName(String name) {
        StringBuilder b = new StringBuilder();
        for (char c : (name == null ? "" : name).toCharArray()) {
            b.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String s = b.length() > 80 ? b.substring(b.length() - 80) : b.toString();
        return s.isEmpty() ? "file" : s;
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            synchronized (this) {
                if (connections >= MAX_CONNECTIONS) {
                    closeQuietly(c);
                    continue;
                }
                connections++;
            }
            Thread t = new Thread(() -> {
                try {
                    serve(c);
                } finally {
                    synchronized (this) {
                        connections--;
                    }
                }
            }, "luch-share-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket c) {
        try (Socket s = c) {
            if (!s.getInetAddress().equals(client)) return;   // only the receiver this was shared with
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String request = line(in);
            String range = null;
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("range")) {
                    range = h.substring(colon + 1).trim();
                }
            }
            OutputStream out = s.getOutputStream();
            String[] parts = request == null ? new String[0] : request.split(" ");
            boolean head = parts.length > 0 && parts[0].equals("HEAD");
            Entry e = parts.length > 1 && (head || parts[0].equals("GET")) ? find(parts[1]) : null;
            if (e == null) {
                out.write(status(404, "Not Found").append("Content-Length: 0\r\n\r\n").toString()
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            long size = e.source.length();
            long start = 0, end = size - 1;
            StringBuilder h = status(200, "OK");
            if (range != null && size >= 0) {
                long[] r = parseRange(range, size);
                if (r == null) {
                    out.write(status(416, "Range Not Satisfiable").append("Content-Range: bytes */").append(size)
                            .append("\r\nContent-Length: 0\r\n\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                start = r[0];
                end = r[1];
                h = status(206, "Partial Content").append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(size).append("\r\n");
            }
            h.append("Content-Type: ").append(e.mime).append("\r\n");
            if (size >= 0) h.append("Accept-Ranges: bytes\r\nContent-Length: ").append(end - start + 1).append("\r\n");
            out.write(h.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            if (head) return;
            long left = size >= 0 ? end - start + 1 : Long.MAX_VALUE;
            try (InputStream src = e.source.open(start)) {
                byte[] buf = new byte[64 * 1024];
                while (left > 0) {
                    int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            out.flush();
        } catch (IOException e) {
            // the receiver let go (a seek ends the request before): nothing to do
        }
    }

    /** The entry at {@code /<token>/<n>[/name]}, null for anything else. */
    private Entry find(String path) {
        String[] p = path.split("/");
        if (p.length < 3 || !p[0].isEmpty() || !token.equals(p[1])) return null;
        try {
            int n = Integer.parseInt(p[2]);
            return n >= 0 && n < entries.size() ? entries.get(n) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static StringBuilder status(int code, String reason) {
        return new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason)
                .append("\r\nConnection: close\r\n");
    }

    /**
     * {@code bytes=a-b}, {@code bytes=a-} or {@code bytes=-n} of something {@code size} long: the first and last byte,
     * or null when it cannot be served (luchd's cast::parse_range, the same answers).
     */
    static long[] parseRange(String r, long size) {
        String spec = r.trim();
        if (!spec.startsWith("bytes=") || size <= 0) return null;
        spec = spec.substring(6);
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma);
        spec = spec.trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash), b = spec.substring(dash + 1);
        long start, end;
        try {
            if (a.isEmpty()) {
                start = Math.max(0, size - Long.parseLong(b));
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return start <= end && start < size && start >= 0 ? new long[]{start, end} : null;
    }

    /** One header line in ASCII, at most 8 KiB; null at the end. */
    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("header too long");
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    /** "video/mp4" for "clip.MP4": the type by the name's extension, "" when unknown (luchd's cast::mime_of). */
    static String mimeOf(String name) {
        String path = name == null ? "" : name;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        path = path.substring(path.lastIndexOf('/') + 1);
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": case "m4v": case "mov": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "ts": case "m2ts": return "video/mp2t";
            case "3gp": return "video/3gpp";
            case "m3u8": return "application/vnd.apple.mpegurl";
            case "mpd": return "application/dash+xml";
            case "mp3": return "audio/mpeg";
            case "m4a": case "m4b": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": case "oga": case "opus": return "audio/ogg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "heic": case "heif": return "image/heif";
            case "pdf": return "application/pdf";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            default: return "";
        }
    }
}
