// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Random;

import org.junit.Test;

/** The audio pass criteria of docs: sync, 8.1: devices of the hard mix playing one stream at pts + D. */
public class AudioSimTest {
    /** The run's length: 4 h as the document asks, shorter with LUCH_SIM_HOURS=0.25 while working on it. */
    static final double HOURS = System.getenv("LUCH_SIM_HOURS") == null ? 4
            : Double.parseDouble(System.getenv("LUCH_SIM_HOURS"));

    static SimDevice device(String name, long seed, double reportedMs, double hiddenMs, boolean timestamps,
                            boolean busy) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L);
        boolean ps = seed % 2 == 0;   // power save on the downlink of every other device
        return new SimDevice(name, r, 100, 100, reportedMs, hiddenMs, timestamps,
                SimGroup.net(r, busy, false), SimGroup.net(r, busy, ps), SimGroup.net(r, busy, ps));
    }

    /** The mix with timestamps everywhere and nothing hidden: what the mechanism itself achieves. */
    static SimGroup withTimestamps(long seed) {
        SimGroup g = new SimGroup();
        g.devices.add(device("phone", seed + 1, 15, 0, true, false));
        g.devices.add(device("tv", seed + 2, 40, 0, true, false));
        g.devices.add(device("projector", seed + 3, 30, 0, true, false));
        g.devices.add(device("bt speaker", seed + 4, 200, 0, true, false));
        return g;
    }

    @Test
    public void fourHoursWithTimestampsStayWithinAMillisecondAndDoNotDrift() {
        SimGroup g = withTimestamps(10);
        g.run(HOURS * 3600e6, 20e6, 1e6);
        double p99 = g.spreadQuantile(0.99), max = g.spreadQuantile(1);
        StringBuilder log = new StringBuilder(String.format(Locale.US,
                "%.2f h: inter-device spread p50 %.0f µs, p99 %.0f µs, max %.0f µs", HOURS,
                g.spreadQuantile(0.5), p99, max));
        for (int i = 0; i < g.devices.size(); i++) {
            SimDevice d = g.devices.get(i);
            double first = g.meanError(i, 0, 0.25), last = g.meanError(i, 0.75, 1);
            log.append(String.format(Locale.US, "; %s: mean %.0f→%.0f µs, rate ≤ %.0f ppm, %d resyncs, %d concealed, "
                            + "%d underruns, clock %.0f ppm, dac %.0f ppm", d.name, first, last, d.maxRate * 1e6,
                    d.playout.hardResyncs(), d.playout.concealed(), d.underruns, d.clock.skew * 1e6,
                    (d.dacRate / SimDevice.RATE - 1) * 1e6));
            assertTrue(log.toString(), Math.abs(last - first) < 1000);
            assertTrue(log.toString(), d.maxRate <= 0.001 + 1e-12);
            assertEquals(log.toString(), 0, d.playout.hardResyncs());
            assertEquals(log.toString(), 0, d.underruns);
        }
        System.out.println("audio " + log);
        assertTrue(log.toString(), p99 < 1000);
        assertTrue(log.toString(), max < 2000);
    }

    @Test
    public void aJoiningDeviceIsWithinAMillisecondThreeSecondsAfterItsFirstPacket() {
        SimGroup g = withTimestamps(20);
        SimDevice late = g.devices.get(3);
        g.joinAt.put(late, 60e6);
        g.run(180e6, 63e6, 0.25e6);
        int i = 3;
        double first = 0;
        for (int k = 0; k < Math.min(40, g.counts[i]); k++) first = Math.max(first, Math.abs(g.errors[i][k]));
        String log = String.format(Locale.US, "join: spread p99 %.0f µs from 3 s after the first packet, "
                + "the newcomer's worst |error| in its first 10 s %.0f µs", g.spreadQuantile(0.99), first);
        System.out.println("audio " + log);
        assertTrue(log, g.spreadQuantile(0.99) < 1000);
        for (SimDevice d : g.devices) assertEquals(log, 0, d.playout.hardResyncs());
    }

    @Test
    public void aStepOfDIsOneHardResyncPerDeviceOnTheSameFrame() {
        SimGroup g = withTimestamps(30);
        g.delay = t -> t < 100e6 ? 300_000 : 350_000;
        g.run(160e6, 110e6, 0.25e6);
        long seq = g.devices.get(0).playout.lastHardSeq();
        StringBuilder log = new StringBuilder(String.format(Locale.US, "D step 300→350 ms: spread p99 %.0f µs after",
                g.spreadQuantile(0.99)));
        for (SimDevice d : g.devices) {
            log.append(String.format(Locale.US, "; %s resynced %d× at chunk %d", d.name, d.playout.hardResyncs(),
                    d.playout.lastHardSeq()));
        }
        System.out.println("audio " + log);
        // The first chunk sent at or after 100 s: (100 s − P0) / 5 ms.
        assertEquals(log.toString(), (long) ((100e6 - SimGroup.P0) / 5000), seq);
        for (SimDevice d : g.devices) {
            assertEquals(log.toString(), 1, d.playout.hardResyncs());
            assertEquals(log.toString(), seq, d.playout.lastHardSeq());
        }
        assertTrue(log.toString(), g.spreadQuantile(0.99) < 1000);
    }

    @Test
    public void afterAnOutageItIsBackWithinAMillisecond() {
        SimGroup g = withTimestamps(40);
        for (SimDevice d : g.devices) {
            for (Sim.Net n : new Sim.Net[]{d.media, d.link.up, d.link.down}) {
                n.outageFrom = 100e6;
                n.outageTo = 103e6;
            }
        }
        g.run(180e6, 104e6, 0.25e6);
        StringBuilder log = new StringBuilder(String.format(Locale.US, "3 s outage: spread p99 %.0f µs from 1 s after",
                g.spreadQuantile(0.99)));
        for (SimDevice d : g.devices) log.append(String.format("; %s %d resyncs", d.name, d.playout.hardResyncs()));
        System.out.println("audio " + log);
        assertTrue(log.toString(), g.spreadQuantile(0.99) < 1000);
        // Three seconds of nothing are concealed with silence: time goes on, no resync is needed.
        for (SimDevice d : g.devices) assertTrue(log.toString(), d.playout.hardResyncs() <= 1);
    }

    @Test
    public void aTabletWithoutTimestampsIsOffOnlyByWhatItCannotSee() {
        // The head fallback guesses 20 ms after the head; this one really has 35: it must sit 15 ms late, steadily.
        SimGroup g = withTimestamps(50);
        SimDevice tablet = device("tablet", 55, 20, 15, false, false);
        g.devices.add(tablet);
        g.run(600e6, 30e6, 0.5e6);
        int i = g.devices.size() - 1;
        double mean = g.meanError(i, 0, 1), p99 = Sim.quantile(g.errors[i], g.counts[i], 0.99);
        String log = String.format(Locale.US, "tablet (head, 15 ms hidden): mean error %.0f µs, p99 %.0f µs, "
                + "rate ≤ %.0f ppm, src %s", mean, p99, tablet.maxRate * 1e6, tablet.audioClock.source());
        System.out.println("audio " + log);
        assertEquals(log, "head", tablet.audioClock.source());
        assertEquals(log, 15_000, mean, 1000);
    }

    /**
     * The whole mix of docs: sync, section 5, with what the platform cannot see: a TV's ARC soundbar (60 ms), a
     * projector's processing (80 ms), a tablet without timestamps (15 ms beyond its guess), a Bluetooth speaker whose
     * report misses 30 ms; each in its own place in the room. First as the platform reports it (the fallback), then
     * after one calibration: each device's sweeps through its room to one microphone, measured by Probe.
     */
    @Test
    public void theWholeMixIsWithinTwoMillisecondsAtTheMicrophoneOnceCalibrated() {
        String[] names = {"phone", "tv + soundbar", "projector", "tablet", "bt speaker"};
        double[] reported = {15, 40, 30, 20, 200}, hidden = {0, 60, 80, 15, 30};
        boolean[] ts = {true, true, true, false, true};
        SimRoom[] rooms = new SimRoom[names.length];
        java.util.function.LongFunction<SimGroup> mix = seed -> {
            SimGroup g = new SimGroup();
            for (int i = 0; i < names.length; i++) {
                g.devices.add(device(names[i], seed + i, reported[i], hidden[i], ts[i], false));
            }
            return g;
        };
        for (int i = 0; i < names.length; i++) rooms[i] = new SimRoom(new Random(900 + i));
        // Fallback: the platform's numbers only. At the microphone each device is off by what it hides plus its way.
        SimGroup fallback = mix.apply(60);
        fallback.run(300e6, 60e6, 0.5e6);
        double[] atMic = new double[names.length];
        StringBuilder log = new StringBuilder("whole mix, fallback (no calibration), error at the microphone:");
        for (int i = 0; i < names.length; i++) {
            atMic[i] = fallback.meanError(i, 0, 1) + rooms[i].travel() * 1e6;
            log.append(String.format(Locale.US, " %s %.1f ms;", names[i], atMic[i] / 1000));
        }
        double fallbackSpread = spread(atMic);
        log.append(String.format(Locale.US, " spread %.1f ms", fallbackSpread / 1000));
        // Calibration: what Probe measures from each device's recording; everyone is made as late as the slowest.
        Probe probe = new Probe(SimRoom.RATE);
        Fft fft = new Fft(32768);
        double[] c = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            double delayS = atMic[i] / 1e6;
            double[] sch = new double[4];
            int[] pos = new int[4];
            double exact = delayS * SimRoom.RATE;
            for (int k = 0; k < 4; k++) {
                sch[k] = SimRoom.RATE / 2 + k * (int) (0.7 * SimRoom.RATE);
                pos[k] = (int) Math.floor(sch[k] + exact);
            }
            float[] rec = SimRoom.place(4 * SimRoom.RATE, rooms[i].response(exact - Math.floor(exact), 0.3, fft), pos);
            rooms[i].addFans(rec, 5, 0.3);   // the projector's fan, the fridge: 5 dB
            Probe.Result res = probe.measure(rec, sch, 150);
            assertTrue(names[i] + ": " + res.why, res.accepted);
            c[i] = res.delayMs(SimRoom.RATE) * 1000;
        }
        double slowest = java.util.Arrays.stream(c).max().getAsDouble();
        SimGroup calibrated = mix.apply(60);
        for (int i = 0; i < names.length; i++) calibrated.devices.get(i).offsetNs = (slowest - c[i]) * 1000;
        calibrated.run(300e6, 60e6, 0.5e6);
        log.append("; calibrated:");
        double[] after = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            after[i] = calibrated.meanError(i, 0, 1) + rooms[i].travel() * 1e6;
            log.append(String.format(Locale.US, " %s %.2f ms;", names[i], after[i] / 1000));
        }
        double calSpread = spread(after);
        log.append(String.format(Locale.US, " spread %.2f ms (D + %.0f ms for everyone)", calSpread / 1000,
                slowest / 1000));
        System.out.println("audio " + log);
        assertTrue(log.toString(), calSpread < 2000);
        // The fallback's cost is what the platform does not see: here about the 80 ms of the projector.
        assertTrue(log.toString(), fallbackSpread > 60_000);
    }

    private static double spread(double[] v) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return hi - lo;
    }
}
