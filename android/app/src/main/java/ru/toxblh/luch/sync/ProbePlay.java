// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/**
 * One calibration's sweeps in the scheduled sound (PROTOCOL.md 4k, docs: sync, 6.7). Sweep i belongs at pts
 * {@code starts[i]} of the audio stream: it begins at the first frame written whose pts is at or after it, so it is
 * heard exactly as late as the program sound of that pts would be (pts + D, plus this receiver's offset, plus whatever
 * comes after the DAC: what the calibration measures). Once begun it plays out whole on consecutive frames, whatever
 * the playout does meanwhile (a dropped or repeated frame, a resync), so the sweep itself is never bent.
 *
 * <p>It replaces the program sound, crossfaded over the sweep's own 5 ms raised-cosine fades so there is no click.
 * When each sweep's first frame will be heard is taken from the output's own reckoning as it is written (AudioClock:
 * the track's timestamps, or the playback head and a guess), in local ns. Pure code, used from the audio thread only.
 */
public final class ProbePlay {
    /** The loudest a probe may be, of full scale: a group already plays at the TV's volume. */
    public static final double MAX_GAIN = 0.5;

    private final long[] starts;
    private final short[] pcm;
    private final float[] env;
    private final long[] heardNs;
    /** The sweep being played (-1: none) and its next sample; the sweeps begun so far. */
    private int pos = -1, begun;
    private long endNs = Long.MIN_VALUE;
    /** What {@link #mark} saved, for an output that took fewer frames than offered. */
    private int markPos, markBegun;
    private long markEndNs;

    /**
     * {@code n} sweeps f0 → f1 of {@code ms} at {@code gain} of full scale (capped at {@link #MAX_GAIN}), the first at
     * pts {@code at} (µs), each {@code ms + gapMs} after the one before.
     */
    public ProbePlay(int rate, double f0, double f1, double ms, double gain, long at, int n, long gapMs) {
        double g = Math.max(0, Math.min(MAX_GAIN, gain));
        float[] s = Probe.sweep(rate, f0, f1, ms, g);
        pcm = new short[s.length];
        for (int i = 0; i < s.length; i++) pcm[i] = (short) Math.round(s[i] * 32767);
        env = envelope(rate, s.length);
        starts = new long[n];
        long step = Math.round((ms + gapMs) * 1000);
        for (int i = 0; i < n; i++) starts[i] = at + i * step;
        heardNs = new long[n];
    }

    /** The fades of Probe.sweep: 0 → 1 over 5 ms, 1, then 1 → 0 over the last 5 ms. */
    static float[] envelope(int rate, int n) {
        int fade = rate * 5 / 1000;
        float[] e = new float[n];
        for (int i = 0; i < n; i++) {
            double g = 1;
            if (i < fade) g = 0.5 - 0.5 * Math.cos(Math.PI * i / fade);
            else if (i >= n - fade) g = 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / fade);
            e[i] = (float) g;
        }
        return e;
    }

    /** The first sweep's pts (µs): a probe whose time was already written is too late to play. */
    public long firstPtsUs() {
        return starts[0];
    }

    /** The last sweep's pts (µs). */
    public long lastPtsUs() {
        return starts[starts.length - 1];
    }

    public int sweeps() {
        return starts.length;
    }

    /** Frames of one sweep. */
    public int length() {
        return pcm.length;
    }

    /** Whether a sweep is being played or still to come: the playout must offer it every frame it writes. */
    public boolean active() {
        return pos >= 0 || begun < starts.length;
    }

    void mark() {
        markPos = pos;
        markBegun = begun;
        markEndNs = endNs;
    }

    void reset() {
        pos = markPos;
        begun = markBegun;
        endNs = markEndNs;
    }

    /**
     * Puts the sweeps into {@code frames} interleaved frames about to be written. Frame i carries the program sound of
     * pts {@code ptsUs[i]} (µs; NaN for silence that stands for no pts) and will be heard at {@code hearNs + i ·
     * nsPerFrame}. A null {@code work} only moves the probe on (frames already written).
     */
    void apply(short[] work, int channels, int frames, double[] ptsUs, long hearNs, double nsPerFrame) {
        for (int i = 0; i < frames; i++) {
            if (pos < 0) {
                if (begun >= starts.length || Double.isNaN(ptsUs[i]) || ptsUs[i] < starts[begun] - 1e-3) continue;
                pos = 0;
                heardNs[begun++] = hearNs + Math.round(i * nsPerFrame);
            }
            float keep = 1 - env[pos];
            short v = pcm[pos];
            for (int c = 0; work != null && c < channels; c++) {
                int k = i * channels + c;
                work[k] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(work[k] * keep) + v));
            }
            if (++pos == pcm.length) {
                pos = -1;
                endNs = hearNs + Math.round((i + 1) * nsPerFrame);
            }
        }
    }

    /** True once every sweep was written and the last one has been heard to its end (local ns). */
    public boolean done(long nowNs) {
        return !active() && nowNs >= endNs;
    }

    /** Local ns at which each sweep's first frame is heard, by the output's reckoning when it was written. */
    public long[] heardNs() {
        return heardNs.clone();
    }
}
