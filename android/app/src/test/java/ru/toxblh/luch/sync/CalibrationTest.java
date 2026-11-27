// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.Test;

/**
 * The calibration's pass criteria of docs: sync, 8.1, on recordings synthesised in memory (SimRoom): rooms with
 * reflections up to 6 dB louder than the direct sound and 0.3–0.8 s of reverberation, fans and hum from −5 to +20 dB,
 * overdriven microphones. Nothing is played or recorded.
 */
public class CalibrationTest {
    static final int RATE = SimRoom.RATE, SWEEPS = 4;
    static final double GAIN = 0.3, WINDOW_MS = 150;

    /** One room's outcome: {accepted 1/0, error ms (NaN when refused), snr dB, true delay ms}. */
    static double[] trial(long seed, double snrLo, double snrHi, double clipP, ThreadLocal<Probe> probes,
                          ThreadLocal<Fft> ffts) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 17);
        SimRoom room = new SimRoom(r);
        // What the device adds after its DAC, which the calibration is for (a soundbar's DSP, a projector's
        // processing), plus the travel to the microphone: 2 to 120 ms, to a fraction of a sample.
        double delay = 0.002 + 0.118 * r.nextDouble();
        double snr = snrLo + (snrHi - snrLo) * r.nextDouble();
        int lead = RATE / 2, gap = (int) (0.7 * RATE);
        double[] scheduled = new double[SWEEPS];
        int[] at = new int[SWEEPS];
        double exact = delay * RATE;
        for (int i = 0; i < SWEEPS; i++) {
            scheduled[i] = lead + i * gap;
            at[i] = (int) Math.floor(scheduled[i] + exact);
        }
        float[] resp = room.response(exact - Math.floor(exact), GAIN, ffts.get());
        float[] rec = SimRoom.place(lead + SWEEPS * gap + RATE, resp, at);
        room.addFans(rec, snr, GAIN);
        if (r.nextDouble() < clipP) SimRoom.clip(rec, 0.2 + 0.6 * r.nextDouble());
        Probe.Result res = probes.get().measure(rec, scheduled, WINDOW_MS);
        double err = res.accepted ? res.delayMs(RATE) - delay * 1000 : Double.NaN;
        return new double[]{res.accepted ? 1 : 0, err, snr, delay * 1000};
    }

    static final ThreadLocal<Probe> PROBES = ThreadLocal.withInitial(() -> new Probe(RATE));
    static final ThreadLocal<Fft> FFTS = ThreadLocal.withInitial(() -> new Fft(32768));

    @Test
    public void atZeroDecibelsOrBetterEachDelayIsWithinHalfAMillisecond() {
        int n = 1000;
        double[][] t = IntStream.range(0, n).parallel()
                .mapToObj(i -> trial(i, 0, 20, 0, PROBES, FFTS)).toArray(double[][]::new);
        int accepted = 0, within = 0;
        double worst = 0;
        double[] errs = new double[n];
        for (double[] x : t) {
            if (x[0] == 0) continue;
            errs[accepted++] = Math.abs(x[1]);
            worst = Math.max(worst, Math.abs(x[1]));
            if (Math.abs(x[1]) <= 0.5) within++;
        }
        String log = String.format(Locale.US, "calibration, SNR 0..20 dB, %d rooms: accepted %.1f %%, |error| p50 %.3f "
                        + "ms, p99 %.3f ms, worst %.3f ms, within 0.5 ms %d of %d", n, 100.0 * accepted / n,
                Sim.quantile(errs, accepted, 0.5), Sim.quantile(errs, accepted, 0.99), worst, within, accepted);
        System.out.println(log);
        assertEquals(log, accepted, within);
        assertTrue(log, accepted >= n * 0.95);
    }

    @Test
    public void neverAcceptsAnErrorBeyondTwoMillisecondsOverTenThousandRooms() {
        int n = 10_000;
        double[][] t = IntStream.range(0, n).parallel()
                .mapToObj(i -> trial(100_000 + i, -5, 20, 0.15, PROBES, FFTS)).toArray(double[][]::new);
        int accepted = 0, wrong = 0;
        int[] bandN = new int[5], bandA = new int[5];
        double worst = 0;
        for (double[] x : t) {
            int b = Math.min(4, (int) ((x[2] + 5) / 5));
            bandN[b]++;
            if (x[0] == 0) continue;
            accepted++;
            bandA[b]++;
            worst = Math.max(worst, Math.abs(x[1]));
            if (Math.abs(x[1]) > 2) wrong++;
        }
        StringBuilder log = new StringBuilder(String.format(Locale.US, "calibration, SNR -5..20 dB, 15 %% clipped, %d "
                + "rooms: accepted %.1f %%, wrongly accepted (> 2 ms) %d, worst accepted %.3f ms; accepted by SNR:", n,
                100.0 * accepted / n, wrong, worst));
        for (int b = 0; b < 5; b++) {
            log.append(String.format(Locale.US, " %d..%d dB %.0f %%", -5 + 5 * b, 5 * b, 100.0 * bandA[b] / bandN[b]));
        }
        System.out.println(log);
        assertTrue(log.toString(), wrong < n / 100);
        assertEquals(log.toString(), 0, wrong);
    }

    /**
     * White noise right in the sweep's band, from where it is easy to where it is hopeless: the detector must turn
     * from measuring to refusing, and never measure wrong on the way.
     */
    @Test
    public void inBandNoiseTurnsMeasuringIntoRefusingNeverIntoWrong() {
        StringBuilder log = new StringBuilder("calibration, white noise in band, 300 rooms per SNR:");
        int wrongAll = 0;
        for (int snr = 0; snr >= -40; snr -= 5) {
            final int at = snr;
            double[][] t = IntStream.range(0, 300).parallel().mapToObj(i -> {
                Random r = new Random((7_000L + at * 1000L + i) * 0x9E3779B97F4A7C15L);
                SimRoom room = new SimRoom(r);
                double delay = 0.002 + 0.118 * r.nextDouble(), exact = delay * RATE;
                double[] sch = new double[SWEEPS];
                int[] pos = new int[SWEEPS];
                for (int k = 0; k < SWEEPS; k++) {
                    sch[k] = RATE / 2 + k * (int) (0.7 * RATE);
                    pos[k] = (int) Math.floor(sch[k] + exact);
                }
                float[] rec = SimRoom.place(RATE / 2 + SWEEPS * (int) (0.7 * RATE) + RATE,
                        room.response(exact - Math.floor(exact), GAIN, FFTS.get()), pos);
                room.addWhite(rec, at, GAIN);
                Probe.Result res = PROBES.get().measure(rec, sch, WINDOW_MS);
                return new double[]{res.accepted ? 1 : 0, res.accepted ? res.delayMs(RATE) - delay * 1000 : 0};
            }).toArray(double[][]::new);
            int acc = 0, wrong = 0;
            double worst = 0;
            for (double[] x : t) {
                if (x[0] == 0) continue;
                acc++;
                worst = Math.max(worst, Math.abs(x[1]));
                if (Math.abs(x[1]) > 2) wrong++;
            }
            wrongAll += wrong;
            log.append(String.format(Locale.US, " %d dB: %d %% (worst %.2f ms, wrong %d);", snr, acc * 100 / 300,
                    worst, wrong));
        }
        System.out.println(log);
        assertEquals(log.toString(), 0, wrongAll);
    }

    // ---- captures that must be refused ----

    private static float[] capture(long seed, java.util.function.Consumer<float[]> spoil, double[] scheduled) {
        Random r = new Random(seed);
        SimRoom room = new SimRoom(r);
        int lead = RATE / 2, gap = (int) (0.7 * RATE);
        int[] at = new int[SWEEPS];
        for (int i = 0; i < SWEEPS; i++) {
            scheduled[i] = lead + i * gap;
            at[i] = (int) scheduled[i] + RATE * 30 / 1000;
        }
        float[] rec = SimRoom.place(lead + SWEEPS * gap + RATE, room.response(0.3, GAIN, new Fft(32768)), at);
        room.addFans(rec, 10, GAIN);
        spoil.accept(rec);
        return rec;
    }

    @Test
    public void aMutedMicrophoneIsRefused() {
        double[] s = new double[SWEEPS];
        float[] rec = capture(1, x -> java.util.Arrays.fill(x, 0f), s);
        Probe.Result res = new Probe(RATE).measure(rec, s, WINDOW_MS);
        assertFalse(res.why, res.accepted);
    }

    @Test
    public void fansAloneAreRefused() {
        // The device did not play at all (muted, unplugged): the recording is only the room's noise.
        Random r = new Random(2);
        SimRoom room = new SimRoom(r);
        double[] s = {RATE / 2.0, RATE / 2.0 + 0.7 * RATE, RATE / 2.0 + 1.4 * RATE, RATE / 2.0 + 2.1 * RATE};
        float[] rec = new float[4 * RATE];
        java.util.Arrays.fill(rec, 0f);
        room.addFans(rec, -10, GAIN);   // as loud as a fan close by
        Probe.Result res = new Probe(RATE).measure(rec, s, WINDOW_MS);
        assertFalse(res.why, res.accepted);
    }

    @Test
    public void aDoorSlamOverOneSweepIsRefusedOrHarmless() {
        double[] s = new double[SWEEPS];
        float[] rec = capture(3, x -> {
            Random r = new Random(4);
            int at = (int) (RATE / 2 + 0.7 * RATE + 0.01 * RATE);   // during the second sweep
            for (int i = 0; i < RATE / 10; i++) x[at + i] += (float) (3 * r.nextGaussian() * Math.exp(-i / 800.0));
        }, s);
        Probe.Result res = new Probe(RATE).measure(rec, s, WINDOW_MS);
        assertTrue(res.why, !res.accepted || Math.abs(res.delayMs(RATE) - 30) < 0.5);
    }

    @Test
    public void someoneTalkingOverTheSweepsIsRefusedOrHarmless() {
        double[] s = new double[SWEEPS];
        float[] rec = capture(5, x -> {
            // Voiced speech, roughly: harmonics of a moving 120–220 Hz pitch up to 4 kHz, in syllables, loud.
            Random r = new Random(6);
            double phase = 0;
            for (int i = 0; i < x.length; i++) {
                double f = 170 + 50 * Math.sin(2 * Math.PI * 3 * i / RATE);
                phase += 2 * Math.PI * f / RATE;
                double syll = Math.max(0, Math.sin(2 * Math.PI * 4 * i / RATE));
                double v = 0;
                for (int h = 1; h * f < 4000; h++) v += Math.sin(h * phase) / h;
                x[i] += (float) (0.2 * syll * v + 0.01 * r.nextGaussian());
            }
        }, s);
        Probe.Result res = new Probe(RATE).measure(rec, s, WINDOW_MS);
        assertTrue(res.why, !res.accepted || Math.abs(res.delayMs(RATE) - 30) < 0.5);
    }

    @Test
    public void anEchoLouderThanTheDirectSoundIsNotTakenForIt() {
        // A far wall 25 ms later, 6 dB louder than the direct sound (the speaker faces away from the listener).
        Random r = new Random(7);
        SimRoom room = new SimRoom(r);
        room.reflDelay[0] = 0.025;
        room.reflGain[0] = 2;
        double[] s = {RATE / 2.0, RATE / 2.0 + 0.7 * RATE, RATE / 2.0 + 1.4 * RATE, RATE / 2.0 + 2.1 * RATE};
        int[] at = new int[SWEEPS];
        for (int i = 0; i < SWEEPS; i++) at[i] = (int) s[i] + RATE * 40 / 1000;
        float[] rec = SimRoom.place(4 * RATE, room.response(0, GAIN, new Fft(32768)), at);
        room.addFans(rec, 10, GAIN);
        Probe.Result res = new Probe(RATE).measure(rec, s, WINDOW_MS);
        assertTrue(res.why, res.accepted);
        assertEquals(40, res.delayMs(RATE), 0.5);
    }

    @Test
    public void theSweepIsInItsBandAndStartsAndEndsQuietly() {
        float[] s = Probe.sweep(RATE, Probe.F0, Probe.F1, Probe.MS, 0.3);
        assertEquals(RATE / 5, s.length);
        double peak = 0;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        assertEquals(0.3, peak, 0.01);
        assertEquals(0, s[0], 1e-6);
        assertEquals(0, s[s.length - 1], 1e-3);
        // Its energy is between 1.5 and 7 kHz.
        Fft f = new Fft(16384);
        double[] re = new double[16384], im = new double[16384];
        for (int i = 0; i < s.length; i++) re[i] = s[i];
        f.transform(re, im, false);
        double in = 0, all = 0;
        for (int k = 1; k < 8192; k++) {
            double p = re[k] * re[k] + im[k] * im[k], hz = k * (double) RATE / 16384;
            all += p;
            if (hz >= Probe.BAND_LO && hz <= Probe.BAND_HI) in += p;
        }
        assertTrue("in band " + in / all, in / all > 0.99);
    }
}
