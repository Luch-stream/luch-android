// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * The heat badge over the picture comes only when the session's sender asked for it ({@code "watch_device":true},
 * PROTOCOL.md 5); the device fields are reported either way. A hot TV is made by setting the thermal status the
 * listener would set: the emulator never gets hot by itself.
 */
@RunWith(AndroidJUnit4.class)
public class HeatBadgeTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestSender sender;
    private Field thermal;
    private String thermalBefore;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        PowerManager pm = Compat.service(context, PowerManager.class);
        shell("input keyevent KEYCODE_WAKEUP");
        waitFor(pm::isInteractive, 5_000, "screen did not wake up");
        shell("wm dismiss-keyguard");
        LuchService.start(context);
        sender = TestSender.connect(10_000);
        sender.hello("mirror");
        waitFor(() -> Bench.cached(context) != null && !Bench.running(), 60_000, "decoder bench did not finish");
        thermal = DeviceState.class.getDeclaredField("thermal");
        thermal.setAccessible(true);
        thermalBefore = (String) thermal.get(null);
        thermal.set(null, "severe");
    }

    @After
    public void tearDown() throws Exception {
        if (thermal != null) thermal.set(null, thermalBefore);
        if (sender != null) sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        shell("input keyevent KEYCODE_HOME");
    }

    @Test
    public void theBadgeShowsOnlyWhenTheSenderAsks() throws Exception {
        sender.watchDevice = false;
        int port = sender.start();
        waitFor(MirrorActivity::isShown, 10_000, "mirror screen did not open");
        sender.stream(port, 2_500);
        assertEquals("a hot TV, a sender that did not ask: no badge", View.GONE, badge());
        assertNotNull("the device fields still go to the sender", thermalSent());

        // The same sender starts again, asking now: the badge follows on the same screen.
        sender.watchDevice = true;
        sender.drain();
        port = sender.start(null).getInt("video_port");
        sender.stream(port, 2_500);
        assertEquals("asked for: the badge", View.VISIBLE, badge());

        sender.watchDevice = false;
        sender.drain();
        port = sender.start(null).getInt("video_port");
        sender.stream(port, 2_500);
        assertEquals("not asked for again: gone", View.GONE, badge());
    }

    /** The badge's visibility, read on the main thread. */
    private int badge() throws Exception {
        Field shown = MirrorActivity.class.getDeclaredField("shown");
        shown.setAccessible(true);
        Object screen = shown.get(null);
        assertNotNull("no mirror screen", screen);
        Field hot = MirrorActivity.class.getDeclaredField("hotBadge");
        hot.setAccessible(true);
        View v = (View) hot.get(screen);
        assertNotNull("no badge view", v);
        int[] out = new int[1];
        instrumentation.runOnMainSync(() -> out[0] = v.getVisibility());
        return out[0];
    }

    /** The thermal status from the stats lines so far (sent once, when it changes), or null. */
    private String thermalSent() {
        String seen = null;
        for (JSONObject m : sender.drain()) {
            if ("stats".equals(m.optString("t")) && m.has("thermal")) seen = m.optString("thermal");
        }
        return seen;
    }

    private interface Check {
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
