// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/** An in-place radix-2 FFT of a power-of-two size, on separate real and imaginary arrays. No libraries in the APK. */
public final class Fft {
    private final int n;
    private final double[] cos, sin;
    private final int[] rev;

    public Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("not a power of two: " + n);
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / n);
            sin[i] = Math.sin(2 * Math.PI * i / n);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    public int size() {
        return n;
    }

    /** Forward (inverse = false) or inverse transform; the inverse divides by n. */
    public void transform(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        double sign = inverse ? 1 : -1;
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    double wr = cos[k * step], wi = sign * sin[k * step];
                    int a = i + k, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
