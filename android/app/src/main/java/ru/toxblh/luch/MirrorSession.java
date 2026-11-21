// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
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

    private final Context context;
    final Control control;
    private final String videoMime;
    private final int width, height, fps;
    /** Experiment: ask every decoder to run flat out (KEY_OPERATING_RATE), not only Qualcomm's. */
    private final boolean operatingRate;
    private final int audioRate, audioChannels, audioFrameMs;
    private DatagramSocket videoSocket, audioSocket;
    private volatile boolean ended;
    private volatile Surface surface;
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
    private byte[][] curData;
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
    }

    /** Opens both sockets and the screen; returns {video port, audio port}. */
    static synchronized int[] start(Context context, Control control, JSONObject msg) throws IOException {
        MirrorSession old = current;
        if (old != null) old.end(null);
        MirrorSession s = new MirrorSession(context, control, msg);
        s.videoSocket = openSocket();
        s.audioSocket = openSocket();
        current = s;
        thread(s::videoLoop, "luch-video");
        if (s.audioRate > 0) thread(s::audioLoop, "luch-audio");
        thread(s::statsLoop, "luch-stats");
        context.startActivity(new Intent(context, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
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

    boolean ended() {
        return ended;
    }

    int fps() {
        return fps;
    }

    void setSurface(Surface s) {
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
            if (curData != null && curGot < curShards) lost("frame " + curSeq + " incomplete");
            if (curSeq >= 0 && seq > curSeq + 1 && curData == null && lastDelivered < seq - 1) {
                lost("frames " + (lastDelivered + 1) + ".." + (seq - 1) + " missing");
            }
            curSeq = seq;
            curShards = shards;
            curGot = 0;
            curFlags = 0;
            curLen = frameLen;
            curData = new byte[shards][];
        }
        if (curData == null || shards != curShards || curData[idx] != null) return;
        byte[] shard = new byte[Math.min(SHARD, n - HEADER)];
        System.arraycopy(buf, HEADER, shard, 0, shard.length);
        curData[idx] = shard;
        curGot++;
        curFlags |= flags;
        if (curGot < curShards) return;

        byte[] frame = new byte[curLen];
        int off = 0;
        for (byte[] s : curData) {
            int take = Math.min(s.length, curLen - off);
            if (take <= 0) break;
            System.arraycopy(s, 0, frame, off, take);
            off += take;
        }
        curData = null;
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
        decode(frame, idr, seq);
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

    private void decode(byte[] frame, boolean idr, long seq) throws IOException {
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
            in.put(frame);
            synchronized (ptsRing) {
                ptsRing[ringPos] = ptsUs;
                seqRing[ringPos] = seq;
                ringPos = (ringPos + 1) % ptsRing.length;
            }
            decoder.queueInputBuffer(index, 0, frame.length, ptsUs, idr ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
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
                codec.releaseOutputBuffer(index, System.nanoTime());
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
                        // The system may hand out a far larger buffer than asked for (6924 frames, 144 ms, on the
                        // XGIMI): use only about 20 ms of it, so audio cannot fall behind the picture.
                        int want = audioRate * Math.max(4 * audioFrameMs, 20) / 1000;
                        int got = track.setBufferSizeInFrames(want);
                        Log.i(LuchService.TAG, "audio track: capacity " + track.getBufferCapacityInFrames()
                                + " frames, using " + got);
                        track.play();
                    }
                    continue;
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
                        track.write(pcm, 0, info.size, AudioTrack.WRITE_NON_BLOCKING);
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

    private AudioTrack audioTrack() {
        int mask = audioChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int frameBytes = audioRate * audioChannels * 2 * audioFrameMs / 1000;
        int min = AudioTrack.getMinBufferSize(audioRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(audioRate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, frameBytes * 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
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

    private void statsLoop() {
        while (!ended) {
            SystemClock.sleep(1000);
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
            if (count == 0) continue;
            try {
                control.send(new JSONObject().put("t", "stats")
                        .put("decode_ms", Math.round(sum / (double) count / 100.0) / 10.0)
                        .put("host_ms", hc == 0 ? 0 : Math.round(hs / (double) hc) / 10.0)
                        .put("fps", count)
                        .put("lost", framesLost)
                        .put("dropped", framesDropped)
                        .put("shown_seq", shownSeq)
                        .put("decoder", decoderName));
            } catch (JSONException ignored) {
            }
        }
    }

    /** Ends the session; {@code byReceiver} is the reason when the receiver ends it, null otherwise. */
    private void end(String byReceiver) {
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
        MirrorActivity.sessionEnded();
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
