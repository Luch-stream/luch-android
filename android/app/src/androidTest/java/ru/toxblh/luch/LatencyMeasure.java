// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Not a test: a measurement, run only when asked for
 * ({@code -Pandroid.testInstrumentationRunnerArguments.measure=true}). Three sessions of 8 s from a sender on the
 * same device: picture only, picture and sound, picture and sound with "sync". Logs one line per mode under the tag
 * LuchMeasure with the receiver's own figures (median of its once-a-second stats).
 */
@RunWith(AndroidJUnit4.class)
public class LatencyMeasure {
    private static final String TAG = "LuchMeasure";

    @Test
    public void measure() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("run with measure=true", "true".equals(args.getString("measure")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LuchService.start(context);
        TestSender warm = TestSender.connect(10_000);
        warm.hello("mirror");
        long until = SystemClock.uptimeMillis() + 90_000;
        while ((Bench.cached(context) == null || Bench.running()) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(200);
        }
        warm.close();
        Log.i(TAG, "android " + Build.VERSION.RELEASE + " sdk " + Build.VERSION.SDK_INT + " bench "
                + Bench.cached(context));
        run("video", null);
        run("video+audio", audio(false));
        run("video+audio sync", audio(true));
    }

    private static JSONObject audio(boolean sync) throws Exception {
        return new JSONObject().put("codec", "audio/opus").put("rate", 48000).put("channels", 2).put("frame_ms", 5)
                .put("sync", sync);
    }

    private static void run(String mode, JSONObject audio) throws Exception {
        TestSender s = TestSender.connect(10_000);
        s.hello("mirror");
        JSONObject ready = s.start(audio);
        if (audio != null) s.sendOpusHead(ready.getInt("audio_port"));
        s.stream(ready.getInt("video_port"), 8_000);
        List<Double> decode = new ArrayList<>();
        int audioMs = 0, holdMs = 0;
        String decoder = "";
        for (JSONObject m : s.drain()) {
            if (!"stats".equals(m.optString("t")) || m.optInt("fps") == 0) continue;
            decode.add(m.optDouble("decode_ms"));
            audioMs = m.optInt("audio_latency_ms");
            holdMs = m.optInt("video_hold_ms");
            decoder = m.optString("decoder");
        }
        s.close();
        SystemClock.sleep(1500);
        Collections.sort(decode);
        double median = decode.isEmpty() ? -1 : decode.get(decode.size() / 2);
        JSONObject line = new JSONObject().put("mode", mode).put("codec", s.codec).put("decoder", decoder)
                .put("decode_ms", median).put("audio_latency_ms", audioMs).put("video_hold_ms", holdMs)
                .put("seconds", decode.size());
        Log.i(TAG, line.toString());
    }
}
