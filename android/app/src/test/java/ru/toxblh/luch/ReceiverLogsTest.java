// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** This phone as a sender asking a receiver for its log (PROTOCOL.md 6a), against a receiver made of LogShare. */
public class ReceiverLogsTest {
    /** A receiver on the far end: it answers a "logs" line with what it is given, and keeps what it was sent. */
    private static final class FakeReceiver implements ReceiverLogs.Channel {
        final List<JSONObject> asked = new ArrayList<>();
        final ArrayDeque<JSONObject> answers = new ArrayDeque<>();
        final List<JSONObject> reply;
        IOException broken;

        FakeReceiver(List<JSONObject> reply) {
            this.reply = reply;
        }

        @Override
        public void send(JSONObject message) throws IOException {
            if (broken != null) throw broken;
            asked.add(message);
            if ("logs".equals(message.optString("t"))) answers.addAll(reply);
        }

        @Override
        public JSONObject next(long timeoutMs) {
            return answers.poll();   // empty: as if timeoutMs went by
        }
    }

    private static byte[] bigLog() {
        Random r = new Random(3);
        StringBuilder b = new StringBuilder();
        while (b.length() < 300_000) {
            b.append("2026-01-31 20:15:02.123+0100 I luch-video: ");
            for (int i = 0; i < 60; i++) b.append((char) ('!' + r.nextInt(90)));
            b.append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String fetch(FakeReceiver rx) throws ReceiverLogs.Failure {
        return ReceiverLogs.fetch(rx, 1024, 10);
    }

    private static String failure(FakeReceiver rx) {
        try {
            fetch(rx);
        } catch (ReceiverLogs.Failure f) {
            return f.getMessage();
        }
        fail("no failure");
        return null;
    }

    @Test
    public void aLogInSeveralPartsComesBackWhole() throws Exception {
        byte[] text = bigLog();
        List<JSONObject> parts = LogShare.parts(text);
        assertTrue(parts.size() > 3);
        FakeReceiver rx = new FakeReceiver(parts);
        assertArrayEquals(text, fetch(rx).getBytes(StandardCharsets.UTF_8));
        assertEquals(1, rx.asked.size());
        assertEquals("{\"t\":\"logs\",\"max_kb\":1024}", rx.asked.get(0).toString());
    }

    @Test
    public void partsOutOfOrderOrCountedFromOneReadTheSame() throws Exception {
        byte[] text = bigLog();
        List<JSONObject> parts = new ArrayList<>(LogShare.parts(text));
        Collections.reverse(parts);
        assertArrayEquals(text, fetch(new FakeReceiver(parts)).getBytes(StandardCharsets.UTF_8));
        for (JSONObject p : parts) p.put("part", p.getInt("part") + 1);
        assertArrayEquals(text, fetch(new FakeReceiver(parts)).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void anEmptyLogIsAnEmptyText() throws Exception {
        assertEquals("", fetch(new FakeReceiver(LogShare.parts(new byte[0]))));
    }

    @Test
    public void otherLinesInBetweenAreLeftAlone() throws Exception {
        byte[] text = "one line\n".getBytes(StandardCharsets.UTF_8);
        List<JSONObject> reply = new ArrayList<>();
        reply.add(new JSONObject("{\"t\":\"error\",\"code\":\"busy\",\"for\":\"start\"}"));
        reply.addAll(LogShare.parts(text));
        assertEquals("one line\n", fetch(new FakeReceiver(reply)));
    }

    @Test
    public void refusalsSayWhy() throws Exception {
        assertEquals("would not hand over its log (not let in)", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"error\",\"code\":\"not_allowed\",\"for\":\"logs\"}")))));
        assertEquals("cannot hand over its log: update Luch on it", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"error\",\"code\":\"unknown\",\"for\":\"logs\"}")))));
        assertEquals("refused its log: too soon", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"error\",\"code\":\"busy\",\"for\":\"logs\",\"detail\":\"too soon\"}")))));
    }

    @Test
    public void silenceAndBreakageAreFailures() throws Exception {
        assertEquals("did not send its log in time", failure(new FakeReceiver(Collections.emptyList())));
        // Only some of the parts: the rest never comes.
        List<JSONObject> parts = LogShare.parts(bigLog());
        assertEquals("did not send its log in time", failure(new FakeReceiver(parts.subList(0, 2))));
        FakeReceiver gone = new FakeReceiver(parts);
        gone.broken = new IOException("Broken pipe");
        assertEquals("the connection broke: Broken pipe", failure(gone));
    }

    @Test
    public void partsThatDoNotFitAreRefused() throws Exception {
        assertEquals("sent a log part that cannot be read", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"logs\",\"part\":0,\"parts\":1,\"gz\":\"not base64!\"}")))));
        assertEquals("sent log part 5 of 2", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"logs\",\"part\":5,\"parts\":2,\"gz\":\"\"}")))));
        assertEquals("sent log parts of two different logs", failure(new FakeReceiver(Arrays.asList(
                new JSONObject("{\"t\":\"logs\",\"part\":0,\"parts\":2,\"gz\":\"\"}"),
                new JSONObject("{\"t\":\"logs\",\"part\":1,\"parts\":3,\"gz\":\"\"}")))));
        // Whole, but not gzip.
        assertEquals("sent a log that does not unpack", failure(new FakeReceiver(Arrays.asList(
                new JSONObject().put("t", "logs").put("part", 0).put("parts", 1)
                        .put("gz", Base64.getEncoder().encodeToString("plain".getBytes(StandardCharsets.UTF_8)))))));
    }

    @Test
    public void onlyAReceiverThatOffersItIsAsked() throws Exception {
        assertTrue(ReceiverLogs.offered(new JSONObject("{\"t\":\"welcome\",\"modes\":[\"mirror\",\"logs\"]}")));
        assertFalse(ReceiverLogs.offered(new JSONObject("{\"t\":\"welcome\",\"modes\":[\"mirror\"]}")));
        assertFalse(ReceiverLogs.offered(new JSONObject("{\"t\":\"welcome\"}")));
        assertFalse(ReceiverLogs.offered(null));
    }

    @Test
    public void base64BackAsTheStandardOne() {
        Random r = new Random(5);
        for (int n = 0; n < 40; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            assertArrayEquals(b, LogShare.unbase64(LogShare.base64(b, 0, n)));
            assertArrayEquals(b, LogShare.unbase64(Base64.getEncoder().encodeToString(b)));
        }
        assertNull(LogShare.unbase64("abc"));
        assertNull(LogShare.unbase64("ab=c"));
        assertNull(LogShare.unbase64("a==="));
        assertNull(LogShare.unbase64("ab==abcd"));
        assertNull(LogShare.unbase64("ab-_"));
    }

    @Test
    public void gunzipStopsAtTheLimit() {
        byte[] text = bigLog();
        byte[] gz = LogShare.gzip(text);
        assertArrayEquals(text, LogShare.gunzip(gz, text.length));
        assertNull(LogShare.gunzip(gz, text.length - 1));
        assertNull(LogShare.gunzip(Arrays.copyOf(gz, gz.length / 2), Integer.MAX_VALUE));
    }

    @Test
    public void aboutListsEachReceiverWithItsFile() {
        ReceiverLogs.Part used = new ReceiverLogs.Part("Living room TV", "XGIMI MoGo 4", "192.168.1.20:47800", true);
        used.note = "log taken (12 KB)";
        used.log = "...";
        ReceiverLogs.Part other = new ReceiverLogs.Part("Bedroom TV", "", "192.168.1.21:47800", false);
        other.note = ReceiverLogs.NOT_ASKED;
        assertEquals("1. Living room TV (in use): log taken (12 KB)\n"
                + "   model XGIMI MoGo 4, at 192.168.1.20:47800\n"
                + "   log: receiver-1.log\n"
                + "2. Bedroom TV: " + ReceiverLogs.NOT_ASKED + "\n"
                + "   model ?, at 192.168.1.21:47800\n", ReceiverLogs.about(Arrays.asList(used, other)));
        assertEquals("", ReceiverLogs.about(Collections.emptyList()));
    }
}
