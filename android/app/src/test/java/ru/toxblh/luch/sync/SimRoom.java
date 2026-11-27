// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.Random;

/**
 * A room for the calibration's simulator (docs: sync, 8.1): what one microphone records while one device plays its
 * sweeps. The direct path and the early reflections are the sweep itself at their exact (fractional) delays; the late
 * reverberation an exponentially decaying noise convolved with it; then fans (pink noise and the harmonics of 120 Hz
 * hum) at a chosen SNR, and sometimes clipping. Everything in memory, nothing is played.
 */
final class SimRoom {
    static final int RATE = 48000;
    static final double SOUND = 343;   // m/s

    final Random r;
    final double distanceM, rt60, directToReverbDb;
    final double[] reflDelay, reflGain;   // after the direct path (s), and relative to it
    final double directGain;

    SimRoom(Random r) {
        this.r = r;
        distanceM = 1 + 5 * r.nextDouble();
        rt60 = 0.3 + 0.5 * r.nextDouble();
        directToReverbDb = -5 + 15 * r.nextDouble();
        directGain = 1 / distanceM;
        int n = 3 + r.nextInt(6);
        reflDelay = new double[n];
        reflGain = new double[n];
        for (int i = 0; i < n; i++) {
            reflDelay[i] = 0.0005 + 0.0195 * r.nextDouble();
            // Up to 6 dB stronger than the direct path (a speaker facing a wall, not the listener), either polarity.
            reflGain[i] = (r.nextBoolean() ? 1 : -1) * Math.pow(10, (-12 + 18 * r.nextDouble()) / 20);
        }
    }

    /** Travel time to the microphone, s. */
    double travel() {
        return distanceM / SOUND;
    }

    /** The sweep, continuous: its value at t seconds after its start (0 outside it), for exact fractional delays. */
    static double sweepAt(double t) {
        double t1 = Probe.MS / 1000, k = Math.log(Probe.F1 / Probe.F0), fade = 0.005;
        if (t < 0 || t >= t1) return 0;
        double g = 1;
        if (t < fade) g = 0.5 - 0.5 * Math.cos(Math.PI * t / fade);
        else if (t > t1 - fade) g = 0.5 - 0.5 * Math.cos(Math.PI * (t1 - t) / fade);
        return g * Math.sin(2 * Math.PI * Probe.F0 * t1 / k * (Math.exp(t / t1 * k) - 1));
    }

    /**
     * The room's response to one sweep, starting at sample 0 when the direct sound arrives {@code fraction} of a sample
     * after it: direct, early reflections, then the late tail.
     */
    float[] response(double fraction, double gain, Fft fft) {
        int m = (int) Math.round(RATE * Probe.MS / 1000), tail = (int) (0.3 * RATE);
        int len = m + tail;
        double[] y = new double[len];
        for (int i = 0; i < len; i++) {
            double t = (i - fraction) / RATE;
            double v = directGain * sweepAt(t);
            for (int k = 0; k < reflDelay.length; k++) v += directGain * reflGain[k] * sweepAt(t - reflDelay[k]);
            y[i] = gain * v;
        }
        // Late reverberation: noise decaying by 60 dB in rt60, from 5 ms on, at the chosen direct-to-reverberant ratio.
        int n = fft.size();
        double[] hr = new double[n], hi = new double[n];
        double energy = 0;
        int start = RATE * 5 / 1000;
        for (int i = start; i < tail; i++) {
            double t = (i - start) / (double) RATE;
            hr[i] = r.nextGaussian() * Math.pow(10, -3 * t / rt60);
            energy += hr[i] * hr[i];
        }
        double scale = directGain * Math.pow(10, -directToReverbDb / 20) / Math.sqrt(energy);
        double[] sr = new double[n], si = new double[n];
        float[] s = Probe.sweep(RATE, Probe.F0, Probe.F1, Probe.MS, 1);
        for (int i = 0; i < s.length; i++) sr[i] = s[i];
        fft.transform(hr, hi, false);
        fft.transform(sr, si, false);
        for (int k = 0; k < n; k++) {
            double a = hr[k] * sr[k] - hi[k] * si[k], b = hr[k] * si[k] + hi[k] * sr[k];
            hr[k] = a;
            hi[k] = b;
        }
        fft.transform(hr, hi, true);
        float[] out = new float[len];
        for (int i = 0; i < len; i++) out[i] = (float) (y[i] + gain * scale * hr[i]);
        return out;
    }

    /** A recording of {@code seconds}, the response added where each sweep's direct sound arrives (samples). */
    static float[] place(int samples, float[] response, int[] at) {
        float[] rec = new float[samples];
        for (int a : at) {
            for (int i = 0; i < response.length && a + i < samples; i++) if (a + i >= 0) rec[a + i] += response[i];
        }
        return rec;
    }

    /**
     * Fans: pink noise and 120 Hz hum with its harmonics, scaled so that the direct sweep's power over the noise's is
     * {@code snrDb} (both over the whole band).
     */
    void addFans(float[] rec, double snrDb, double sweepGain) {
        double signal = sq(sweepGain * directGain) / 2;
        double[] noise = new double[rec.length];
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, power = 0;
        double[] phase = new double[8];
        for (int h = 0; h < phase.length; h++) phase[h] = 2 * Math.PI * r.nextDouble();
        double humShare = 0.3 + 0.6 * r.nextDouble();
        for (int i = 0; i < rec.length; i++) {
            // Paul Kellet's pink filter.
            double w = r.nextGaussian();
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            double pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
            b6 = w * 0.115926;
            double hum = 0;
            for (int h = 1; h <= phase.length; h++) hum += Math.sin(2 * Math.PI * 120 * h * i / RATE + phase[h - 1]) / h;
            noise[i] = pink + humShare * 2 * hum;
            power += noise[i] * noise[i];
        }
        power /= rec.length;
        double scale = Math.sqrt(signal / power / Math.pow(10, snrDb / 10));
        for (int i = 0; i < rec.length; i++) rec[i] += (float) (scale * noise[i]);
    }

    /** White noise over the whole band (the sweep's band included: much harder than fans) at {@code snrDb}. */
    void addWhite(float[] rec, double snrDb, double sweepGain) {
        double signal = sq(sweepGain * directGain) / 2;
        double scale = Math.sqrt(signal / Math.pow(10, snrDb / 10));
        for (int i = 0; i < rec.length; i++) rec[i] += (float) (scale * r.nextGaussian());
    }

    /** A microphone overdriven: everything beyond {@code level} of the peak is cut off. */
    static void clip(float[] rec, double level) {
        double peak = 0;
        for (float v : rec) peak = Math.max(peak, Math.abs(v));
        float c = (float) (peak * level);
        for (int i = 0; i < rec.length; i++) rec[i] = Math.max(-c, Math.min(c, rec[i]));
    }

    private static double sq(double x) {
        return x * x;
    }
}
