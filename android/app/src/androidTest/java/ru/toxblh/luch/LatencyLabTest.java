// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * The latency lab on the receiver (PROTOCOL.md 4h): a sender turns it on, the mirror screen draws the receiver's
 * pattern bottom right, and its counter, read back from the screen as a camera would, goes up once per refresh.
 */
@RunWith(AndroidJUnit4.class)
public class LatencyLabTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestSender sender;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_WAKEUP");
        LuchService.start(context);
        sender = TestSender.connect(10_000);
        JSONObject welcome = sender.hello("mirror");
        assertTrue("latency not in " + welcome.optJSONArray("modes"),
                welcome.optJSONArray("modes").toString().contains("\"latency\""));
        waitFor(() -> Bench.cached(context) != null && !Bench.running(), 60_000, "decoder bench did not finish");
    }

    @After
    public void tearDown() throws Exception {
        if (sender != null) sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        shell("input keyevent KEYCODE_HOME");
    }

    @Test
    public void thePatternCountsRefreshesAndGoesWithTheSender() throws Exception {
        // Turned on before the mirroring starts, as luchd may.
        sender.send(new JSONObject().put("t", "latency").put("on", true));
        int port = sender.start();
        waitFor(MirrorActivity::isShown, 10_000, "mirror screen did not open");
        sender.stream(port, 1_500);
        long t1 = SystemClock.uptimeMillis();
        long n1 = readCounter(screenshot());
        // Kept for a look by hand, and for the analyser: `luch-latency scan` on it reads the QR code and the counter.
        shell("screencap -p /data/local/tmp/luch-latency-lab.png");
        sender.stream(port, 1_500);
        long t2 = SystemClock.uptimeMillis();
        long n2 = readCounter(screenshot());
        for (JSONObject m : sender.drain()) {
            if ("error".equals(m.optString("t"))) fail("receiver said " + m);
        }

        // A clock of this screen's refreshes: as many per second as the display has, whatever the emulator keeps up.
        Display d = Compat.service(context, WindowManager.class).getDefaultDisplay();
        double perSecond = ((n2 - n1 + 4096) % 4096) * 1000.0 / (t2 - t1);
        assertEquals("counts per second", d.getRefreshRate(), perSecond, d.getRefreshRate() * 0.2);

        sender.send(new JSONObject().put("t", "latency").put("on", false));
        waitFor(() -> !MirrorActivity.latencyLabOn(), 2_000, "the lab stayed on");
        sender.stream(port, 500);
        assertTrue("the pattern stayed on the screen", readCounterOrMinus(screenshot()) < 0);
    }

    @Test
    public void theLabEndsWithTheSenderThatAskedForIt() throws Exception {
        sender.send(new JSONObject().put("t", "latency").put("on", true));
        waitFor(MirrorActivity::latencyLabOn, 2_000, "the lab did not come on");
        sender.close();
        sender = null;
        waitFor(() -> !MirrorActivity.latencyLabOn(), 5_000, "the lab outlived its sender");
    }

    // ---- reading the screen as the analyser does (luch-latency/src/analyze.rs) -------------------------------------

    private Bitmap screenshot() {
        Bitmap b = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("no screenshot", b);
        return b;
    }

    private static long readCounter(Bitmap b) {
        long n = readCounterOrMinus(b);
        assertFalse("no pattern bottom right", n < 0);
        return n;
    }

    /** The counter from the cells' middles, or -1 when the references are not white and black. */
    private static long readCounterOrMinus(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float unit = LatencyPattern.SIDE * h;
        float[] o = LatencyPattern.origin(w, h);
        int[] luma = new int[LatencyPattern.CELLS];
        for (int i = 0; i < LatencyPattern.CELLS; i++) {
            float[] c = LatencyPattern.cell(i);
            int px = b.getPixel(Math.round(o[0] + (c[0] + c[2]) / 2 * unit), Math.round(o[1] + (c[1] + c[3]) / 2 * unit));
            luma[i] = ((px >> 16) & 0xFF) * 3 / 10 + ((px >> 8) & 0xFF) * 6 / 10 + (px & 0xFF) / 10;
        }
        if (luma[0] < 200 || luma[1] > 50) return -1;
        int code = 0;
        for (int i = 2; i < LatencyPattern.CELLS; i++) code = code << 1 | (luma[i] < 128 ? 1 : 0);
        int n = code;
        for (int shift = 1; shift < LatencyPattern.BITS; shift <<= 1) n ^= n >> shift;
        return n;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private interface Check {
        boolean ok();
    }

    private static void waitFor(Check c, long ms, String what) {
        long until = SystemClock.uptimeMillis() + ms;
        while (!c.ok()) {
            if (SystemClock.uptimeMillis() > until) fail(what);
            SystemClock.sleep(50);
        }
    }

    private void shell(String cmd) throws IOException {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) {
                // drain: the command finishes when its output is read
            }
        }
    }
}
