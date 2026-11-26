// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * PROTOCOL.md 4d-4g as a sender meets them: sound only with "now playing" and the remote's keys sent back, the TV's
 * volume, its own apps, and a PDF. Safe on a real TV: nothing here puts it to sleep.
 */
@RunWith(AndroidJUnit4.class)
public class RemoteControlTest {
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
        long until = SystemClock.uptimeMillis() + 60_000;
        JSONObject w = sender.hello("audio");
        welcome = w;
        while ((Bench.cached(context) == null || Bench.running()) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(200);
        }
    }

    private JSONObject welcome;

    @After
    public void tearDown() throws Exception {
        if (sender != null) sender.close();
        if (http != null) http.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while ((MirrorSession.current != null || DocActivity.current != null) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
        }
        shell("input keyevent KEYCODE_HOME");
    }

    private JSONObject until(String t, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) throw new AssertionError("no " + t);
            JSONObject m;
            try {
                m = sender.next(left);
            } catch (AssertionError quiet) {
                throw new AssertionError("no " + t);
            }
            if (m.optString("t").equals(t)) return m;
        }
    }

    @Test
    public void theWelcomeSaysWhatIsThere() throws Exception {
        String modes = welcome.getJSONArray("modes").toString();
        assertTrue(modes, modes.contains("audio") && modes.contains("doc") && modes.contains("media"));
        assertTrue(welcome.has("apps"));
        assertTrue(welcome.has("volume"));
    }

    @Test
    public void soundOnlyShowsWhatPlaysAndSendsTheKeysBack() throws Exception {
        sender.send(new JSONObject().put("t", "start").put("mode", "audio")
                .put("audio", new JSONObject().put("codec", "audio/opus").put("rate", 48000).put("channels", 2)
                        .put("frame_ms", 5)));
        assertEquals("ready", until("ready", 5_000).getString("t"));
        waitFor(NowPlayingActivity::isShown, 5_000, "the now playing screen");
        assertTrue("no picture in sound only", !MirrorActivity.isShown());
        sender.send(new JSONObject().put("t", "now_playing").put("title", "Song").put("artist", "Band")
                .put("state", "playing").put("pos", 10).put("dur", 200)
                .put("art", "http://192.0.2.9/cover.jpg")); // not on the sender: dropped
        waitFor(() -> Remote.nowPlaying != null && "Song".equals(Remote.nowPlaying.optString("title")), 3_000, "now playing");
        assertTrue("art off the sender is dropped", Remote.nowPlaying.optString("art").isEmpty());
        sender.drain();
        shell("input keyevent KEYCODE_DPAD_CENTER");
        assertEquals("play_pause", until("media_key", 5_000).getString("key"));
        shell("input keyevent KEYCODE_DPAD_RIGHT");
        assertEquals("next", until("media_key", 5_000).getString("key"));
        shell("input keyevent KEYCODE_MEDIA_PREVIOUS");
        assertEquals("previous", until("media_key", 5_000).getString("key"));
        shell("input keyevent KEYCODE_BACK");
        assertEquals("back", until("stop", 5_000).getString("reason"));
        waitFor(() -> MirrorSession.current == null, 3_000, "session ended");
    }

    @Test
    public void volumeFollowsTheSender() throws Exception {
        JSONObject before = Remote.volume(context);
        if (before == null) return; // a TV that keeps its volume to itself: nothing to check
        try {
            // Quiet: never above 5 % on a real TV (someone may be asleep next to it), or one step where a step is more.
            android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int quiet = Math.max(5, Remote.percent(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)));
            sender.send(new JSONObject().put("t", "volume").put("level", 2));
            JSONObject v = until("volume", 3_000);
            assertTrue(v.toString(), v.getInt("level") > 0 && v.getInt("level") <= quiet);
            sender.send(new JSONObject().put("t", "volume").put("level", 0));
            JSONObject zero = until("volume", 3_000);
            assertEquals(0, zero.getInt("level"));
            sender.send(new JSONObject().put("t", "volume").put("step", 1));
            JSONObject up = until("volume", 3_000);
            assertTrue(up.toString(), up.getInt("level") > 0 && up.getInt("level") <= quiet);
        } finally {
            sender.send(new JSONObject().put("t", "volume").put("level", Math.min(5, before.getInt("level")))
                    .put("muted", before.optBoolean("muted")));
            until("volume", 3_000);
        }
    }

    @Test
    public void appsAreOpenedOnlyByKnownNamesAndIds() throws Exception {
        sender.send(new JSONObject().put("t", "open_app").put("app", "netflix").put("id", "1"));
        assertEquals("no_app", until("error", 3_000).getString("code"));
        sender.send(new JSONObject().put("t", "open_app").put("app", "youtube").put("id", "x\"; rm -rf /"));
        JSONObject e = until("error", 3_000);
        assertTrue(e.toString(), e.getString("code").equals("bad_id") || e.getString("code").equals("no_app"));
        // Opening the app plays sound: only where nobody hears it (an emulator) or when asked for.
        boolean quietDevice = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")
                || "true".equals(InstrumentationRegistry.getArguments().getString("luchLoud"));
        if (quietDevice && welcome.getJSONArray("apps").toString().contains("youtube")) {
            sender.send(new JSONObject().put("t", "open_app").put("app", "youtube").put("id", "aqz-KE-bpKQ").put("start", 30));
            assertEquals("youtube", until("app_opened", 5_000).getString("app"));
            SystemClock.sleep(3000);
        }
    }

    @Test
    public void aPdfTurnsPages() throws Exception {
        sender.send(new JSONObject().put("t", "doc").put("url", http.url("three.pdf")).put("mime", "application/pdf")
                .put("name", "three.pdf"));
        assertEquals(3, until("doc_ready", 10_000).getInt("pages"));
        assertEquals(0, until("doc_page", 3_000).getInt("page"));
        shell("input keyevent KEYCODE_DPAD_RIGHT");
        assertEquals(1, until("doc_page", 3_000).getInt("page"));
        sender.send(new JSONObject().put("t", "doc_page").put("page", 2));
        assertEquals(2, until("doc_page", 3_000).getInt("page"));
        shell("input keyevent KEYCODE_DPAD_RIGHT"); // the last page: nothing to turn to
        shell("input keyevent KEYCODE_BACK");
        assertEquals("back", until("stop", 5_000).getString("reason"));
        assertNull(DocActivity.current);
    }

    @Test
    public void aDocumentOffTheSenderIsRefused() throws Exception {
        sender.send(new JSONObject().put("t", "doc").put("url", "http://192.0.2.1/a.pdf"));
        assertEquals("bad_link", until("error", 3_000).getString("code"));
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
