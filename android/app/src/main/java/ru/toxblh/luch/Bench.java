// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures every hardware video decoder the way a live session uses it: the same low-latency configuration, 1080p
 * clips from assets/bench fed at 60 frames a second, the time from queueInputBuffer to the frame coming out. The
 * result goes to senders in "welcome" so they can pick the codec this TV decodes fastest.
 *
 * Run only on request ({"t":"bench"} or the status screen): a TV has few hardware decoders, and taking one while a
 * video plays would stop that video. The result is kept until the firmware or this app changes.
 */
final class Bench {
    private static final String[][] CLIPS = {
            {"video/avc", "bench/avc.h264"},
            {"video/hevc", "bench/hevc.h265"},
            {"video/x-vnd.on2.vp9", "bench/vp9.ivf"},
            {"video/av01", "bench/av1.ivf"},
    };
    /** Break ties within the measurement noise in favour of compression efficiency. */
    private static final String[] COMPRESSION_ORDER = {
            "video/av01", "video/hevc", "video/x-vnd.on2.vp9", "video/avc"
    };
    private static final double TIE_MS = 0.5;
    private static final int W = 1920, H = 1080, FPS = 60;
    /** The first frames include the decoder warming up; they are not counted. */
    private static final int WARMUP = 5;
    private static volatile boolean running;

    private Bench() {
    }

