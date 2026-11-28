// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sender's side of the control connection against a receiver played here, the way ControlServer answers. TLS
 * itself is the platform's; these run over a plain socket with the two fingerprints a handshake would have shown.
 */
public class LinkTest {
    private static byte[] filled(int b) {
        byte[] a = new byte[32];
        Arrays.fill(a, (byte) b);
        return a;
    }

    private static final byte[] PHONE_FP = filled(0x11), TV_FP = filled(0x22), MIDDLE_FP = filled(0x33);

    /** The receiver's end of one connection. */
    private interface Script {
        void play(BufferedReader in, Out out) throws Exception;
    }

    private static final class Out {
        private final OutputStream o;

        Out(OutputStream o) {
            this.o = o;
        }

        void send(JSONObject m) throws Exception {
            o.write((m + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }
    }

    private static JSONObject read(BufferedReader in) throws Exception {
        String l = in.readLine();
        if (l == null) throw new AssertionError("the phone closed the connection");
        return new JSONObject(l);
    }

    /** A receiver on a port of its own running {@code script}; the phone's link to it, with {@code peerFp} as seen. */
    private static Link connect(Script script, byte[] ownFp, byte[] peerFp, AtomicReference<Throwable> failed)
            throws Exception {
        ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (ServerSocket ss = srv; Socket s = ss.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                script.play(in, new Out(s.getOutputStream()));
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return new Link(new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort()), ownFp, peerFp);
    }

    private static final class MemoryPins implements Link.Pins {
        final Map<String, String> map = new HashMap<>();

        @Override
        public String get(String receiverId) {
            return map.get(receiverId);
        }

        @Override
        public void put(String receiverId, String fingerprint) {
            map.put(receiverId, fingerprint);
        }
    }

    private static final class FakeUi implements Link.Ui {
        final String code;
        final AtomicInteger allows = new AtomicInteger(), codes = new AtomicInteger();

        FakeUi(String code) {
            this.code = code;
        }

        @Override
        public void allowOnTv() {
            allows.incrementAndGet();
        }

        @Override
        public String code() {
            codes.incrementAndGet();
            return code;
        }
    }

    private static JSONObject hello() throws Exception {
        return new JSONObject().put("t", "hello").put("id", "phone").put("name", "Phone").put("want",
                new org.json.JSONArray().put("photo"));
    }

    private static JSONObject welcome(int v) throws Exception {
        return new JSONObject().put("t", "welcome").put("v", v).put("id", "tv-1").put("modes",
                new org.json.JSONArray().put("photo"));
    }

    /** The receiver's code check as ControlServer runs it, with {@code fps} as it sees them in the handshake. */
    private static void receiverPairs(BufferedReader in, Out out, String code, byte[] senderFp, byte[] receiverFp)
            throws Exception {
        Pairing p = new Pairing(Pairing.RECEIVER, code, senderFp, receiverFp);
        for (int r = 0; r < Pairing.DIGITS; r++) {
            JSONObject c = read(in);
            assertEquals("pair_commit", c.getString("t"));
            assertEquals(r, c.getInt("round"));
            out.send(new JSONObject().put("t", "pair_commit").put("round", r).put("c", p.commit(r)));
            JSONObject o = read(in);
            assertEquals("pair_open", o.getString("t"));
            if (!p.check(r, c.getString("c"), o.getString("n"))) {
                out.send(new JSONObject().put("t", "pair_refused").put("reason", "wrong_code"));
                return;
            }
            out.send(new JSONObject().put("t", "pair_open").put("round", r).put("n", p.open(r)));
        }
        out.send(new JSONObject().put("t", "paired"));
        out.send(welcome(1));
    }

    @Test
    public void aNewPhonePairsWithTheCodeAndPins() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<JSONObject> said = new AtomicReference<>();
        Link l = connect((in, out) -> {
            said.set(read(in));
            out.send(new JSONObject().put("t", "pair").put("step", "allow"));
            out.send(new JSONObject().put("t", "pair").put("step", "code").put("digits", 6));
            receiverPairs(in, out, "407213", PHONE_FP, TV_FP);
            read(in);   // bye
        }, PHONE_FP, TV_FP, failed);
        MemoryPins pins = new MemoryPins();
        FakeUi ui = new FakeUi("407213");
        JSONObject w = l.hello(hello(), "tv-1", pins, ui);
        assertEquals("welcome", w.getString("t"));
        assertTrue(l.letIn);
        assertTrue(l.pairedNow);
        assertEquals(1, said.get().getInt("v"));
        assertTrue(said.get().getBoolean("pair"));
        assertEquals(1, ui.allows.get());
        assertEquals(1, ui.codes.get());
        assertEquals(Pairing.hex(TV_FP), pins.get("tv-1"));
        l.close();
        assertNull(failed.get());
    }

