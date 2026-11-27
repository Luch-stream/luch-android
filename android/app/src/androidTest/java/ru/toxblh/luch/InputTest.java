// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * PROTOCOL.md 4h on the emulator: a session over localhost, touches and keys injected into the mirror screen, and what
 * comes back to the sender's video socket decoded and checked. Behaviour only; latency needs a real device.
 */
@RunWith(AndroidJUnit4.class)
public class InputTest {
    private static final String TOKEN = "0123456789abcdef";
    private Instrumentation instrumentation;
    private Context context;
    private TestSender sender;
    private DatagramSocket udp;
    private Thread streaming;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        wake();
        LuchService.start(context);
        sender = TestSender.connect(10_000);
        JSONObject welcome = sender.hello("mirror", "input");
        JSONArray caps = welcome.getJSONArray("input");
        assertTrue("welcome lists keyboards: " + caps, caps.toString().contains("keyboard"));
        assertTrue(welcome.has("touch_points"));
        waitFor(() -> Bench.cached(context) != null && !Bench.running(), 180_000, "decoder bench did not finish");
    }

    @After
    public void tearDown() throws Exception {
        MirrorActivity.virtualIsKeyboard = false;
        if (udp != null) udp.close();
        if (streaming != null) streaming.join(2_000);
        if (sender != null) sender.close();
        long until = SystemClock.uptimeMillis() + 5_000;
        while (MirrorSession.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().remove("touch_mode").commit();
        shell("input keyevent KEYCODE_HOME");
    }

    /** Starts a session whose video comes from {@link #udp}, and waits until the receiver knows where it comes from. */
    private void startSession(int touchMode) throws Exception {
        context.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putInt("touch_mode", touchMode)
                .putBoolean("stats", false).commit();
        udp = new DatagramSocket();
        udp.setSoTimeout(50);
        int port = sender.start();
        streaming = new Thread(() -> {
            try {
                sender.stream(udp, port, 30_000);
            } catch (Exception ignored) {
                // the socket closed at the end of the test
            }
        });
        streaming.setDaemon(true);
        streaming.start();
        waitFor(MirrorActivity::isShown, 10_000, "mirror screen did not open");
        SystemClock.sleep(1_500);   // the first frames, and the screen settled
        sender.drain();
        drainDatagrams();
    }

    private void grant(String accept) throws Exception {
        JSONArray a = new JSONArray();
        for (String x : accept.split(",")) a.put(x);
        sender.send(new JSONObject().put("t", "input").put("grant", "once").put("token", TOKEN).put("accept", a)
                .put("area", new JSONArray().put(1920).put(1080)));
        waitFor(() -> MirrorSession.current != null && MirrorSession.current.input.granted(), 3_000, "grant not taken");
    }

    @Test
    public void touchAsksFirstThenGoesOutAsMultitouchOnThePicture() throws Exception {
        startSession(MirrorActivity.TOUCH_DIRECT);
        tap(960, 540);
        JSONObject request = nextOfType("input_request", 3_000);
        assertEquals("touch", request.getJSONArray("devices").getString(0));
        assertNull("input went out before the grant", nextDatagram(600));
        assertTrue("asked, so the tap is not the panel's", !panelShown());
        tap(960, 540);
        assertNull("one request per kind", nextOfTypeOrNull("input_request", 500));

        grant("touch,pointer,keyboard");
        float[] r = pictureRect();
        long t = SystemClock.uptimeMillis();
        float ax = r[0] + r[2] * 0.25f, ay = r[1] + r[3] * 0.25f, bx = r[0] + r[2] * 0.75f, by = r[1] + r[3] * 0.75f;
        touch(t, MotionEvent.ACTION_DOWN, 0, new float[][] {{ax, ay}});
        touch(t, MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 0,
                new float[][] {{ax, ay}, {bx, by}});
        touch(t, MotionEvent.ACTION_MOVE, 20, new float[][] {{ax + 10, ay}, {bx, by - 10}});
        touch(t, MotionEvent.ACTION_POINTER_UP | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 40,
                new float[][] {{ax + 10, ay}, {bx, by - 10}});
        touch(t, MotionEvent.ACTION_UP, 60, new float[][] {{ax + 10, ay}});

        List<byte[]> got = datagrams(700);
        assertTrue("datagrams: " + got.size(), got.size() >= 4);
        long last = -1;
        boolean two = false;
        for (byte[] p : got) {
            assertEquals(3, p[0]);
            assertEquals(Long.parseUnsignedLong(TOKEN, 16), ByteBuffer.wrap(p).getLong(12));
            long seq = ByteBuffer.wrap(p).getInt(4) & 0xffffffffL;
            assertTrue("sequence goes up", seq > last);
            last = seq;
            byte[] touch = record(p, 1);
            assertNotNull("every datagram carries the touch state", touch);
            if (touch[0] == 2) {
                two = true;
                assertNear(0.25, u16(touch, 3) / 65535.0, "first finger x");
                assertNear(0.25, u16(touch, 5) / 65535.0, "first finger y");
                assertNear(0.75, u16(touch, 11) / 65535.0, "second finger x");
                assertNear(0.75, u16(touch, 13) / 65535.0, "second finger y");
                assertEquals(InputWire.TOOL_FINGER, touch[2]);
                assertTrue("ids differ", touch[1] != touch[9]);
            }
        }
        assertTrue("two contacts were seen together", two);
        assertEquals("all up at the end", 0, record(got.get(got.size() - 1), 1)[0]);
        assertTrue("the release was repeated", got.size() - indexOfFirstAllUp(got) >= 3);
    }

    @Test
    public void keyboardKeysGoAsEvdevEvents() throws Exception {
        MirrorActivity.virtualIsKeyboard = true;
        startSession(MirrorActivity.TOUCH_DIRECT);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A);
        assertEquals("keyboard", nextOfType("input_request", 3_000).getJSONArray("devices").getString(0));
        grant("keyboard");
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT);
        List<byte[]> got = datagrams(500);
        assertTrue(got.size() >= 2);
        byte[] keys = null;
        for (byte[] p : got) {
            byte[] k = record(p, 3);
            if (k != null && (keys == null || k[2] > keys[2])) keys = k;
        }
        assertNotNull("no keys record", keys);
        assertEquals("the events before the grant were dropped: numbering starts at 0", 0, u16(keys, 0));
        assertEquals(4, keys[2]);
        int[][] want = {{42, 1}, {30, 1}, {30, 0}, {42, 0}};
        for (int i = 0; i < 4; i++) {
            assertEquals(want[i][0], u16(keys, 3 + i * 3));
            assertEquals(want[i][1], keys[5 + i * 3]);
        }
        assertEquals("nothing held", 0, keys[3 + 4 * 3]);
    }

    @Test
    public void touchpadModeMovesThePointerAndTapsClick() throws Exception {
        startSession(MirrorActivity.TOUCH_PAD);
        tap(960, 540);
        nextOfType("input_request", 3_000);
        grant("pointer");
        long t = SystemClock.uptimeMillis();
        touch(t, MotionEvent.ACTION_DOWN, 0, new float[][] {{500, 500}});
        for (int i = 1; i <= 10; i++) touch(t, MotionEvent.ACTION_MOVE, i * 16, new float[][] {{500 + i * 20, 500}});
        touch(t, MotionEvent.ACTION_UP, 180, new float[][] {{700, 500}});
        List<byte[]> moved = datagrams(400);
        byte[] last = record(moved.get(moved.size() - 1), 2);
        assertNotNull("no pointer record", last);
        int relX = ByteBuffer.wrap(last).getInt(6), relY = ByteBuffer.wrap(last).getInt(10);
        assertTrue("moved right: " + relX, relX > 200 * 16 / 2);
        assertEquals(0, relY);
        assertEquals("no button after a move", 0, last[0]);
        assertNull("no touch record in touchpad mode", record(moved.get(0), 1));

        tap(960, 540);
        List<byte[]> clicked = datagrams(400);
        boolean down = false;
        for (byte[] p : clicked) down |= (record(p, 2)[0] & InputWire.LEFT) != 0;
        assertTrue("the tap pressed the left button", down);
        assertEquals("and let it go", 0, record(clicked.get(clicked.size() - 1), 2)[0]);
    }

    @Test
    public void noKeepsTheTapsForThePanel() throws Exception {
        startSession(MirrorActivity.TOUCH_DIRECT);
        tap(960, 540);
        nextOfType("input_request", 3_000);
        sender.send(new JSONObject().put("t", "input").put("grant", "no"));
        waitFor(() -> MirrorSession.current.input.denied(), 3_000, "no not taken");
        boolean before = panelShown();
        tap(960, 540);
        waitFor(() -> panelShown() != before, 2_000, "the tap did not toggle the screen's own controls");
        assertNull("nothing went out", nextDatagram(500));
        assertNull("no new request", nextOfTypeOrNull("input_request", 300));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void assertNear(double want, double got, String what) {
        assertTrue(what + ": " + got + " not near " + want, Math.abs(want - got) < 0.02);
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    /** The payload of the first record of {@code type}, null when there is none. */
    private static byte[] record(byte[] p, int type) {
        for (int at = InputWire.HEADER; at + 2 <= p.length; ) {
            int t = p[at] & 0xff, len = p[at + 1] & 0xff;
            if (t == type) {
                byte[] r = new byte[len];
                System.arraycopy(p, at + 2, r, 0, len);
                return r;
            }
            at += 2 + len;
        }
        return null;
    }

    private static int indexOfFirstAllUp(List<byte[]> got) {
        for (int i = 0; i < got.size(); i++) if (record(got.get(i), 1)[0] == 0) return i;
        return got.size();
    }

    private byte[] nextDatagram(long ms) throws IOException {
        long until = SystemClock.uptimeMillis() + ms;
        byte[] buf = new byte[2048];
        while (SystemClock.uptimeMillis() < until) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                udp.receive(p);
            } catch (SocketTimeoutException quiet) {
                continue;
            }
            byte[] b = new byte[p.getLength()];
            System.arraycopy(buf, 0, b, 0, b.length);
            if (b.length > 0 && b[0] == 3) return b;
        }
        return null;
    }

    private List<byte[]> datagrams(long ms) throws IOException {
        List<byte[]> all = new ArrayList<>();
        long until = SystemClock.uptimeMillis() + ms;
        for (byte[] b; (b = nextDatagram(Math.max(1, until - SystemClock.uptimeMillis()))) != null; ) all.add(b);
        return all;
    }

    private void drainDatagrams() throws IOException {
        while (nextDatagram(100) != null) {
            // left from an earlier session
        }
    }

    private JSONObject nextOfType(String type, long ms) throws Exception {
        JSONObject m = nextOfTypeOrNull(type, ms);
        if (m == null) fail("no " + type);
        return m;
    }

    private JSONObject nextOfTypeOrNull(String type, long ms) throws Exception {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            for (JSONObject m : sender.drain()) if (type.equals(m.optString("t"))) return m;
            SystemClock.sleep(20);
        }
        return null;
    }

    private Object screen() throws Exception {
        java.lang.reflect.Field f = MirrorActivity.class.getDeclaredField("shown");
        f.setAccessible(true);
        return f.get(null);
    }

    private boolean panelShown() throws Exception {
        java.lang.reflect.Field f = MirrorActivity.class.getDeclaredField("panel");
        f.setAccessible(true);
        return ((View) f.get(screen())).getVisibility() == View.VISIBLE;
    }

    /** Where the picture is on the screen: x, y, width, height. */
    private float[] pictureRect() throws Exception {
        java.lang.reflect.Field f = MirrorActivity.class.getDeclaredField("video");
        f.setAccessible(true);
        View v = (View) f.get(screen());
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return new float[] {at[0], at[1], v.getWidth(), v.getHeight()};
    }

    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        touch(t, MotionEvent.ACTION_DOWN, 0, new float[][] {{x, y}});
        touch(t, MotionEvent.ACTION_UP, 50, new float[][] {{x, y}});
    }

    private void touch(long down, int action, long after, float[][] fingers) {
        int n = fingers.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = fingers[i][0];
            coords[i].y = fingers[i][1];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(down, down + after, action, n, props, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        instrumentation.sendPointerSync(e);
        e.recycle();
    }

    private void key(int action, int code) {
        long t = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(t, t, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD));
    }

    private void wake() throws IOException {
        PowerManager pm = Compat.service(context, PowerManager.class);
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
