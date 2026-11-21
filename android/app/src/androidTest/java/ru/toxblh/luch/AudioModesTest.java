// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What the receiver says about its Android version, and the audio modes a sender can pick from that
 * (PROTOCOL.md, sections 4 and 5): on Android 5 and 6 the sound cannot be made short, so a sender either leaves it
 * out, lets it lag, or asks the receiver to hold the picture back to match it ("sync").
 */
@RunWith(AndroidJUnit4.class)
public class AudioModesTest {
    private Context context;
    private TestSender sender;
    private JSONObject welcome;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LuchService.start(context);
        sender = TestSender.connect(10_000);
        welcome = sender.hello("mirror");
        long until = SystemClock.uptimeMillis() + 60_000;
        while ((Bench.cached(context) == null || Bench.running()) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(200);
        }
    }

    @After
    public void tearDown() {
        sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
    }

    @Test
    public void theWelcomeSaysWhatWorksWorseHere() throws Exception {
        assertEquals(Build.VERSION.SDK_INT, welcome.getInt("sdk"));
        JSONObject limits = welcome.getJSONObject("limits");
        JSONArray l = limits.getJSONArray("limits");
        boolean lag = l.toString().contains("\"audio_lag\"");
        if (Build.VERSION.SDK_INT >= 24) {
            assertFalse(l.toString(), lag);
            assertEquals(20, limits.getInt("audio_latency_ms"));
        } else {
            assertTrue(l.toString(), lag);
            assertTrue(limits.toString(), limits.getInt("audio_latency_ms") > 20);
        }
    }

    @Test
    public void syncHoldsThePictureBackByTheAudioDelay() throws Exception {
        JSONObject stats = session(true);
        int audio = stats.getInt("audio_latency_ms");
        assertTrue(stats.toString(), audio > 0);
        assertEquals(stats.toString(), audio, stats.getInt("video_hold_ms"));
    }

    @Test
    public void withoutSyncThePictureIsNotHeld() throws Exception {
        JSONObject stats = session(false);
        assertTrue(stats.toString(), stats.getInt("audio_latency_ms") > 0);
        assertEquals(stats.toString(), 0, stats.getInt("video_hold_ms"));
    }

    /** A session with audio; returns the last stats once the audio track is up. */
    private JSONObject session(boolean sync) throws Exception {
        JSONObject ready = sender.start(new JSONObject().put("codec", "audio/opus").put("rate", 48000)
                .put("channels", 2).put("frame_ms", 5).put("sync", sync));
        sender.sendOpusHead(ready.getInt("audio_port"));
        sender.stream(ready.getInt("video_port"), 3_000);
        JSONObject last = null;
        for (JSONObject m : sender.drain()) {
            if ("stats".equals(m.optString("t")) && m.optInt("audio_latency_ms") > 0) last = m;
        }
        assertNotNull("no stats with the audio track up", last);
        return last;
    }
}
