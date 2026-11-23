// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One mirroring session, PROTOCOL.md v1, built the way moonlight-common-c and moonlight-android receive video:
 * <ul>
 * <li>video and audio on their own UDP sockets; a frame is a run of fixed-size data shards, cut to frame_len;</li>
 * <li>a frame goes to the decoder from the receive thread the moment its last shard arrives: no queue, no jitter
 *     buffer, no timers (a frame is lost when a packet of a later frame shows up);</li>
 * <li>after a loss everything is dropped until the next IDR, asked for once the network is back;</li>
 * <li>of the decoded frames only the newest is drawn, at once.</li>
 * </ul>
 */
final class MirrorSession {
    interface Control {
        void send(JSONObject msg);
    }

    /** The sender's app name from its hello; a phone running Luch is shown without its own black bars by default. */
    volatile String senderApp = "";

    static final int HEADER = 24;
    static final int SHARD = 1376;
    private static final int KIND_VIDEO = 1, KIND_AUDIO = 2;
    private static final int FLAG_IDR = 1, FLAG_CONFIG = 2, FLAG_PARITY = 4;
    private static final int MAX_SHARDS = 4096;
    /** No packet at all for this long ends the session (the sender is gone). */
    private static final long SILENCE_MS = 15_000;
    /** An IDR asked for and not seen after this long is asked for again (about RTT + 100 ms). */
    private static final long KEYFRAME_RETRY_MS = 150;
    /** Frames dropped in a row after which an IDR is asked for even with no frame complete yet. */
    private static final int FORCE_KEYFRAME_AFTER = 120;

    static volatile MirrorSession current;

    /** Announced on every change: extras "active" (a picture is being received) and "away" (the screen is not shown). */
    static final String ACTION_STATE = "ru.toxblh.luch.MIRROR_STATE";
    /** How long the screen may stay away (Home) before the session ends. */
    private static final long AWAY_LIMIT_MS = 15 * 60_000L;
    static final String AWAY_TIMEOUT = "away_timeout";

    private final Context context;
    final Control control;
    private final String videoMime;
    private final int width, height, fps;
    /** Experiment: ask every decoder to run flat out (KEY_OPERATING_RATE), not only Qualcomm's. */
    private final boolean operatingRate;
    private final int audioRate, audioChannels, audioFrameMs;
    /**
     * {"audio":{...,"sync":true}}: hold each picture back by the audio's delay so both come out together. Only worth it
     * where audio cannot be made short (Android 5 and 6, see Caps.limits); costs that much picture latency.
     */
    private final boolean audioSync;
    /** Set once the audio track exists: how far behind the picture its sound comes out, and the picture's hold. */
    private volatile int audioLatencyMs;
    private volatile long videoHoldNs;
    private DatagramSocket videoSocket, audioSocket;
    private volatile boolean ended;
    /** Ended because the same sender started again (it turned, or changed the size): the screen is not closed. */
    private volatile boolean replaced;
    private volatile Surface surface;
    /** Stand-in output while the screen is not shown, so the session outlives a visit to the launcher (Android 8+). */
    private SurfaceTexture standInTexture;
    private Surface standIn;
    /** Uptime when the screen was left, 0 while it is shown. */
    private volatile long awaySince;
    /** The audio track while it exists: silent while the screen is away, so a minimised stream does not play on. */
    private volatile AudioTrack activeTrack;
    private final Object surfaceLock = new Object();
    /** The screen came back with a new surface: the decoder is made again for it, from the next IDR. */
    private volatile boolean surfaceSwapped;
    private int decoderResets;
    private static final int MAX_DECODER_RESETS = 5;

    // ---- video state (receive thread only, except where noted) ----
    private MediaCodec decoder;
    private String decoderName = "";
    private long curSeq = -1;
    private int curShards, curGot, curFlags, curLen;
    /** The frame being assembled: shards are copied straight to their place, one buffer for the whole session. */
    private final byte[] frameBuf = new byte[MAX_SHARDS * SHARD];
    private final boolean[] curSeen = new boolean[MAX_SHARDS];
    private boolean curActive;
    private long lastDelivered = -1;
    /** Waiting for an IDR after a loss (or at the start). */
    private boolean waitIdr = true;
    private boolean idrRequested;
    private long idrRequestedAt;
    private int droppedInRow;
    /** Decoder input pts (uptime µs at enqueue) → frame seq, for the stats line; written here, read by the renderer. */
    private final long[] ptsRing = new long[64], seqRing = new long[64];
    private int ringPos;

