// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.util.TreeMap;

/**
 * Plays decoded sound at {@code pts + D} on the sender's clock (docs: sync, 6.4), into any output that says when the
 * next frame written will be heard.
 *
 * <p>Chunks wait here until the output has room. Before each chunk the error {@code e = heard − wanted} is measured.
 * Beyond 20 ms (the start, a join, a change of D, an outage, a clock step) it is fixed at once by writing silence or
 * skipping sound, with a 3 ms fade on both sides. Below, a PI controller sets a rate of single frames to drop or
 * repeat, at most 0.1 % (inaudible), each time smoothing the neighbours. The rate also carries a feed-forward of the
 * drift already known (the DAC's crystal against this device's clock, this clock against the sender's), so the
 * controller only has to clean up what is left. A chunk not here when it is due becomes silence of its length: time
 * is never stretched to wait for it.
 *
 * <p>Pure code on 16-bit interleaved PCM; the simulator runs exactly this against fake outputs.
 */
public final class AudioPlayout {
    /** The output: an AudioTrack, or the simulator's model of one. */
    public interface Output {
        /** Local ns at which the next frame written will be heard (AudioClock, plus the platform's own guesses). */
        long hearNs();

        /** Frames written and not yet played. */
        int buffered();

        /** Takes {@code frames} frames; returns how many it took. */
        int write(short[] pcm, int frames);

        /** Frames per local second the output really plays (0 while not known): for the feed-forward. */
        double framesPerSecond();

        /** Whether {@link #hearNs} can be trusted yet (the track's clock is known, the sender's clock too). */
        boolean ready();
    }

    static final double KP = 0.1, KI = 0.005;   // s⁻¹, s⁻²
    static final double MAX_RATE = 0.001;       // 0.1 %
    static final long HARD_NS = 20_000_000L, DEAD_NS = 200_000L, FADE_NS = 3_000_000L, STEP_NS = 2_000_000L;

    private final int rate, channels, fadeFrames;
    private final TreeMap<Long, short[]> queue = new TreeMap<>();
    private final TreeMap<Long, Long> ptsOf = new TreeMap<>(), delayOf = new TreeMap<>();
    /** The playout delay D of the chunk being written (µs): each chunk carries its own, so a change hits one frame. */
    private long curDelay;
    private long nextSeq = -1;
    /** Chunks in a row that came after their time, for this long, start the playout again on what arrives. */
    static final long STALE_REALIGN_US = 1_000_000;
    private int staleRun;
    private boolean realign;
    private long lastDelay = -1;
    private short[] cur;
    private long curSeq, curPts;
    private int curPos, curFrames;
    private int silenceLeft, skipLeft, fadeInLeft, fadeOutLeft, fadeOutTotal;
    private long pendingNs;
    private boolean aligned;

    private double integral, s, acc;
    private long lastNs = Long.MIN_VALUE;
    private double lastErrorNs;
    private int hardResyncs, concealed, dropped, repeated;
    private long lastHardSeq = -1, lastHardErrorNs;
    private short[] work = new short[0];
    private final short[] prev;
    private boolean havePrev;

    public AudioPlayout(int rate, int channels) {
        this.rate = rate;
        this.channels = channels;
        this.fadeFrames = (int) (rate * FADE_NS / 1_000_000_000L);
        this.prev = new short[channels];
    }

    /** A decoded chunk: its frame sequence (unwrapped), its pts on the sender's clock (µs, unwrapped), its PCM. */
    public void push(long seq, long ptsUs, long delayUs, short[] pcm) {
        if (nextSeq >= 0 && seq < nextSeq) {
            // Too late: its time went by as silence. A second of them in a row (not a burst held by power save) means
            // D is too small for this receiver, until the sender raises it from this receiver's need: start again
            // from what arrives, with a hard resync.
            staleRun++;
            if (chunkUs <= 0 || staleRun * chunkUs < STALE_REALIGN_US) return;
            staleRun = 0;
            queue.clear();
            ptsOf.clear();
            delayOf.clear();
            silenceLeft = skipLeft = fadeOutLeft = 0;
            cur = null;
            nextSeq = seq;
            realign = true;
        }
        queue.put(seq, pcm);
        ptsOf.put(seq, ptsUs);
        delayOf.put(seq, delayUs);
        while (queue.size() > 400) {   // two seconds of 5 ms frames: something is badly off, forget the oldest
            Long first = queue.firstKey();
            queue.remove(first);
            ptsOf.remove(first);
            delayOf.remove(first);
        }
    }

    /** Forgets everything waiting and starts over (a new timeline, the sender restarted). */
    public void reset() {
        queue.clear();
        ptsOf.clear();
        delayOf.clear();
        nextSeq = -1;
        cur = null;
        silenceLeft = skipLeft = fadeInLeft = fadeOutLeft = 0;
        aligned = false;
        integral = s = acc = 0;
        havePrev = false;
    }

