// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * The pointer, the ink and the blank screen of PROTOCOL.md 4h, as a sender drives them over a document and over a
 * mirrored screen, checked in the pixels of a screenshot: drawn where the picture is, by the receiver itself.
 */
@RunWith(AndroidJUnit4.class)
public class InkOverlayTest {
    private static final int GREEN = 0xFF00C853;
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
        http.put("deck.pdf", deck());
        sender = TestSender.connect(10_000);
        JSONObject welcome = sender.hello("mirror", "doc");
        assertTrue("ink in modes: " + welcome, welcome.getJSONArray("modes").toString().contains("\"ink\""));
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
        while ((DocActivity.current != null || MirrorSession.current != null) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
        }
        shell("input keyevent KEYCODE_HOME");
    }

    /** Two blank 4:3 pages, drawn on the device as a sender's PDF. */
    private static byte[] deck() throws Exception {
        PdfDocument pdf = new PdfDocument();
        for (int i = 0; i < 2; i++) {
            PdfDocument.Page p = pdf.startPage(new PdfDocument.PageInfo.Builder(400, 300, i + 1).create());
            pdf.finishPage(p);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdf.writeTo(out);
        pdf.close();
        return out.toByteArray();
    }

    @Test
    public void inkAndPointerOverADocumentStayWithTheirPage() throws Exception {
        sender.send(new JSONObject().put("t", "doc").put("url", http.url("deck.pdf")).put("mime", "application/pdf")
                .put("name", "Deck").put("page", 0));
        until("doc_ready", 15_000);
        until("doc_page", 5_000);
        RectF page = picture();
        assertTrue("a 4:3 page fitted: " + page, Math.abs(page.width() / page.height() - 4f / 3f) < 0.02f);

        // A stroke across the middle, sent in two parts as a sender sends one being drawn.
        sender.send(stroke(1, 0.2, 0.5, 0.4, 0.5).put("color", "#00c853").put("width", 0.02));
        sender.send(stroke(1, 0.6, 0.5, 0.8, 0.5));
        waitPixel(page, 0.7f, 0.5f, true, "the stroke's second part is drawn");
        assertFalse("nothing above the stroke", isGreen(shot(), page, 0.5f, 0.3f));

        // The pointer: a red dot, at its place on the page.
        sender.send(new JSONObject().put("t", "pointer").put("x", 0.25).put("y", 0.25));
        float core = 0.008f * Math.min(page.width(), page.height());
        waitFor(() -> isRed(shot(), page.left + 0.25f * page.width() + core * 0.75f, page.top + 0.25f * page.height()),
                3_000, "the pointer is drawn where it was sent");

        // Another sender may not touch it.
        TestSender other = TestSender.connect(5_000);
        other.hello("doc");
        other.send(new JSONObject().put("t", "ink").put("do", "clear"));
        other.send(new JSONObject().put("t", "blank").put("on", true));
        SystemClock.sleep(500);
        assertTrue("a foreign clear is ignored", isGreen(shot(), page, 0.7f, 0.5f));
        assertFalse(InkView.front().model.blank());
        other.close();

        // Blank: the screen black, told back to the sender.
        sender.send(new JSONObject().put("t", "blank").put("on", true));
        assertTrue(until("blank", 3_000).getBoolean("on"));
        waitFor(() -> isBlack(shot(), page.centerX(), page.centerY()), 3_000, "blank covers the page");

        // A turn ends it, and the next page has no ink of its own.
        sender.send(new JSONObject().put("t", "doc_page").put("page", 1));
        assertFalse("the turn ended blank", until("blank", 3_000).getBoolean("on"));
        waitPixel(page, 0.7f, 0.5f, false, "page 2 is clean");
        sender.send(new JSONObject().put("t", "doc_page").put("page", 0));
        waitPixel(page, 0.7f, 0.5f, true, "page 1 has its stroke again");

        // A clicker on the TV: B blanks, F5 brings it back; the sender hears both.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B);
        assertTrue(until("blank", 3_000).getBoolean("on"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_F5);
        assertFalse(until("blank", 3_000).getBoolean("on"));

        sender.send(new JSONObject().put("t", "ink").put("do", "undo"));
        waitPixel(page, 0.7f, 0.5f, false, "undo took the stroke away");
    }

    @Test
    public void thePointerOverAMirroredScreenFollowsThePicture() throws Exception {
        int port = sender.start();
        new Thread(() -> {
            try {
                sender.stream(port, 6_000);
            } catch (Exception ignored) {
            }
        }).start();
        waitFor(() -> MirrorActivity.isShown() && InkView.front() != null, 10_000, "mirror screen did not open");
        SystemClock.sleep(1_500);   // the picture fitted to the video's 16:9
        RectF video = picture();
        sender.send(new JSONObject().put("t", "pointer").put("x", 0.1).put("y", 0.9));
        float core = 0.008f * Math.min(video.width(), video.height());
        waitFor(() -> isRed(shot(), video.left + 0.1f * video.width() + core * 0.75f, video.top + 0.9f * video.height()),
                3_000, "the pointer is drawn at its place on the mirrored screen");
        sender.send(new JSONObject().put("t", "pointer").put("show", false));
        waitFor(() -> !InkView.front().model.pointerShown(), 2_000, "the pointer hides");
    }

    private static JSONObject stroke(int id, double... points) throws Exception {
        JSONArray a = new JSONArray();
        for (double p : points) a.put(p);
        return new JSONObject().put("t", "ink").put("do", "stroke").put("id", id).put("points", a);
    }

    /** Where the front screen's picture is on the display. */
    private RectF picture() throws Exception {
        RectF r = new RectF();
        waitFor(() -> InkView.front() != null && InkView.front().pictureOnScreen(r) && r.width() > 0, 5_000, "no picture");
        return r;
    }

    private Bitmap shot() {
        Bitmap b = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("no screenshot", b);
        return b.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void waitPixel(RectF r, float x, float y, boolean green, String what) throws Exception {
        waitFor(() -> isGreen(shot(), r, x, y) == green, 3_000, what);
    }

    private static boolean isGreen(Bitmap b, RectF r, float x, float y) {
        int c = b.getPixel(Math.round(r.left + x * r.width()), Math.round(r.top + y * r.height()));
        return Math.abs(Color.red(c) - Color.red(GREEN)) < 40 && Math.abs(Color.green(c) - Color.green(GREEN)) < 40
                && Math.abs(Color.blue(c) - Color.blue(GREEN)) < 40;
    }

    private static boolean isRed(Bitmap b, float x, float y) {
        int c = b.getPixel(Math.round(x), Math.round(y));
        return Color.red(c) > 200 && Color.green(c) < 110 && Color.blue(c) < 110;
    }

    private static boolean isBlack(Bitmap b, float x, float y) {
        int c = b.getPixel(Math.round(x), Math.round(y));
        return Color.red(c) < 10 && Color.green(c) < 10 && Color.blue(c) < 10;
    }

    /** The next message of type {@code t}, skipping others. */
    private JSONObject until(String t, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            JSONObject m = sender.next(Math.max(1, end - SystemClock.uptimeMillis()));
            if (t.equals(m.optString("t"))) return m;
            if ("error".equals(m.optString("t"))) throw new AssertionError("receiver said " + m);
            if (SystemClock.uptimeMillis() > end) throw new AssertionError("no " + t);
        }
    }

    interface Cond {
        boolean ok() throws Exception;
    }

    private static void waitFor(Cond c, long ms, String what) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (!c.ok()) {
            if (SystemClock.uptimeMillis() > end) throw new AssertionError(what);
            SystemClock.sleep(100);
        }
    }

    private void shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) {
                // drain
            }
        }
        pfd.close();
    }
}
