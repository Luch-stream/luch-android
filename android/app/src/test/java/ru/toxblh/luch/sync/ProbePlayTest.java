// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * The calibration's sweeps as the receiver plays them (PROTOCOL.md 4k): the signal itself, where it lands among the
 * frames written, and where it is heard through the scheduled playout with D and the offset.
 */
public class ProbePlayTest {
    static final int RATE = 48000;

    /** {@code frames} stereo frames of {@code level}, the first of pts {@code pts0} (µs), with the probe put in. */
    static short[] played(ProbePlay p, int frames, double pts0, short level, long hearNs) {
        short[] w = new short[2 * frames];
        Arrays.fill(w, level);
        double[] pts = new double[frames];
        for (int i = 0; i < frames; i++) pts[i] = pts0 + i * 1e6 / RATE;
        p.apply(w, 2, frames, pts, hearNs, 1e9 / RATE);
        return w;
    }

    static float[] left(short[] w, int from, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = w[2 * (from + i)] / 32768f;
        return f;
    }

    /** The strongest frequency of {@code n} samples from {@code from} (Hann window, zero-padded): Hz. */
    static double peakHz(short[] w, int from, int n) {
        int size = 16384;
        double[] re = new double[size], im = new double[size];
        for (int i = 0; i < n; i++) re[i] = w[2 * (from + i)] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        new Fft(size).transform(re, im, false);
        int best = 1;
        for (int k = 1; k < size / 2; k++) if (Math.hypot(re[k], im[k]) > Math.hypot(re[best], im[best])) best = k;
        return best * (double) RATE / size;
    }

    @Test
    public void aSweepRisesFromF0ToF1AndTheDetectorFindsItWhereItStarts() {
        // 1 s from pts 0; the sweep at pts 257 291 µs: frame 12 349.97 → the first frame at or after, 12 350.
        ProbePlay p = new ProbePlay(RATE, 2000, 6000, 200, 0.3, 257_291, 1, 500);
        short[] w = played(p, RATE, 0, (short) 0, 0);
        int start = 12_350, m = p.length();
        assertEquals(9600, m);
        assertEquals(0, w[2 * (start - 1)]);
        assertTrue(w[2 * (start + 1000)] != 0);
        assertEquals(0, w[2 * (start + m)]);
        // A log sweep: 10 ms windows centred 10 ms and 190 ms in read f0·3^(t/200 ms).
        assertEquals(2000 * Math.pow(3, 0.05), peakHz(w, start + 240, 480), 60);
        assertEquals(2000 * Math.pow(3, 0.95), peakHz(w, start + m - 720, 480), 120);
        // Both channels carry it.
        assertEquals(w[2 * (start + 3000)], w[2 * (start + 3000) + 1]);
        Probe.Arrival a = new Probe(RATE).find(left(w, 0, RATE - 12_000), start - 2400, start + 2400);
        assertTrue(a.why, a.trusted);
        assertEquals(start, a.at, 0.5);
        assertEquals(1, p.heardNs().length);
        assertEquals(Math.round(start * 1e9 / RATE), p.heardNs()[0]);
        assertFalse(p.active());
    }

    @Test
    public void theSweepReplacesTheProgramAndIsCappedAtHalfScale() {
        ProbePlay p = new ProbePlay(RATE, 2000, 6000, 200, 0.9, 100_000, 1, 500);
        short[] w = played(p, RATE / 2, 0, (short) 12_000, 0);
        int start = 4800, m = p.length();
        float[] sweep = Probe.sweep(RATE, 2000, 6000, 200, ProbePlay.MAX_GAIN);
        int peak = 0;
        for (int i = 0; i < m; i++) peak = Math.max(peak, Math.abs(w[2 * (start + i)]));
        assertTrue("peak " + peak, peak <= 16_400 && peak > 16_000);
        // Past the fade in: the sweep alone, nothing of the program under it.
        for (int i = 240; i < m - 240; i += 97) assertEquals(Math.round(sweep[i] * 32767), w[2 * (start + i)]);
        // The program on both sides, crossfaded over the sweep's own fades (no step at its edges).
        assertEquals(12_000, w[2 * (start - 1)]);
        assertEquals(12_000, w[2 * start]);
        assertEquals(12_000, w[2 * (start + m)]);
    }