    // ---- counters (logged at the end and sent in stats) ----
    private volatile int framesIn, framesRendered, framesLost, framesDropped;
    private volatile long decodeUsSum, decodeCount, hostLatencySum, hostLatencyCount;
    private volatile long shownSeq = -1;
    private volatile long bytesIn;
    /** The sender's own figures, once a second ({"t":"sender_stats",...}); null until the first. */
    private volatile JSONObject senderStats;

    /** What the status screen shows while a picture is being received. */
    static final class Live {
        final String codec, res, decoder;
        final int shownFps, lost, dropped;
        final double recvMbps, latencyMs, rttMs;
        final long sinceMs;

        Live(String codec, String res, String decoder, int shownFps, int lost, int dropped, double recvMbps, double latencyMs,
                double rttMs, long sinceMs) {
            this.codec = codec;
            this.res = res;
            this.decoder = decoder;
            this.shownFps = shownFps;
            this.lost = lost;
            this.dropped = dropped;
            this.recvMbps = recvMbps;
            this.latencyMs = latencyMs;
            this.rttMs = rttMs;
            this.sinceMs = sinceMs;
        }
    }

    private volatile Live live;
    private final long startedAt = SystemClock.elapsedRealtime();

    /** The figures of the last second, null before the first one and once the session has ended. */
    Live live() {
        return ended ? null : live;
    }

    private void publishLive(double decodeMs, double hostMs, long shownFps, double recvMbps) {
        JSONObject sd = senderStats;
        double rtt = sd == null ? 0 : sd.optDouble("rtt_ms", 0);
        double host = sd != null && sd.has("host_ms") ? sd.optDouble("host_ms", hostMs) : hostMs;
        String res = sd != null ? sd.optString("res", width + "x" + height) : width + "x" + height;
        live = new Live(videoMime.equals("video/hevc") ? "HEVC" : "H.264", res.replace('x', '\u00d7'), decoderName,
                (int) shownFps, framesLost, framesDropped, recvMbps, host + rtt / 2 + decodeMs, rtt,
                SystemClock.elapsedRealtime() - startedAt);
        Luch.notifyListeners();
    }

    private MirrorSession(Context context, Control control, JSONObject start) {
        this.context = context.getApplicationContext();
        this.control = control;
        JSONObject v = start.optJSONObject("video");
        JSONObject a = start.optJSONObject("audio");
        videoMime = v == null ? "video/avc" : v.optString("codec", "video/avc");
        width = v == null ? 1920 : v.optInt("width", 1920);
        height = v == null ? 1080 : v.optInt("height", 1080);
        fps = v == null ? 60 : v.optInt("fps", 60);
        operatingRate = v != null && v.optBoolean("operating_rate", false);
        audioRate = a == null ? 0 : a.optInt("rate", 48000);
        audioChannels = a == null ? 0 : a.optInt("channels", 2);
        audioFrameMs = a == null ? 5 : a.optInt("frame_ms", 5);
        audioSync = a != null && a.optBoolean("sync", false);
        senderApp = start.optString("_app"); // put there by the control server from the sender's hello
    }

    /** Opens both sockets and the screen; returns {video port, audio port}. */
    static synchronized int[] start(Context context, Control control, JSONObject msg) throws IOException {
        MirrorSession old = current;
        if (old != null) {
            old.replaced = true;   // the sender went on with another size: the screen stays up for the new session
            old.end(null);
        }
        MirrorSession s = new MirrorSession(context, control, msg);
        s.videoSocket = openSocket();
        s.audioSocket = openSocket();
        current = s;
        broadcast(context, true, false);
        wakeScreen(context);
        thread(s::videoLoop, "luch-video");
        if (s.audioRate > 0) thread(s::audioLoop, "luch-audio");
        thread(s::statsLoop, "luch-stats");
        showScreen(context);
        return new int[]{s.videoSocket.getLocalPort(), s.audioSocket.getLocalPort()};
    }

    /** A UDP socket with as large a receive buffer as the system gives (Moonlight asks for 2048 × 1400 bytes). */
    private static DatagramSocket openSocket() throws SocketException {
        DatagramSocket s = new DatagramSocket(0);
        for (int size = 2048 * 1400; size >= 64 * 1024; size /= 2) {
            try {
                s.setReceiveBufferSize(size);
                if (s.getReceiveBufferSize() >= size / 2) break;
            } catch (SocketException ignored) {
            }
        }
        s.setSoTimeout(100);
        return s;
    }