    /**
     * Fills the output up to {@code targetFrames} buffered. {@code wantNs} maps a time on the sender's clock (µs) to
     * the local ns at which it must be heard: {@code toLocal(t) + offset}; the chunk's own D is added here. {@code skew}
     * is the sender's clock against this one (ClockEstimator.skewPpm / 1e6), for the feed-forward. Until the output
     * is ready, silence keeps the track running, so that its clock starts.
     */
    public void fill(Output out, long nowNs, int targetFrames, PtsToLocal wantNs, double skew) {
        if (!aligned && (!out.ready() || queue.isEmpty())) {
            // Nothing to align to yet: silence keeps the track running.
            int room = targetFrames - out.buffered();
            if (room > 0) writeSilence(out, room);
            return;
        }
        int guard = 0;
        while (out.buffered() < targetFrames && guard++ < 64) {
            int room = targetFrames - out.buffered();
            if (silenceLeft > 0) {
                int n = Math.min(room, silenceLeft);
                n = writeSilence(out, n);
                silenceLeft -= n;
                if (n == 0) return;
                continue;
            }
            if (cur == null && !nextChunk(out, nowNs, wantNs)) return;
            if (cur == null) continue;   // a missing chunk became silence
            if (curPos == 0 && skipLeft == 0 && !beginChunk(out, nowNs, wantNs, skew)) continue;
            if (fadeOutLeft > 0) {
                // A hard resync: fade out over 3 ms of this chunk, then the silence or the skip.
                int n = Math.min(room, Math.min(fadeOutLeft, curFrames - curPos));
                int wrote = writeStuffed(out, n);
                if (wrote == 0) return;
                fadeOutLeft -= wrote;
                if (fadeOutLeft <= 0) adjust(pendingNs);
                if (curPos >= curFrames) cur = null;
                continue;
            }
            if (skipLeft > 0) {
                int n = Math.min(skipLeft, curFrames - curPos);
                curPos += n;
                skipLeft -= n;
                if (curPos >= curFrames) cur = null;
                continue;
            }
            int n = Math.min(room, curFrames - curPos);
            int wrote = writeStuffed(out, n);
            if (wrote == 0) return;
            if (curPos >= curFrames) cur = null;
        }
    }

    /** Maps a time on the sender's clock (µs) to the local ns at which it must be heard. */
    public interface PtsToLocal {
        long at(long senderPtsUs);
    }

    /** Takes the next chunk, or turns a missing one into silence when it is due; false to wait. */
    private boolean nextChunk(Output out, long nowNs, PtsToLocal want) {
        if (queue.isEmpty()) {
            if (!aligned || nextSeq < 0) return false;
            // Nothing here: the next chunk is late if it should already be in the output.
            return concealIfDue(out, want);
        }
        long first = queue.firstKey();
        if (nextSeq < 0) nextSeq = first;
        if (first == nextSeq) {
            staleRun = 0;
            cur = queue.remove(first);
            curPts = ptsOf.remove(first);
            curDelay = delayOf.remove(first);
            curSeq = first;
            curFrames = cur.length / channels;
            curPos = 0;
            nextSeq++;
            return true;
        }
        if (first < nextSeq) {   // stale
            queue.remove(first);
            ptsOf.remove(first);
            delayOf.remove(first);
            return true;
        }
        return concealIfDue(out, want);
    }

    /** The sender's chunk duration, from the pts of consecutive chunks (the first one may be short of frames). */
    private long chunkUs = -1, prevSeq = -1, prevPts;

    private boolean concealIfDue(Output out, PtsToLocal want) {
        if (chunkUs <= 0) return false;
        long missingPts = curPts + chunkUs;
        if (out.hearNs() < want.at(missingPts + curDelay) - HARD_NS) return false;   // still early enough to wait
        // Its time has come and it is not here: silence of its length, with a fade in after.
        silenceLeft = (int) Math.round(chunkUs * rate / 1e6);
        curPts = missingPts;
        nextSeq++;
        concealed++;
        fadeInLeft = fadeFrames;
        havePrev = false;
        return true;
    }

    /** Measures the error at a chunk's first frame and acts on it; false when it chose to write silence first. */
    private boolean beginChunk(Output out, long nowNs, PtsToLocal want, double skew) {
        long frames = curFrames;
        if (prevSeq >= 0 && curSeq == prevSeq + 1 && curPts > prevPts) chunkUs = curPts - prevPts;
        else if (chunkUs < 0) chunkUs = frames * 1_000_000L / rate;
        prevSeq = curSeq;
        prevPts = curPts;
        long e = out.hearNs() - want.at(curPts + curDelay);
        lastErrorNs = e;
        double dt = lastNs == Long.MIN_VALUE ? 0 : Math.min(1.0, (nowNs - lastNs) / 1e9);
        lastNs = nowNs;
        if (!aligned) {
            // The start: nothing was heard yet, so no fade out is needed.
            aligned = true;
            lastDelay = curDelay;
            adjust(e);
            return skipLeft > 0;   // skipping goes on with this chunk; silence comes before it
        }
        if (realign && Math.abs(e) <= HARD_NS) {
            realign = false;   // back on time by itself (D rose meanwhile)
        }
        // A new D is deliberate and reaches every device on the same frame: it is taken at once there, not slewed
        // in at 0.1 % for many seconds (unless it is below the PI's own reach).
        boolean newDelay = lastDelay >= 0 && curDelay != lastDelay && Math.abs(e) > STEP_NS;
        lastDelay = curDelay;
        if (Math.abs(e) > HARD_NS || realign || newDelay) {
            realign = false;
            hardResyncs++;
            lastHardSeq = curSeq;
            lastHardErrorNs = e;
            fadeOutLeft = Math.min(fadeFrames, curFrames);
            fadeOutTotal = fadeOutLeft;
            pendingNs = e;   // the same after the fade: heard and wanted move on together
            return true;
        }
        double es = e / 1e9;
        integral += es * dt;
        double iMax = MAX_RATE / KI;
        integral = Math.max(-iMax, Math.min(iMax, integral));
        double ff = 0;
        double r = out.framesPerSecond();
        if (r > 0) ff = 1 - (r / (1 + skew)) / rate;
        double p = Math.abs(e) < DEAD_NS ? 0 : KP * es;
        s = Math.max(-MAX_RATE, Math.min(MAX_RATE, ff + p + KI * integral));
        return true;
    }

