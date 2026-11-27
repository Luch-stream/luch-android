// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a sender serves for media (PROTOCOL.md 4b): files from the test assets over HTTP/1.1 with Range, on loopback. */
final class TestHttp implements AutoCloseable {
    private final ServerSocket server;
    private final AssetManager assets;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> ranges = new CopyOnWriteArrayList<>();
    /** Files made by a test (a PDF drawn on the device), served before the assets. */
    private final java.util.Map<String, byte[]> made = new java.util.concurrent.ConcurrentHashMap<>();

    TestHttp(AssetManager assets) throws IOException {
        this.assets = assets;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::loop, "test-http");
        t.setDaemon(true);
        t.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + path;
    }

    private void loop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "test-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Serves {@code body} at {@code path} from now on. */
    void put(String path, byte[] body) {
        made.put(path, body);
    }

    private byte[] file(String path) throws IOException {
        byte[] own = made.get(path);
        if (own != null) return own;
        try (InputStream in = assets.open("media/" + path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = sock.getOutputStream();
            while (true) {
                String line = r.readLine();
                if (line == null || line.isEmpty()) return;
                String[] req = line.split(" ");
                String range = null;
                for (String h; (h = r.readLine()) != null && !h.isEmpty(); ) {
                    if (h.toLowerCase().startsWith("range:")) range = h.substring(6).trim();
                }
                String path = req.length > 1 ? req[1].substring(1) : "";
                requests.add(path);
                byte[] body;
                try {
                    body = file(path);
                } catch (IOException missing) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }
                String type = path.endsWith(".vtt") ? "text/vtt" : path.endsWith(".pdf") ? "application/pdf" : "video/mp4";
                int start = 0, end = body.length - 1;
                String status = "200 OK";
                String extra = "";
                if (range != null) {
                    ranges.add(range);
                    Matcher m = Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
                    if (m.find()) {
                        start = Integer.parseInt(m.group(1));
                        if (!m.group(2).isEmpty()) end = Math.min(end, Integer.parseInt(m.group(2)));
                    }
                    status = "206 Partial Content";
                    extra = "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n";
                }
                String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nAccept-Ranges: bytes\r\n" + extra
                        + "Content-Length: " + (end - start + 1) + "\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                if (!"HEAD".equals(req[0])) out.write(body, start, end - start + 1);
                out.flush();
            }
        } catch (IOException ignored) {
            // the player closed the connection
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
