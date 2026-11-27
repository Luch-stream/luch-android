// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/**
 * When a frame written to an AudioTrack will be heard, from the track's own timestamps (docs: sync, 6.4).
 *
 * <p>{@code AudioTrack.getTimestamp} gives a frame position F and the local time T at which that frame is presented,
 * HAL and mixer included, which the buffer size alone does not tell. The next frame written (number W) is then heard at
 * {@code T + (W - F) / r}, where r is the DAC's own rate as seen by {@code System.nanoTime}: a crystal of its own, tens
 * of ppm off the nominal rate, so r is fitted over the points seen.
 *
 * <p>Routes that give no timestamp (some HDMI passthrough and Bluetooth stacks, very old HALs) fall back to the
 * playback head and a fixed guess for what lies after it ("head"). Pure code, times in local ns: tested on the JVM.
 */
public final class AudioClock {
    /** What lies after the playback head when the platform gives no timestamp: a guess (docs: sync, 6.4). */
    public static final long HEAD_GUESS_NS = 20_000_000L;
    /** A timestamp must advance this many times in a row before it counts. */
    private static final int ADVANCES = 2;
    /** No advancing timestamp after this long playing: the route has none. */
    private static final long GIVE_UP_NS = 2_000_000_000L;
    /** The fitted rate is trusted once its points span this long, and kept within this much of nominal. */
    private static final long FIT_SPAN_NS = 1_000_000_000L;
    private static final double MAX_PPM = 1000;
    private static final int POINTS = 16;

    private final int rate;
    private final double nominalNsPerFrame;
    private final long[] fs = new long[POINTS], ts = new long[POINTS];
    private int n, next;
    private long lastF = -1, lastT;
    private int advances;
    private boolean timestamps, head;
    private long firstPollNs = -1, lastPollNs;
    private double nsPerFrame;
    private long anchorF, anchorT;
    private int polls;

    public AudioClock(int rate) {
        this.rate = rate;
        this.nominalNsPerFrame = 1e9 / rate;
        this.nsPerFrame = nominalNsPerFrame;
    }

    /** "timestamp", "head", or null while neither is known yet. */
    public String source() {
        return timestamps ? "timestamp" : head ? "head" : null;
    }

    public boolean valid() {
        return timestamps || head;
    }

    /** The DAC's rate against the local clock, in ppm from nominal (0 until fitted). */
    public double ppm() {
        return (nominalNsPerFrame / nsPerFrame - 1) * 1e6;
    }

    /**
     * When to ask for the next timestamp: every 100 ms until it advances, then every second for 10 s (to fit the rate),
     * then every 10 s, as the platform documentation recommends.
     */
    public long nextPollNs() {
        if (!valid()) return lastPollNs + 100_000_000L;
        if (polls < 10) return lastPollNs + 1_000_000_000L;
        return lastPollNs + 10_000_000_000L;
    }

    /** Polls after a route change or an underrun: back to the warm-up schedule, keeping what is known. */
    public void rewarm() {
        polls = 0;
    }

    /**
     * One answer of getTimestamp. {@code ok} false when the call returned false (no timestamp yet, or none on this
     * route); {@code nowNs} is when it was asked.
     */
    public void onTimestamp(boolean ok, long framePos, long timeNs, long nowNs) {
        lastPollNs = nowNs;
        if (firstPollNs < 0) firstPollNs = nowNs;
        polls++;
        boolean advanced = ok && lastF >= 0 && framePos > lastF && timeNs > lastT;
        if (advanced) advances++;
        else if (ok && lastF >= 0) advances = 0;   // stalled: paused, or no clock behind this route
        if (ok) {
            lastF = framePos;
            lastT = timeNs;
        }
        if (!timestamps && advances >= ADVANCES) {
            timestamps = true;
            head = false;
            n = 0;
        }
        if (timestamps && advanced) addPoint(framePos, timeNs);
        if (!timestamps && !head && nowNs - firstPollNs > GIVE_UP_NS) head = true;
    }

    /**
     * The fallback without timestamps: frames the mixer has taken ({@code getPlaybackHeadPosition}, unwrapped) at
     * {@code nowNs}, heard a fixed guess later.
     */
    public void onHead(long headFrames, long nowNs) {
        if (timestamps) return;
        anchorF = headFrames;
        anchorT = nowNs + HEAD_GUESS_NS;
    }

    private void addPoint(long f, long t) {
        fs[next] = f;
        ts[next] = t;
        next = (next + 1) % POINTS;
        if (n < POINTS) n++;
        anchorF = f;
        anchorT = t;
        fit();
    }

    /** Least squares of T against F over the points kept: the slope is the DAC's ns per frame. */
    private void fit() {
        if (n < 3) return;
        int oldest = n < POINTS ? 0 : next;
        int newest = (next - 1 + POINTS) % POINTS;
        if (ts[newest] - ts[oldest] < FIT_SPAN_NS) return;
        double mf = 0, mt = 0;
        for (int i = 0; i < n; i++) {
            mf += fs[i] - fs[oldest];
            mt += ts[i] - ts[oldest];
        }
        mf /= n;
        mt /= n;
        double sff = 0, sft = 0;
        for (int i = 0; i < n; i++) {
            double df = fs[i] - fs[oldest] - mf, dt = ts[i] - ts[oldest] - mt;
            sff += df * df;
            sft += df * dt;
        }
        if (sff <= 0) return;
        double slope = sft / sff;
        double lim = nominalNsPerFrame * MAX_PPM / 1e6;
        nsPerFrame = Math.max(nominalNsPerFrame - lim, Math.min(nominalNsPerFrame + lim, slope));
    }

    /** Local time at which frame number {@code framesWritten} (the next one to write) will be heard. */
    public long hearNs(long framesWritten) {
        return anchorT + Math.round((framesWritten - anchorF) * nsPerFrame);
    }

    /** Local time at which a frame written now will be heard, minus now: the track's whole output latency. */
    public long latencyNs(long framesWritten, long nowNs) {
        return hearNs(framesWritten) - nowNs;
    }

    /** Frames the DAC plays per local second (the r of docs: sync, 6.4). */
    public double framesPerSecond() {
        return 1e9 / nsPerFrame;
    }

    public int rate() {
        return rate;
    }
}
