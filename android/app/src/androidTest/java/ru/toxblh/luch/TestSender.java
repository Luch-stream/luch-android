// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** The sender's side of PROTOCOL.md, just enough to start a session and send the bench clip as H.264. */
final class TestSender {
    private final Socket socket;
    private final OutputStream out;
    private final LinkedBlockingQueue<JSONObject> in = new LinkedBlockingQueue<>();
    private int seq;

    private TestSender(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread t = new Thread(() -> {
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.isEmpty()) in.add(new JSONObject(line));
                }
            } catch (Exception ignored) {
                // closed
            }
        }, "test-sender-read");
        t.setDaemon(true);
        t.start();
    }

    static TestSender connect(long ms) throws IOException {
        long until = SystemClock.uptimeMillis() + ms;
        while (true) {
            try {
                return new TestSender(new Socket(InetAddress.getLoopbackAddress(), Luch.PORT));
            } catch (IOException e) {
                if (SystemClock.uptimeMillis() > until) throw e;
                SystemClock.sleep(200);
            }
        }
    }

    /** Says hello as a sender that wants {@code want}; returns the welcome. */
    JSONObject hello(String... want) throws Exception {
        org.json.JSONArray w = new org.json.JSONArray();
        for (String x : want) w.put(x);
        send(new JSONObject().put("t", "hello").put("id", "test").put("name", "Test sender").put("app", "test")
                .put("want", w));
        JSONObject welcome = next(5_000);
        assertEquals("welcome", welcome.getString("t"));
        return welcome;
    }

    void send(JSONObject m) throws IOException {
        out.write((m + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Next message other than ping/pong/bench chatter. */
    JSONObject next(long ms) throws Exception {
        long until = SystemClock.uptimeMillis() + ms;
        while (true) {
            JSONObject m = in.poll(Math.max(1, until - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
            if (m == null) throw new AssertionError("no message from the receiver");
            String t = m.getString("t");
            if (t.equals("ping")) {
                send(new JSONObject().put("t", "pong").put("n", m.optInt("n")));
                continue;
            }
            if (t.equals("pong") || t.equals("bench_result")) continue;
            return m;
        }
    }

    List<JSONObject> drain() {
        List<JSONObject> all = new ArrayList<>();
        in.drainTo(all);
        return all;
    }

    /** Sends start, returns the video port from ready. */
    int start() throws Exception {
        send(new JSONObject().put("t", "start").put("mode", "mirror")
                .put("video", new JSONObject().put("codec", "video/avc").put("width", 1920).put("height", 1080)
                        .put("fps", 30)));
        JSONObject ready = next(5_000);
        assertEquals("ready: " + ready, "ready", ready.getString("t"));
        return ready.getInt("video_port");
    }

    /** The bench clip (one access unit per AUD), looped at 30 fps for {@code ms}. */
    void stream(int port, long ms) throws Exception {
        List<byte[]> frames = accessUnits(readAsset("bench/avc.h264"));
        try (DatagramSocket udp = new DatagramSocket()) {
            InetAddress to = InetAddress.getLoopbackAddress();
            long until = SystemClock.uptimeMillis() + ms;
            for (int i = 0; SystemClock.uptimeMillis() < until; i++) {
                byte[] f = frames.get(i % frames.size());
                int flags = (hasNal(f, 5) ? 1 : 0) | (hasNal(f, 7) ? 2 : 0);
                sendFrame(udp, to, port, f, flags);
                SystemClock.sleep(33);
            }
        }
    }

    private void sendFrame(DatagramSocket udp, InetAddress to, int port, byte[] f, int flags) throws IOException {
        int shards = (f.length + MirrorSession.SHARD - 1) / MirrorSession.SHARD;
        int s = seq++;
        for (int i = 0; i < shards; i++) {
            int off = i * MirrorSession.SHARD, len = Math.min(MirrorSession.SHARD, f.length - off);
            ByteBuffer b = ByteBuffer.allocate(MirrorSession.HEADER + len);
            b.put((byte) 1).put((byte) flags).putShort((short) i).putShort((short) shards).putShort((short) 0)
                    .putInt(s).putInt((int) (SystemClock.uptimeMillis() * 1000)).putInt(f.length)
                    .putShort((short) 0).putShort((short) 0).put(f, off, len);
            udp.send(new DatagramPacket(b.array(), b.position(), to, port));
        }
    }

    private static byte[] readAsset(String name) throws IOException {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream s = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = s.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    /** Splits Annex-B H.264 at access unit delimiters (the clip is made with aud=1). */
    private static List<byte[]> accessUnits(byte[] es) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < es.length; i++) {
            if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 0 && es[i + 3] == 1 && (es[i + 4] & 0x1f) == 9) {
                starts.add(i);
            }
        }
        starts.add(es.length);
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k + 1 < starts.size(); k++) {
            byte[] f = new byte[starts.get(k + 1) - starts.get(k)];
            System.arraycopy(es, starts.get(k), f, 0, f.length);
            out.add(f);
        }
        return out;
    }

    private static boolean hasNal(byte[] f, int type) {
        for (int i = 0; i + 3 < f.length; i++) {
            if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 1 && (f[i + 3] & 0x1f) == type) return true;
        }
        return false;
    }

    void close() {
        try {
            send(new JSONObject().put("t", "stop"));
            send(new JSONObject().put("t", "bye"));
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
