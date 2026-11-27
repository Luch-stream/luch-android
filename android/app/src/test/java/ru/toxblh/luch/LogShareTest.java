// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

public class LogShareTest {
    /** What a sender does: every part in order, each decoded, the bytes joined and gunzipped. */
    private static byte[] receive(List<JSONObject> parts) throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            JSONObject p = parts.get(i);
            assertEquals("logs", p.getString("t"));
            assertEquals(i, p.getInt("part"));
            assertEquals(parts.size(), p.getInt("parts"));
            String b64 = p.getString("gz");
            assertTrue("each line fits every sender", b64.length() <= 48 * 1024);
            assertTrue(p.toString().length() < 64 * 1024);
            gz.write(Base64.getDecoder().decode(b64));
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz.toByteArray()))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void aBigLogComesBackWholeInSeveralParts() throws Exception {
        // Random letters gzip to about three quarters: about 230 KB, seven parts.
        Random r = new Random(7);
        StringBuilder b = new StringBuilder();
        while (b.length() < 300_000) {
            for (int i = 0; i < 80; i++) b.append((char) ('!' + r.nextInt(90)));
            b.append('\n');
        }
        byte[] text = b.toString().getBytes(StandardCharsets.UTF_8);
        List<JSONObject> parts = LogShare.parts(text);
        assertTrue(String.valueOf(parts.size()), parts.size() > 3);
        // All but the last are full slices.
        for (int i = 0; i < parts.size() - 1; i++) assertEquals(48 * 1024, parts.get(i).getString("gz").length());
        assertArrayEquals(text, receive(parts));
    }

    @Test
    public void aTypicalLogIsOnePart() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            b.append("2026-01-31 20:15:02.123+0100 I luch-stats: stats {\"t\":\"stats\",\"fps\":60,\"lost\":").append(i)
                    .append("}\n");
        }
        byte[] text = b.toString().getBytes(StandardCharsets.UTF_8);
        List<JSONObject> parts = LogShare.parts(text);
        assertEquals(1, parts.size());
        assertArrayEquals(text, receive(parts));
    }

    @Test
    public void anEmptyLogIsStillOnePart() throws Exception {
        List<JSONObject> parts = LogShare.parts(new byte[0]);
        assertEquals(1, parts.size());
        assertEquals(0, receive(parts).length);
    }

    @Test
    public void base64AsTheStandardOne() {
        byte[] b = new byte[10];
        new Random(1).nextBytes(b);
        for (int n = 0; n <= b.length; n++) {
            byte[] slice = java.util.Arrays.copyOf(b, n);
            assertEquals(Base64.getEncoder().encodeToString(slice), LogShare.base64(b, 0, n));
        }
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{b[3], b[4]}), LogShare.base64(b, 3, 2));
    }

    @Test
    public void howMuchIsAskedFor() throws Exception {
        assertEquals(1024 * 1024, LogShare.maxBytes(new JSONObject("{\"t\":\"logs\"}")));
        assertEquals(64 * 1024, LogShare.maxBytes(new JSONObject("{\"t\":\"logs\",\"max_kb\":64}")));
        assertEquals(1024 * 1024, LogShare.maxBytes(new JSONObject("{\"t\":\"logs\",\"max_kb\":0}")));
        assertEquals(2048 * 1024, LogShare.maxBytes(new JSONObject("{\"t\":\"logs\",\"max_kb\":99999}")));
    }

    /** Only a sender let in gets the log; before that, and over Bluetooth, it is not_allowed (ControlServer). */
    @Test
    public void onlyALetInSenderGetsTheLog() throws Exception {
        JSONObject before = ControlServer.refused("logs", false, false);
        assertEquals("error", before.getString("t"));
        assertEquals("not_allowed", before.getString("code"));
        assertEquals("logs", before.getString("for"));
        assertEquals(null, ControlServer.refused("logs", true, false));
        JSONObject overBle = ControlServer.refused("logs", true, true);
        assertEquals("not_allowed", overBle.getString("code"));
        assertEquals("logs", overBle.getString("for"));
        // What anyone may send stays free; the rest keeps the same rule as the log.
        assertEquals(null, ControlServer.refused("hello", false, false));
        assertEquals(null, ControlServer.refused("ping", false, true));
        assertEquals("not_allowed", ControlServer.refused("start", false, false).getString("code"));
        assertEquals(null, ControlServer.refused("direct", true, true));
    }
}
