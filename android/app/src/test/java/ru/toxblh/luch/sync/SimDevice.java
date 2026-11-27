// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.PriorityQueue;
import java.util.Random;

/**
 * A receiver in the simulator: its own clock, its own Wi-Fi, a DAC on a crystal of its own, an output latency that the
 * platform reports (in getTimestamp) and one it hides (an ARC soundbar's DSP, a projector's processing). It runs the
 * real ClockEstimator, AudioClock and AudioPlayout. The PCM carries the number of each frame, so what comes out of
 * the DAC at any moment can be traced back to its pts, through every dropped or repeated frame.
 */
final class SimDevice implements AudioPlayout.Output {
    static final int RATE = 48000, CHUNK = 240, RING = 1 << 17;
    /** How much the loop keeps in the track: two chunks and 10 ms. */
    static final int TARGET = 2 * CHUNK + RATE / 100;

    final String name;
    final Sim.Clock clock;
    final double dacRate;            // frames per true second
    final double reportedUs, hiddenUs;
    final boolean timestamps;
    final Sim.Net media;
    final Sim.ClockLink link;
    final AudioClock audioClock = new AudioClock(RATE);
    final AudioPlayout playout = new AudioPlayout(RATE, 2);
    final Random r;
    /** The offset C this device applies (ns): a calibration's result, or 0. */
    double offsetNs;

    private double now = -1;
    private long written;
    private double start = -1;       // true µs at which the DAC took frame 0
    private final short[] ring = new short[2 * RING];
    int underruns;
    double maxRate;
    private final PriorityQueue<long[]> arrivals = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));

    SimDevice(String name, Random r, double clockPpm, double dacPpm, double reportedMs, double hiddenMs,
              boolean timestamps, Sim.Net up, Sim.Net down, Sim.Net media) {
        this.name = name;
        this.r = r;
        this.clock = Sim.Clock.random(r, clockPpm);
        this.dacRate = RATE * (1 + (r.nextDouble() * 2 - 1) * dacPpm * 1e-6);
        this.reportedUs = reportedMs * 1000;
        this.hiddenUs = hiddenMs * 1000;
        this.timestamps = timestamps;
        this.media = media;
        this.link = new Sim.ClockLink(r, clock, up, down);
    }

    /** The sender sent chunk {@code seq} (pts in true µs) at true time {@code sentAt}, with playout delay D. */
    void send(long seq, long ptsUs, long delayUs, double sentAt) {
        double d = media.delay(sentAt);
        if (d < 0) return;
        arrivals.add(new long[]{Math.round(sentAt + d + 500), seq, ptsUs, delayUs});   // + 0.5 ms to decode
    }

    private long localNs(double trueUs) {
        return clock.local(trueUs) * 1000;
    }

    private double dacPos(double t) {
        return start < 0 ? 0 : (t - start) * dacRate / 1e6;
    }

    /** One pass of the receiver's audio loop at true time t. */
    void step(double t) {
        now = t;
        link.advance(t);
        while (!arrivals.isEmpty() && arrivals.peek()[0] <= t) {
            long[] a = arrivals.poll();
            playout.push(a[1], a[2], a[3], pcmOf(a[1]));
        }
        // Underrun: the DAC stops where the data ends (a real track plays silence and its position stalls).
        if (start >= 0 && dacPos(t) > written) {
            underruns++;
            start = t - written * 1e6 / dacRate;
        }
        long local = localNs(t);
        if (start >= 0 && local >= audioClock.nextPollNs()) poll(t, local);
        link.est.setPlaying(playout.aligned());
        double skew = link.est.skewPpm() / 1e6;
        playout.fill(this, local, TARGET, s -> link.est.toLocal(s) * 1000 + Math.round(offsetNs), skew);
        maxRate = Math.max(maxRate, Math.abs(playout.rate()));
    }

    private void poll(double t, long local) {
        boolean ok = timestamps && t - start > 50_000;   // the HAL gives timestamps once the stream runs
        long f = (long) Math.max(0, dacPos(t) - RATE * 0.005);
        double presented = start + f * 1e6 / dacRate + reportedUs;
        long ts = localNs(presented) + Math.round((r.nextDouble() * 2 - 1) * 30_000);   // ±30 µs of HAL jitter
        audioClock.onTimestamp(ok, f, ts, local);
        if (!"timestamp".equals(audioClock.source())) audioClock.onHead((long) dacPos(t), local);
    }

    static short[] pcmOf(long seq) {
        short[] p = new short[2 * CHUNK];
        for (int i = 0; i < CHUNK; i++) {
            long idx = seq * CHUNK + i + 1;
            p[2 * i] = (short) (idx & 0xffff);
            p[2 * i + 1] = (short) (idx >>> 16);
        }
        return p;
    }

    /**
     * What is heard at true time t, as its error against {@code pts + D} (µs, positive: late), or NaN when it is
     * silence, a frame mixed by a drop, or out of reach.
     */
    double heardError(double t, java.util.function.LongUnaryOperator delayOfSeq) {
        if (start < 0) return Double.NaN;
        long k = (long) Math.floor((t - hiddenUs - reportedUs - start) * dacRate / 1e6);
        if (k < 1 || k >= written || k < written - RING + 1) return Double.NaN;
        long idx = idx(k), before = idx(k - 1);
        if (idx == 0 || before == 0 || idx - before < 0 || idx - before > 2) return Double.NaN;
        long seq = (idx - 1) / CHUNK, i = (idx - 1) % CHUNK;
        double pts = seq * 5000.0 + i * 1e6 / RATE + SimGroup.P0;
        // A frame's own position inside its DAC period: k is the frame being played at t.
        double frac = (t - hiddenUs - reportedUs - start) * dacRate / 1e6 - k;
        return t - frac * 1e6 / dacRate - (pts + delayOfSeq.applyAsLong(seq));
    }

    private long idx(long k) {
        int at = (int) (k % RING) * 2;
        return (ring[at] & 0xffffL) | ((ring[at + 1] & 0xffffL) << 16);
    }

    // ---- AudioPlayout.Output ----

    @Override
    public long hearNs() {
        return audioClock.hearNs(written, buffered(), localNs(now));
    }

    @Override
    public int buffered() {
        return (int) Math.max(0, written - (long) dacPos(now));
    }

    @Override
    public int write(short[] pcm, int frames) {
        if (start < 0) start = now;
        for (int i = 0; i < frames; i++) {
            int at = (int) ((written + i) % RING) * 2;
            ring[at] = pcm[2 * i];
            ring[at + 1] = pcm[2 * i + 1];
        }
        written += frames;
        return frames;
    }

    @Override
    public double framesPerSecond() {
        return "timestamp".equals(audioClock.source()) ? audioClock.framesPerSecond() : 0;
    }

    @Override
    public boolean ready() {
        return audioClock.valid() && link.est.synced();
    }
}
