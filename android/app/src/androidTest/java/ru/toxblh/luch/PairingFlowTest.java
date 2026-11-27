// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pairing as senders meet it (PROTOCOL.md, section 3a): over the TLS port on localhost, two senders with keys of their
 * own, the TV's prompt answered the way its buttons answer it, and a clock of the test's own for bans and guest
 * pairings. Safe on an emulator only: one test puts the screen to sleep.
 */
@RunWith(AndroidJUnit4.class)
public class PairingFlowTest {
    private static final String A = "luch-test-a", B = "luch-test-b";
    private Instrumentation instrumentation;
    private Context context;
    private final long[] now = {1_700_000_000_000L};
    private String saved = "";
    private Gate gate;
    private final List<TestSender> open = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        gate = new Gate(() -> now[0], new Gate.Store() {
            @Override
            public String load() {
                return saved;
            }

            @Override
            public void save(String json) {
                saved = json;
            }
        });
        ControlServer.gate = gate;
        LuchService.start(context);
    }

    @After
    public void tearDown() {
        for (TestSender s : open) s.close();
        PairPrompt p = PairPrompt.current;
        if (p != null) p.dismiss();
        ControlServer.gate = null;   // the TV's own list again
    }

    private TestSender sender(String alias, String name) throws Exception {
        TestSender s = TestSender.tls(10_000, alias);
        s.id = alias;
        s.name = name;
        open.add(s);
        return s;
    }

    private static PairPrompt prompt() {
        long until = SystemClock.uptimeMillis() + 5_000;
        while (PairPrompt.current == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(20);
        assertNotNull("no prompt on the TV", PairPrompt.current);
        return PairPrompt.current;
    }

    private static void noPrompt() {
        long until = SystemClock.uptimeMillis() + 2_000;
        while (PairPrompt.current != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(20);
        assertNull("the prompt is still up", PairPrompt.current);
    }

    /** Pairs {@code s} through the code on the TV; returns the welcome. */
    private JSONObject pair(TestSender s) throws Exception {
        JSONObject first = s.helloOnly("mirror");
        assertEquals("pair: " + first, "pair", first.getString("t"));
        assertEquals("code", first.getString("step"));
        assertEquals(6, first.getInt("digits"));
        PairPrompt p = prompt();
        assertEquals(s.name, p.name());
        assertEquals("paired", s.typeCode(p.code()).getString("t"));
        JSONObject welcome = s.next(5_000);
        assertEquals("welcome", welcome.getString("t"));
        assertEquals(1, welcome.getInt("v"));
        noPrompt();
        return welcome;
    }

    @Test
    public void theCodeOnTheTvPairsAndTheNextConnectionNeedsNothing() throws Exception {
        TestSender a = sender(A, "Linux Laptop");
        // The sender pins this: the certificate of the TV's own key, from the keystore.
        assertArrayEquals(Identity.load(context, Identity.ALIAS).fingerprint, a.receiverFp);
        pair(a);
        assertTrue(gate.isPaired(Pairing.hex(a.ownFp)));
        a.close();

        TestSender again = sender(A, "Linux Laptop");
        assertArrayEquals("the same TV certificate every time", a.receiverFp, again.receiverFp);
        assertEquals("welcome", again.helloOnly("mirror").getString("t"));
        assertNull("no prompt for a paired sender", PairPrompt.current);
    }

    /** The receiver pins keys, not ids: another key that says it is the paired sender is a stranger. */
    @Test
    public void anotherKeyWithAPairedIdIsAStranger() throws Exception {
        pair(sender(A, "Linux Laptop"));
        TestSender impostor = sender(B, "Linux Laptop");
        impostor.id = A;
        JSONObject first = impostor.helloOnly("mirror");
        assertEquals("pair", first.getString("t"));
        prompt().pressDeny();
        assertEquals("denied", impostor.next(5_000).getString("reason"));
    }

    @Test
    public void aWrongCodeIsRefusedAndCounts() throws Exception {
        TestSender a = sender(A, "Linux Laptop");
        assertEquals("pair", a.helloOnly().getString("t"));
        String code = prompt().code();
        String wrong = (code.charAt(0) == '9' ? "0" : String.valueOf((char) (code.charAt(0) + 1))) + code.substring(1);
        JSONObject refused = a.typeCode(wrong);
        assertEquals("pair_refused", refused.getString("t"));
        assertEquals("wrong_code", refused.getString("reason"));
        assertTrue(a.closedWithin(3_000));
        noPrompt();
        assertFalse(gate.isPaired(Pairing.hex(a.ownFp)));
    }

    /** Three denials and the sender is not asked about for an hour, nor is a new key from its address. */
    @Test
    public void threeDenialsBanForAnHour() throws Exception {
        for (int i = 0; i < Gate.STRIKES; i++) {
            TestSender a = sender(A, "Linux Laptop");
            assertEquals("pair", a.helloOnly().getString("t"));
            prompt().pressDeny();
            JSONObject refused = a.next(5_000);
            assertEquals("pair_refused", refused.getString("t"));
            assertEquals("denied", refused.getString("reason"));
            assertTrue(a.closedWithin(3_000));
            noPrompt();
            if (i < Gate.STRIKES - 1) now[0] += 60_000;
        }
        TestSender a = sender(A, "Linux Laptop");
        JSONObject banned = a.helloOnly();
        assertEquals("banned", banned.getString("reason"));
        assertEquals(3600, banned.getInt("retry_s"));
        assertNull("a banned sender puts nothing on the TV", PairPrompt.current);

        // The same address with a fresh key (a sender picks its own) is banned all the same.
        assertEquals("banned", sender(B, "Other name").helloOnly().getString("reason"));

        now[0] += Gate.BAN_MS;
        assertEquals("pair", sender(A, "Linux Laptop").helloOnly().getString("t"));
        prompt();
    }

    @Test
    public void closingThePromptIsNotADenial() throws Exception {
        for (int i = 0; i < Gate.STRIKES + 1; i++) {
            TestSender a = sender(A, "Linux Laptop");
            assertEquals("pair", a.helloOnly().getString("t"));
            prompt().pressBack();
            assertEquals("dismissed", a.next(5_000).getString("reason"));
            noPrompt();
        }
        assertEquals("pair", sender(A, "Linux Laptop").helloOnly().getString("t"));
    }

    /** Two unknown senders at once: the second is told to wait, and nothing of it shows on the TV. */
    @Test
    public void oneQuestionAtATime() throws Exception {
        TestSender a = sender(A, "Linux Laptop");
        assertEquals("pair", a.helloOnly().getString("t"));
        PairPrompt first = prompt();
        JSONObject busy = sender(B, "Second laptop").helloOnly();
        assertEquals("busy", busy.getString("reason"));
        assertTrue(busy.getInt("retry_s") > 0);
        assertTrue("the first prompt stays", first == PairPrompt.current);
        assertEquals("paired", a.typeCode(first.code()).getString("t"));
    }

    /**
     * While a paired sender's picture is up, a new one only asks "allow?" first; the code comes after the allow, and
     * the picture goes on all the while.
     */
    @Test
    public void duringASessionAStrangerAsksBeforeAnyCode() throws Exception {
        waitForBench();
        TestSender owner = TestSender.connect(10_000);
        open.add(owner);
        owner.hello("mirror");
        JSONObject ready = owner.start(null);
        assertEquals("aes-128-gcm", ready.getString("crypt"));
        MirrorSession session = MirrorSession.current;
        assertNotNull(session);

        TestSender b = sender(B, "Second laptop");
        JSONObject ask = b.helloOnly("mirror");
        assertEquals("pair", ask.getString("t"));
        assertEquals("allow", ask.getString("step"));
        PairPrompt p = prompt();
        assertTrue(p.asking());
        assertNull("no code before the allow", p.code());
        p.pressAllow();
        JSONObject code = b.next(5_000);
        assertEquals("code", code.getString("step"));
        assertNotNull(p.code());
        assertEquals("paired", b.typeCode(p.code()).getString("t"));
        assertEquals("welcome", b.next(5_000).getString("t"));
        assertTrue("the picture went on", session == MirrorSession.current && !session.ended());
    }

    @Test
    public void twentyPairedTheLeastRecentlyUsedIsForgotten() throws Exception {
        for (int i = 0; i < Gate.MAX_PAIRED; i++) {
            gate.pair("ff" + i, "old-" + i, "Old sender " + i);
            now[0] += 1000;
        }
        pair(sender(A, "Linux Laptop"));
        assertEquals(Gate.MAX_PAIRED, gate.paired().size());
        assertFalse(gate.isPaired("ff0"));
        assertTrue(gate.isPaired("ff1"));
        assertEquals(Pairing.hex(open.get(0).ownFp), gate.paired().get(0).fp);
    }

    @Test
    public void aGuestIsForgottenAfterADay() throws Exception {
        gate.setGuest(true);
        pair(sender(A, "Guest phone"));
        now[0] += Gate.GUEST_MS - 60_000;
        assertEquals("welcome", sender(A, "Guest phone").helloOnly().getString("t"));
        now[0] += 60_000;
        assertEquals("pair", sender(A, "Guest phone").helloOnly().getString("t"));
    }

    /** The real standby: the screen goes off (the remote's power key on a TV) and guests are forgotten. */
    @Test
    public void aGuestIsForgottenAtStandbyAndTheOwnerAtHomeIsNot() throws Exception {
        pair(sender(A, "Linux Laptop"));
        String fp = Pairing.hex(open.get(0).ownFp);
        sleepAndWake(() -> false);
        assertTrue("home keeps pairings through standby", gate.isPaired(fp));
        gate.setGuest(true);
        // The screen-off broadcast may come a moment after the screen.
        sleepAndWake(() -> !gate.isPaired(fp));
        assertFalse(gate.isPaired(fp));
        assertEquals("pair", sender(A, "Linux Laptop").helloOnly().getString("t"));
    }

    /** An older sender on the plain port: welcomed as before, nothing else until it is allowed, once per connection. */
    @Test
    public void anOlderSenderIsAllowedPerConnection() throws Exception {
        waitForBench();
        TestSender old = TestSender.plain(10_000);
        open.add(old);
        JSONObject welcome = old.hello("mirror");
        assertEquals(0, welcome.getInt("v"));
        assertEquals(ControlServer.tlsPort, welcome.getInt("tls_port"));
        PairPrompt p = prompt();
        assertTrue(p.asking());
        old.send(new JSONObject().put("t", "volume").put("step", 1));
        JSONObject no = old.next(5_000);
        assertEquals("not_allowed", no.getString("code"));
        p.pressAllow();
        noPrompt();
        JSONObject ready = old.start(null);
        assertFalse("no media key without TLS", ready.has("key"));
        old.close();

        TestSender next = TestSender.plain(10_000);
        open.add(next);
        next.hello();
        prompt().pressDeny();
        assertEquals("not_allowed", next.next(5_000).getString("code"));
        assertTrue(next.closedWithin(3_000));
    }

    /** Over TLS the picture must be sealed with the session's key: unsealed frames from the right address are not shown. */
    @Test
    public void unsealedMediaIsNotShown() throws Exception {
        waitForBench();
        TestSender owner = TestSender.connect(10_000);
        open.add(owner);
        owner.hello("mirror");
        int port = owner.start();
        owner.forge = true;
        owner.stream(port, 2_000);
        MirrorSession.Live live = MirrorSession.current.live();
        assertTrue("nothing shown: " + (live == null ? "-" : live.shownFps), live == null || live.shownFps == 0);
        owner.forge = false;
        owner.stream(port, 3_000);
        live = MirrorSession.current.live();
        assertNotNull(live);
        assertTrue("sealed frames are shown: " + live.shownFps, live.shownFps > 0);
    }

    /** How long opening a frame takes here, for the report; the real number comes from a TV. */
    @Test
    public void openingAFrameIsCheap() throws Exception {
        MediaCrypt c = new MediaCrypt(MediaCrypt.newKey());
        byte[] aad = new byte[MirrorSession.HEADER];
        for (int size : new int[]{30_000, 200_000}) {
            byte[] sealed = c.seal(1, 1, aad, new byte[size]);
            byte[] buf = new byte[sealed.length];
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 200; i++) {
                System.arraycopy(sealed, 0, buf, 0, sealed.length);
                long t = System.nanoTime();
                assertEquals(size, c.open(1, 1, aad, buf, 0, buf.length));
                best = Math.min(best, System.nanoTime() - t);
            }
            android.util.Log.i(LuchService.TAG, "open " + size + " bytes: " + best / 1000 + " us");
            assertTrue(size + " bytes took " + best / 1000 + " us", best < 5_000_000);
        }
    }

    private void waitForBench() {
        long until = SystemClock.uptimeMillis() + 90_000;
        // A session competes with the decoder bench a first hello starts.
        while ((Bench.cached(context) == null || Bench.running()) && SystemClock.uptimeMillis() < until) {
            if (Bench.cached(context) == null && !Bench.running()) Bench.autoRun(context, r -> {
            });
            SystemClock.sleep(200);
        }
    }

    /** Standby and back; while the screen is off, waits up to 5 s for {@code done}. */
    /** BooleanSupplier is Android 7: these tests run on 5.0 too. */
    private interface Condition {
        boolean met();
    }

    private void sleepAndWake(Condition done) throws Exception {
        PowerManager pm = Compat.service(context, PowerManager.class);
        shell("input keyevent KEYCODE_SLEEP");
        long until = SystemClock.uptimeMillis() + 5_000;
        while (pm.isInteractive() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        until = SystemClock.uptimeMillis() + 5_000;
        while (!done.met() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        shell("input keyevent KEYCODE_WAKEUP");
        until = SystemClock.uptimeMillis() + 5_000;
        while (!pm.isInteractive() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
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
