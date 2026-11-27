// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Random;

/** The sound modem, in memory only: nothing is played. The same vectors as luchd's find/modem.rs and find/rs.rs. */
public class ChirpTest {
    /** The card this TV would say: id 3f0c2a8e, 192.168.1.20:47800, token 0badf00d. */
    static final byte[] CARD = new Card(Card.RECEIVER, "3f0c2a8e-9d1b-4c7e-8a55-0b6f2d9e1c47", "192.168.1.20", 47800,
            "0badf00d").encode();

    /** Shared with luchd: both sides must make the very same tones for the same card. */
    static final String GOLDEN_TONES = "0f3c691e114c75013f0c2a8ec0a80114bab80badf00d1f8320c0e4ed937c";

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    @Test
    public void theParityIsLuchds() {
        assertEquals("4c7563681c7c9b839d89732d", hex(Chirp.rsEncode("Luch".getBytes(), 8)));
    }

    @Test
    public void theTonesAreLuchds() {
        StringBuilder sb = new StringBuilder();
        for (int t : Chirp.symbols(CARD)) sb.append(Integer.toHexString(t));
        assertEquals(GOLDEN_TONES, sb.toString());
    }

    @Test
    public void reedSolomonMendsUpToFourBytes() {
        Random r = new Random(7);
        for (int round = 0; round < 200; round++) {
            byte[] msg = new byte[1 + r.nextInt(30)];
            r.nextBytes(msg);
            byte[] good = Chirp.rsEncode(msg, 8);
            int[] w = new int[good.length];
            for (int i = 0; i < w.length; i++) w[i] = good[i] & 0xFF;
            int errors = round % 5;
            java.util.Set<Integer> hit = new java.util.HashSet<>();
            while (hit.size() < errors) hit.add(r.nextInt(w.length));
            for (int i : hit) w[i] ^= 1 + r.nextInt(255);
            assertTrue("round " + round, Chirp.rsCorrect(w, 8));
            for (int i = 0; i < w.length; i++) assertEquals(good[i] & 0xFF, w[i]);
        }
    }

    /** A room: silence before, a gain, white noise, a sample clock {@code ppm} off. */
    static float[] room(float[] s, int lead, float gain, float noise, double ppm, long seed) {
        Random r = new Random(seed);
        double ratio = 1 + ppm * 1e-6;
        int n = (int) (s.length * ratio) + Chirp.RATE / 2;
        float[] out = new float[lead + n];
        for (int i = 0; i < lead; i++) out[i] = (float) (noise * r.nextGaussian());
        for (int i = 0; i < n; i++) {
            double pos = i / ratio;
            int k = (int) pos;
            float frac = (float) (pos - k);
            float v = k + 1 < s.length ? s[k] + (s[k + 1] - s[k]) * frac : k < s.length ? s[k] : 0;
            out[lead + i] = gain * v + (float) (noise * r.nextGaussian());
        }
        return out;
    }

    @Test
    public void aQuietFrameInANoisyRoomWithASkewedClock() {
        float[] x = Chirp.synth(CARD, Chirp.BANDS[0], 0.5f, Chirp.RATE);
        for (double ppm : new double[] {500, -500, 0}) {
            List<byte[]> got = Chirp.demodulate(room(x, 12345, 0.01f, 0.01f, ppm, (long) ppm + 3));
            assertEquals("ppm " + ppm, 1, got.size());
            assertArrayEquals(CARD, got.get(0));
        }
    }

    @Test
    public void aClapIsMended() {
        float[] x = room(Chirp.synth(CARD, Chirp.BANDS[0], 0.5f, Chirp.RATE), 4000, 0.2f, 0.002f, 0, 6);
        Random r = new Random(9);
        int from = 4000 + (Chirp.PREAMBLE.length + 10) * Chirp.SYMBOL;
        for (int i = from; i < from + 3 * Chirp.SYMBOL; i++) x[i] += (float) (0.8 * r.nextGaussian());
        List<byte[]> got = Chirp.demodulate(x);
        assertEquals(1, got.size());
        assertArrayEquals(CARD, got.get(0));
    }

    @Test
    public void noiseSaysNothing() {
        Random r = new Random(11);
        float[] x = new float[Chirp.RATE * 3];
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.1 * r.nextGaussian());
        assertTrue(Chirp.demodulate(x).isEmpty());
    }

    @Test
    public void aTvAt44kSoundsTheSame() {
        float[] x = Chirp.synth(CARD, Chirp.BANDS[0], 0.5f, 44100);
        assertEquals(Chirp.symbols(CARD).length * 1764, x.length);
        short[] pcm = Chirp.pcm(Chirp.synth(CARD, Chirp.BANDS[0], 0.5f, Chirp.RATE));
        int peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        assertTrue(peak > 16000 && peak <= 16384);
        assertFalse(Chirp.demodulate(Chirp.synth(CARD, Chirp.BANDS[1], 0.5f, Chirp.RATE)).isEmpty());
    }
}
