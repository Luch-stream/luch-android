// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.Arrays;

/**
 * The acoustic calibration's signal and its detector (docs: sync, 6.7), pure code: it is tested only on recordings
 * synthesised in memory, and nothing here plays or records.
 *
 * <p>The probe is a logarithmic sweep from 2 to 6 kHz: above most of the energy of fans and mains hum, reproduced by
 * every small speaker, and compressed by its matched filter into a pulse about 0.25 ms wide. Each device plays n of
 * them at times on the sender's clock; one microphone records. Per sweep, the recording around its expected time is
 * correlated with the sweep in 1.5–7 kHz, as the envelope of the analytic correlation (so the pulse's phase, which a
 * reflection or a speaker's polarity turns, does not matter).
 *
 * <p>The direct path is the earliest strong peak, not the largest one: reflections come later and can be louder. It
 * is the first local maximum of at least a third of the largest within 50 ms before it, timed to a fraction of a
 * sample by a parabola. It is trusted when it stands at least 12 dB over everything before it (only noise comes before
 * the direct sound), and a device is measured when all its sweeps are trusted and agree within 0.5 ms. Otherwise the
 * device is "not measured" and keeps its fallback (the platform's latencies and the slider).
 */
public final class Probe {
    public static final double F0 = 2000, F1 = 6000, MS = 200;
    public static final double BAND_LO = 1500, BAND_HI = 7000;
    public static final double MIN_PSR_DB = 12, AGREE_MS = 0.5;
    /** The direct path: the earliest peak of at least this share of the largest, at most this long before it. */
    static final double DIRECT_SHARE = 1 / 3.0, DIRECT_BEFORE_MS = 50;
    /** What counts as "before the direct sound" for the sidelobes: from the window's start to this long before it. */
    static final double GUARD_MS = 1, MIN_LEAD_MS = 20;

    public final int rate;
    private final float[] sweep;
    private Fft fft;
    private double[] sweepRe, sweepIm;

    public Probe(int rate) {
        this.rate = rate;
        this.sweep = sweep(rate, F0, F1, MS, 1);
    }

