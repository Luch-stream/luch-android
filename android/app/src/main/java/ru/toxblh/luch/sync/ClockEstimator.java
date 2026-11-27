// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/**
 * The sender's clock as seen from here, from kind 3 exchanges (PROTOCOL.md 4h, docs: sync, 6.3).
 *
 * <p>Wi-Fi delay is one-sided: queues, retries and power save only ever add to it. So the exchanges with the
 * smallest round trip are the true ones, and the error of one exchange is at most half its round trip. Two scales:
 * the skew between the two crystals is the slope of a line through the best exchange of each second over the last
 * four minutes; the offset is the mean of the last 64 exchanges moved to now along that slope, each weighted by how
 * wrong it can be (half its round trip beyond the best, plus the slope's error times its age).
 *
 * <p>While something plays, a new estimate is not jumped to but slewed in at 0.5 ms per second, which the audio loop
 * absorbs like drift; a difference over 5 ms is a step (the caller does a hard resync). All times are µs: local on
 * this device's monotonic clock, sender on its pts clock. Pure code: the simulator runs exactly this.
 */
public final class ClockEstimator {
    static final int WINDOW = 64;
    static final long OUTLIER_US = 2_000;
    static final int MIN_SELECTED = 6;
    static final double FLOOR_US = 50;
    /** Flatter weights for the skew: its line needs points spread over time more than the very best few. */
    static final double SKEW_FLOOR_US = 250;
    static final double MAX_SKEW = 500e-6, PRIOR_SKEW = 100e-6, MIN_SIGMA = 0.2e-6;
    static final long SKEW_SPAN_US = 10_000_000, MIN_SKEW_SPAN_US = 2_000_000;
    static final int BUCKETS = 256;
    static final long BUCKET_US = 1_000_000;
    static final long MAX_RTT_US = 200_000;
    static final double SLEW = 0.5e-3;   // 0.5 ms per second
    static final long STEP_US = 5_000;
    static final int BURST = 16;
    /** 16 requests 30 ms apart at the start, then 4 a second (2 a second gave 30 % more error in the simulator). */
    static final long BURST_GAP_US = 30_000, PERIOD_US = 250_000, OUTAGE_US = 3_000_000;
    /**
     * A burst again when the fit's residual is this bad. Not the whole error bound: half the smallest round trip is
     * mostly the symmetric base delay, over 2 ms all the time on a busy 2.4 GHz link.
     */
    static final double REBURST_RESIDUAL_US = 1000;

    private final long[] mid = new long[WINDOW], rtt = new long[WINDOW];
    private final double[] theta = new double[WINDOW];
    private int n, next;
    // The best exchange of each 2 s, for the skew.
    private final long[] bId = new long[BUCKETS], bMid = new long[BUCKETS], bRtt = new long[BUCKETS];
    private final double[] bTheta = new double[BUCKETS];
    private int buckets;
    private double skewSigma = PRIOR_SKEW;

    // The fit: offset(t) = a + b·(t − ref), sender − local.
    private double a, b;
    private long ref;
    private boolean fitted;
    private double errUs = Double.NaN, residualUs, minRttUs;

    // What is applied: offset off at local time at, moving with slope b.
    private double off;
    private long at;
    private boolean applied, playing;
    private int steps;

    // The request schedule.
    private int burstLeft = BURST;
    private long nextRequest = Long.MIN_VALUE, lastReply = Long.MIN_VALUE;
    private int seq;

    /** When to send the next request (local µs). */
    public long nextRequestUs() {
        return nextRequest;
    }

    /** A request goes out now; returns its probe number (the header's seq). */
    public int sent(long nowUs) {
        if (lastReply != Long.MIN_VALUE && nowUs - lastReply > OUTAGE_US && burstLeft == 0) burstLeft = BURST;
        if (burstLeft > 0) {
            burstLeft--;
            nextRequest = nowUs + BURST_GAP_US;
        } else {
            nextRequest = nowUs + PERIOD_US;
        }
        return seq++;
    }

    /** Asks for a quick burst again: the network changed, or the playout saw something odd. */
    public void burst() {
        burstLeft = BURST;
        nextRequest = Long.MIN_VALUE;
    }

    /**
     * One reply: t1 sent and t4 received here (local µs), t2 received and t3 sent by the sender (sender µs).
     * Returns false when the exchange was thrown away.
     */
    public boolean onReply(long t1, long t2, long t3, long t4) {
        long r = (t4 - t1) - (t3 - t2);
        if (r < 0 || r > MAX_RTT_US || t3 < t2) return false;
        lastReply = t4;
        mid[next] = t1 + (t4 - t1) / 2;
        rtt[next] = r;
        theta[next] = ((double) (t2 - t1) + (double) (t3 - t4)) / 2;
        next = (next + 1) % WINDOW;
        if (n < WINDOW) n++;
        bucket(t1 + (t4 - t1) / 2, r, ((double) (t2 - t1) + (double) (t3 - t4)) / 2);
        fit();
        if (residualUs > REBURST_RESIDUAL_US && burstLeft == 0 && n >= BURST) burst();
        tick(t4);
        return true;
    }

    private void fit() {
        long min = Long.MAX_VALUE, newest = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, rtt[i]);
            newest = Math.max(newest, mid[i]);
        }
        minRttUs = min;
        double slope = skew();
        // Each exchange's offset, moved to the newest along the skew, weighted by how wrong it can be: half of what its
        // round trip has beyond the best, plus what the skew's own error adds over its age. Outliers and power-save
        // holds (2 ms beyond the best) are left out while enough others remain.
        int k = 0;
        for (int i = 0; i < n; i++) if (rtt[i] <= min + OUTLIER_US) k++;
        long cut = k >= MIN_SELECTED ? min + OUTLIER_US : Long.MAX_VALUE;
        double sw = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            if (rtt[i] > cut) continue;
            double w = weight(i, min, newest);
            sw += w;
            sy += w * (theta[i] - slope * (mid[i] - newest));
        }
        double intercept = sy / sw;
        double res = 0;
        for (int i = 0; i < n; i++) {
            if (rtt[i] > cut) continue;
            res += weight(i, min, newest) * sq(theta[i] - slope * (mid[i] - newest) - intercept);
        }
        a = intercept;
        b = slope;
        ref = newest;
        fitted = true;
        residualUs = Math.sqrt(res / sw);
        errUs = min / 2.0 + residualUs;
    }

    private double weight(int i, long min, long newest) {
        return 1.0 / sq((rtt[i] - min) / 2.0 + FLOOR_US + skewSigma * (newest - mid[i]));
    }

    /**
     * The skew, from the best exchange of each second over the last few minutes: 64 exchanges span only 32 s, too
     * short to see 2 ppm under 100 µs of noise. Before 10 s of history it is used only when clearly not zero (3 σ).
     * Sets {@link #skewSigma}, the slope's own standard error.
     */
    private double skew() {
        skewSigma = PRIOR_SKEW;
        int m = Math.min(buckets, BUCKETS);
        if (m < 3) return 0;
        long bmin = Long.MAX_VALUE;
        for (int i = 0; i < m; i++) bmin = Math.min(bmin, bRtt[i]);
        double sw = 0, st = 0, sy = 0;
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE, t0 = bMid[0];
        int used = 0;
        for (int i = 0; i < m; i++) {
            if (bRtt[i] > bmin + OUTLIER_US) continue;
            double w = bucketWeight(i, bmin);
            sw += w;
            st += w * (bMid[i] - t0);
            sy += w * bTheta[i];
            first = Math.min(first, bMid[i]);
            last = Math.max(last, bMid[i]);
            used++;
        }
        if (used < 3 || last - first < MIN_SKEW_SPAN_US) return 0;
        double tm = st / sw, ym = sy / sw, stt = 0, sty = 0;
        for (int i = 0; i < m; i++) {
            if (bRtt[i] > bmin + OUTLIER_US) continue;
            double w = bucketWeight(i, bmin), dt = bMid[i] - t0 - tm;
            stt += w * dt * dt;
            sty += w * dt * (bTheta[i] - ym);
        }
        if (stt <= 0) return 0;
        double slope = sty / stt, res = 0;
        for (int i = 0; i < m; i++) {
            if (bRtt[i] > bmin + OUTLIER_US) continue;
            res += bucketWeight(i, bmin) * sq(bTheta[i] - ym - slope * (bMid[i] - t0 - tm));
        }
        double sigma = Math.max(MIN_SIGMA, Math.sqrt(res / (Math.max(1, used - 2) * stt)));
        if (last - first < SKEW_SPAN_US && Math.abs(slope) < 3 * sigma) {
            skewSigma = Math.min(PRIOR_SKEW, Math.abs(slope) + sigma);
            return 0;
        }
        skewSigma = sigma;
        return Math.max(-MAX_SKEW, Math.min(MAX_SKEW, slope));
    }

    private double bucketWeight(int i, long bmin) {
        return 1.0 / sq((bRtt[i] - bmin) / 2.0 + SKEW_FLOOR_US);
    }

    /** Keeps the exchange as its second's best when it is the smallest round trip seen in that bucket. */
    private void bucket(long m, long r, double th) {
        long id = Math.floorDiv(m, BUCKET_US);
        int cur = (buckets - 1 + BUCKETS) % BUCKETS;
        if (buckets > 0 && bId[cur] == id) {
            if (r < bRtt[cur]) {
                bRtt[cur] = r;
                bMid[cur] = m;
                bTheta[cur] = th;
            }
            return;
        }
        int slot = buckets % BUCKETS;
        bId[slot] = id;
        bRtt[slot] = r;
        bMid[slot] = m;
        bTheta[slot] = th;
        buckets++;
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Moves the applied offset towards the fit; call often (each audio step) while playing. */
    public void tick(long nowUs) {
        if (!fitted) return;
        double target = a + b * (nowUs - ref);
        if (!applied) {
            off = target;
            at = nowUs;
            applied = true;
            return;
        }
        if (nowUs <= at) return;
        double dt = nowUs - at;
        off += b * dt;
        at = nowUs;
        double diff = target - off;
        if (!playing) {
            off = target;
        } else if (Math.abs(diff) > STEP_US) {
            off = target;
            steps++;
        } else {
            double max = SLEW * dt;
            off += Math.max(-max, Math.min(max, diff));
        }
    }

    /** True from the first good exchange. */
    public boolean synced() {
        return applied;
    }

    /** While playing, new estimates are slewed in instead of jumped to. */
    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    /** Steps (differences over 5 ms) taken while playing: each one is a hard resync of the audio. */
    public int steps() {
        return steps;
    }

    /** The sender's clock at local time {@code localUs}. */
    public long toSender(long localUs) {
        return localUs + Math.round(off + b * (localUs - at));
    }

    /** Local time at which the sender's clock reads {@code senderUs}. */
    public long toLocal(long senderUs) {
        // sender = local + off + b·(local − at)  ⇒  local = (sender − off + b·at) / (1 + b)
        double local = (senderUs - off + b * at) / (1 + b);
        return Math.round(local);
    }

    /** Half the smallest round trip plus the fit's residual: a bound of the offset's error, µs. */
    public double errUs() {
        return errUs;
    }

    public double skewPpm() {
        return b * 1e6;
    }

    public double minRttUs() {
        return minRttUs;
    }

    /** Exchanges kept. */
    public int samples() {
        return n;
    }
}