    private static void thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Before Android 8.1 an activity cannot turn the screen on by itself (FLAG_TURN_SCREEN_ON only works for a window
     * that is already showing): wake it with a lock, as apps did then. From 8.1 MirrorActivity's setTurnScreenOn does
     * it (issue #1).
     */
    @SuppressWarnings("deprecation")   // SCREEN_BRIGHT_WAKE_LOCK and ACQUIRE_CAUSES_WAKEUP: the way on those versions
    private static void wakeScreen(Context context) {
        if (Build.VERSION.SDK_INT >= 27) return;
        PowerManager pm = Compat.service(context, PowerManager.class);
        if (pm == null || pm.isInteractive()) return;
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "luch:start")
                .acquire(3_000);   // released by itself; the screen's FLAG_KEEP_SCREEN_ON keeps it on from there
    }

    static synchronized void stopFromSender(Control control) {
        MirrorSession s = current;
        if (s != null && s.control == control) s.end(null);
    }

    /** Ways the receiver ends a session itself, sent as the {@code reason} of its stop. */
    static final String BY_BACK = "back", SCREEN_CLOSED = "screen_closed";

    /** The person (Back) or the system (Home, another app, the screen going off) closed the picture. */
    void stopByReceiver(String reason) {
        end(reason);
    }

    boolean isAway() {
        return awaySince != 0;
    }

    boolean ended() {
        return ended;
    }

    int fps() {
        return fps;
    }

    /** Whether the session can go on without the screen (a stand-in surface needs Android 8). */
    static boolean canStayAway() {
        return Build.VERSION.SDK_INT >= 26;
    }

    private synchronized Surface standIn() {
        if (Build.VERSION.SDK_INT < 26) return null;
        if (standIn == null) {
            standInTexture = new SurfaceTexture(false);
            standIn = new Surface(standInTexture);
        }
        return standIn;
    }

    /** The screen was left (Home, another app) or came back: the sound is muted meanwhile and the state announced. */
    void setAway(boolean away) {
        if (ended) return;
        awaySince = away ? SystemClock.uptimeMillis() : 0;
        AudioTrack t = activeTrack;
        if (t != null) {
            try {
                t.setVolume(away ? 0f : 1f);
            } catch (RuntimeException ignored) {
            }
        }
        broadcast(context, true, away);
    }

    static void broadcast(Context context, boolean active, boolean away) {
        context.sendBroadcast(new Intent(ACTION_STATE).putExtra("active", active).putExtra("away", away));
    }

    void setSurface(Surface s) {
        if (s == null && canStayAway() && !ended) s = standIn();
        Surface old;
        synchronized (surfaceLock) {
            old = surface;
            surface = s;
            surfaceLock.notifyAll();
        }
        if (s == null) return;
        // The screen was stopped for a moment (waking up, the lock screen passing by) and came back with a new
        // surface. The receive thread makes the decoder again for it (setOutputSurface is not reliable on every TV).
        if (decoder != null && s != old) surfaceSwapped = true;
    }

    /** Drops the decoder; the next IDR makes a new one on the current surface (receive thread). */
    private void resetDecoder(String why) throws IOException {
        if (++decoderResets > MAX_DECODER_RESETS) throw new IOException("decoder keeps failing: " + why);
        Log.i(LuchService.TAG, "decoder reset: " + why);
        MediaCodec d = decoder;
        decoder = null;
        if (d != null) {
            try {
                d.stop();
            } catch (RuntimeException ignored) {
            }
            d.release();
        }
        lost(why);
    }

    // ---- video: receive and reassemble ---------------------------------------------------------------------