    /**
     * A logarithmic sweep f0 → f1 of {@code ms}, peak {@code gain}, with 5 ms raised-cosine fades so that it starts and
     * ends without a click.
     */
    public static float[] sweep(int rate, double f0, double f1, double ms, double gain) {
        int n = (int) Math.round(rate * ms / 1000);
        double t1 = ms / 1000, k = Math.log(f1 / f0);
        int fade = rate * 5 / 1000;
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double phase = 2 * Math.PI * f0 * t1 / k * (Math.exp(t / t1 * k) - 1);
            double g = gain;
            if (i < fade) g *= 0.5 - 0.5 * Math.cos(Math.PI * i / fade);
            else if (i >= n - fade) g *= 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / fade);
            s[i] = (float) (g * Math.sin(phase));
        }
        return s;
    }

    /** The probe at unit gain (a device scales it to the probe message's gain). */
    public float[] signal() {
        return sweep.clone();
    }

    /** One sweep's arrival: where its first sample was heard (fractional sample), and how sure the detector is. */
    public static final class Arrival {
        public final double at, psrDb;
        public final boolean trusted;
        public final String why;

        Arrival(double at, double psrDb, boolean trusted, String why) {
            this.at = at;
            this.psrDb = psrDb;
            this.trusted = trusted;
            this.why = why;
        }
    }

    /** A device's measurement: the median of (heard − scheduled), in samples, when it passed. */
    public static final class Result {
        public final boolean accepted;
        public final double delaySamples;
        public final Arrival[] arrivals;
        public final String why;

        Result(boolean accepted, double delaySamples, Arrival[] arrivals, String why) {
            this.accepted = accepted;
            this.delaySamples = delaySamples;
            this.arrivals = arrivals;
            this.why = why;
        }

        public double delayMs(int rate) {
            return delaySamples * 1000 / rate;
        }
    }

    /**
     * Measures one device: its sweeps were scheduled to start at {@code scheduled} (samples of the recording's own
     * timeline, its capture timestamps mapped to the sender's clock); each is looked for within ±{@code windowMs}.
     */
    public Result measure(float[] rec, double[] scheduled, double windowMs) {
        int w = (int) Math.round(rate * windowMs / 1000);
        Arrival[] a = new Arrival[scheduled.length];
        double[] d = new double[scheduled.length];
        for (int i = 0; i < scheduled.length; i++) {
            int from = (int) Math.floor(scheduled[i]) - w, to = (int) Math.ceil(scheduled[i]) + w;
            a[i] = find(rec, from, to);
            d[i] = a[i].at - scheduled[i];
            if (!a[i].trusted) return new Result(false, Double.NaN, a, "sweep " + i + ": " + a[i].why);
        }
        double[] s = d.clone();
        Arrays.sort(s);
        if ((s[s.length - 1] - s[0]) * 1000 / rate > AGREE_MS) {
            return new Result(false, Double.NaN, a, String.format(java.util.Locale.US,
                    "the sweeps disagree by %.2f ms", (s[s.length - 1] - s[0]) * 1000 / rate));
        }
        int n = s.length;
        double median = n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
        return new Result(true, median, a, "ok");
    }

    /** Where the sweep starts in {@code rec}, looked for at starts from..to (samples). */
    public Arrival find(float[] rec, int from, int to) {
        int m = sweep.length, span = to - from;
        if (from < 0 || to + m > rec.length || span <= 0) return new Arrival(Double.NaN, 0, false, "outside the recording");
        int len = span + m;
        int size = Integer.highestOneBit(len - 1) << 1;
        prepare(size);
        double[] re = new double[size], im = new double[size];
        for (int i = 0; i < len; i++) re[i] = rec[from + i];
        fft.transform(re, im, false);
        // The analytic correlation in the band: X · conj(S) on positive frequencies only, doubled.
        int lo = (int) Math.ceil(BAND_LO * size / rate), hi = (int) Math.floor(BAND_HI * size / rate);
        for (int k = 0; k < size; k++) {
            if (k < lo || k > hi) {
                re[k] = 0;
                im[k] = 0;
                continue;
            }
            double xr = re[k], xi = im[k], sr = sweepRe[k], si = -sweepIm[k];
            re[k] = 2 * (xr * sr - xi * si);
            im[k] = 2 * (xr * si + xi * sr);
        }
        fft.transform(re, im, true);
        double[] env = new double[span + 1];
        int peak = 0;
        for (int k = 0; k <= span; k++) {
            env[k] = Math.hypot(re[k], im[k]);
            if (env[k] > env[peak]) peak = k;
        }
        if (env[peak] <= 0) return new Arrival(Double.NaN, 0, false, "silence");
        // The direct path: the earliest local maximum of at least a third of the largest within 50 ms before it.
        int before = (int) (rate * DIRECT_BEFORE_MS / 1000);
        int direct = peak;
        for (int k = Math.max(1, peak - before); k < peak; k++) {
            if (env[k] >= DIRECT_SHARE * env[peak] && env[k] >= env[k - 1] && env[k] >= env[k + 1]) {
                direct = k;
                break;
            }
        }
        // Only noise comes before the direct sound: the sweep must stand 12 dB over all of it.
        int guard = (int) Math.ceil(rate * GUARD_MS / 1000), lead = (int) (rate * MIN_LEAD_MS / 1000);
        if (direct - guard < lead) {
            return new Arrival(from + direct, 0, false, "too close to the window's start: too early to tell");
        }
        double side = 0;
        for (int k = 0; k < direct - guard; k++) side = Math.max(side, env[k]);
        double psr = 20 * Math.log10(env[direct] / Math.max(side, 1e-30));
        double at = from + direct + parabola(env, direct);
        boolean ok = psr >= MIN_PSR_DB;
        return new Arrival(at, psr, ok, ok ? "ok" : String.format(java.util.Locale.US, "peak only %.1f dB over noise", psr));
    }

    /** The vertex of the parabola through a peak and its neighbours, as an offset from the peak (−0.5..0.5). */
    private static double parabola(double[] v, int i) {
        if (i <= 0 || i >= v.length - 1) return 0;
        double a = v[i - 1], b = v[i], c = v[i + 1];
        double den = a - 2 * b + c;
        return den == 0 ? 0 : Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / den));
    }

    private void prepare(int size) {
        if (fft != null && fft.size() == size) return;
        fft = new Fft(size);
        sweepRe = new double[size];
        sweepIm = new double[size];
        for (int i = 0; i < sweep.length; i++) sweepRe[i] = sweep[i];
        fft.transform(sweepRe, sweepIm, false);
    }
}
