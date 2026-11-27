// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/**
 * How a sender chooses the playout delay D of a group (docs: sync, 6.6), from what its receivers report once a second.
 * luchd has the same policy in Rust (luchd/src/sync.rs) and both are tested with the same vectors.
 *
 * <p>Watching: one D for picture and sound, the slowest receiver's need plus 2 ms, at least the floor (150 ms). It
 * rises at once; it falls by at most 1 ms a second, and only after 30 s with every need 10 ms below it, so that one
 * slow second does not move every device back and forth.
 *
 * <p>Interactive: the picture is shown as soon as possible (D_v = 0) and the sound waits for it: D_a is the later of
 * what the speakers need and when the TVs show a picture (pts → display). Only when a speaker needs more than the
 * picture plus the tolerance does the picture wait, and then only for the excess (D_v = need − tolerance). The same
 * rise-at-once, fall-slowly rule keeps D_a from moving with every second's median (here: 10 s, 5 ms).
 */
public final class SenderPolicy {
    public enum Mode { INTERACTIVE, WATCHING }

    private final Mode mode;
    private final double toleranceMs, floorMs;
    private double dv, da;
    private double lowSince = Double.NaN;
    private boolean first = true;

    /** {@code floorMs}: the least D while watching (150 by default); ignored while interactive. */
    public SenderPolicy(Mode mode, double toleranceMs, double floorMs) {
        this.mode = mode;
        this.toleranceMs = toleranceMs;
        this.floorMs = mode == Mode.WATCHING ? Math.max(floorMs, 0) : 0;
    }

    /**
     * One second's reports: {@code videoPtsMs}, each picture receiver's pts → display (median), and {@code needMs},
     * each receiver's need (watching: all of them; interactive: the sound receivers). Returns {D_v, D_a} in ms.
     */
    public double[] update(double nowS, double[] videoPtsMs, double[] needMs) {
        double need = max(needMs), video = max(videoPtsMs);
        double ta;   // the target before smoothing
        if (mode == Mode.WATCHING) {
            ta = Double.isNaN(need) ? floorMs : Math.max(floorMs, need + 2);
            double d = smooth(nowS, da, ta, 1, 30, 10);
            dv = da = d;
        } else {
            if (Double.isNaN(need)) need = 0;
            if (Double.isNaN(video)) video = 0;
            ta = Math.max(need, video);
            double d = smooth(nowS, da, ta, 1, 10, 5);
            // The picture waits only when the sound would trail it by more than the tolerance, and then for the excess.
            dv = d > video + toleranceMs ? d - toleranceMs : 0;
            da = d;
        }
        first = false;
        return new double[]{round(dv), round(da)};
    }

    /** Rises to the target at once (beyond {@code riseMs}); falls by 1 ms a second after {@code waitS} well below. */
    private double smooth(double now, double d, double target, double riseMs, double waitS, double belowMs) {
        if (first || target > d + riseMs || (target > d && d == 0)) {
            lowSince = Double.NaN;
            return target;
        }
        if (target < d - belowMs) {
            if (Double.isNaN(lowSince)) lowSince = now;
            if (now - lowSince >= waitS) return Math.max(target, d - 1);
        } else {
            lowSince = Double.NaN;
        }
        return d;
    }

    private static double max(double[] v) {
        double m = Double.NaN;
        if (v != null) for (double x : v) if (!Double.isNaN(x)) m = Double.isNaN(m) ? x : Math.max(m, x);
        return m;
    }

    private static double round(double x) {
        return Math.round(x * 10) / 10.0;
    }

    /** D in the packet header's unit (0.1 ms), clamped to what the field holds. */
    public static int headerUnits(double ms) {
        return (int) Math.max(0, Math.min(65535, Math.round(ms * 10)));
    }
}
