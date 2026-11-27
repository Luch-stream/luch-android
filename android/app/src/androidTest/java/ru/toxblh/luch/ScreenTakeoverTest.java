// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

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
import java.io.IOException;
import java.io.InputStream;

/**
 * Something new for the screen while something is shown. From the same computer (another connection of its, as a Mac
 * casts beside its mirroring) it simply replaces what is shown; from another computer the TV asks, and only Allow
 * lets it take over.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenTakeoverTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestSender mirroring, caster;
    private TestHttp http;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_WAKEUP");
        LuchService.start(context);
        http = new TestHttp(instrumentation.getContext().getAssets());
        mirroring = TestSender.connect(10_000);
        mirroring.hello("mirror");
        waitFor(() -> Bench.cached(context) != null && !Bench.running(), 60_000, "decoder bench did not finish");
        int port = mirroring.start();
        waitFor(MirrorActivity::isShown, 10_000, "mirror screen did not open");
        new Thread(() -> {
            try {
                mirroring.stream(port, 3_000);
            } catch (Exception ignored) {
                // the session was replaced, as the test wants
            }
        }, "test-stream").start();
        waitFor(() -> MirrorSession.current != null, 5_000, "no session");
    }

    @After
    public void tearDown() throws Exception {
        PairPrompt p = PairPrompt.current;
        if (p != null) p.dismiss();
        if (caster != null) caster.close();
        if (mirroring != null) mirroring.close();
        if (http != null) http.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while ((MirrorSession.current != null || MediaCast.current != null) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
        }
        shell("input keyevent KEYCODE_HOME");
    }

    private JSONObject media() throws Exception {
        return new JSONObject().put("t", "media").put("via", "sender").put("title", "Test clip")
                .put("src", new JSONArray().put(new JSONObject().put("url", http.url("clip.mp4"))
                        .put("mime", "video/mp4").put("w", 480).put("h", 270)));
    }

    /** The next message of {@code s} of type {@code t}, skipping the rest. */
    private static JSONObject until(TestSender s, String t, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            JSONObject m = s.next(end - SystemClock.uptimeMillis());
            if (t.equals(m.optString("t"))) return m;
        }
        throw new AssertionError("no " + t);
    }

    @Test
    public void theSameComputerReplacesWhatItShows() throws Exception {
        caster = TestSender.connect(10_000);   // the same id as the mirroring one: one computer, two connections
        caster.hello("media");
        caster.send(media());
        until(caster, "media_ready", 10_000);
        assertNull("no question for one's own screen", PairPrompt.current);
        assertEquals("replaced", until(mirroring, "stop", 5_000).optString("reason"));
        waitFor(() -> MirrorSession.current == null, 5_000, "the mirroring did not end");
        assertNotNull(MediaCast.current);
    }

    @Test
    public void anotherComputerIsAskedAboutAndOnlyAllowTakesOver() throws Exception {
        caster = TestSender.connect(10_000);
        caster.id = "another-laptop";
        caster.name = "Bedroom laptop";
        caster.hello("media");

        caster.send(media());
        waitFor(() -> PairPrompt.current != null, 5_000, "the TV did not ask");
        PairPrompt.current.pressDeny();
        JSONObject busy = until(caster, "error", 5_000);
        assertEquals("busy", busy.optString("code"));
        assertNotNull("the mirroring goes on", MirrorSession.current);

        caster.send(media());
        waitFor(() -> PairPrompt.current != null && PairPrompt.current.asking(), 5_000, "the TV did not ask again");
        PairPrompt.current.pressAllow();
        until(caster, "media_ready", 10_000);
        assertEquals("replaced", until(mirroring, "stop", 5_000).optString("reason"));
        assertNotNull(MediaCast.current);
    }

    interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check c, long ms, String what) {
        long until = SystemClock.uptimeMillis() + ms;
        try {
            while (!c.ok()) {
                if (SystemClock.uptimeMillis() > until) fail(what);
                SystemClock.sleep(50);
            }
        } catch (Exception e) {
            throw new AssertionError(what, e);
        }
    }

    private void shell(String cmd) throws IOException {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] b = new byte[1024];
            while (in.read(b) >= 0) {
                // wait for the command to finish
            }
        }
        pfd.close();
    }
}