    /** Paired before: the welcome comes at once, nothing is asked on either side, the hello does not ask to pair. */
    @Test
    public void aPairedPhoneIsLetInWithoutAQuestion() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<JSONObject> said = new AtomicReference<>();
        Link l = connect((in, out) -> {
            said.set(read(in));
            out.send(welcome(1));
            read(in);
        }, PHONE_FP, TV_FP, failed);
        MemoryPins pins = new MemoryPins();
        pins.put("tv-1", Pairing.hex(TV_FP));
        FakeUi ui = new FakeUi(null);
        l.hello(hello(), "tv-1", pins, ui);
        assertTrue(l.letIn);
        assertFalse(said.get().has("pair"));
        assertEquals(0, ui.allows.get() + ui.codes.get());
        l.close();
        assertNull(failed.get());
    }

    @Test
    public void aWrongCodeIsRefusedAndNothingIsPinned() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> {
            read(in);
            out.send(new JSONObject().put("t", "pair").put("step", "code").put("digits", 6));
            receiverPairs(in, out, "407213", PHONE_FP, TV_FP);
        }, PHONE_FP, TV_FP, failed);
        MemoryPins pins = new MemoryPins();
        try {
            l.hello(hello(), "tv-1", pins, new FakeUi("407219"));
            fail("paired with a wrong code");
        } catch (Link.Refused r) {
            assertEquals("wrong_code", r.reason);
        }
        assertTrue(pins.map.isEmpty());
        assertNull(failed.get());
    }

    /**
     * Someone in the middle shows the phone a certificate of its own: its commitments, made over the fingerprints it
     * sees, never open for the phone, even when it knows the code.
     */
    @Test
    public void someoneInTheMiddleIsCaughtAtTheFirstRound() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> {
            read(in);
            out.send(new JSONObject().put("t", "pair").put("step", "code").put("digits", 6));
            // the middle, towards the phone: the phone's certificate and its own
            Pairing p = new Pairing(Pairing.RECEIVER, "407213", PHONE_FP, MIDDLE_FP);
            read(in);
            out.send(new JSONObject().put("t", "pair_commit").put("round", 0).put("c", p.commit(0)));
            read(in);
            out.send(new JSONObject().put("t", "pair_open").put("round", 0).put("n", p.open(0)));
            in.readLine();
        }, PHONE_FP, TV_FP, failed);
        MemoryPins pins = new MemoryPins();
        try {
            l.hello(hello(), "tv-1", pins, new FakeUi("407213"));
            fail("paired through the middle");
        } catch (Link.Refused r) {
            assertEquals("mismatch", r.reason);
        }
        assertTrue(pins.map.isEmpty());
    }

    @Test
    public void anotherCertificateUnderAPairedTvIsRefusedBeforeHello() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<String> heard = new AtomicReference<>();
        Link l = connect((in, out) -> heard.set(in.readLine()), PHONE_FP, MIDDLE_FP, failed);
        MemoryPins pins = new MemoryPins();
        pins.put("tv-1", Pairing.hex(TV_FP));
        try {
            l.hello(hello(), "tv-1", pins, new FakeUi(null));
            fail("took another certificate");
        } catch (Link.Refused r) {
            assertEquals("other_device", r.reason);
        }
        l.close();
        Thread.sleep(100);
        assertFalse(String.valueOf(heard.get()).contains("hello"));
    }

    /** A paired TV that turns up without TLS is not talked to on the plain port. */
    @Test
    public void aPairedTvIsNeverReachedInPlain() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> in.readLine(), null, null, failed);
        MemoryPins pins = new MemoryPins();
        pins.put("tv-1", Pairing.hex(TV_FP));
        try {
            l.hello(hello(), "tv-1", pins, new FakeUi(null));
            fail("fell back to plain");
        } catch (Link.Refused r) {
            assertEquals("other_device", r.reason);
        }
        l.close();
    }

    @Test
    public void refusedPairingSaysWhy() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> {
            read(in);
            out.send(new JSONObject().put("t", "pair_refused").put("reason", "banned").put("retry_s", 3600));
        }, PHONE_FP, TV_FP, failed);
        try {
            l.hello(hello(), "tv-1", new MemoryPins(), new FakeUi(null));
            fail();
        } catch (Link.Refused r) {
            assertEquals("banned", r.reason);
            assertEquals(3600, r.retryS);
        }
    }

    /**
     * The plain port: the welcome at once, then every request refused until the person allows the connection on the
     * TV; the phone asks again each second and goes on once it is let in. Pings are answered meanwhile.
     */
    @Test
    public void aPlainConnectionWaitsForAllowOnTheTv() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger tries = new AtomicInteger(), pongs = new AtomicInteger();
        Link l = connect((in, out) -> {
            JSONObject h = read(in);
            assertEquals(0, h.getInt("v"));
            assertFalse(h.has("pair"));
            out.send(welcome(0));
            out.send(new JSONObject().put("t", "ping").put("n", 1));
            while (true) {
                JSONObject m = read(in);
                if (m.getString("t").equals("pong")) {
                    pongs.incrementAndGet();
                    continue;
                }
                assertEquals("photo", m.getString("t"));
                if (tries.incrementAndGet() < 3) {
                    out.send(new JSONObject().put("t", "error").put("code", "not_allowed").put("for", "photo")
                            .put("detail", "allow it on the TV first"));
                } else {
                    out.send(new JSONObject().put("t", "media_state").put("state", "x"));
                    out.send(new JSONObject().put("t", "photo_ready"));
                    break;
                }
            }
            read(in);
        }, null, null, failed);
        FakeUi ui = new FakeUi(null);
        l.hello(hello(), "tv-1", new MemoryPins(), ui);
        assertFalse(l.letIn);
        JSONObject ready = l.request(new JSONObject().put("t", "photo"), "photo_ready", ui);
        assertEquals("photo_ready", ready.getString("t"));
        assertTrue(l.letIn);
        assertEquals(3, tries.get());
        assertEquals(1, ui.allows.get());
        assertEquals(1, pongs.get());
        // what came in between is kept for the reader
        assertEquals("media_state", l.next(1000).getString("t"));
        l.close();
        assertNull(failed.get());
    }

    @Test
    public void denyOnTheTvEndsAPlainConnection() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> {
            read(in);
            out.send(welcome(0));
            read(in);
            out.send(new JSONObject().put("t", "error").put("code", "not_allowed").put("for", "doc")
                    .put("detail", "allow it on the TV first"));
            read(in);
            out.send(new JSONObject().put("t", "error").put("code", "not_allowed").put("detail", "refused on the TV"));
        }, null, null, failed);
        FakeUi ui = new FakeUi(null);
        l.hello(hello(), "tv-1", new MemoryPins(), ui);
        try {
            l.request(new JSONObject().put("t", "doc"), "doc_ready", ui);
            fail();
        } catch (Link.Refused r) {
            assertEquals("not_allowed", r.reason);
            assertEquals("refused on the TV", r.getMessage());
        }
    }

    @Test
    public void otherErrorsFailTheRequest() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Link l = connect((in, out) -> {
            read(in);
            out.send(welcome(1));
            read(in);
            out.send(new JSONObject().put("t", "error").put("code", "busy").put("for", "media"));
            read(in);
        }, PHONE_FP, TV_FP, failed);
        MemoryPins pins = new MemoryPins();
        pins.put("tv-1", Pairing.hex(TV_FP));
        l.hello(hello(), "tv-1", pins, new FakeUi(null));
        try {
            l.request(new JSONObject().put("t", "media"), "media_ready", new FakeUi(null));
            fail();
        } catch (Link.Failure f) {
            assertEquals("busy", f.code);
        }
        l.close();
    }
}
