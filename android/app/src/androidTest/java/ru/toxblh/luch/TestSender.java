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

    /**
     * The sender's pts clock (µs): this device's monotonic clock 7 s ahead, so that a receiver which did not map it
     * through its clock estimate would be 7 s off.
     */
    static long senderUs() {
        return System.nanoTime() / 1000 + 7_000_000;
    }

    /** The playout delay D written into every packet (0.1 ms; 0 = as soon as possible, what an older sender sends). */
    volatile int delayUnits;
    /** Clock requests seen and answered (kind 3), over both media sockets. */
    final java.util.concurrent.atomic.AtomicInteger clockRequests = new java.util.concurrent.atomic.AtomicInteger();
    /** Whether kind 3 requests are answered: an older sender does not. */
    volatile boolean answerClock = true;
    private DatagramSocket videoUdp, audioUdp;

    /** The media sockets: one per kind, kept for the session so that the receiver's clock requests come back to them. */
    private synchronized DatagramSocket udp(boolean audio) throws IOException {
        DatagramSocket s = audio ? audioUdp : videoUdp;
        if (s != null) return s;
        DatagramSocket made = new DatagramSocket();
        if (audio) audioUdp = made;
        else videoUdp = made;
        Thread t = new Thread(() -> answer(made), "test-sender-clock");
        t.setDaemon(true);
        t.start();
        return made;
    }

    /** Answers kind 3 requests on {@code s} as PROTOCOL.md 4k says: t1 back, with t2 and t3 on the pts clock. */
    private void answer(DatagramSocket s) {
        byte[] b = new byte[64];
        DatagramPacket p = new DatagramPacket(b, b.length);
        try {
            while (!s.isClosed()) {
                s.receive(p);
                long t2 = senderUs();
                if (p.getLength() < 32 || b[0] != 3 || (b[1] & 16) != 0) continue;
                clockRequests.incrementAndGet();
                if (!answerClock) continue;
                ByteBuffer r = ByteBuffer.allocate(48);
                r.put(b, 0, 24).putLong(ByteBuffer.wrap(b, 24, 8).getLong());
                r.put(1, (byte) 16).putInt(16, 24);
                r.position(32);
                r.putLong(t2).putLong(senderUs());
                s.send(new DatagramPacket(r.array(), 48, p.getAddress(), p.getPort()));
            }
        } catch (IOException ignored) {
            // closed
        }
    }

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

    /** A start's "group" (PROTOCOL.md 4k), null for none: what an older sender sends. */
    JSONObject group;

    JSONObject startCodec(String mime, JSONObject audio) throws Exception {
        codec = mime;
        JSONObject start = new JSONObject().put("t", "start").put("mode", "mirror")
                .put("video", new JSONObject().put("codec", codec).put("width", 1920).put("height", 1080)
                        .put("fps", 30));
        if (audio != null) start.put("audio", audio);
        if (group != null) start.put("group", group);
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
                .putInt(0).putInt((int) senderUs()).putInt(len).putShort((short) 0).putShort((short) delayUnits);
        b.put(seal(2, 0, b.array(), h));
        udp(true).send(new DatagramPacket(b.array(), b.position(), InetAddress.getByName("127.0.0.1"), port));
    }

    /**
     * Digital silence as the sender's Opus encoder makes it (libopus, CELT 5 ms, 128 kbit/s CBR, stereo): what a
     * receiver decodes and plays, without a sound. Tests never send anything louder.
     */
    static byte[] silentOpus() {
        byte[] p = new byte[80];
        p[0] = (byte) 0xec;   // TOC: CELT full band, 5 ms, stereo, one frame
        p[1] = (byte) 0xff;
        p[2] = (byte) 0xfe;
        return p;
    }

    /** Silent Opus frames every 5 ms to {@code port} for {@code ms}, on a thread of their own; pts on the video's clock. */
    Thread streamSilence(int port, long ms) {
        Thread t = new Thread(() -> {
            byte[] f = silentOpus();
            try {
                DatagramSocket udp = udp(true);
                InetAddress to = InetAddress.getByName("127.0.0.1");
                long start = SystemClock.uptimeMillis();
                long pts0 = senderUs();
                for (int s = 0; SystemClock.uptimeMillis() - start < ms; s++) {
                    // The pts of a 5 ms chunk, as an encoder stamps it: its first sample's capture time.
                    long pts = pts0 + s * 5000L;
                    int wireLen = crypt == null ? f.length : f.length + MediaCrypt.TAG_BYTES;
                    byte[] head = ByteBuffer.allocate(MirrorSession.HEADER).put((byte) 2).put((byte) 0)
                            .putShort((short) 0).putShort((short) 1).putShort((short) 0).putInt(s).putInt((int) pts)
                            .putInt(wireLen).putShort((short) 0).putShort((short) delayUnits).array();
                    byte[] w = seal(2, s, head, f);
                    ByteBuffer b = ByteBuffer.allocate(MirrorSession.HEADER + w.length).put(head).put(w);
                    udp.send(new DatagramPacket(b.array(), b.position(), to, port));
                    long next = start + (s + 1) * 5L;
                    long wait = next - SystemClock.uptimeMillis();
                    if (wait > 0) SystemClock.sleep(wait);
                }
            } catch (IOException ignored) {
                // the session ended
            }
        }, "test-sender-audio");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** The bench clip of {@link #codec} (one access unit per AUD), looped at 30 fps for {@code ms}. */
    void stream(int port, long ms) throws Exception {
        stream(udp(false), port, ms); // the socket that answers the receiver's clock requests
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
        int pts = (int) senderUs();
        int wireLen = crypt == null || forge ? plain.length : plain.length + MediaCrypt.TAG_BYTES;
        int shards = (wireLen + MirrorSession.SHARD - 1) / MirrorSession.SHARD;
        byte[] head = ByteBuffer.allocate(MirrorSession.HEADER).put((byte) 1).put((byte) flags).putShort((short) 0)
                .putShort((short) shards).putShort((short) 0).putInt(s).putInt(pts).putInt(wireLen).putShort((short) 0)
                .putShort((short) delayUnits).array();
        byte[] f = forge ? plain : seal(1, s, head, plain);
        for (int i = 0; i < shards; i++) {
            int off = i * MirrorSession.SHARD, len = Math.min(MirrorSession.SHARD, f.length - off);
            ByteBuffer b = ByteBuffer.allocate(MirrorSession.HEADER + len);
            b.put((byte) 1).put((byte) flags).putShort((short) i).putShort((short) shards).putShort((short) 0)
                    .putInt(s).putInt(pts).putInt(f.length)
                    .putShort((short) 0).putShort((short) delayUnits).put(f, off, len);
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
        synchronized (this) {
            if (videoUdp != null) videoUdp.close();
            if (audioUdp != null) audioUdp.close();
        }
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
