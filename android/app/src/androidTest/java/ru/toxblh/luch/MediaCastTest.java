// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Media (PROTOCOL.md section 4b) as a sender plays it: a 12 s clip with two sound tracks and WebVTT subtitles, served
 * over HTTP on loopback, so the sender's address is 127.0.0.1 and the "only from the sender" rule holds.
 */
@RunWith(AndroidJUnit4.class)
public class MediaCastTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestSender sender;
    private TestHttp http;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_WAKEUP");
        LuchService.start(context);
        http = new TestHttp(instrumentation.getContext().getAssets());
        sender = TestSender.connect(10_000);
        JSONObject welcome = sender.hello("media");
        assertTrue("media in modes: " + welcome, welcome.getJSONArray("modes").toString().contains("media"));
        long until = SystemClock.uptimeMillis() + 60_000;
        while ((Bench.cached(context) == null || Bench.running()) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(200);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (sender != null) sender.close();
        if (http != null) http.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MediaCast.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        shell("input keyevent KEYCODE_HOME");
    }

    private JSONObject media() throws Exception {
        return new JSONObject().put("t", "media").put("via", "sender").put("title", "Test clip")
                .put("src", new JSONArray()
                        .put(new JSONObject().put("url", http.url("huge.mp4")).put("mime", "video/mp4")
                                .put("w", 15360).put("h", 8640).put("codec", "hevc"))
                        .put(new JSONObject().put("url", http.url("clip.mp4")).put("mime", "video/mp4")
                                .put("w", 480).put("h", 270).put("codec", "avc1.4d401e")))
                .put("subs", new JSONArray().put(new JSONObject().put("id", "en").put("url", http.url("en.vtt"))
                        .put("mime", "text/vtt").put("lang", "en").put("label", "English")));
    }

    interface Check {
        boolean test(JSONObject m) throws Exception;
    }

    /** The next message that matches, skipping the rest; fails after {@code ms}. */
    private JSONObject until(Check p, long ms, String what) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) throw new AssertionError("timed out waiting for " + what);
            JSONObject m;
            try {
                m = sender.next(left);
            } catch (AssertionError quiet) {
                throw new AssertionError("timed out waiting for " + what);
            }
            if (p.test(m)) return m;
        }
    }

    private JSONObject state(Check p, String what) throws Exception {
        return until(m -> m.optString("t").equals("media_state") && p.test(m), 15_000, what);
    }

    /** {uptime ms, position s} right now. */
    private double[] sample() throws Exception {
        sender.drain(); // older reports still queued would give an old position
        cmd(new JSONObject().put("do", "state"));
        JSONObject m = state(x -> true, "state");
        return new double[]{SystemClock.uptimeMillis(), m.getDouble("pos")};
    }

    private void cmd(JSONObject c) throws Exception {
        sender.send(c.put("t", "media_cmd"));
    }

    @Test
    public void aLinkOffTheSenderIsRefused() throws Exception {
        sender.send(new JSONObject().put("t", "media").put("via", "sender")
                .put("src", new JSONArray().put(new JSONObject().put("url", "http://192.0.2.1/x.mp4"))));
        JSONObject e = until(m -> m.optString("t").equals("error"), 5_000, "error");
        assertEquals("bad_link", e.getString("code"));
        assertNull(MediaCast.current);
    }

    @Test
    public void nothingPlayableIsSaid() throws Exception {
        sender.send(new JSONObject().put("t", "media").put("via", "sender").put("src", new JSONArray()
                .put(new JSONObject().put("url", http.url("x.mpd")).put("mime", "application/dash+xml"))));
        JSONObject e = until(m -> m.optString("t").equals("error"), 5_000, "error");
        assertEquals("media_failed", e.getString("code"));
    }

    @Test
    public void playsAndFollowsTheSender() throws Exception {
        sender.send(media());
        until(m -> m.optString("t").equals("media_ready"), 5_000, "media_ready");
        state(m -> m.optString("state").equals("loading"), "loading said at once");
        JSONObject playing = state(m -> m.optString("state").equals("playing"), "playing");
        assertEquals("the source that fits the screen", 480, playing.optInt("w", 480));
        assertFalse(http.requests.contains("huge.mp4"));
        JSONObject listed = state(m -> m.getJSONObject("tracks").getJSONArray("sub").length() > 0, "subtitles listed");
        assertEquals(2, listed.getJSONObject("tracks").getJSONArray("audio").length());
        assertEquals("en", listed.getJSONObject("tracks").getJSONArray("sub").getJSONObject(0).getString("id"));
        assertEquals(12.0, listed.getDouble("dur"), 0.5);
        assertTrue(listed.getJSONObject("sel").isNull("sub"));

        cmd(new JSONObject().put("do", "pause"));
        JSONObject paused = state(m -> m.optString("state").equals("paused"), "paused");
        assertEquals("sender", paused.getString("by"));

        cmd(new JSONObject().put("do", "seek").put("pos", 8));
        state(m -> Math.abs(m.getDouble("pos") - 8) < 1.2, "at 8 s");
        // Range on seeking is checked by tools/testmedia.py with a 30 s clip: this one is small enough to be cached whole.

        cmd(new JSONObject().put("do", "rate").put("rate", 1.5));
        cmd(new JSONObject().put("do", "seek").put("pos", 1));
        cmd(new JSONObject().put("do", "play"));
        JSONObject fast = state(m -> m.optString("state").equals("playing") && Math.abs(m.getDouble("rate") - 1.5) < 0.01,
                "playing at 1.5x");
        SystemClock.sleep(1000); // the seek settles
        double[] first = sample();
        SystemClock.sleep(4000);
        double[] second = sample();
        double speed = (second[1] - first[1]) / ((second[0] - first[0]) / 1000.0);
        assertTrue("runs at about 1.5x: " + speed, speed > 1.35 && speed < 1.65);

        cmd(new JSONObject().put("do", "sub").put("id", "en"));
        state(m -> "en".equals(m.getJSONObject("sel").optString("sub")), "subtitles on");
        String other = listed.getJSONObject("tracks").getJSONArray("audio").getJSONObject(1).getString("id");
        cmd(new JSONObject().put("do", "audio").put("id", other));
        state(m -> other.equals(m.getJSONObject("sel").optString("audio")), "second sound track");
        cmd(new JSONObject().put("do", "sub").put("id", JSONObject.NULL));
        state(m -> m.getJSONObject("sel").isNull("sub"), "subtitles off");

        sender.send(new JSONObject().put("t", "stop"));
        waitFor(() -> MediaCast.current == null, 3_000, "the player closed");
    }

    @Test
    public void theRemoteTogglesAndBackStops() throws Exception {
        sender.send(media());
        state(m -> m.optString("state").equals("playing"), "playing");
        SystemClock.sleep(800);
        shell("input keyevent KEYCODE_DPAD_CENTER");
        JSONObject paused = state(m -> m.optString("state").equals("paused"), "paused by OK");
        assertEquals("receiver", paused.getString("by"));
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE");
        state(m -> m.optString("state").equals("playing"), "playing again");
        double before = paused.getDouble("pos");
        shell("input keyevent KEYCODE_DPAD_RIGHT");
        state(m -> m.getDouble("pos") >= before + 8, "10 s on");
        shell("input keyevent KEYCODE_BACK");
        JSONObject stop = until(m -> m.optString("t").equals("stop"), 5_000, "stop");
        assertEquals("back", stop.getString("reason"));
        assertNull(MediaCast.current);
    }

    /** Another quality of the same video from the same sender: no stop, the new one plays from where it is asked. */
    @Test
    public void theSameSenderSwitchesQualityWithoutAStop() throws Exception {
        sender.send(media());
        state(m -> m.optString("state").equals("playing"), "playing");
        sender.send(media().put("start", 6));
        until(m -> m.optString("t").equals("media_ready"), 5_000, "media_ready again");
        JSONObject again = state(m -> m.optString("state").equals("playing") && m.getDouble("pos") >= 5.5, "playing from 6 s");
        assertTrue(again.toString(), again.getDouble("pos") < 9);
        for (JSONObject m : sender.drain()) {
            assertTrue("no stop for a switch: " + m, !m.optString("t").equals("stop"));
        }
    }

    @Test
    public void theEndIsSaid() throws Exception {
        sender.send(media().put("start", 10.5));
        state(m -> m.optString("state").equals("playing"), "playing");
        state(m -> m.optString("state").equals("ended"), "ended");
        JSONObject stop = until(m -> m.optString("t").equals("stop"), 5_000, "stop");
        assertEquals("ended", stop.getString("reason"));
    }

    @Test
    public void aSenderThatGoesTakesItsVideoAlong() throws Exception {
        sender.send(media());
        state(m -> m.optString("state").equals("playing"), "playing");
        assertNotNull(MediaCast.current);
        sender.close();
        sender = null;
        waitFor(() -> MediaCast.current == null, 5_000, "closed with the connection");
    }

    @Test
    public void aSecondMediaReplacesTheFirst() throws Exception {
        sender.send(media());
        state(m -> m.optString("state").equals("playing"), "playing");
        TestSender other = TestSender.connect(5_000);
        try {
            other.hello("media");
            other.send(media());
            JSONObject r = other.next(5_000);
            // A second media replaces the first: the first sender is told.
            assertEquals("media_ready", r.getString("t"));
            JSONObject stop = until(m -> m.optString("t").equals("stop"), 5_000, "replaced");
            assertEquals("replaced", stop.getString("reason"));
        } finally {
            other.close();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier ok, long ms, String what) {
        long until = SystemClock.uptimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (SystemClock.uptimeMillis() > until) throw new AssertionError(what);
            SystemClock.sleep(100);
        }
    }

    private void shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) {
                // wait for the command to finish
            }
        }
        pfd.close();
    }
}