    private void videoLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        byte[] buf = new byte[HEADER + SHARD + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        ByteBuffer h = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        long lastPacket = SystemClock.uptimeMillis();
        boolean first = true;
        try {
            while (!ended) {
                try {
                    videoSocket.receive(p);
                } catch (SocketTimeoutException quiet) {
                    if (SystemClock.uptimeMillis() - lastPacket > SILENCE_MS) throw new IOException("no video");
                    retryKeyframe();
                    continue;
                }
                lastPacket = SystemClock.uptimeMillis();
                bytesIn += p.getLength();
                if (first) {
                    first = false;
                    Log.i(LuchService.TAG, "video from " + p.getAddress().getHostAddress() + ":" + p.getPort());
                }
                int n = p.getLength();
                if (n < HEADER || (buf[0] & 0xff) != KIND_VIDEO) continue;
                int flags = buf[1] & 0xff;
                int idx = h.getShort(2) & 0xffff;
                int dataShards = h.getShort(4) & 0xffff;
                long seq = h.getInt(8) & 0xffffffffL;
                int frameLen = h.getInt(16);
                int hostLatency = h.getShort(20) & 0xffff;   // 0.1 ms units
                if ((flags & FLAG_PARITY) != 0) continue;      // FEC comes later
                if (dataShards == 0 || dataShards > MAX_SHARDS || idx >= dataShards) continue;
                if (frameLen <= 0 || frameLen > dataShards * SHARD) continue;
                onShard(seq, idx, dataShards, flags, frameLen, hostLatency, buf, n);
                retryKeyframe();
            }
        } catch (IOException e) {
            if (!ended) Log.i(LuchService.TAG, "video ended: " + e);
            if (!ended && String.valueOf(e.getMessage()).startsWith("decoder keeps failing")) report("video", e);
        } finally {
            end(null);
        }
    }

    private void onShard(long seq, int idx, int shards, int flags, int frameLen, int hostLatency, byte[] buf, int n)
            throws IOException {
        if (surfaceSwapped) {
            surfaceSwapped = false;
            resetDecoder("new surface");
        }
        if (seq <= lastDelivered || (curSeq >= 0 && seq < curSeq)) return;   // late or duplicate
        if (seq != curSeq) {
            // A packet of a later frame: the one in flight, if any, will never complete.
            if (curActive && curGot < curShards) lost("frame " + curSeq + " incomplete");
            if (curSeq >= 0 && seq > curSeq + 1 && !curActive && lastDelivered < seq - 1) {
                lost("frames " + (lastDelivered + 1) + ".." + (seq - 1) + " missing");
            }
            curSeq = seq;
            curShards = shards;
            curGot = 0;
            curFlags = 0;
            curLen = frameLen;
            java.util.Arrays.fill(curSeen, 0, shards, false);
            curActive = true;
        }
        if (!curActive || shards != curShards || curSeen[idx]) return;
        System.arraycopy(buf, HEADER, frameBuf, idx * SHARD, Math.min(SHARD, n - HEADER));
        curSeen[idx] = true;
        curGot++;
        curFlags |= flags;
        if (curGot < curShards) return;

        curActive = false;
        if (lastDelivered >= 0 && seq != lastDelivered + 1) lost("gap before frame " + seq);
        lastDelivered = seq;
        framesIn++;
        hostLatencySum += hostLatency;
        hostLatencyCount++;
        boolean idr = (curFlags & FLAG_IDR) != 0;
        if (waitIdr && !idr) {
            framesDropped++;
            droppedInRow++;
            // A whole frame arrived: the network is back, so now an IDR is worth asking for.
            if (!idrRequested || droppedInRow >= FORCE_KEYFRAME_AFTER) askKeyframe();
            return;
        }
        if (decoder == null && !startDecoder()) return;
        if (idr) {
            waitIdr = false;
            idrRequested = false;
            droppedInRow = 0;
        }
        decode(frameBuf, curLen, idr, seq);
    }

    private void lost(String why) {
        framesLost++;
        if (!waitIdr) Log.i(LuchService.TAG, "lost: " + why);
        waitIdr = true;
        idrRequested = false;   // ask again once a frame completes
    }

    private void askKeyframe() {
        idrRequested = true;
        idrRequestedAt = SystemClock.uptimeMillis();
        droppedInRow = 0;
        send("keyframe");
    }

    private void retryKeyframe() {
        if (waitIdr && idrRequested && SystemClock.uptimeMillis() - idrRequestedAt > KEYFRAME_RETRY_MS) askKeyframe();
    }

    // ---- video: decode and render --------------------------------------------------------------------------

