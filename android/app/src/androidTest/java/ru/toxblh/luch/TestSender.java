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

/**
 * The sender's side of PROTOCOL.md, just enough to start a session and send the bench clip as H.264. By default a
 * paired sender on the TLS port (its key in the keystore under its own alias), sealing its media as a real one does;
 * {@link #plain} is an older sender on the plain port.
 */
final class TestSender {
    static final String ALIAS = "luch-test-sender";

    private final Socket socket;
    private final OutputStream out;
    private final LinkedBlockingQueue<JSONObject> in = new LinkedBlockingQueue<>();
    private int seq;
    /** This sender's certificate and the receiver's as seen in the handshake (null on the plain port). */
    byte[] ownFp, receiverFp;
    /** The media key from ready, when the session has one. */
    private MediaCrypt crypt;
    String id = "test", name = "Test sender";

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
            reading = false;
        }, "test-sender-read");
        t.setDaemon(true);
        t.start();
    }

    /** A sender paired beforehand, on the TLS port: what every test that is not about pairing uses. */
    static TestSender connect(long ms) throws Exception {
        TestSender s = tls(ms, ALIAS);
        ControlServer.gate(context()).pair(Pairing.hex(s.ownFp), s.id, s.name);
        return s;
    }

    /** A sender with the key under {@code alias} on the TLS port, paired or not. */
    static TestSender tls(long ms, String alias) throws Exception {
        long until = SystemClock.uptimeMillis() + ms;
        while (ControlServer.tlsPort <= 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        Identity me = Identity.load(context(), alias);
        byte[][] seen = new byte[1][];
        javax.net.ssl.X509TrustManager pin = new Identity.AnyCertificate() {
            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                super.checkServerTrusted(chain, authType);
                seen[0] = Identity.fingerprint(chain[0]);
            }
        };
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(new javax.net.ssl.KeyManager[]{Identity.keyManager(me.key, me.cert)},
                new javax.net.ssl.TrustManager[]{pin}, null);
        while (true) {
            try {
                javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                        .createSocket(InetAddress.getByName("127.0.0.1"), ControlServer.tlsPort);
                s.startHandshake();
                TestSender t = new TestSender(s);
                t.ownFp = me.fingerprint;
                t.receiverFp = seen[0];
                return t;
            } catch (IOException e) {
                if (SystemClock.uptimeMillis() > until) throw e;
                SystemClock.sleep(200);
            }
        }
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** An older sender: the plain port, no key, asked about on the TV every time. */
    static TestSender plain(long ms) throws IOException {
        long until = SystemClock.uptimeMillis() + ms;
        // The service picks its port when it starts (another receiver on the device may hold 47800).
        while (LuchService.listening <= 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        while (true) {
            try {
                return new TestSender(new Socket(InetAddress.getByName("127.0.0.1"),
                        LuchService.listening > 0 ? LuchService.listening : Luch.PORT));
            } catch (IOException e) {
                if (SystemClock.uptimeMillis() > until) throw e;
                SystemClock.sleep(200);
            }
        }
    }

    /** Says hello as a sender that wants {@code want}; returns the welcome. */
    JSONObject hello(String... want) throws Exception {
        JSONObject welcome = helloOnly(want);
        assertEquals("welcome: " + welcome, "welcome", welcome.getString("t"));
        return welcome;
    }

    /** Says hello; returns the first answer (welcome, or pair / pair_refused for a sender that is not paired). */
    JSONObject helloOnly(String... want) throws Exception {
        org.json.JSONArray w = new org.json.JSONArray();
        for (String x : want) w.put(x);
        send(new JSONObject().put("t", "hello").put("v", ownFp == null ? 0 : 1).put("id", id).put("name", name)
                .put("app", "test").put("want", w));
        return next(5_000);
    }

    /**
     * The sender's half of the code check (PROTOCOL.md 3a) with {@code code} as typed; returns the receiver's answer
     * after it: "paired" (then the welcome follows) or "pair_refused".
     */
    JSONObject typeCode(String code) throws Exception {
        Pairing p = new Pairing(Pairing.SENDER, code, ownFp, receiverFp);
        for (int r = 0; r < Pairing.DIGITS; r++) {
            send(new JSONObject().put("t", "pair_commit").put("round", r).put("c", p.commit(r)));
            JSONObject c = next(5_000);
            if (!c.getString("t").equals("pair_commit")) return c;
            send(new JSONObject().put("t", "pair_open").put("round", r).put("n", p.open(r)));
            JSONObject o = next(5_000);
            if (!o.getString("t").equals("pair_open")) return o;
            if (!p.check(r, c.getString("c"), o.getString("n"))) throw new AssertionError("the TV failed round " + r);
        }
        return next(5_000);
    }

    /** Whether the receiver has closed the connection (within {@code ms}). */
    boolean closedWithin(long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            if (!reading) return true;
            SystemClock.sleep(50);
        }
        return false;
    }

    private volatile boolean reading = true;

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

    /** The codec a real sender would pick: the receiver's bench "prefer", AVC if it has none. */
    String codec = "video/avc";

    /** Sends start, returns the video port from ready. */
    int start() throws Exception {
        return start(null).getInt("video_port");
    }

    /** Sends start with {@code audio} (null: none), returns the ready message. */
    JSONObject start(JSONObject audio) throws Exception {
        JSONObject bench = Bench.cached(InstrumentationRegistry.getInstrumentation().getTargetContext());
        if (bench != null && !bench.optString("prefer").isEmpty()) codec = bench.optString("prefer");
        if (!codec.equals("video/hevc")) codec = "video/avc";   // the two clips the test can send
        return startCodec(codec, audio);
    }

    JSONObject startCodec(String mime, JSONObject audio) throws Exception {
        codec = mime;
        JSONObject start = new JSONObject().put("t", "start").put("mode", "mirror")
                .put("video", new JSONObject().put("codec", codec).put("width", 1920).put("height", 1080)
                        .put("fps", 30));
        if (audio != null) start.put("audio", audio);
        send(start);
        JSONObject ready = next(5_000);
        assertEquals("ready: " + ready, "ready", ready.getString("t"));
        String key = ready.optString("key");
        crypt = key.isEmpty() ? null : new MediaCrypt(android.util.Base64.decode(key, android.util.Base64.NO_WRAP));
        return ready;
    }

    /** The audio stream's config packet: an OpusHead (stereo, 48 kHz), which is all the receiver needs to set up. */
    void sendOpusHead(int port) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 2).putShort((short) 312)
                .putInt(48000).putShort((short) 0).put((byte) 0);
        byte[] h = head.array();
        int len = crypt == null ? h.length : h.length + MediaCrypt.TAG_BYTES;
        ByteBuffer b = ByteBuffer.allocate(MirrorSession.HEADER + len);
        b.put((byte) 2).put((byte) 2).putShort((short) 0).putShort((short) 1).putShort((short) 0)
                .putInt(0).putInt(0).putInt(len).putShort((short) 0).putShort((short) 0);
        b.put(seal(2, 0, b.array(), h));
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.send(new DatagramPacket(b.array(), b.position(), InetAddress.getByName("127.0.0.1"), port));
        }
    }

    /** The bench clip of {@link #codec} (one access unit per AUD), looped at 30 fps for {@code ms}. */
    void stream(int port, long ms) throws Exception {
        try (DatagramSocket udp = new DatagramSocket()) {
            stream(udp, port, ms);
        }
    }

    /** As {@link #stream(int, long)}, from {@code udp}: the socket the receiver's input comes back to (PROTOCOL.md 4i). */
    void stream(DatagramSocket udp, int port, long ms) throws Exception {
        boolean hevc = codec.equals("video/hevc");
        List<byte[]> frames = accessUnits(readAsset(hevc ? "bench/hevc.h265" : "bench/avc.h264"), hevc);
        {
            InetAddress to = InetAddress.getByName("127.0.0.1"); // IPv4, as the receiver binds it
            long until = SystemClock.uptimeMillis() + ms;
            for (int i = 0; SystemClock.uptimeMillis() < until && !udp.isClosed(); i++) {
                byte[] f = frames.get(i % frames.size());
                boolean idr = hevc ? hasNal(f, 19, true) || hasNal(f, 20, true) : hasNal(f, 5, false);
                boolean config = hasNal(f, hevc ? 32 : 7, hevc);   // VPS / SPS
                int flags = (idr ? 1 : 0) | (config ? 2 : 0);
                sendFrame(udp, to, port, f, flags);
                SystemClock.sleep(33);
            }
        }
    }

    /** {@code data} as it goes on the wire: sealed when the session has a key (MediaCrypt), else as it is. */
    private byte[] seal(int kind, long seq, byte[] header, byte[] data) throws IOException {
        if (crypt == null) return data;
        try {
            return crypt.seal(kind, seq, MediaCrypt.aad(header, 0), data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Leaves the media unsealed even when the session has a key: what a stranger on the LAN would send. */
    boolean forge;

    private void sendFrame(DatagramSocket udp, InetAddress to, int port, byte[] plain, int flags) throws IOException {
        int s = seq++;
        int pts = (int) (SystemClock.uptimeMillis() * 1000);
        int wireLen = crypt == null || forge ? plain.length : plain.length + MediaCrypt.TAG_BYTES;
        int shards = (wireLen + MirrorSession.SHARD - 1) / MirrorSession.SHARD;
        byte[] head = ByteBuffer.allocate(MirrorSession.HEADER).put((byte) 1).put((byte) flags).putShort((short) 0)
                .putShort((short) shards).putShort((short) 0).putInt(s).putInt(pts).putInt(wireLen).putShort((short) 0)
                .putShort((short) 0).array();
        byte[] f = forge ? plain : seal(1, s, head, plain);
        for (int i = 0; i < shards; i++) {
            int off = i * MirrorSession.SHARD, len = Math.min(MirrorSession.SHARD, f.length - off);
            ByteBuffer b = ByteBuffer.allocate(MirrorSession.HEADER + len);
            b.put((byte) 1).put((byte) flags).putShort((short) i).putShort((short) shards).putShort((short) 0)
                    .putInt(s).putInt(pts).putInt(f.length)
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

    /** Splits Annex-B H.264 or HEVC at access unit delimiters (the clips are made with aud=1). */
    private static List<byte[]> accessUnits(byte[] es, boolean hevc) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < es.length; i++) {
            if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 0 && es[i + 3] == 1 && nalType(es[i + 4], hevc) == (hevc ? 35 : 9)) {
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

    private static boolean hasNal(byte[] f, int type, boolean hevc) {
        for (int i = 0; i + 3 < f.length; i++) {
            if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 1 && nalType(f[i + 3], hevc) == type) return true;
        }
        return false;
    }

    private static int nalType(byte b, boolean hevc) {
        return hevc ? (b >> 1) & 0x3f : b & 0x1f;
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
