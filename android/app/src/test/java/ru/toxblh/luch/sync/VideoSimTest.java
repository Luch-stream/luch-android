// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Random;

import org.junit.Test;

/**
 * The picture's pass criteria of docs: sync, 8.1: TVs showing each frame at pts + D through releaseOutputBuffer and
 * their own VSYNC, and the interactive TV + speaker, with the sender's policy for D (SenderPolicy, the same as
 * luchd's).
 */
public class VideoSimTest {
    /** A TV: its clock, its Wi-Fi, its display's VSYNC (on its own clock) and its decoder. */
    static final class Tv {
        final Sim.ClockLink link;
        final Sim.Net media;
        final double periodLocalUs, phaseUs, decodeMeanUs;
        final Random r;

        Tv(long seed, double hz) {
            r = new Random(seed * 0x9E3779B97F4A7C15L);
            Sim.Clock c = Sim.Clock.random(r, 100);
            link = new Sim.ClockLink(r, c, Sim.Net.wifi5(r, false), Sim.Net.wifi5(r, seed % 2 == 0));
            media = Sim.Net.wifi5(r, seed % 2 == 0);
            periodLocalUs = 1e6 / hz;
            phaseUs = r.nextDouble() * periodLocalUs;
            decodeMeanUs = 4000 + r.nextDouble() * 6000;
        }

        /** The true time at which a frame released at local µs {@code rel} reaches the panel. */
        double shownAt(long relLocalUs) {
            double k = Math.ceil((relLocalUs - phaseUs) / periodLocalUs);
            return link.clock.trueOf(Math.round(k * periodLocalUs + phaseUs));
        }

        /**
         * Shows frame pts (true µs) with delay D (µs; 0 = at once), sent at true time {@code sent}; returns the true time
         * it is shown, or NaN when lost or dropped.
         */
        double show(double pts, double d, double sent) {
            double net = media.delay(sent);
            if (net < 0) return Double.NaN;
            double arrived = sent + net;
            link.advance(arrived);
            ClockEstimator est = link.est;
            long arrivedLocal = link.clock.local(arrived);
            double decode = decodeMeanUs * (0.7 + 0.6 * r.nextDouble());
            long period = Math.round(periodLocalUs * 1000);
            if (d == 0) {
                long decoded = arrivedLocal + Math.round(decode);
                return shownAt(decoded);
            }
            long target = est.toLocal(Math.round(pts + d)) * 1000;   // ns
            long feed = Math.max(arrivedLocal * 1000, VideoScheduler.feedNs(target, Math.round(decode * 1000), period));
            long decodedNs = feed + Math.round(decode * 1000);
            long rel = VideoScheduler.releaseNs(target, decodedNs, period, true, false);
            if (rel == VideoScheduler.DROP) return Double.NaN;
            return shownAt(rel / 1000);
        }
    }

    @Test
    public void watchingEveryFrameIsShownWithinHalfAPeriodOfItsTime() {
        double d = 150_000;
        double worst = 0, sum = 0;
        int n = 0, late = 0;
        double[] spread = new double[20_000];
        int ns = 0;
        Tv[] tvs = {new Tv(1, 60), new Tv(2, 60), new Tv(3, 50), new Tv(4, 60)};
        for (Tv tv : tvs) tv.link.advance(5e6);   // the clock burst at the join
        for (double pts = 5e6; pts < 120e6; pts += 1e6 / 60) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Tv tv : tvs) {
                double shown = tv.show(pts, d, pts + 8000);   // 8 ms to capture and encode
                if (Double.isNaN(shown)) continue;
                double e = shown - (pts + d);
                double bound = tv.periodLocalUs / 2 + 2000;
                if (Math.abs(e) > bound) late++;
                worst = Math.max(worst, Math.abs(e) - tv.periodLocalUs / 2);
                sum += Math.abs(e);
                n++;
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
            }
            if (hi > lo && ns < spread.length) spread[ns++] = hi - lo;
        }
        String log = String.format(Locale.US, "video, watching (D 150 ms, 60 and 50 Hz): mean |error| %.1f ms, "
                        + "worst beyond P/2 %.2f ms, outside ±(P/2 + 2 ms) %d of %d, spread between TVs p99 %.1f ms",
                sum / n / 1000, worst / 1000, late, n, Sim.quantile(spread, ns, 0.99) / 1000);
        System.out.println(log);
        // Frames whose packets come later than D (outliers and power-save holds of over 100 ms) are shown late.
        assertTrue(log, late < n * 0.005);
    }

    @Test
    public void interactiveTvAndSpeakerSoundTrailsThePictureWithinTheTolerance() {
        // The TV shows at once (D_v = 0); the speaker needs 35 ms. The policy sets D_a from the TV's reported pts →
        // display time; the picture is not held because the speaker keeps up.
        Tv tv = new Tv(7, 60);
        tv.link.advance(5e6);
        Window videoPts = new Window(600);
        SenderPolicy p = new SenderPolicy(SenderPolicy.Mode.INTERACTIVE, 20, 0);
        double[] diffs = new double[30_000];
        int n = 0;
        double needAudio = 35;
        double[] dd = {0, 0};
        for (double pts = 5e6; pts < 60e6; pts += 1e6 / 60) {
            double shown = tv.show(pts, dd[0] * 1000, pts + 8000);
            if (Double.isNaN(shown)) continue;
            videoPts.add((shown - pts) / 1000);
            if (((long) pts) % 1_000_000 < 17_000) {   // once a second, as stats come
                dd = p.update(pts / 1e6, new double[]{videoPts.median()}, new double[]{needAudio});
            }
            if (pts > 10e6 && n < diffs.length) {
                double sound = pts + dd[1] * 1000;   // the speaker is exact (AudioSimTest)
                diffs[n++] = (sound - shown) / 1000;
            }
        }
        double p50 = Sim.quantile(diffs, n, 0.5), p5 = Sim.quantile(diffs, n, 0.05), p95 = Sim.quantile(diffs, n, 0.95);
        String log = String.format(Locale.US, "interactive TV + speaker: D_v %.0f, D_a %.1f ms; sound − picture "
                + "p5 %.1f, p50 %.1f, p95 %.1f ms", dd[0], dd[1], p5, p50, p95);
        System.out.println(log);
        assertEquals(log, 0, dd[0], 0);
        assertTrue(log, p50 >= -2 && p50 <= 22);
        assertTrue(log, p5 >= -2 - 8 && p95 <= 22 + 8);   // the picture's own jitter without a buffer: about a period
    }

    @Test
    public void interactiveSlowSpeakerHoldsThePictureOnlyByTheExcess() {
        SenderPolicy p = new SenderPolicy(SenderPolicy.Mode.INTERACTIVE, 20, 0);
        // The TV shows 30 ms after capture; a Bluetooth speaker needs 230 ms.
        double[] d = p.update(1, new double[]{30}, new double[]{230});
        assertEquals(210, d[0], 0.01);
        assertEquals(230, d[1], 0.01);
    }
}