    /** The kept result, or null when there is none for this firmware and app version. */
    static JSONObject cached(Context context) {
        String s = prefs(context).getString("bench", null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (!key(context).equals(o.optString("key"))) return null;
            String best = preferred(o);
            o.put("prefer", best == null ? JSONObject.NULL : best);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean running() {
        return running;
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long RETRY_MS = 10 * 60 * 1000L;
    private static final Runnable[] retry = new Runnable[1];

    /**
     * After install, a firmware update or the first connection: measure once, on its own, when nothing would notice.
     * A video playing (any audio playing) or a mirroring session means "later": in 10 minutes or at the next hello.
     */
    /** Gets the result of a measurement (java.util.function.Consumer is Android 7). */
    interface Done {
        void accept(JSONObject result);
    }

    static synchronized void autoRun(Context context, Done done) {
        Context app = context.getApplicationContext();
        if (cached(app) != null || running) return;
        android.media.AudioManager audio = Compat.service(app, android.media.AudioManager.class);
        boolean busy = MirrorSession.current != null || (audio != null && audio.isMusicActive());
        if (retry[0] != null) MAIN.removeCallbacks(retry[0]);
        if (busy) {
            retry[0] = () -> autoRun(app, done);
            MAIN.postDelayed(retry[0], RETRY_MS);
            Log.i(LuchService.TAG, "bench put off: something is playing");
            return;
        }
        running = true;   // claimed here, so a second caller does not start another
        new Thread(() -> {
            JSONObject r = run(app);
            if (done != null) done.accept(r);
        }, "luch-bench").start();
    }

    /** Runs the measurement (seconds; call off the main thread) and keeps the result. */
    static JSONObject run(Context context) {
        running = true;
        Log.i(LuchService.TAG, "bench started");
        try {
            JSONObject result = new JSONObject();
            for (String[] clip : CLIPS) {
                String mime = clip[0];
                if (LowLatencyDecoder.hardwareDecoder(mime) == null) continue;
                JSONObject r;
                try {
                    r = measure(context, mime, clip[1]);
                } catch (Exception e) {
                    Log.w(LuchService.TAG, "bench " + mime, e);
                    r = new JSONObject().put("error", String.valueOf(e));
                }
                result.put(mime, r);
                Log.i(LuchService.TAG, "bench " + mime + ": " + r);
            }
            String best = preferred(result);
            result.put("prefer", best == null ? JSONObject.NULL : best);
            result.put("res", W + "x" + H);
            result.put("key", key(context));
            prefs(context).edit().putString("bench", result.toString()).apply();
            return result;
        } catch (JSONException e) {
            return new JSONObject();
        } finally {
            running = false;
        }
    }

    /** Consider only codecs within 0.5 ms of the global minimum; ties cannot accumulate across codecs. */
    static String preferred(JSONObject results) {
        double fastest = Double.POSITIVE_INFINITY;
        for (String mime : COMPRESSION_ORDER) {
            JSONObject r = results.optJSONObject(mime);
            double ms = r == null || r.has("error") ? Double.NaN : r.optDouble("ms", Double.NaN);
            if (!Double.isNaN(ms) && !Double.isInfinite(ms) && ms >= 0) fastest = Math.min(fastest, ms);
        }
        for (String mime : COMPRESSION_ORDER) {
            JSONObject r = results.optJSONObject(mime);
            double ms = r == null || r.has("error") ? Double.NaN : r.optDouble("ms", Double.NaN);
            if (!Double.isNaN(ms) && !Double.isInfinite(ms) && ms >= 0 && ms <= fastest + TIE_MS) return mime;
        }
        return null;
    }

    private static JSONObject measure(Context context, String mime, String asset) throws IOException, JSONException {
        List<byte[]> frames = asset.endsWith(".ivf") ? ivfFrames(read(context, asset)) : annexBFrames(read(context, asset), mime);
        HandlerThread ht = new HandlerThread("luch-bench-out");
        ht.start();
        // An invisible surface: the decoder works as for the screen, nothing shows. It needs ImageFormat.PRIVATE
        // (whatever layout the decoder likes), Android 6. Android 5 decodes to memory instead: a little slower than
        // to the screen, so its figures are on the safe side.
        ImageReader reader = null;
        if (Build.VERSION.SDK_INT >= 23) {
            reader = ImageReader.newInstance(W, H, ImageFormat.PRIVATE, 4);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img != null) img.close();
            }, new Handler(ht.getLooper()));
        }
        boolean toSurface = reader != null;
        MediaCodec codec = LowLatencyDecoder.create(mime, W, H, FPS, toSurface ? reader.getSurface() : null, false);
        if (codec == null) {
            if (reader != null) reader.close();
            ht.quitSafely();
            return new JSONObject().put("error", "no decoder");
        }
        String name = codec.getName();
        long[] latencyUs = new long[frames.size()];
        int[] outCount = {0};
        Thread out = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (outCount[0] < frames.size()) {
                int i;
                try {
                    i = codec.dequeueOutputBuffer(info, 200_000);
                } catch (IllegalStateException e) {
                    return;
                }
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) break;   // nothing more is coming
                if (i < 0) continue;
                long now = System.nanoTime() / 1000;
                if (outCount[0] < latencyUs.length) latencyUs[outCount[0]] = now - info.presentationTimeUs;
                outCount[0]++;
                codec.releaseOutputBuffer(i, toSurface);
            }
        }, "luch-bench-drain");
        out.start();
        long t0 = SystemClock.elapsedRealtimeNanos();
        for (int n = 0; n < frames.size(); n++) {
            long due = t0 + n * 1_000_000_000L / FPS;
            long wait = due - SystemClock.elapsedRealtimeNanos();
            if (wait > 0) SystemClock.sleep(wait / 1_000_000);
            int in = codec.dequeueInputBuffer(200_000);
            if (in < 0) continue;
            ByteBuffer b = codec.getInputBuffer(in);
            b.clear();
            b.put(frames.get(n));
            codec.queueInputBuffer(in, 0, frames.get(n).length, System.nanoTime() / 1000,
                    n == 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        }
        try {
            out.join(1500);
        } catch (InterruptedException ignored) {
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
        if (reader != null) reader.close();
        ht.quitSafely();

        int got = Math.min(outCount[0], latencyUs.length);
        if (got <= WARMUP) return new JSONObject().put("error", "decoded " + got + " of " + frames.size()).put("decoder", name);
        long[] l = Arrays.copyOfRange(latencyUs, WARMUP, got);
        Arrays.sort(l);
        return new JSONObject()
                .put("ms", round(l[l.length / 2]))
                .put("p95", round(l[Math.min(l.length - 1, l.length * 95 / 100)]))
                .put("frames", got)
                .put("decoder", name);
    }

    private static double round(long us) {
        return Math.round(us / 100.0) / 10.0;
    }

    // ---- clip parsing --------------------------------------------------------------------------------------

    /** Access units of an Annex-B stream, split at its access unit delimiters. */
    static List<byte[]> annexBFrames(byte[] s, String mime) {
        boolean hevc = mime.equals("video/hevc");
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < s.length; i++) {
            if (s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 0 && s[i + 3] == 1) {
                int nal = s[i + 4] & 0xff;
                boolean aud = hevc ? ((nal >> 1) & 0x3f) == 35 : (nal & 0x1f) == 9;
                if (aud) starts.add(i);
            }
        }
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int a = starts.get(k), b = k + 1 < starts.size() ? starts.get(k + 1) : s.length;
            out.add(Arrays.copyOfRange(s, a, b));
        }
        return out;
    }

    /** Frames of an IVF file: a 32-byte file header, then a 12-byte header (size, pts) before each frame. */
    static List<byte[]> ivfFrames(byte[] s) {
        List<byte[]> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN);
        int pos = b.getShort(6) & 0xffff;
        while (pos + 12 <= s.length) {
            int size = b.getInt(pos);
            pos += 12;
            if (size <= 0 || pos + size > s.length) break;
            out.add(Arrays.copyOfRange(s, pos, pos + size));
            pos += size;
        }
        return out;
    }

    private static byte[] read(Context context, String asset) throws IOException {
        try (InputStream in = context.getAssets().open(asset)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("luch", Context.MODE_PRIVATE);
    }

    /** What the result depends on: the firmware build and this app's version. */
    private static String key(Context context) {
        long version = 0;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return Build.FINGERPRINT + "#" + version;
    }
}
