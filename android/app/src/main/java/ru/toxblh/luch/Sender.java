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
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * This device showing its own screen on a receiver (a Steam Deck or a computer with luch-recv, a TV): the other direction
 * of Luch. The screen is captured with MediaProjection into a hardware encoder, and the access units go out as the packets
 * PROTOCOL.md section 4 describes. One session at a time; {@link #active()} also tells the receiving side to say "busy".
 */
final class Sender {
    private static final String TAG = LuchService.TAG;
    private static final int SHARD = 1376, HEADER = 24;
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

    static final class Session {
        private final Context context;
        private final Luch.Receiver to;
        private final String pref;
        private final MediaProjection projection;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final long startedAt = SystemClock.elapsedRealtime();
        volatile boolean ended;
        volatile Live live;
        volatile String error = "";

        private Socket control;
        private OutputStream out;
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
            close(control);
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

        private static void close(Socket s) {
            try {
                if (s != null) s.close();
            } catch (IOException ignored) {
            }
        }

        private synchronized void send(JSONObject o) throws IOException {
            if (out == null) return;
            out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
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
            control = new Socket();
            control.connect(new InetSocketAddress(to.address, to.port), 4000);
            control.setTcpNoDelay(true);
            out = control.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            send(new JSONObject().put("t", "hello").put("v", Luch.VERSION).put("id", Luch.id(context))
                    .put("name", Luch.name(context)).put("app", "luch-android").put("want", new JSONArray().put("mirror")));
            control.setSoTimeout(4000);
            JSONObject welcome = null;
            while (welcome == null) {
                JSONObject m = new JSONObject(readLine(in));
                if ("welcome".equals(m.optString("t"))) welcome = m;
            }
            String mime = chooseCodec(welcome);
            int[] size = chooseSize();
            DisplayMetrics dm = metrics();
            JSONObject start = new JSONObject().put("t", "start").put("mode", "mirror").put("video", new JSONObject()
                    .put("codec", mime).put("width", size[0]).put("height", size[1]).put("fps", FPS));
            if (wantAudio) start.put("audio", audioSpec());
            send(start);
            int port = 0;
            while (port == 0) {
                JSONObject m = new JSONObject(readLine(in));
                String t = m.optString("t");
                if ("ready".equals(t)) {
                    port = m.getInt("video_port");
                    audioPort = m.optInt("audio_port");
                }
                else if ("error".equals(t)) throw new IOException(m.optString("code") + " " + m.optString("detail"));
            }
            control.setSoTimeout(0);
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
                if (!feed.start()) feed = null;
            }
            Thread reader = new Thread(() -> read(in), "luch-sender-read");
            reader.start();
            pump(peer, port, mime, size);
        }

        private String readLine(BufferedReader in) throws IOException {
            String l = in.readLine();
            if (l == null) throw new IOException("the receiver closed the connection");
            return l;
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

        /** What the receiver says: pong, keyframe, stats, stop, error. */
        private void read(BufferedReader in) {
            try {
                String l;
                while (!ended && (l = in.readLine()) != null) {
                    JSONObject m = new JSONObject(l);
                    switch (m.optString("t")) {
                        case "ready":
                            audioPort = m.optInt("audio_port");
                            readyPort = m.optInt("video_port");
                            break;
                        case "keyframe":
                            wantKey = true;
                            break;
                        case "ping":
                            send(new JSONObject().put("t", "pong").put("n", m.optInt("n")));
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
                        case "error":
                            error = m.optString("code");
                            Log.w(TAG, "receiver says: " + m);
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException | JSONException e) {
                if (!ended) end("connection lost");
                return;
            }
            if (!ended) end("receiver closed");
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
            int shards = Math.max(1, (data.length + SHARD - 1) / SHARD);
            for (int k = 0; k < shards; k++) {
                int off = k * SHARD, n = Math.min(SHARD, data.length - off);
                ByteBuffer h = ByteBuffer.wrap(packet);
                h.put((byte) 1).put((byte) (key ? 1 : 0)).putShort((short) k).putShort((short) shards).putShort((short) 0)
                        .putInt(seq).putInt((int) (pts & 0xFFFFFFFFL)).putInt(data.length).putShort((short) host)
                        .putShort((short) 0);
                System.arraycopy(data, off, packet, HEADER, n);
                udp.send(new DatagramPacket(packet, HEADER + n, peer, port));
            }
            seq++;
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
