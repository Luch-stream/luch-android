// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.Arrays;

/** The last {@code capacity} values, for a median or a percentile once a second (the stats of docs: sync, 6.1). */
public final class Window {
    private final double[] v;
    private final double[] sorted;
    private int n, next;

    public Window(int capacity) {
        v = new double[capacity];
        sorted = new double[capacity];
    }

    public void add(double x) {
        v[next] = x;
        next = (next + 1) % v.length;
        if (n < v.length) n++;
    }

    public int size() {
        return n;
    }

    public void clear() {
        n = 0;
        next = 0;
    }

    /** The p-quantile (0..1) of what is kept, nearest rank; NaN when empty. */
    public double quantile(double p) {
        if (n == 0) return Double.NaN;
        System.arraycopy(v, 0, sorted, 0, n);
        Arrays.sort(sorted, 0, n);
        int i = (int) Math.ceil(p * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, i))];
    }

    public double median() {
        return quantile(0.5);
    }
}
