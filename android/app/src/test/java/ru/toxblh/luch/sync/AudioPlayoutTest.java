// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** AudioPlayout against an ideal output: what it does with short chunks, losses, and the joins it makes. */
public class AudioPlayoutTest {
    /** A DAC on the same clock as everything, 15 ms of latency after the buffer, consuming 48 frames a millisecond. */
    static final class Ideal implements AudioPlayout.Output {
        long now, written, startedAt = -1;
        final java.util.ArrayList<Short> heard = new java.util.ArrayList<>();

        long played() {
            return startedAt < 0 ? 0 : Math.min(written, (now - startedAt) * 48 / 1_000_000);
        }

        @Override
        public long hearNs() {
            return now + (written - played()) * 1_000_000_000L / 48000 + 15_000_000;
        }

        @Override
        public int buffered() {
            return (int) (written - played());
        }

        @Override
        public int write(short[] pcm, int frames) {
            if (startedAt < 0) startedAt = now;
            for (int i = 0; i < frames; i++) heard.add(pcm[2 * i]);
            written += frames;
            return frames;
        }

        @Override
        public double framesPerSecond() {
            return 48000;
        }

        @Override
        public boolean ready() {
            return true;
        }
    }

    @Test
    public void aShortFirstChunkAndLostChunksDoNotRunTimeAway() {
        Ideal out = new Ideal();
        AudioPlayout p = new AudioPlayout(48000, 2);
        Random r = new Random(1);
        int lost = 0;
        long d = 100_000;   // D = 100 ms; the sender's clock is this one
        for (long ms = 0, seq = 0; ms < 20_000; ms++) {
            out.now = ms * 1_000_000L;
            if (ms % 5 == 0) {
                // Chunk seq, sent at its pts, arriving 3 ms later; the decoder's first output is short (pre-skip).
                int frames = seq == 0 ? 168 : 240;
                short[] pcm = new short[2 * frames];
                java.util.Arrays.fill(pcm, (short) 1000);
                if (seq > 10 && r.nextDouble() < 0.1) lost++;
                else p.push(seq, ms * 1000 - 3000, d, pcm);
                seq++;
            }
            p.fill(out, out.now, 960, s -> s * 1000, 0);
        }
        assertEquals(0, p.hardResyncs());
        assertEquals(lost, p.concealed(), lost / 20.0 + 2);
        assertTrue("error " + p.lastErrorNs(), Math.abs(p.lastErrorNs()) < 1_000_000);
        // Not one frame more than real time: 20 s of playing since the first write, in frames.
        long expect = (out.now - out.startedAt) * 48 / 1_000_000 + 960;
        assertEquals(expect, out.written, 300);
    }

    @Test
    public void droppedAndRepeatedFramesAreSmoothed() {
        // At the most it corrects (0.1 %), a full-scale sine must not get a step larger than its own slope allows.
        Ideal out = new Ideal();
        AudioPlayout p = new AudioPlayout(48000, 2);
        for (long ms = 0, seq = 0; ms < 10_000; ms++) {
            out.now = ms * 1_000_000L;
            if (ms % 5 == 0) {
                short[] pcm = new short[480];
                for (int i = 0; i < 240; i++) {
                    short v = (short) (20000 * Math.sin(2 * Math.PI * 440 * (seq * 240 + i) / 48000.0));
                    pcm[2 * i] = v;
                    pcm[2 * i + 1] = v;
                }
                // The sender's clock (and its sample clock) runs 1000 ppm fast: 48 frames a second too many, dropped.
                p.push(seq, Math.round((ms * 1000 - 2000) * 1.001), 50_000, pcm);
                seq++;
            }
            p.fill(out, out.now, 960, s -> Math.round(s / 1.001 * 1000), 0.001);
        }
        assertTrue("dropped " + p.dropped(), p.dropped() > 0);
        int maxStep = 0, start = 48000;   // after the start and its fade
        for (int i = start + 1; i < out.heard.size(); i++) {
            maxStep = Math.max(maxStep, Math.abs(out.heard.get(i) - out.heard.get(i - 1)));
        }
        // 440 Hz at 20000: at most 2π·440·20000/48000 ≈ 1152 per frame; a drop folds two frames, about twice that.
        assertTrue("step " + maxStep, maxStep < 2 * 1152 + 50);
    }

    /**
     * D smaller than this receiver needs: every chunk comes after its time and becomes silence. When the sender raises
     * D (from the receiver's need), the playout must come back on time, not stay behind its own concealment.
     */
    @Test
    public void tooSmallADIsLeftOnceTheSenderRaisesIt() {
        Ideal out = new Ideal();
        AudioPlayout p = new AudioPlayout(48000, 2);
        for (long ms = 0, seq = 0; ms < 10_000; ms++) {
            out.now = ms * 1_000_000L;
            if (ms % 5 == 0 && ms >= 30) {
                short[] pcm = new short[480];
                java.util.Arrays.fill(pcm, (short) 1000);
                // Arrives 30 ms after its pts; the output takes 35 ms: it needs D ≥ 65. D is 20 for 3 s, then 80.
                long d = ms < 3000 ? 20_000 : 80_000;
                p.push(seq, (ms - 30) * 1000, d, pcm);
                seq++;
            }
            p.fill(out, out.now, 960, s -> s * 1000, 0);
        }
        assertTrue("error " + p.lastErrorNs(), Math.abs(p.lastErrorNs()) < 1_000_000);
        int silent = 0;
        for (int i = out.heard.size() - 48000; i < out.heard.size(); i++) if (out.heard.get(i) == 0) silent++;
        assertEquals("the last second plays sound, not silence", 0, silent);
    }
}
