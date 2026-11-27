// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/** One sender playing 5 ms Opus chunks to a group of SimDevices, and what each of them makes heard. */
final class SimGroup {
    static final double P0 = 1_000_000;   // the pts of the first chunk, true µs
    static final double STEP_US = 2000;   // the receivers' loop: about the audio socket's timeout

    final List<SimDevice> devices = new ArrayList<>();
    /** D for a chunk sent at true time t (µs). */
    DoubleUnaryOperator delay = t -> 300_000;
    private final java.util.TreeMap<Long, Long> delayOfSeq = new java.util.TreeMap<>();
    /** From which true time a device takes part (a join), per device; 0 = from the start. */
    final java.util.Map<SimDevice, Double> joinAt = new java.util.HashMap<>();

    /** Per device, the heard errors (µs) sampled every {@code everyUs} after {@code fromUs}. */
    double[][] errors;
    int[] counts;
    /** Per sample instant, max − min of the errors of all devices that had one (µs). */
    double[] spread;
    int spreadCount;

    static Sim.Net net(Random r, boolean busy, boolean ps) {
        return busy ? Sim.Net.busy24(r, ps) : Sim.Net.wifi5(r, ps);
    }

    void run(double untilUs, double fromUs, double everyUs) {
        int nd = devices.size();
        int max = (int) ((untilUs - fromUs) / everyUs) + 2;
        errors = new double[nd][max];
        counts = new int[nd];
        spread = new double[max];
        long seq = 0;
        double nextChunk = P0, nextSample = fromUs;
        for (double t = 0; t <= untilUs; t += STEP_US) {
            while (nextChunk <= t) {
                long d = Math.round(delay.applyAsDouble(nextChunk));
                delayOfSeq.put(seq, d);
                for (SimDevice dev : devices) {
                    Double j = joinAt.get(dev);
                    if (j == null || nextChunk >= j) dev.send(seq, Math.round(nextChunk), d, nextChunk + 1000);
                }
                seq++;
                nextChunk += 5000;
            }
            for (SimDevice dev : devices) {
                Double j = joinAt.get(dev);
                if (j == null || t >= j - 1_000_000) dev.step(t);   // a joining device's clock starts a second early
            }
            if (t >= nextSample) {
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                int n = 0;
                for (int i = 0; i < nd; i++) {
                    double e = devices.get(i).heardError(t, s -> delayOfSeq.floorEntry(s).getValue());
                    if (Double.isNaN(e)) continue;
                    errors[i][counts[i]++] = e;
                    lo = Math.min(lo, e);
                    hi = Math.max(hi, e);
                    n++;
                }
                if (n >= 2) spread[spreadCount++] = hi - lo;
                nextSample += everyUs;
            }
            // Old delays are no longer needed once every device has played past them.
            if (delayOfSeq.size() > 4000) delayOfSeq.pollFirstEntry();
        }
    }

    double spreadQuantile(double p) {
        return Sim.quantile(spread, spreadCount, p);
    }

    /** Mean error of device i over its samples [from, to) (fractions of the run). */
    double meanError(int i, double from, double to) {
        int a = (int) (counts[i] * from), b = (int) (counts[i] * to);
        double s = 0;
        for (int k = a; k < b; k++) s += errors[i][k];
        return b > a ? s / (b - a) : Double.NaN;
    }
}
