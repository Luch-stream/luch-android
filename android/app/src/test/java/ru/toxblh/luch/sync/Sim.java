// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.Random;

/**
 * The simulator's world (docs: sync, 8.1): seeded, deterministic, silent. True time is the sender's pts clock, in µs;
 * every device has its own crystal and its own Wi-Fi.
 */
final class Sim {
    private Sim() {
    }

    /** A device's monotonic clock: local = true·(1 + skew) + offset. */
    static final class Clock {
        final double skew;
        final double offset;

        Clock(double skew, double offset) {
            this.skew = skew;
            this.offset = offset;
        }

        static Clock random(Random r, double maxPpm) {
            return new Clock((r.nextDouble() * 2 - 1) * maxPpm * 1e-6, 1e9 + (r.nextDouble() * 2 - 1) * 10e6);
        }

        long local(double trueUs) {
            return Math.round(trueUs * (1 + skew) + offset);
        }

        double trueOf(long localUs) {
            return (localUs - offset) / (1 + skew);
        }
    }

    /** One direction of a Wi-Fi link. */
    static final class Net {
        final double baseUs, jitterMeanUs, outlierP, lossP, powerSaveP;
        final Random r;
        /** True-time windows in which nothing gets through. */
        double outageFrom = -1, outageTo = -1;

        Net(Random r, double baseUs, double jitterMeanUs, double outlierP, double lossP, double powerSaveP) {
            this.r = r;
            this.baseUs = baseUs;
            this.jitterMeanUs = jitterMeanUs;
            this.outlierP = outlierP;
            this.lossP = lossP;
            this.powerSaveP = powerSaveP;
        }

        /** Idle 5 GHz: 1.5 ms + gamma jitter (mean 1 ms), 2 % outliers of 10–100 ms, 1 % loss. */
        static Net wifi5(Random r, boolean powerSave) {
            return new Net(r, 1500, 1000, 0.02, 0.01, powerSave ? 0.15 : 0);
        }

        /** Busy 2.4 GHz: 3 ms base, more jitter, 5 % outliers. */
        static Net busy24(Random r, boolean powerSave) {
            return new Net(r, 3000, 2000, 0.05, 0.01, powerSave ? 0.15 : 0);
        }

        /** The delay of a packet sent at true time t, or negative when it is lost. */
        double delay(double t) {
            if (t >= outageFrom && t < outageTo) return -1;
            if (r.nextDouble() < lossP) return -1;
            double d = baseUs + gamma(r, 2, jitterMeanUs / 2);
            if (r.nextDouble() < outlierP) d += 10_000 + r.nextDouble() * 90_000;
            if (powerSaveP > 0 && r.nextDouble() < powerSaveP) {
                // Held by the access point until the station wakes for the next beacon (102.4 ms).
                double beacon = 102_400;
                d += beacon - ((t + d) % beacon);
            }
            return d;
        }
    }

    /** Gamma(k, θ) for integer k: a sum of exponentials. */
    static double gamma(Random r, int k, double theta) {
        double s = 0;
        for (int i = 0; i < k; i++) s -= Math.log(1 - r.nextDouble());
        return s * theta;
    }

    /** Sorted copy's p-quantile. */
    static double quantile(double[] v, int n, double p) {
        double[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return c[Math.max(0, Math.min(n - 1, (int) Math.ceil(p * n) - 1))];
    }

    /**
     * A device running a ClockEstimator against the sender over its own links: requests on its schedule, replies
     * processed in the order they arrive.
     */
    static final class ClockLink {
        final Clock clock;
        final Net up, down;
        final ClockEstimator est = new ClockEstimator();
        final Random r;
        private final java.util.PriorityQueue<double[]> inFlight = new java.util.PriorityQueue<>(
                (x, y) -> Double.compare(x[0], y[0]));

        ClockLink(Random r, Clock clock, Net up, Net down) {
            this.r = r;
            this.clock = clock;
            this.up = up;
            this.down = down;
        }

        private double now;

        /** Runs everything due up to true time t. */
        void advance(double t) {
            while (true) {
                double nextReq = est.nextRequestUs() == Long.MIN_VALUE ? now
                        : Math.max(now, clock.trueOf(est.nextRequestUs()));
                double nextReply = inFlight.isEmpty() ? 1e18 : inFlight.peek()[0];
                if (Math.min(nextReq, nextReply) > t) break;
                if (nextReply <= nextReq) {
                    double[] e = inFlight.poll();
                    now = Math.max(now, e[0]);
                    est.onReply((long) e[1], (long) e[2], (long) e[3], clock.local(e[0]));
                } else {
                    double ts = nextReq;
                    now = ts;
                    long t1 = clock.local(ts);
                    est.sent(t1);
                    double du = up.delay(ts);
                    if (du < 0) continue;
                    double t2 = ts + du;
                    double t3 = t2 + 30 + r.nextDouble() * 170;   // the sender's own turnaround
                    double dd = down.delay(t3);
                    if (dd < 0) continue;
                    inFlight.add(new double[]{t3 + dd, t1, Math.round(t2), Math.round(t3)});
                }
            }
            now = t;
            est.tick(clock.local(t));
        }

        /** Estimate minus truth at true time t, µs. */
        double error(double t) {
            return est.toSender(clock.local(t)) - t;
        }
    }
}
