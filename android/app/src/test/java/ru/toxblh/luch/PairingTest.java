// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class PairingTest {
    private static byte[] filled(int b) {
        byte[] a = new byte[32];
        Arrays.fill(a, (byte) b);
        return a;
    }

    private static final byte[] SENDER_FP = filled(0x11), RECEIVER_FP = filled(0x22), OTHER_FP = filled(0x33);

    /** The same bytes as luchd's pair::tests::commitment_matches_the_receiver (luch-linux). */
    @Test
    public void commitmentIsTheSameOnEveryImplementation() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) nonce[i] = (byte) i;
        assertEquals("48826a53f4d157eb5596913e25946f39730c10aeafabc935e98c7ac732544373",
                Pairing.hex(Pairing.hash(Pairing.SENDER, 0, '4', nonce, SENDER_FP, RECEIVER_FP)));
    }

    /** Both sides go through all six rounds the way ControlServer and luchd do. */
    private static int roundsPassed(Pairing sender, Pairing receiver) {
        for (int r = 0; r < Pairing.DIGITS; r++) {
            String sc = sender.commit(r), rc = receiver.commit(r);
            if (!receiver.check(r, sc, sender.open(r))) return r;
            if (!sender.check(r, rc, receiver.open(r))) return r;
        }
        return Pairing.DIGITS;
    }

    @Test
    public void theRightCodePairs() {
        String code = Pairing.newCode();
        assertEquals(6, code.length());
        assertEquals(Pairing.DIGITS, roundsPassed(new Pairing(Pairing.SENDER, code, SENDER_FP, RECEIVER_FP),
                new Pairing(Pairing.RECEIVER, code, SENDER_FP, RECEIVER_FP)));
    }

    @Test
    public void aWrongDigitStopsAtItsRound() {
        assertEquals(3, roundsPassed(new Pairing(Pairing.SENDER, "123956", SENDER_FP, RECEIVER_FP),
                new Pairing(Pairing.RECEIVER, "123456", SENDER_FP, RECEIVER_FP)));
    }

    /** Someone in the middle shows each side a certificate of its own: even with the right code nothing opens. */
    @Test
    public void anotherCertificateInTheMiddleFailsAtOnce() {
        assertEquals(0, roundsPassed(new Pairing(Pairing.SENDER, "123456", SENDER_FP, OTHER_FP),
                new Pairing(Pairing.RECEIVER, "123456", SENDER_FP, RECEIVER_FP)));
        assertEquals(0, roundsPassed(new Pairing(Pairing.SENDER, "123456", OTHER_FP, RECEIVER_FP),
                new Pairing(Pairing.RECEIVER, "123456", SENDER_FP, RECEIVER_FP)));
    }

    /**
     * The middle has to commit to the receiver before it sees the sender open: guessing a digit it does not know yet
     * is right one time in ten, so getting past the six rounds is one in 10^5 even if the sender gives away one digit.
     */
    @Test
    public void theMiddleMustGuessEachDigitBeforeItIsOpened() {
        Pairing receiver = new Pairing(Pairing.RECEIVER, "123456", OTHER_FP, RECEIVER_FP);
        int right = 0;
        for (char guess = '0'; guess <= '9'; guess++) {
            String code = guess + "23456";
            Pairing middle = new Pairing(Pairing.SENDER, code, OTHER_FP, RECEIVER_FP);
            receiver.commit(0);
            if (receiver.check(0, middle.commit(0), middle.open(0))) right++;
        }
        assertEquals(1, right);
    }

    @Test
    public void oneSidesCommitmentDoesNotPassForTheOther() {
        Pairing a = new Pairing(Pairing.SENDER, "123456", SENDER_FP, RECEIVER_FP);
        Pairing b = new Pairing(Pairing.SENDER, "123456", SENDER_FP, RECEIVER_FP);
        // A receiver's commitment bounced back to the sender (or the other way) is not the other side's.
        assertFalse(a.check(0, a.commit(0), a.open(0)));
        Pairing r = new Pairing(Pairing.RECEIVER, "123456", SENDER_FP, RECEIVER_FP);
        assertTrue(r.check(0, b.commit(0), b.open(0)));
        assertFalse(r.check(1, b.commit(0), b.open(0)));   // nor for another round
    }

    @Test
    public void badHexIsNotAnOpening() {
        Pairing r = new Pairing(Pairing.RECEIVER, "123456", SENDER_FP, RECEIVER_FP);
        r.commit(0);
        assertFalse(r.check(0, "zz", "00"));
        assertFalse(r.check(0, null, null));
        assertFalse(r.check(0, "00", "0011"));   // nonce too short
    }
}