    @Test
    public void sweepsFollowTheirScheduleAcrossWritesAndAPartialWriteIsTakenBack() {
        ProbePlay p = new ProbePlay(RATE, 2000, 6000, 200, 0.3, 50_000, 3, 500);
        long[] expectFrame = {2400, 2400 + 33_600, 2400 + 2 * 33_600};
        // Written 700 frames at a time. The write from 35 700 holds the second sweep's start (36 000) but takes only
        // 200 frames: the rest is offered again, and the sweep must start from its first sample there.
        short[] heard = new short[2 * RATE + 700];
        int pos = 0;
        while (pos < 2 * RATE) {
            int n = 700;
            short[] w = new short[2 * n];
            double[] pts = new double[n];
            for (int i = 0; i < n; i++) pts[i] = (pos + i) * 1e6 / RATE;
            long hear = Math.round(pos * 1e9 / RATE);
            p.mark();
            p.apply(w, 2, n, pts, hear, 1e9 / RATE);
            int took = pos == 35_700 ? 200 : n;
            if (took < n) {
                p.reset();
                p.apply(null, 2, took, pts, hear, 1e9 / RATE);
            }
            for (int i = 0; i < took; i++) heard[pos + i] = w[2 * i];
            pos += took;
        }
        long[] h = p.heardNs();
        for (int i = 0; i < 3; i++) assertEquals(Math.round(expectFrame[i] * 1e9 / RATE), h[i]);
        float[] sweep = Probe.sweep(RATE, 2000, 6000, 200, 0.3);
        assertEquals(0, heard[35_999]);
        for (int k = 0; k < p.length(); k += 101) assertEquals(Math.round(sweep[k] * 32767), heard[36_000 + k]);
        assertTrue(p.done(Math.round((expectFrame[2] + p.length()) * 1e9 / RATE)));
        assertFalse(p.done(Math.round((expectFrame[2] + p.length() - 1) * 1e9 / RATE)));
    }

    /**
     * Through the playout: sweep i, at pts {@code at + i·(ms + gap)}, is heard where program sound of that pts would be,
     * at local(pts + D) + offset; what the probe says it heard is where it really is in the output.
     */
    @Test
    public void inThePlayoutASweepIsHeardAtItsPtsPlusDPlusTheOffset() {
        AudioPlayoutTest.Ideal out = new AudioPlayoutTest.Ideal();
        AudioPlayout pl = new AudioPlayout(RATE, 2);
        long d = 100_000, offsetNs = 7_000_000, ahead = 7_000_000;   // D 100 ms, offset 7 ms, sender's clock 7 s ahead
        long at = ahead + 3_000_000;
        ProbePlay p = new ProbePlay(RATE, 2000, 6000, 200, 0.3, at, 2, 500);
        ProbePlay late = new ProbePlay(RATE, 2000, 6000, 200, 0.3, ahead + 500_000, 1, 500);
        for (long ms = 0, seq = 0; ms < 5_000; ms++) {
            out.now = ms * 1_000_000L;
            if (ms % 5 == 0) {
                short[] pcm = new short[2 * 240];
                Arrays.fill(pcm, (short) 300);
                pl.push(seq++, ahead + ms * 1000 - 3000, d, pcm);
            }
            if (ms == 1_000) {
                assertFalse("its pts was already written", pl.probe(late));
                assertTrue(pl.probe(p));
            }
            pl.fill(out, out.now, 960, s -> (s - ahead) * 1000 + offsetNs, 0);
        }
        assertEquals(0, pl.hardResyncs());
        assertTrue(p.done(out.now));
        long[] h = p.heardNs();
        // Local time = the sender's − 7 s: heard at at + D + offset, the second 700 ms later.
        for (int i = 0; i < 2; i++) {
            long want = (at + i * 700_000 + d - ahead) * 1000 + offsetNs;
            assertEquals("sweep " + i, want, h[i], 21_000);   // within a frame
        }
        // Where it really is in what was played: the ideal output hears frame j at startedAt + j / rate + 15 ms.
        float[] rec = new float[out.heard.size()];
        for (int i = 0; i < rec.length; i++) rec[i] = out.heard.get(i) / 32768f;
        Probe probe = new Probe(RATE);
        for (int i = 0; i < 2; i++) {
            double frame = (h[i] - 15_000_000 - out.startedAt) * RATE / 1e9;
            Probe.Arrival a = probe.find(rec, (int) frame - 2400, (int) frame + 2400);
            assertTrue(a.why, a.trusted);
            assertEquals("sweep " + i, frame, a.at, 0.1);
        }
    }
}