    private void decode(byte[] frame, int len, boolean idr, long seq) throws IOException {
        int index;
        try {
            // Short normally; longer for an IDR and the first frames, while the decoder sets up its output buffers.
            index = decoder.dequeueInputBuffer(idr || framesIn < 30 ? 200_000 : 20_000);
        } catch (IllegalStateException e) {   // also MediaCodec.CodecException: e.g. its surface went away under it
            resetDecoder("decoder failed: " + e);
            return;
        }
        if (index < 0) {
            // Decoder full: this frame is gone, and the pictures after it would be smeared.
            framesDropped++;
            lost("decoder busy at frame " + seq);
            return;
        }
        long ptsUs = System.nanoTime() / 1000;
        try {
            ByteBuffer in = decoder.getInputBuffer(index);
            in.clear();
            in.put(frame, 0, len);
            synchronized (ptsRing) {
                ptsRing[ringPos] = ptsUs;
                seqRing[ringPos] = seq;
                ringPos = (ringPos + 1) % ptsRing.length;
            }
            decoder.queueInputBuffer(index, 0, len, ptsUs, idr ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        } catch (IllegalStateException e) {
            resetDecoder("decoder failed: " + e);
        }
    }

    /** Waits a little for the screen's surface, then configures the decoder with Moonlight's low-latency keys. */
    private boolean startDecoder() throws IOException {
        Surface s;
        synchronized (surfaceLock) {
            long until = SystemClock.uptimeMillis() + 3000;
            while ((s = surface) == null && !ended && SystemClock.uptimeMillis() < until) {
                try {
                    surfaceLock.wait(50);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        if (s == null) {
            framesDropped++;
            return false;
        }
        decoder = LowLatencyDecoder.create(videoMime, width, height, fps, s, operatingRate);
        if (decoder == null && (ended || !s.isValid())) {
            // The screen went away while the decoder was being set up: not the decoder's fault (issue #1). Wait for
            // it to come back; if it does not, MirrorActivity ends the session as closed.
            framesDropped++;
            return false;
        }
        if (decoder == null) {
            send("error", "start_failed", "no decoder for " + videoMime);
            report("no decoder for " + videoMime, null);
            throw new IOException("no decoder for " + videoMime);
        }
        decoderName = decoder.getName();
        thread(this::renderLoop, "luch-render");
        return true;
    }

    /** Takes every decoded frame that is ready, draws only the newest one, at once. */
    private void renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        MediaCodec codec = decoder;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!ended) {
                int index = codec.dequeueOutputBuffer(info, 50_000);
                if (index < 0) continue;
                long pts = info.presentationTimeUs;
                int next;
                while ((next = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    codec.releaseOutputBuffer(index, false);   // an older frame: skip it
                    framesDropped++;
                    index = next;
                    pts = info.presentationTimeUs;
                }
                if (surface == null) {
                    codec.releaseOutputBuffer(index, false);   // the screen is away for a moment: nowhere to draw
                    framesDropped++;
                    continue;
                }
                codec.releaseOutputBuffer(index, System.nanoTime() + videoHoldNs);
                framesRendered++;
                long now = System.nanoTime() / 1000;
                decodeUsSum += now - pts;
                decodeCount++;
                synchronized (ptsRing) {
                    for (int i = 0; i < ptsRing.length; i++) {
                        if (ptsRing[i] == pts) {
                            shownSeq = seqRing[i];
                            break;
                        }
                    }
                }
            }
        } catch (IllegalStateException e) {
            if (!ended && codec == decoder) {   // a reset decoder ends quietly
                Log.w(LuchService.TAG, "render", e);
                report("render", e);
            }
        }
    }

    // ---- audio ---------------------------------------------------------------------------------------------

    private static final int AUDIO_BUFFER_STEP_MS = 10, MAX_AUDIO_BUFFER_MS = 100;

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[HEADER + SHARD + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        ByteBuffer h = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        MediaCodec dec = null;
        AudioTrack track = null;
        long startedAt = SystemClock.uptimeMillis();
        long lastSeq = -1;
        byte[] pcm = new byte[0];
        boolean playing = false;
        int bufMs = 0, underruns = 0;
        long checkedAt = SystemClock.uptimeMillis();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!ended) {
                try {
                    audioSocket.receive(p);
                } catch (SocketTimeoutException quiet) {
                    continue;
                }
                int n = p.getLength();
                if (n < HEADER || (buf[0] & 0xff) != KIND_AUDIO) continue;
                int flags = buf[1] & 0xff;
                long seq = h.getInt(8) & 0xffffffffL;
                int len = Math.min(h.getInt(16), n - HEADER);
                if (len <= 0) continue;
                byte[] data = new byte[len];
                System.arraycopy(buf, HEADER, data, 0, len);
                if ((flags & FLAG_CONFIG) != 0) {
                    if (dec == null) {
                        dec = opusDecoder(data);
                        track = audioTrack();
                        activeTrack = track;
                        if (awaySince != 0) track.setVolume(0f);
                        int frames;
                        if (Build.VERSION.SDK_INT >= 24) {
                            // The system may hand out a far larger buffer than asked for (6924 frames, 144 ms, on the
                            // XGIMI): use only about 20 ms of it, so audio cannot fall behind the picture.
                            int want = audioRate * Math.max(4 * audioFrameMs, 20) / 1000;
                            frames = track.setBufferSizeInFrames(want);
                            Log.i(LuchService.TAG, "audio track: capacity " + track.getBufferCapacityInFrames()
                                    + " frames, using " + frames);
                        } else {
                            // Android 5 and 6 cannot shorten the buffer: the sound runs this far behind.
                            frames = trackBytes / (2 * audioChannels);
                            Log.i(LuchService.TAG, "audio track: " + frames + " frames, cannot be shortened here");
                        }
                        audioLatencyMs = frames * 1000 / audioRate;
                        if (audioSync) videoHoldNs = audioLatencyMs * 1_000_000L;
                        bufMs = frames * 1000 / audioRate;
                        // Not played yet: a fast track that runs dry before the first sound arrives (the first half
                        // second is dropped below) is switched off by the mixer and stays silent for the session.
                        // It starts with the first sound written.
                    }
                    continue;
                }
                // A track that ran dry (a late packet, a pause of the garbage collector) crackles: give it more room,
                // a step at a time, and keep it for the session. Costs that much more sound delay, only when needed.
                if (track != null && Build.VERSION.SDK_INT >= 24 && SystemClock.uptimeMillis() - checkedAt >= 1000) {
                    checkedAt = SystemClock.uptimeMillis();
                    int u = track.getUnderrunCount();
                    if (u > underruns && bufMs < MAX_AUDIO_BUFFER_MS) {
                        bufMs += AUDIO_BUFFER_STEP_MS;
                        int frames = track.setBufferSizeInFrames(audioRate * bufMs / 1000);
                        audioLatencyMs = frames * 1000 / audioRate;
                        if (audioSync) videoHoldNs = audioLatencyMs * 1_000_000L;
                        Log.i(LuchService.TAG, "audio underruns " + u + ": buffer now " + audioLatencyMs + " ms");
                    }
                    underruns = u;
                }
                if (dec == null || seq <= lastSeq) continue;
                lastSeq = seq;
                // Moonlight drops the first half second: the pipeline settles and would otherwise build a backlog.
                if (SystemClock.uptimeMillis() - startedAt < 500) continue;
                int in = dec.dequeueInputBuffer(5_000);
                if (in >= 0) {
                    ByteBuffer b = dec.getInputBuffer(in);
                    b.clear();
                    b.put(data);
                    dec.queueInputBuffer(in, 0, len, seq * audioFrameMs * 1000L, 0);
                }
                int out;
                while ((out = dec.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer o = dec.getOutputBuffer(out);
                    if (info.size > 0 && o != null) {
                        if (pcm.length < info.size) pcm = new byte[info.size];
                        o.position(info.offset);
                        o.get(pcm, 0, info.size);
                        // The track holds about two frames; when it is full the rest is dropped rather than queued,
                        // so audio never drifts behind the picture.
                        if (Build.VERSION.SDK_INT >= 23) {
                            track.write(pcm, 0, info.size, AudioTrack.WRITE_NON_BLOCKING);
                        } else {
                            o.position(info.offset).limit(info.offset + info.size);   // the byte[] form is Android 6
                            track.write(o, info.size, AudioTrack.WRITE_NON_BLOCKING);
                        }
                        if (!playing) {
                            track.play();
                            playing = true;
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!ended) {
                Log.w(LuchService.TAG, "audio", e);
                report("audio", e);
            }
        } finally {
            activeTrack = null;
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                }
                track.release();
            }
            if (dec != null) {
                try {
                    dec.stop();
                } catch (RuntimeException ignored) {
                }
                dec.release();
            }
        }
    }

    private MediaCodec opusDecoder(byte[] opusHead) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat("audio/opus", audioRate, audioChannels);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead));
        f.setByteBuffer("csd-1", leLong(6_500_000L));
        f.setByteBuffer("csd-2", leLong(80_000_000L));
        MediaCodec dec = MediaCodec.createDecoderByType("audio/opus");
        dec.configure(f, null, null, 0);
        dec.start();
        return dec;
    }

    /** Size of the track's buffer, in bytes. */
    private int trackBytes;

    private AudioTrack audioTrack() {
        int mask = audioChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int frameBytes = audioRate * audioChannels * 2 * audioFrameMs / 1000;
        int min = AudioTrack.getMinBufferSize(audioRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        trackBytes = Math.max(min, frameBytes * 2);
        if (Build.VERSION.SDK_INT < 23) {
            // Android 5: no Builder; the stream type stands for the usage.
            return new AudioTrack(android.media.AudioManager.STREAM_MUSIC, audioRate, mask,
                    AudioFormat.ENCODING_PCM_16BIT, trackBytes, AudioTrack.MODE_STREAM);
        }
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(audioRate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(trackBytes)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        return b.build();
    }

    private static ByteBuffer leLong(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(v).flip();
        return b;
    }

    // ---- stats and end -------------------------------------------------------------------------------------

    void onSenderStats(JSONObject msg) {
        senderStats = msg;
    }

    /** The on-screen overlay, after Moonlight's: the estimate first, then where it comes from. */
    private String overlay(double decodeMs, double hostMs, long shownFps, double recvMbps) {
        JSONObject s = senderStats;
        double rtt = s == null ? 0 : s.optDouble("rtt_ms", 0);
        double host = s != null && s.has("host_ms") ? s.optDouble("host_ms", hostMs) : hostMs;
        StringBuilder b = new StringBuilder();
        b.append(String.format(java.util.Locale.US, "≈ %.0f ms  host + RTT/2 + decode, display not counted\n",
                host + rtt / 2 + decodeMs));
        b.append(String.format(java.util.Locale.US, "RTT %.1f · host %.1f · decode %.1f ms\n", rtt, host, decodeMs));
        if (s != null) {
            b.append(String.format(java.util.Locale.US, "send %d fps %.1f Mbit/s · ", s.optInt("fps"),
                    s.optDouble("mbps", 0)));
        }
        b.append(String.format(java.util.Locale.US, "recv %.1f Mbit/s · shown %d fps · lost %d · dropped %d\n",
                recvMbps, shownFps, framesLost, framesDropped));
        String res = s != null ? s.optString("res", width + "x" + height) : width + "x" + height;
        String enc = s != null ? s.optString("encoder", "?") : "?";
        b.append(res).append(' ').append(videoMime.equals("video/hevc") ? "HEVC" : "H.264").append(' ')
                .append(enc).append(" → ").append(decoderName);
        return b.toString();
    }

    private static void showScreen(Context context) {
        context.startActivity(new Intent(context, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    /** Seconds to keep asking for the screen; then the session ends, reason "screen_not_shown". */
    private static final int SHOW_TRIES = 8;
    /** The same, once a notification to tap is up: the person needs time to pull the shade down and tap it. */
    private static final int OFFER_TRIES = 60;
    private static final int OFFER_ID = 2;
    private static final String OFFER_CHANNEL = "mirror_offer";
    private boolean offered;

    int videoWidth() { return width; }
    int videoHeight() { return height; }

    /**
     * Android will not let an app in the background open its screen by itself. A notification can: a tap on it is the
     * person's own action. Returns false when notifications are not allowed (Android 13+ asks once, in StatusActivity).
     */
    private boolean postOffer() {
        if (!Luch.offerNotification(context)) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(OFFER_CHANNEL,
                    context.getString(R.string.offer_channel), NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(context, OFFER_CHANNEL);
        } else {
            b = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }
        java.util.List<Luch.Peer> peers = Luch.connected();
        String who = peers.isEmpty() ? context.getString(R.string.offer_someone) : peers.get(0).name;
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MirrorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        nm.notify(OFFER_ID, b.setSmallIcon(R.drawable.ic_luch)
                .setContentTitle(context.getString(R.string.offer_title, who))
                .setContentText(context.getString(R.string.offer_text))
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(open).setFullScreenIntent(open, true).setAutoCancel(true).build());
        return true;
    }

    private void cancelOffer() {
        if (!offered) return;
        offered = false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(OFFER_ID);
    }
    static final String SCREEN_NOT_SHOWN = "screen_not_shown";

    private void statsLoop() {
        int tries = 0;
        while (!ended) {
            SystemClock.sleep(1000);
            // The system may refuse to open the screen for a while: for 5 s after Home on older Android, or without
            // the "display over other apps" permission. Ask again each second, then give up saying why.
            if (surface == null && !MirrorActivity.isShown() && !ended) {
                if (++tries == 2 && !offered) offered = postOffer();
                if (tries > (offered ? OFFER_TRIES : SHOW_TRIES)) {
                    end(SCREEN_NOT_SHOWN);
                    break;
                }
                Log.i(LuchService.TAG, "mirror screen not shown yet: asking again" + (offered ? ", notification up" : ""));
                showScreen(context);
            } else if (offered) {
                cancelOffer();
            }
            long away = awaySince;
            if (away != 0 && SystemClock.uptimeMillis() - away > AWAY_LIMIT_MS && !ended) {
                end(AWAY_TIMEOUT);
                break;
            }
            long count = decodeCount, sum = decodeUsSum;
            decodeCount = 0;
            decodeUsSum = 0;
            long hc = hostLatencyCount, hs = hostLatencySum;
            hostLatencyCount = 0;
            hostLatencySum = 0;
            long bytes = bytesIn;
            bytesIn = 0;
            if (ended) continue;
            double decodeMs = count == 0 ? 0 : sum / (double) count / 1000.0;
            double hostMs = hc == 0 ? 0 : hs / (double) hc / 10.0;
            MirrorActivity.showStats(overlay(decodeMs, hostMs, count, bytes * 8 / 1e6));
            publishLive(decodeMs, hostMs, count, bytes * 8 / 1e6);
            if (count == 0) continue;
            try {
                control.send(new JSONObject().put("t", "stats")
                        .put("decode_ms", Math.round(sum / (double) count / 100.0) / 10.0)
                        .put("host_ms", hc == 0 ? 0 : Math.round(hs / (double) hc) / 10.0)
                        .put("fps", count)
                        .put("lost", framesLost)
                        .put("dropped", framesDropped)
                        .put("shown_seq", shownSeq)
                        .put("decoder", decoderName)
                        .put("audio_latency_ms", audioLatencyMs)
                        .put("video_hold_ms", videoHoldNs / 1_000_000));
            } catch (JSONException ignored) {
            }
        }
    }

    /** Ends the session; {@code byReceiver} is the reason when the receiver ends it, null otherwise. */
    private void end(String byReceiver) {
        cancelOffer();
        synchronized (this) {
            if (ended) return;
            ended = true;
        }
        if (byReceiver != null) {
            try {
                control.send(new JSONObject().put("t", "stop").put("by", "receiver").put("reason", byReceiver));
            } catch (JSONException ignored) {
            }
        }
        Luch.notifyListeners();
        if (videoSocket != null) videoSocket.close();
        if (audioSocket != null) audioSocket.close();
        MediaCodec d = decoder;
        if (d != null) {
            try {
                d.stop();
            } catch (RuntimeException ignored) {
            }
            d.release();
        }
        synchronized (MirrorSession.class) {
            if (current == this) current = null;
        }
        Log.i(LuchService.TAG, "mirror ended" + (byReceiver != null ? " here (" + byReceiver + ")" : "") + ": in=" + framesIn + " rendered="
                + framesRendered + " dropped=" + framesDropped + " lost=" + framesLost + " decoder=" + decoderName);
        broadcast(context, false, false);
        if (!replaced) MirrorActivity.sessionEnded();
    }

    /** Keeps a report of a failure the person will have noticed, with what the session was doing (Reports). */
    private void report(String what, Throwable e) {
        try {
            Reports.error(context, what, e, new JSONObject()
                    .put("codec", videoMime).put("width", width).put("height", height).put("fps", fps)
                    .put("decoder", decoderName).put("audio", audioRate > 0)
                    .put("frames_in", framesIn).put("rendered", framesRendered).put("lost", framesLost));
        } catch (JSONException ignored) {
        }
    }

    private void send(String type) {
        try {
            control.send(new JSONObject().put("t", type));
        } catch (JSONException ignored) {
        }
    }

    private void send(String type, String code, String detail) {
        try {
            control.send(new JSONObject().put("t", type).put("code", code).put("detail", detail));
        } catch (JSONException ignored) {
        }
    }
}
