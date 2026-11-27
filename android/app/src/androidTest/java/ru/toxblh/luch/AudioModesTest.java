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

    /**
     * Phase 0 of docs: sync: the hold is what the sound takes beyond the picture, both measured (the track's
     * timestamps, the render callbacks), not the audio buffer's size.
     */
    @Test
    public void syncHoldsThePictureBackByWhatTheSoundTakesMore() throws Exception {
        JSONObject stats = session(true);
        JSONObject out = stats.getJSONObject("out");
        double audio = out.getDouble("audio_ms"), video = out.getDouble("video_ms");
        assertTrue(stats.toString(), audio > 0);
        // The emulator reports a render a few ms before the time asked for, so a held picture can read slightly
        // negative there; a TV reports when it really showed it.
        assertTrue(stats.toString(), video > -10);
        String src = out.getString("audio_src");
        assertTrue(stats.toString(), src.equals("timestamp") || src.equals("head"));
        assertTrue(stats.toString(), out.has("route"));
        // video_hold_ms is set from the same medians a moment before they are rounded into the stats.
        assertEquals(stats.toString(), Math.max(0, audio - video), stats.getInt("video_hold_ms"), 3);
        assertEquals(stats.toString(), stats.getInt("video_hold_ms"), stats.getInt("audio_latency_ms"), 1);
    }

    @Test
    public void withoutSyncThePictureIsNotHeld() throws Exception {
        JSONObject stats = session(false);
        assertTrue(stats.toString(), stats.getJSONObject("out").getDouble("audio_ms") > 0);
        assertEquals(stats.toString(), 0, stats.getInt("video_hold_ms"));
    }

    /** A session with audio; returns the last stats once the audio track is up. */
    private JSONObject session(boolean sync) throws Exception {
        JSONObject ready = sender.start(new JSONObject().put("codec", "audio/opus").put("rate", 48000)
                .put("channels", 2).put("frame_ms", 5).put("sync", sync));
        sender.sendOpusHead(ready.getInt("audio_port"));
        Thread audio = sender.streamSilence(ready.getInt("audio_port"), 4_000);
        sender.stream(ready.getInt("video_port"), 4_000);
        audio.join();
        JSONObject last = null;
        for (JSONObject m : sender.drain()) {
            if ("stats".equals(m.optString("t")) && m.optJSONObject("out") != null
                    && m.getJSONObject("out").has("audio_ms") && m.getJSONObject("out").has("video_ms")) last = m;
        }
        assertNotNull("no stats with the audio track up", last);
        android.util.Log.i("LuchTest", "sync=" + sync + " stats " + last);
        return last;
    }
}
