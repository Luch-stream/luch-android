// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.Random;

/**
 * The calibration without a sound: a recording made up in memory (four sweeps arriving after a random delay, with a
 * direct path, three reflections, one of them louder than the direct sound, and noise), measured by the real Probe.
 * It shows on the device that the detector runs and how fast; the real run, which plays and listens, is for a daytime
 * test by hand.
 */
public final class DryRun {
    public static final int RATE = 48000, SWEEPS = 4;

    public static final class Outcome {
        public final boolean accepted;
        public final double trueMs, measuredMs, psrDb;
        public final String why;
        public final long tookMs;

        Outcome(boolean accepted, double trueMs, double measuredMs, double psrDb, String why, long tookMs) {
            this.accepted = accepted;
            this.trueMs = trueMs;
            this.measuredMs = measuredMs;
            this.psrDb = psrDb;
            this.why = why;
            this.tookMs = tookMs;
        }
    }

    private DryRun() {
    }

    public static Outcome run(long seed) {
        long t0 = System.nanoTime();
        Random r = new Random(seed);
        double delay = 0.01 + 0.09 * r.nextDouble();
        float[] s = Probe.sweep(RATE, Probe.F0, Probe.F1, Probe.MS, 0.3);
        int gap = (int) (0.7 * RATE), lead = RATE / 2;
        float[] rec = new float[lead + SWEEPS * gap + RATE];
        double[] sch = new double[SWEEPS];
        // Direct path, then reflections at 3, 12 (6 dB louder) and 30 ms, whole samples: enough for a dry run.
        double[][] paths = {{0, 1}, {0.003, -0.6}, {0.012, 2}, {0.030, 0.5}};
        for (int k = 0; k < SWEEPS; k++) {
            sch[k] = lead + k * gap;
            for (double[] p : paths) {
                int at = (int) Math.round(sch[k] + (delay + p[0]) * RATE);
                for (int i = 0; i < s.length; i++) rec[at + i] += (float) (p[1] * s[i]);
            }
        }
        for (int i = 0; i < rec.length; i++) rec[i] += (float) (0.1 * r.nextGaussian());
        Probe.Result res = new Probe(RATE).measure(rec, sch, 150);
        double psr = Double.MAX_VALUE;
        for (Probe.Arrival a : res.arrivals) if (a != null) psr = Math.min(psr, a.psrDb);
        long took = (System.nanoTime() - t0) / 1_000_000;
        // The arrival is measured to the nearest whole sample here, as the recording was made.
        double trueMs = Math.round(delay * RATE) * 1000.0 / RATE;
        return new Outcome(res.accepted, trueMs, res.accepted ? res.delayMs(RATE) : Double.NaN, psr, res.why, took);
    }
}
