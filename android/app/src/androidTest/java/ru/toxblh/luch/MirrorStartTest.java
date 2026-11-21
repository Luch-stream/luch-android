// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A mirroring session started the way a sender starts it: over the control port, with the bench clip as the video.
 * Runs on an emulator ({@code ./gradlew connectedAndroidTest}); the decoder there is a software one, which is enough
 * here: these tests are about the screen's lifecycle, not about latency.
 */
@RunWith(AndroidJUnit4.class)
public class MirrorStartTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestSender sender;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        // As on a TV after installation (README): the service may bring up the picture from the background.
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        wake();
        LuchService.start(context);
        sender = TestSender.connect(10_000);
        sender.hello("mirror");
        // The first hello measures the decoders (started just after the welcome); a session would compete with it.
        waitFor(() -> Bench.cached(context) != null && !Bench.running(), 60_000, "decoder bench did not finish");
    }

    @After
    public void tearDown() throws Exception {
        if (sender != null) sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        wake();
        shell("input keyevent KEYCODE_HOME");
    }

    /** Issue #1: with the screen asleep the picture used to close at once and the sender got "no decoder". */
    @Test
    public void startWithTheScreenAsleepWakesItAndShowsThePicture() throws Exception {
        sleepScreen();

        int port = sender.start();
        sender.stream(port, 4_000);

        JSONObject stats = null;
        for (JSONObject m : sender.drain()) {
            String t = m.getString("t");
            if ("error".equals(t) || "stop".equals(t)) fail("receiver gave up: " + m);
            if ("stats".equals(t) && m.optInt("fps") > 0) stats = m;
        }
        assertNotNull("no frames shown", stats);
        assertTrue("screen still asleep", context.getSystemService(PowerManager.class).isInteractive());
    }

    /** A screen that closes before the first picture says so, instead of blaming the decoder. */
    @Test
    public void screenClosedBeforeThePictureIsReportedAsSuch() throws Exception {
        int port = sender.start();
        waitFor(() -> MirrorActivity.isShown(), 5_000, "mirror screen did not open");
        shell("input keyevent KEYCODE_HOME");
        sender.stream(port, 3_000); // a stopped screen has a second to come back before the session counts as closed

        JSONObject stop = null;
        for (JSONObject m : sender.drain()) {
            String t = m.getString("t");
            if ("error".equals(t)) fail("expected a stop, got " + m);
            if ("stop".equals(t)) stop = m;
        }
        assertNotNull("no stop from the receiver", stop);
        assertEquals("receiver", stop.optString("by"));
        assertEquals("screen_closed", stop.optString("reason"));
    }

    /** Back still ends the session at once, without the grace a stopped screen gets. */
    @Test
    public void backEndsTheSessionAtOnce() throws Exception {
        int port = sender.start();
        sender.stream(port, 1_000);
        shell("input keyevent KEYCODE_BACK");
        JSONObject stop = null;
        long until = SystemClock.uptimeMillis() + 500;
        while (stop == null && SystemClock.uptimeMillis() < until) {
            for (JSONObject m : sender.drain()) if ("stop".equals(m.getString("t"))) stop = m;
            SystemClock.sleep(20);
        }
        assertNotNull("no stop within half a second of Back", stop);
        assertEquals("back", stop.optString("reason"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void sleepScreen() throws IOException {
        PowerManager pm = context.getSystemService(PowerManager.class);
        shell("input keyevent KEYCODE_SLEEP");
        waitFor(() -> !pm.isInteractive(), 5_000, "screen did not go to sleep");
        SystemClock.sleep(1_000); // let the display finish turning off, as on a TV left alone
    }

    private void wake() throws IOException {
        PowerManager pm = context.getSystemService(PowerManager.class);
        shell("input keyevent KEYCODE_WAKEUP");
        waitFor(pm::isInteractive, 5_000, "screen did not wake up");
        shell("wm dismiss-keyguard");
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