    /** Writes silence for an early error, skips sound for a late one, and fades in after. */
    private void adjust(long e) {
        integral = 0;
        if (e < 0) silenceLeft = (int) Math.round(-e * rate / 1e9);
        else skipLeft = (int) Math.round(e * rate / 1e9);
        fadeInLeft = fadeFrames;
        havePrev = false;
    }

    private int writeSilence(Output out, int frames) {
        if (work.length < frames * channels) work = new short[frames * channels];
        java.util.Arrays.fill(work, 0, frames * channels, (short) 0);
        return out.write(work, frames);
    }

    /**
     * Writes up to {@code n} frames of the current chunk, dropping or repeating single frames as the rate says. A
     * dropped frame is folded into its predecessor (their mean), a repeated one is the mean of its neighbours.
     */
    private int writeStuffed(Output out, int n) {
        int max = n + 2;
        if (work.length < max * channels) work = new short[max * channels];
        int o = 0, used = 0;
        while (used < n) {
            int src = (curPos + used) * channels;
            acc += s;
            if (acc >= 1 && o > 0) {
                acc -= 1;
                for (int c = 0; c < channels; c++) {
                    work[(o - 1) * channels + c] = (short) ((work[(o - 1) * channels + c] + cur[src + c]) / 2);
                }
                used++;
                dropped++;
                continue;
            }
            if (acc <= -1) {
                acc += 1;
                for (int c = 0; c < channels; c++) {
                    int before = o > 0 ? work[(o - 1) * channels + c] : havePrev ? prev[c] : cur[src + c];
                    work[o * channels + c] = (short) ((before + cur[src + c]) / 2);
                }
                o++;
                repeated++;
            }
            System.arraycopy(cur, src, work, o * channels, channels);
            o++;
            used++;
        }
        if (fadeOutLeft > 0) {
            for (int i = 0; i < o; i++) {
                double g = Math.max(0, (fadeOutLeft - i - 1) / (double) fadeOutTotal);
                for (int c = 0; c < channels; c++) work[i * channels + c] = (short) (work[i * channels + c] * g);
            }
        } else if (fadeInLeft > 0) {
            for (int i = 0; i < o && fadeInLeft > 0; i++, fadeInLeft--) {
                double g = 1 - fadeInLeft / (double) fadeFrames;
                for (int c = 0; c < channels; c++) work[i * channels + c] = (short) (work[i * channels + c] * g);
            }
        }
        int took = out.write(work, o);
        if (took < o) {
            // The output took less than offered: count back what it did not take (rare; it said it had room).
            used = Math.max(0, used - (o - took));
        }
        if (took > 0) {
            System.arraycopy(work, (took - 1) * channels, prev, 0, channels);
            havePrev = true;
        }
        curPos += used;
        return took;
    }

    /** The error at the last chunk's start, ns (heard minus wanted; positive: late). */
    public double lastErrorNs() {
        return lastErrorNs;
    }

    /** The drop (+) or repeat (−) rate in use. */
    public double rate() {
        return s;
    }

    public int hardResyncs() {
        return hardResyncs;
    }

    /** The chunk at which the last hard resync happened (the same on every device for a change of D). */
    public long lastHardSeq() {
        return lastHardSeq;
    }

    /** The error that made the last hard resync, ns. */
    public long lastHardErrorNs() {
        return lastHardErrorNs;
    }

    public int concealed() {
        return concealed;
    }

    public int dropped() {
        return dropped;
    }

    public int repeated() {
        return repeated;
    }

    public boolean aligned() {
        return aligned;
    }

    /** The pts (µs, sender) of the frame about to be written next, or Long.MIN_VALUE before the first chunk. */
    public long nextPtsUs() {
        if (cur == null) return Long.MIN_VALUE;
        return curPts + (long) curPos * 1_000_000L / rate;
    }

    /** The sequence of the chunk being written. */
    public long currentSeq() {
        return curSeq;
    }
}
