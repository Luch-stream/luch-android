// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * PROTOCOL.md 4h on a device: a sender with a group gets clock requests and its packets are played at pts + D; an
 * older sender (no group, D = 0) gets none and is played as before. Silent: the sound is digital silence.
 */
@RunWith(AndroidJUnit4.class)
public class SyncTest {
    private TestSender sender;

    @Before
    public void setUp() throws Exception {
        LuchService.start(InstrumentationRegistry.getInstrumentation().getTargetContext());
        sender = TestSender.connect(10_000);
        JSONObject welcome = sender.hello("mirror");
        JSONObject sync = welcome.getJSONObject("sync");
        assertEquals(1, sync.getInt("v"));
        assertTrue(sync.getBoolean("clock"));
        assertFalse("calibration is off unless turned on", sync.getBoolean("probe"));
        assertFalse(sync.getBoolean("mic"));
    }

    @After
    public void tearDown() {
        sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
    }

    private JSONObject audio() throws Exception {
        return new JSONObject().put("codec", "audio/opus").put("rate", 48000).put("channels", 2).put("frame_ms", 5);
    }

    /** Streams picture and silence for {@code ms}; returns the stats lines. */
    private java.util.List<JSONObject> stream(JSONObject ready, long ms) throws Exception {
        sender.sendOpusHead(ready.getInt("audio_port"));
        Thread a = sender.streamSilence(ready.getInt("audio_port"), ms);
        sender.stream(ready.getInt("video_port"), ms);
        a.join();
        java.util.List<JSONObject> stats = new java.util.ArrayList<>();
        for (JSONObject m : sender.drain()) if ("stats".equals(m.optString("t"))) stats.add(m);
        return stats;
    }

    @Test
    public void anOlderSenderGetsNoClockRequestsAndIsPlayedAtOnce() throws Exception {
        JSONObject ready = sender.start(audio());
        java.util.List<JSONObject> stats = stream(ready, 3_000);
        assertEquals(0, sender.clockRequests.get());
        for (JSONObject s : stats) {
            assertFalse(s.toString(), s.has("clock"));
            assertFalse(s.toString(), s.has("need_ms"));
        }
    }

    @Test
    public void aGroupSyncsTheClockAndPlaysAtPtsPlusD() throws Exception {
        sender.group = new JSONObject().put("id", "test-group").put("timeline", 1).put("role", "both")
                .put("mode", "watch").put("tolerance_ms", 0).put("offset_ms", 0);
        sender.delayUnits = 3000;   // D = 300 ms: the emulator's sound path grows to over 120 ms
        JSONObject ready = sender.start(audio());
        java.util.List<JSONObject> stats = stream(ready, 8_000);
        assertTrue("clock requests: " + sender.clockRequests.get(), sender.clockRequests.get() >= 16);
        JSONObject last = null;
        for (JSONObject s : stats) if (s.has("sync_err_ms") && s.optJSONObject("out") != null
                && s.getJSONObject("out").has("video_err_ms")) last = s;
        assertNotNull("no stats with both errors: " + stats, last);
        Log.i("LuchTest", "group stats " + last);
        JSONObject clock = last.getJSONObject("clock");
        // On one device the round trip is local: the estimate must be well under a millisecond.
        assertTrue(clock.toString(), clock.getLong("err_us") < 1000);
        assertTrue(last.toString(), Math.abs(last.getDouble("sync_err_ms")) < 2);
        // The emulator's sound runs dry at first, its buffer grows and its timestamps move: a few resyncs then, none
        // after (the stats of the last seconds must agree with the first that had them).
        JSONObject out = last.getJSONObject("out");
        assertTrue(last.toString(), out.getInt("audio_resyncs") <= 4);
        // A picture is shown near pts + D. Released at T − P/2, it should read within ±P/2 on a TV; the emulator
        // reports its renders 5 to 10 ms before the time asked for, so here it reads −14 to −18 ms at 60 Hz. Only a
        // real display can prove the half period (docs: sync, real-device plan).
        double ve = out.getDouble("video_err_ms");
        assertTrue(last.toString(), Math.abs(ve) < 25);
        assertEquals(last.toString(), 300, out.getDouble("video_pts_ms"), 25);
        assertTrue(last.toString(), last.getDouble("need_ms") > 0 && last.getDouble("need_ms") < 300);
        int resyncs = -1;
        for (JSONObject s : stats) {
            JSONObject o = s.optJSONObject("out");
            if (o == null || !o.has("audio_resyncs") || stats.indexOf(s) < stats.size() - 4) continue;
            if (resyncs < 0) resyncs = o.getInt("audio_resyncs");
            assertEquals("no resync in the last seconds: " + stats, resyncs, o.getInt("audio_resyncs"));
        }
    }

    @Test
    public void aGroupWithoutDIsShownAtOnceAndReportsWhenThePictureComes() throws Exception {
        // Interactive TV in a group: D = 0 for the picture, as soon as possible; it reports pts → display.
        sender.group = new JSONObject().put("id", "test-group").put("timeline", 2).put("role", "video")
                .put("mode", "interactive").put("tolerance_ms", 20);
        sender.delayUnits = 0;
        JSONObject ready = sender.start(null);
        sender.stream(ready.getInt("video_port"), 5_000);
        JSONObject last = null;
        for (JSONObject m : sender.drain()) {
            if ("stats".equals(m.optString("t")) && m.optJSONObject("out") != null
                    && m.getJSONObject("out").has("video_pts_ms")) last = m;
        }
        assertNotNull("no pts → display in stats", last);
        Log.i("LuchTest", "interactive stats " + last);
        assertTrue(sender.clockRequests.get() >= 16);
        double vpts = last.getJSONObject("out").getDouble("video_pts_ms");
        assertTrue(last.toString(), vpts > 0 && vpts < 100);
        assertFalse(last.toString(), last.getJSONObject("out").has("video_err_ms"));
    }

    /** The calibration never makes a sound here: refused while its setting is off, a dry run while it is on. */
    @Test
    public void aProbeIsRefusedOrOnlyADryRun() throws Exception {
        android.content.Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject probe = new JSONObject().put("t", "probe").put("id", "p1").put("at", 1_000_000).put("n", 4)
                .put("ms", 200).put("gap_ms", 500).put("gain", 0.3);
        sender.send(probe);
        JSONObject m = sender.next(3_000);
        assertEquals(m.toString(), "probe_off", m.optString("code"));
        Calibration.setEnabled(c, true);
        try {
            sender.send(probe);
            m = sender.next(3_000);
            assertEquals(m.toString(), "probe_done", m.getString("t"));
            assertTrue(m.getBoolean("dry_run"));
            assertEquals(1_700_000, m.getJSONArray("played").getLong(1));
            // The detector itself, on this device, on a recording made up in memory.
            ru.toxblh.luch.sync.DryRun.Outcome o = ru.toxblh.luch.sync.DryRun.run(42);
            Log.i("LuchTest", "dry run: measured " + o.measuredMs + " ms, true " + o.trueMs + " ms, " + o.psrDb
                    + " dB, took " + o.tookMs + " ms");
            assertTrue(o.why, o.accepted);
            assertEquals(o.trueMs, o.measuredMs, 0.1);
        } finally {
            Calibration.setEnabled(c, false);
        }
    }
}
