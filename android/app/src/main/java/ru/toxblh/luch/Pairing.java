// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The code check of a pairing (PROTOCOL.md, section 3a): one round per digit of the code shown on the TV. In each round
 * both sides commit to the digit, bound to the two certificates each of them sees, and then open their commitment, the
 * sender first. Someone in the middle sees two different certificates on its two sides, so it has to commit to each
 * digit before it can learn it: it gets past at most one digit, and the code it could guess offline from a single hash
 * is never sent. No I/O here: ControlServer carries the messages.
 */
final class Pairing {
    static final int DIGITS = 6;
    static final int NONCE_BYTES = 16;
    private static final byte[] LABEL = "luch-pair-1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Who commits: the sender or the receiver; a commitment of one is never valid as the other's. */
    static final byte SENDER = 'S', RECEIVER = 'R';

    private final byte role;
    private final String code;
    private final byte[] senderFp, receiverFp;
    private final byte[][] nonces = new byte[DIGITS][];

    /** {@code role}: our side; the fingerprints are the two certificates as this side sees them. */
    Pairing(byte role, String code, byte[] senderFp, byte[] receiverFp) {
        if (code.length() != DIGITS) throw new IllegalArgumentException("a code has " + DIGITS + " digits");
        this.role = role;
        this.code = code;
        this.senderFp = senderFp.clone();
        this.receiverFp = receiverFp.clone();
    }

    /** A fresh code for the TV to show. */
    static String newCode() {
        StringBuilder b = new StringBuilder(DIGITS);
        for (int i = 0; i < DIGITS; i++) b.append((char) ('0' + RANDOM.nextInt(10)));
        return b.toString();
    }

    /** Our commitment for {@code round}, in hex; its nonce is kept for {@link #open}. */
    String commit(int round) {
        byte[] n = new byte[NONCE_BYTES];
        RANDOM.nextBytes(n);
        nonces[round] = n;
        return hex(hash(role, round, code.charAt(round), n, senderFp, receiverFp));
    }

    /** Our nonce for {@code round}, which opens the commitment made before. */
    String open(int round) {
        return hex(nonces[round]);
    }

    /** Whether the other side's opening matches its commitment for this round's digit of our code. */
    boolean check(int round, String theirCommit, String theirNonce) {
        byte[] n = unhex(theirNonce);
        byte[] c = unhex(theirCommit);
        if (n == null || n.length != NONCE_BYTES || c == null) return false;
        byte other = role == SENDER ? RECEIVER : SENDER;
        return MessageDigest.isEqual(c, hash(other, round, code.charAt(round), n, senderFp, receiverFp));
    }

    /**
     * SHA-256 of "luch-pair-1" | role | round | digit (ASCII) | nonce (16) | sender's fingerprint (32) | receiver's
     * fingerprint (32): the same bytes on every implementation.
     */
    static byte[] hash(byte role, int round, char digit, byte[] nonce, byte[] senderFp, byte[] receiverFp) {
        MessageDigest d = sha256();
        d.update(LABEL);
        d.update(new byte[]{role, (byte) round, (byte) digit});
        d.update(nonce);
        d.update(senderFp);
        d.update(receiverFp);
        return d.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Android has it
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    /** Null when {@code s} is not even-length lower or upper case hex. */
    static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}
