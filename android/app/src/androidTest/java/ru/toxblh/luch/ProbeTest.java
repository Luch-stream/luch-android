// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A calibration's sweeps played into a group's scheduled sound (PROTOCOL.md 4k). It MAKES A SOUND, if a quiet one
 * (two 200 ms sweeps at 1 % of full scale): run it on an emulator, or on a TV by day only.
 */
@RunWith(AndroidJUnit4.class)
public class ProbeTest {
    private TestSender sender;

    @Before
    public void setUp() throws Exception {
        LuchService.start(InstrumentationRegistry.getInstrumentation().getTargetContext());
        sender = TestSender.connect(10_000);
        sender.hello("mirror");
    }

    @After
    public void tearDown() {
        sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
    }

    @Test
    public void sweepsArePlayedAtPtsPlusDPlusTheOffset() throws Exception {
        sender.group = new JSONObject().put("id", "test-group").put("timeline", 3).put("role", "both")
                .put("mode", "watch").put("tolerance_ms", 0).put("offset_ms", 5);
        sender.delayUnits = 3000;   // D = 300 ms
        JSONObject ready = sender.start(new JSONObject().put("codec", "audio/opus").put("rate", 48000)
                .put("channels", 2).put("frame_ms", 5));
        sender.sendOpusHead(ready.getInt("audio_port"));
        Thread a = sender.streamSilence(ready.getInt("audio_port"), 9_000);
        Thread v = new Thread(() -> {
            try {
                sender.stream(ready.getInt("video_port"), 9_000);
            } catch (Exception ignored) {
                // the session ended
            }
        });
        v.start();
        SystemClock.sleep(4_000);   // the clock synced, the sound aligned
        long at = TestSender.senderUs() + 1_000_000;
        sender.send(new JSONObject().put("t", "probe").put("id", "p1").put("at", at).put("signal", "sweep")
                .put("f0", 2000).put("f1", 6000).put("ms", 200).put("n", 2).put("gap_ms", 300).put("gain", 0.01));
        JSONObject done = null;
        long until = SystemClock.uptimeMillis() + 4_000;
        while (done == null && SystemClock.uptimeMillis() < until) {
            JSONObject m = sender.next(until - SystemClock.uptimeMillis());
            if ("probe_done".equals(m.optString("t")) || "error".equals(m.optString("t"))) done = m;
        }
        assertNotNull(done);
        Log.i("LuchTest", "probe " + done);
        assertEquals(done.toString(), "probe_done", done.getString("t"));
        assertEquals("p1", done.getString("id"));
        assertTrue(done.toString(), done.getString("src").matches("timestamp|head"));
        JSONArray played = done.getJSONArray("played");
        assertEquals(2, played.length());
        // Heard where the program sound of that pts is: at + D + offset, as closely as the playout keeps time.
        assertEquals(done.toString(), at + 300_000 + 5_000, played.getLong(0), 5_000);
        assertEquals(done.toString(), 500_000, played.getLong(1) - played.getLong(0), 1_000);
        a.join();
        v.join();
    }
}
