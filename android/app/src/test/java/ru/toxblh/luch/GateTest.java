// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class GateTest {
    private long now = 1_700_000_000_000L;
    private String saved = "";
    private Gate gate;

    private final Gate.Store store = new Gate.Store() {
        @Override
        public String load() {
            return saved;
        }

        @Override
        public void save(String json) {
            saved = json;
        }
    };

    @Before
    public void setUp() {
        gate = new Gate(() -> now, store);
    }

    /** What check() gives, with the prompt (if any) ended at once. */
    private Gate.Verdict ask(String fp, String address) {
        Gate.Verdict v = gate.check(fp, address);
        if (v == Gate.Verdict.PROMPT) gate.promptEnded();
        return v;
    }

    @Test
    public void anUnknownSenderIsAskedAboutAndAPairedOneIsNot() {
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
        gate.pair("aa", "laptop", "Linux Laptop");
        assertEquals(Gate.Verdict.PAIRED, ask("aa", "192.168.1.20"));
        assertEquals("a paired key works from another address", Gate.Verdict.PAIRED, ask("aa", "192.168.1.21"));
    }

    @Test
    public void oneQuestionAtATime() {
        assertEquals(Gate.Verdict.PROMPT, gate.check("aa", "192.168.1.20"));
        assertEquals(Gate.Verdict.BUSY, gate.check("bb", "192.168.1.30"));
        assertEquals(Gate.BUSY_RETRY_MS, gate.retryMs("bb", "192.168.1.30"));
        gate.promptEnded();
        assertEquals(Gate.Verdict.PROMPT, ask("bb", "192.168.1.30"));
    }

    @Test
    public void threeRefusalsBanForAnHour() {
        for (int i = 0; i < 2; i++) {
            assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
            gate.strike("aa", "192.168.1.20");
            now += 60_000;
        }
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
        gate.strike("aa", "192.168.1.20");
        assertEquals(Gate.Verdict.BANNED, ask("aa", "192.168.1.20"));
        assertEquals(Gate.BAN_MS, gate.retryMs("aa", "192.168.1.20"));
        now += Gate.BAN_MS - 1;
        assertEquals(Gate.Verdict.BANNED, ask("aa", "192.168.1.20"));
        now += 1;
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
    }

    /** A sender picks its own key and id: a new key from the same address is still banned, and so is the old key. */
    @Test
    public void aBanHoldsForTheAddressAndForTheKey() {
        for (int i = 0; i < Gate.STRIKES; i++) gate.strike("aa", "192.168.1.20");
        assertEquals(Gate.Verdict.BANNED, ask("fresh-key", "192.168.1.20"));
        assertEquals(Gate.Verdict.BANNED, ask("aa", "192.168.1.77"));
        assertEquals(Gate.Verdict.PROMPT, ask("other", "192.168.1.30"));
    }

    @Test
    public void refusalsSpreadOverMoreThanAnHourDoNotAddUp() {
        gate.strike("aa", "192.168.1.20");
        gate.strike("aa", "192.168.1.20");
        now += Gate.BAN_MS;
        gate.strike("aa", "192.168.1.20");
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
    }

    @Test
    public void promptsAreLimitedWhoeverAsks() {
        for (int i = 0; i < Gate.PROMPTS; i++) {
            assertEquals(Gate.Verdict.PROMPT, ask("key" + i, "192.168.1." + (10 + i)));
            now += 1000;
        }
        assertEquals(Gate.Verdict.BUSY, ask("key-new", "192.168.1.99"));
        long wait = gate.retryMs("key-new", "192.168.1.99");
        assertTrue(wait > 0 && wait <= Gate.PROMPT_WINDOW_MS);
        now += wait;
        assertEquals(Gate.Verdict.PROMPT, ask("key-new", "192.168.1.99"));
        assertEquals("the window is full again", Gate.Verdict.BUSY, gate.check("key-other", "192.168.1.98"));
        // A paired sender is never held back by the limit.
        gate.pair("key-new", "id-new", "Linux Laptop");
        assertEquals(Gate.Verdict.PAIRED, ask("key-new", "192.168.1.99"));
    }

    @Test
    public void twentyPairedAtMostTheLeastRecentlyUsedGoes() {
        for (int i = 0; i < Gate.MAX_PAIRED; i++) {
            gate.pair("key" + i, "id" + i, "Sender " + i);
            now += 1000;
        }
        assertEquals(Gate.Verdict.PAIRED, ask("key0", "192.168.1.20"));   // used: now the most recent
        now += 1000;
        gate.pair("key20", "id20", "Sender 20");
        assertEquals(Gate.MAX_PAIRED, gate.paired().size());
        assertTrue(gate.isPaired("key0"));
        assertFalse("key1 was the least recently used", gate.isPaired("key1"));
        assertEquals("key20", gate.paired().get(0).fp);
    }

    @Test
    public void aSenderThatPairsAgainWithANewKeyKeepsOneEntry() {
        gate.pair("old", "laptop", "Linux Laptop");
        gate.pair("new", "laptop", "Linux Laptop");
        assertFalse(gate.isPaired("old"));
        assertTrue(gate.isPaired("new"));
        assertEquals(1, gate.paired().size());
    }

    @Test
    public void pairingsSurviveARestart() {
        gate.pair("aa", "laptop", "Linux Laptop");
        gate.setGuest(true);
        Gate again = new Gate(() -> now, store);
        assertTrue(again.isPaired("aa"));
        assertTrue(again.guest());
        assertEquals("Linux Laptop", again.paired().get(0).name);
    }

    @Test
    public void homeKeepsPairingsThroughStandbyAndDays() {
        gate.pair("aa", "laptop", "Linux Laptop");
        gate.standby();
        now += 30L * 24 * 60 * 60_000;
        assertEquals(Gate.Verdict.PAIRED, ask("aa", "192.168.1.20"));
    }

    @Test
    public void guestForgetsAtStandby() {
        gate.setGuest(true);
        gate.pair("aa", "phone", "Guest phone");
        assertEquals(Gate.Verdict.PAIRED, ask("aa", "192.168.1.20"));
        gate.standby();
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
    }

    @Test
    public void guestForgetsAfterADayEvenWhenUsed() {
        gate.setGuest(true);
        gate.pair("aa", "phone", "Guest phone");
        now += Gate.GUEST_MS - 1;
        assertEquals(Gate.Verdict.PAIRED, ask("aa", "192.168.1.20"));
        now += 1;
        assertEquals(Gate.Verdict.PROMPT, ask("aa", "192.168.1.20"));
        assertEquals(0, gate.paired().size());
    }

    @Test
    public void aDamagedListTrustsNobody() {
        saved = "{\"paired\":[{\"fp\":";
        Gate g = new Gate(() -> now, store);
        assertEquals(0, g.paired().size());
    }

    @Test
    public void olderSendersWithoutAKeyAreJudgedByAddress() {
        for (int i = 0; i < Gate.STRIKES; i++) {
            assertEquals(Gate.Verdict.PROMPT, ask(null, "192.168.1.40"));
            gate.strike(null, "192.168.1.40");
        }
        assertEquals(Gate.Verdict.BANNED, ask(null, "192.168.1.40"));
        assertEquals(Gate.Verdict.PROMPT, ask(null, "192.168.1.41"));
    }
}
