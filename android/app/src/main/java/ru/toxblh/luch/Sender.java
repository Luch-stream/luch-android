// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * This device showing its own screen on a receiver (a Steam Deck or a computer with luch-recv, a TV): the other direction
 * of Luch. The screen is captured with MediaProjection into a hardware encoder, and the access units go out as the packets
 * PROTOCOL.md section 4 describes. One session at a time; {@link #active()} also tells the receiving side to say "busy".
 */
final class Sender {
    private static final String TAG = LuchService.TAG;
    static final int SHARD = 1376, HEADER = 24;
    private static final int MAX_LONG_SIDE = 1920, FPS = 60;

    static volatile Session current;

    private Sender() {
    }

    static boolean active() {
        Session s = current;
        return s != null && !s.ended;
    }

    /** The figures of the last second, for the screen. */
    static final class Live {
        final String codec, res, encoder, peerName, peerAddress;
        final int fps, lost;
        final double mbps, rttMs, delayMs;
        final long sinceMs;

        Live(String codec, String res, String encoder, String peerName, String peerAddress, int fps, int lost, double mbps,
                double rttMs, double delayMs, long sinceMs) {
            this.codec = codec;
            this.res = res;
            this.encoder = encoder;
            this.peerName = peerName;
            this.peerAddress = peerAddress;
            this.fps = fps;
            this.lost = lost;
            this.mbps = mbps;
            this.rttMs = rttMs;
            this.delayMs = delayMs;
            this.sinceMs = sinceMs;
        }
    }

    /** Starts showing the screen on {@code to}; {@code codecPref} is "auto", "hevc" or "avc". */
    static synchronized Session begin(Context context, Luch.Receiver to, String codecPref, MediaProjection projection, boolean audio) {
        Session old = current;
        if (old != null) old.end("replaced");
        Session s = new Session(context.getApplicationContext(), to, codecPref, projection, audio);
        current = s;
        Thread t = new Thread(s::run, "luch-sender");
        t.start();
        return s;
    }

    static void stop() {
        Session s = current;
        // the end message goes over the network: never on the thread that was clicked
        if (s != null) new Thread(() -> s.end("stopped"), "luch-sender-stop").start();
    }

    static boolean canEncode(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) return true;
            }
        }
        return false;
    }

    /** Where {@link #frame} puts each packet: the first {@code length} bytes of its buffer. */
    interface Packets {
        void send(int length) throws IOException;
    }

    /**
     * One access unit as the packets of PROTOCOL.md 4, each built in {@code packet} (HEADER + SHARD bytes) and handed
     * to {@code out}; over TLS ({@code c}) the frame is sealed as a whole first, the tag at its end.
     */
    static void frame(byte[] packet, MediaCrypt c, int seq, boolean key, long pts, int host, byte[] data, Packets out)
            throws IOException {
        int len = c == null ? data.length : data.length + MediaCrypt.TAG_BYTES;
        int shards = Math.max(1, (len + SHARD - 1) / SHARD);
        ByteBuffer.wrap(packet).put((byte) 1).put((byte) (key ? 1 : 0)).putShort((short) 0).putShort((short) shards)
                .putShort((short) 0).putInt(seq).putInt((int) (pts & 0xFFFFFFFFL)).putInt(len).putShort((short) host)
                .putShort((short) 0);
        byte[] wire = data;
        if (c != null) {
            try {
                wire = c.seal(1, seq & 0xFFFFFFFFL, MediaCrypt.aad(packet, 0), data);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
        for (int k = 0; k < shards; k++) {
            int off = k * SHARD, n = Math.min(SHARD, wire.length - off);
            ByteBuffer.wrap(packet).putShort(2, (short) k);
            System.arraycopy(wire, off, packet, HEADER, n);
            out.send(HEADER + n);
        }
    }

    static final class Session {
        private final Context context;
        final Luch.Receiver to;
        private final String pref;
        private final MediaProjection projection;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final long startedAt = SystemClock.elapsedRealtime();
        volatile boolean ended;
        volatile Live live;
        volatile String error = "";
        /** The receiver's welcome, and whether it let this connection in (it answered the start): a report asks it then. */
        volatile JSONObject welcome;
        volatile boolean letIn;
        /** The "logs" and "error" lines while a report waits for the receiver's log; null otherwise. */
        private volatile java.util.concurrent.BlockingQueue<JSONObject> logLines;
        private final Object logLock = new Object();

        /** The control connection: paired over TLS when the receiver can, so the TV asks only once (Link). */
        private volatile Link control;
        /** The media key of the session over TLS (PROTOCOL.md 4, sealed media); null on the plain port. */
        private volatile MediaCrypt crypt;
        private DatagramSocket udp;
        private VirtualDisplay display;
        private MediaCodec encoder;
        private volatile boolean wantKey;
        private volatile double rtt;
        private volatile int rxLost;
        private volatile double rxDecodeMs;
        private long pingSentAt;
        private int pingN;
        private int seq;
        private byte[] config;
        private volatile int readyPort;
        private final boolean wantAudio;
        private volatile int audioPort;
        private AudioFeed feed;
        private volatile int[] pendingSize;
        private int[] currentSize;
        private int dpi;
        private DisplayManager.DisplayListener rotation;

        Session(Context context, Luch.Receiver to, String pref, MediaProjection projection, boolean audio) {
            this.wantAudio = audio && AudioFeed.possible(context);
            this.context = context;
            this.to = to;
            this.pref = pref;
            this.projection = projection;
        }

        void end(String why) {
            synchronized (this) {
                if (ended) return;
                ended = true;
            }
            // the projection and the buttons call this on the main thread; the goodbye goes over the network
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                new Thread(() -> finish(why), "luch-sender-end").start();
            } else {
                finish(why);
            }
        }

        private void finish(String why) {
            Log.i(TAG, "sender ended: " + why);
            try {
                send(new JSONObject().put("t", "stop"));
                send(new JSONObject().put("t", "bye"));
            } catch (JSONException | IOException ignored) {
            }
            if (feed != null) feed.stop();
            Link l = control;
            if (l != null) l.close();
            if (udp != null) udp.close();
            if (rotation != null) {
                ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(rotation);
            }
            try {
                if (display != null) display.release();
            } catch (RuntimeException ignored) {
            }
            MediaCodec e = encoder;
            if (e != null) {
                try {
                    e.stop();
                } catch (RuntimeException ignored) {
                }
                e.release();
            }
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            main.post(() -> {
                context.stopService(new android.content.Intent(context, SenderService.class));
                Luch.notifyListeners();
            });
        }

        private void send(JSONObject o) throws IOException {
            Link l = control;
            if (l != null) l.send(o);
        }

        private void run() {
            try {
                talk();
            } catch (Exception e) {
                Log.w(TAG, "sender failed", e);
                error = String.valueOf(e.getMessage());
                end("failed");
            }
        }

        private void talk() throws Exception {
            Link link = Link.open(context, to);
            control = link;
            if (ended) {
                link.close();
                return;
            }
            JSONObject welcome = link.hello(Link.helloFrom(context, "mirror"), to.id, Link.pins(context), pairUi());
            this.welcome = welcome;
            String mime = chooseCodec(welcome);
            int[] size = chooseSize();
            DisplayMetrics dm = metrics();
            JSONObject start = new JSONObject().put("t", "start").put("mode", "mirror").put("video", new JSONObject()
                    .put("codec", mime).put("width", size[0]).put("height", size[1]).put("fps", FPS));
            if (wantAudio) start.put("audio", audioSpec());
            JSONObject ready = link.request(start, "ready", pairUi());
            int port = ready.getInt("video_port");
            audioPort = ready.optInt("audio_port");
            crypt = crypt(ready);
            udp = new DatagramSocket();
            InetAddress peer = InetAddress.getByName(to.address);
            dpi = dm.densityDpi;
            currentSize = size;
            Surface input = makeEncoder(mime, size[0], size[1]);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    end("projection");
                }
            }, main);
            display = projection.createVirtualDisplay("luch", size[0], size[1], dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null);
            watchRotation();
            if (wantAudio && audioPort > 0) {
                // the sound goes to the receiver and the phone itself goes quiet while it does
                feed = new AudioFeed(context, projection, udp, peer, audioPort, true);
                feed.setCrypt(crypt(ready));   // a cipher of its own: the sound has a thread of its own
                if (!feed.start()) feed = null;
            }
            link.passPongs = true;   // this side's pings measure the round trip
            Thread reader = new Thread(this::read, "luch-sender-read");
            reader.start();
            letIn = true;
            pump(peer, port, mime, size);
        }

        /** The session's key from a ready: sealed media over TLS, nothing on the plain port. */
        private static MediaCrypt crypt(JSONObject ready) throws java.security.GeneralSecurityException {
            String key = ready.optString("key");
            return key.isEmpty() ? null : new MediaCrypt(android.util.Base64.decode(key, android.util.Base64.NO_WRAP));
        }

        /** What a connection asks of the person: the TV's Allow is said here, the pairing code typed in PairCode. */
        private Link.Ui pairUi() {
            return new Link.Ui() {
                @Override
                public void allowOnTv() {
                    main.post(() -> android.widget.Toast.makeText(context, context.getString(R.string.cast_allow, to.name),
                            android.widget.Toast.LENGTH_LONG).show());
                }

                @Override
                public String code() throws InterruptedException {
                    return PairCode.ask(context, to.name);
                }
            };
        }

        /** Opus, 48 kHz stereo, 20 ms packets: what the capture and the encoder of {@link AudioFeed} make. */
        private JSONObject audioSpec() throws JSONException {
            return new JSONObject().put("codec", "audio/opus").put("rate", 48000).put("channels", 2).put("frame_ms", 20)
                    .put("sync", false);
        }

        /** Auto: what the receiver measured as best, else HEVC when both ends have it, else H.264. */
        private String chooseCodec(JSONObject welcome) {
            JSONArray dec = welcome.optJSONArray("decoders");
            boolean rxHevc = false, rxAvc = false;
            for (int i = 0; dec != null && i < dec.length(); i++) {
                if ("video/hevc".equals(dec.optString(i))) rxHevc = true;
                if ("video/avc".equals(dec.optString(i))) rxAvc = true;
            }
            if (dec == null) rxAvc = true;
            boolean hevc = rxHevc && canEncode("video/hevc");
            boolean avc = rxAvc && canEncode("video/avc");
            if ("hevc".equals(pref) && hevc) return "video/hevc";
            if ("avc".equals(pref) && avc) return "video/avc";
            JSONObject bench = welcome.optJSONObject("bench");
            String prefer = bench == null ? "" : bench.optString("prefer");
            if ("video/hevc".equals(prefer) && hevc) return "video/hevc";
            if ("video/avc".equals(prefer) && avc) return "video/avc";
            if (hevc) return "video/hevc";
            return "video/avc";
        }

        private DisplayMetrics metrics() {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            return dm;
        }

        /** The screen's own proportions, the long side cut to 1920, both sides a multiple of 16. */
        private int[] chooseSize() {
            DisplayMetrics dm = metrics();
            double k = Math.min(1.0, MAX_LONG_SIDE / (double) Math.max(dm.widthPixels, dm.heightPixels));
            int w = Math.max(16, ((int) (dm.widthPixels * k)) / 16 * 16);
            int h = Math.max(16, ((int) (dm.heightPixels * k)) / 16 * 16);
            return new int[] {w, h};
        }

        private Surface makeEncoder(String mime, int w, int h) throws IOException {
            MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 60);   // an IDR when the receiver asks, not on a timer
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);   // a still screen still sends something
            f.setInteger("prepend-sps-pps-to-idr-frames", 1);
            if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            MediaCodec e = MediaCodec.createEncoderByType(mime);
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = e.createInputSurface();
            e.start();
            encoder = e;
            config = null;
            return input;
        }

        /** The phone turned: the picture is made again at the new size, and the receiver is told with a new start. */
        private void watchRotation() {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            rotation = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) {
                }

                @Override
                public void onDisplayRemoved(int id) {
                }

                @Override
                public void onDisplayChanged(int id) {
                    if (id != android.view.Display.DEFAULT_DISPLAY || ended) return;
                    int[] now = chooseSize();
                    if (now[0] != currentSize[0] || now[1] != currentSize[1]) pendingSize = now;
                }
            };
            dm.registerDisplayListener(rotation, main);
        }

        /** Same session, new size: stop the encoder, say start again with the new size, resize the display onto a new encoder. */
        private void resize(String mime, int[] size) throws Exception {
            MediaCodec old = encoder;
            if (old != null) {
                try {
                    old.stop();
                } catch (RuntimeException ignored) {
                }
                old.release();
            }
            readyPort = 0;
            JSONObject again = new JSONObject().put("t", "start").put("mode", "mirror").put("video", new JSONObject()
                    .put("codec", mime).put("width", size[0]).put("height", size[1]).put("fps", FPS));
            if (feed != null) again.put("audio", audioSpec());
            send(again);
            long until = SystemClock.elapsedRealtime() + 4000;
            while (readyPort == 0 && !ended && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
            if (readyPort == 0) throw new IOException("the receiver did not answer the new size");
            if (feed != null && audioPort > 0) feed.setPort(audioPort); // the new session has new sockets
            Surface input = makeEncoder(mime, size[0], size[1]);
            display.resize(size[0], size[1], dpi);
            display.setSurface(input);
            currentSize = size;
        }

        /** What the receiver says: pong, keyframe, stats, stop, error; pings are answered by the link. */
        private void read() {
            try {
                while (!ended) {
                    JSONObject m;
                    try {
                        m = control.next(Integer.MAX_VALUE);
                    } catch (java.io.EOFException closed) {
                        break;
                    }
                    switch (m.optString("t")) {
                        case "ready":
                            // a new size is a new session on the receiver: new ports, and over TLS a new key
                            crypt = crypt(m);
                            if (feed != null) feed.setCrypt(crypt(m));
                            audioPort = m.optInt("audio_port");
                            readyPort = m.optInt("video_port");
                            break;
                        case "keyframe":
                            wantKey = true;
                            break;
                        case "pong":
                            if (m.optInt("n") == pingN) rtt = (SystemClock.elapsedRealtimeNanos() - pingSentAt) / 1e6;
                            break;
                        case "stats":
                            rxLost = m.optInt("lost");
                            rxDecodeMs = m.optDouble("decode_ms", 0);
                            break;
                        case "stop":
                            end("receiver " + m.optString("reason"));
                            return;
                        case LogShare.MODE: {
                            java.util.concurrent.BlockingQueue<JSONObject> q = logLines;
                            if (q != null) q.offer(m);
                            break;
                        }
                        case "error": {
                            java.util.concurrent.BlockingQueue<JSONObject> q = logLines;
                            if (q != null && LogShare.MODE.equals(m.optString("for"))) {
                                q.offer(m);   // the report's, not the session's
                                break;
                            }
                            error = m.optString("code");
                            Log.w(TAG, "receiver says: " + m);
                            break;
                        }
                        default:
                            break;
                    }
                }
            } catch (IOException | java.security.GeneralSecurityException e) {
                if (!ended) end("connection lost");
                return;
            }
            if (!ended) end("receiver closed");
        }

        /**
         * The receiver's log for a problem report, over this session's connection, which it has already let in: a new
         * one from this phone would be a plain one, asked about on the TV (PROTOCOL.md 6a).
         */
        String receiverLog(int maxKb) throws ReceiverLogs.Failure {
            if (ended || !letIn) throw new ReceiverLogs.Failure("the session is not on");
            if (!ReceiverLogs.offered(welcome)) {
                throw new ReceiverLogs.Failure("its Luch cannot hand over its log yet (update it)");
            }
            // not the session's own lock: send() takes that, and the picture must not wait for a report
            synchronized (logLock) {
                java.util.concurrent.BlockingQueue<JSONObject> q = new java.util.concurrent.LinkedBlockingQueue<>();
                logLines = q;
                try {
                    return ReceiverLogs.fetch(logChannel(q), maxKb, ReceiverLogs.TIMEOUT_MS);
                } finally {
                    logLines = null;
                }
            }
        }

        private ReceiverLogs.Channel logChannel(java.util.concurrent.BlockingQueue<JSONObject> q) {
            return new ReceiverLogs.Channel() {
                @Override
                public void send(JSONObject message) throws IOException {
                    Session.this.send(message);
                }

                @Override
                public JSONObject next(long timeoutMs) throws IOException, InterruptedException {
                    // the reader stops at the end of the session: no use waiting out the timeout then
                    long until = SystemClock.elapsedRealtime() + timeoutMs;
                    while (!ended) {
                        long left = until - SystemClock.elapsedRealtime();
                        if (left <= 0) return null;
                        JSONObject m = q.poll(Math.min(left, 250), java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (m != null) return m;
                    }
                    throw new IOException("the session ended");
                }
            };
        }

        private void pump(InetAddress peer, int firstPort, String mime, int[] firstSize) throws Exception {
            int port = firstPort;
            int[] size = firstSize;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] packet = new byte[HEADER + SHARD];
            long second = SystemClock.elapsedRealtime(), lastPing = 0;
            int frames = 0;
            long bytes = 0, hostSum = 0;
            String name = encoder.getName();
            String codec = mime.equals("video/hevc") ? "HEVC" : "H.264";
            String res = size[0] + "x" + size[1];
            while (!ended) {
                int[] want = pendingSize;
                if (want != null) {
                    pendingSize = null;
                    resize(mime, want);
                    port = readyPort;
                    size = want;
                    res = size[0] + "x" + size[1];
                    name = encoder.getName();
                }
                if (wantKey) {
                    wantKey = false;
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    encoder.setParameters(b);
                }
                int i = encoder.dequeueOutputBuffer(info, 100_000);
                if (i >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(i);
                    byte[] data = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(data, 0, info.size);
                    int flags = info.flags;
                    long pts = info.presentationTimeUs;
                    encoder.releaseOutputBuffer(i, false);
                    if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        config = data;
                        continue;
                    }
                    boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (key && config != null && !startsWithParameterSets(data, mime)) data = concat(config, data);
                    long host = Math.max(0, Math.min(6500, (System.nanoTime() / 1000 - pts) / 100));
                    sendFrame(packet, peer, port, data, key, pts, (int) host);
                    frames++;
                    bytes += data.length;
                    hostSum += host;
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastPing >= 2000) {
                    lastPing = now;
                    pingN++;
                    pingSentAt = SystemClock.elapsedRealtimeNanos();
                    send(new JSONObject().put("t", "ping").put("n", pingN));
                }
                if (now - second >= 1000) {
                    double secs = (now - second) / 1000.0;
                    double mbps = bytes * 8 / secs / 1e6;
                    double hostMs = frames == 0 ? 0 : hostSum / (double) frames / 10.0;
                    int fps = (int) Math.round(frames / secs);
                    send(new JSONObject().put("t", "sender_stats").put("rtt_ms", rtt).put("host_ms", hostMs).put("fps", fps)
                            .put("mbps", mbps).put("encoder", name).put("res", res));
                    live = new Live(codec, res, name.replaceFirst("^c2\\.", "").replaceFirst("\\.encoder$", ""), to.name, to.address, fps, rxLost, mbps, rtt,
                            hostMs + rtt / 2 + rxDecodeMs, now - startedAt);
                    Luch.notifyListeners();
                    second = now;
                    frames = 0;
                    bytes = 0;
                    hostSum = 0;
                }
            }
        }

        private void sendFrame(byte[] packet, InetAddress peer, int port, byte[] data, boolean key, long pts, int host)
                throws IOException {
            frame(packet, crypt, seq++, key, pts, host, data, n -> udp.send(new DatagramPacket(packet, n, peer, port)));
        }

        /** An access unit that already opens with a parameter set (H.264 SPS = 7, HEVC VPS = 32). */
        private static boolean startsWithParameterSets(byte[] d, String mime) {
            int i = d.length > 4 && d[2] == 0 ? 4 : 3;
            if (d.length <= i) return false;
            return mime.equals("video/hevc") ? ((d[i] >> 1) & 0x3F) == 32 : (d[i] & 0x1F) == 7;
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] r = new byte[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}
