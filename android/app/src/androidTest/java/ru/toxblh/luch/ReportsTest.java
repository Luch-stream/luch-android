// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/** Crash and error reports kept on the TV and handed to a sender that asks for them (PROTOCOL.md, section 6). */
@RunWith(AndroidJUnit4.class)
public class ReportsTest {
    private Context context;
    private TestSender sender;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Reports.taken(context, ids(Reports.pending(context)));
        LuchService.start(context);
    }

    @After
    public void tearDown() {
        if (sender != null) sender.close();
        Reports.taken(context, ids(Reports.pending(context)));
    }

    @Test
    public void addressesAreScrubbedFromReports() {
        String s = Reports.scrub("failed to connect to /192.168.1.40 (port 47800) from /fe80::1c2b:3aff:fe4d%wlan0, "
                + "peer ::1; at ru.toxblh.luch.MirrorSession.decode(MirrorSession.java:360) 07:09:56");
        assertFalse(s, s.contains("192.168.1.40"));
        assertFalse(s, s.contains("fe80"));
        assertFalse(s, s.contains("::1"));
        assertTrue(s, s.contains("MirrorSession.java:360"));
        assertTrue(s, s.contains("port 47800"));
    }

    @Test
    public void aCrashIsKeptWithItsStack() {
        Reports.crash(context, new Thread("luch-video"),
                new IllegalStateException("decoder died talking to 10.0.0.7"));
        JSONArray p = Reports.pending(context);
        assertEquals(1, p.length());
        JSONObject r = p.optJSONObject(0);
        assertEquals("crash", r.optString("kind"));
        assertEquals("luch-video", r.optString("thread"));
        assertTrue(r.optString("stack"), r.optString("stack").contains("IllegalStateException"));
        assertFalse(r.toString(), r.toString().contains("10.0.0.7"));
        assertTrue(r.optInt("sdk") > 0);
        assertFalse(r.optString("app").isEmpty());
    }

    @Test
    public void theSameFailureTwiceCountsUp() {
        Exception e = new IOException("no decoder for video/hevc");
        Reports.error(context, "no decoder for video/hevc", e, null);
        Reports.error(context, "no decoder for video/hevc", e, null);
        JSONArray p = Reports.pending(context);
        assertEquals(1, p.length());
        assertEquals(2, p.optJSONObject(0).optInt("count"));
    }

    @Test
    public void onlyTheNewestAreKept() {
        for (int i = 0; i < Reports.MAX + 3; i++) {
            Reports.error(context, "failure " + i, null, null);
            SystemClock.sleep(5);   // distinct file times
        }
        JSONArray p = Reports.pending(context);
        assertEquals(Reports.MAX, p.length());
        assertEquals("failure " + (Reports.MAX + 2), p.optJSONObject(p.length() - 1).optString("what"));
    }

    @Test
    public void aSenderThatAsksGetsTheReportsAndTheyAreGoneOnceTaken() throws Exception {
        Reports.error(context, "no decoder for video/hevc", null, new JSONObject().put("codec", "video/hevc"));

        sender = TestSender.connect(10_000);
        sender.hello("mirror", "reports");
        JSONObject reports = sender.next(3_000);
        assertEquals("reports", reports.getString("t"));
        JSONArray items = reports.getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals("no decoder for video/hevc", items.getJSONObject(0).getString("what"));
        assertEquals("video/hevc", items.getJSONObject(0).getJSONObject("details").getString("codec"));

        sender.send(new JSONObject().put("t", "reports_taken").put("ids", ids(items)));
        waitUntilEmpty();

        sender.close();
        sender = TestSender.connect(10_000);
        sender.hello("mirror", "reports");
        assertNull("reports offered again", reportsWithin(1_000));
    }

    @Test
    public void aSenderThatDoesNotAskGetsNothing() throws Exception {
        Reports.error(context, "no decoder for video/hevc", null, null);
        sender = TestSender.connect(10_000);
        sender.hello("mirror");
        assertNull(reportsWithin(1_000));
        assertEquals("kept for a sender that asks", 1, Reports.pending(context).length());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private JSONObject reportsWithin(long ms) throws Exception {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            for (JSONObject m : sender.drain()) if ("reports".equals(m.optString("t"))) return m;
            SystemClock.sleep(50);
        }
        return null;
    }

    private void waitUntilEmpty() {
        long until = SystemClock.uptimeMillis() + 3_000;
        while (Reports.pending(context).length() > 0) {
            assertTrue("reports not forgotten after reports_taken", SystemClock.uptimeMillis() < until);
            SystemClock.sleep(50);
        }
    }

    private static JSONArray ids(JSONArray reports) {
        JSONArray ids = new JSONArray();
        for (int i = 0; i < reports.length(); i++) {
            JSONObject r = reports.optJSONObject(i);
            assertNotNull(r);
            ids.put(r.optString("id"));
        }
        return ids;
    }
}
