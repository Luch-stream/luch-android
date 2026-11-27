// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import ru.toxblh.luch.sync.ClockEstimator;
import ru.toxblh.luch.sync.Window;
import ru.toxblh.luch.sync.Wire;

/**
 * The sync side of one session (PROTOCOL.md 4h): the sender's clock from kind 3 exchanges, this receiver's part of
 * the group, and what it reports in stats. It exists only when the sender's start has a "group": an older sender never
 * sees a clock request, and packets with D = 0 are shown as soon as possible, as before.
 */
final class SyncSession {
    final String id, role, mode;
    final double toleranceMs;
    final long timeline;
    /** This receiver's own correction (the slider or a calibration), ms, positive = later. */
    volatile double offsetMs;
    /** The panel's own delay after the display gets a frame (a calibration's result for the picture), ms. */
    volatile double panelMs;

    private final ClockEstimator est = new ClockEstimator();
    private DatagramSocket socket;
    private InetAddress to;
    private int port;

    /** Lag of each packet behind its pts (arrival − local(pts)), per kind, ms: for need_ms. */
    private final Window audioLag = new Window(2000), videoLag = new Window(600);
    /** Presented minus target over the last second, ms: the sound's (sync_err_ms) and the picture's (video_err_ms). */
    private final Window audioError = new Window(1000), videoError = new Window(200);
    /** Picture pts → display, ms (what an interactive sender waits the sound for). */
    private final Window videoPts = new Window(600);

    private SyncSession(JSONObject g) {
        id = g.optString("id");
        role = g.optString("role", "both");
        mode = g.optString("mode", "interactive");
        toleranceMs = g.optDouble("tolerance_ms", 20);
        timeline = g.optLong("timeline");
        offsetMs = g.optDouble("offset_ms", 0);
    }

    /** The session's sync part, or null for a sender that knows nothing of it. */
    static SyncSession from(JSONObject start) {
        JSONObject g = start.optJSONObject("group");
        return g == null ? null : new SyncSession(g);
    }

    /** What the welcome says this receiver can do (PROTOCOL.md 4h). */
    static JSONObject capabilities(boolean probe) throws JSONException {
        return new JSONObject().put("v", 1).put("clock", true).put("probe", probe).put("mic", false);
    }

    boolean wantsAudio() {
        return !"video".equals(role);
    }

    boolean wantsVideo() {
        return !"audio".equals(role);
    }

    /** Media came from the sender at this address and port: clock requests go there, on {@code s}. */
    synchronized void mediaFrom(DatagramSocket s, InetAddress a, int p) {
        if (socket != null) return;
        socket = s;
        to = a;
        port = p;
    }

    /** True for the socket that carries the clock exchanges (the audio one when there is audio). */
    synchronized boolean clockSocket(DatagramSocket s) {
        return socket == s;
    }

    /** Sends a request when the schedule says so (from the thread that reads the clock socket). */
    void poll(long nowUs) {
        byte[] req;
        DatagramSocket s;
        synchronized (this) {
            if (socket == null || nowUs < est.nextRequestUs()) return;
            req = Wire.request(est.sent(nowUs), nowUs);
            s = socket;
        }
        try {
            s.send(new DatagramPacket(req, req.length, to, port));
        } catch (IOException e) {
            Log.i(LuchService.TAG, "clock request: " + e);
        }
    }

    /** A packet on the clock socket: true when it was a clock reply (and is used up). */
    boolean onPacket(byte[] p, int len, long nowUs) {
        long[] t = Wire.reply(p, len);
        if (t == null) return false;
        synchronized (this) {
            est.onReply(t[0], t[1], t[2], nowUs);
        }
        return true;
    }

    synchronized boolean synced() {
        return est.synced();
    }

    synchronized void tick(long nowUs) {
        est.tick(nowUs);
    }

    synchronized void setPlaying(boolean playing) {
        est.setPlaying(playing);
    }

    /** Local µs at which the sender's clock reads {@code senderUs}. */
    synchronized long toLocal(long senderUs) {
        return est.toLocal(senderUs);
    }

    /** The full pts of a packet's low 32 bits. */
    synchronized long unwrap(int pts32, long nowUs) {
        return Wire.unwrapPts(pts32, est.toSender(nowUs));
    }

    synchronized double skew() {
        return est.skewPpm() / 1e6;
    }

    /** A packet of {@code kind} with full pts {@code pts} arrived at local µs {@code nowUs}. */
    void arrived(boolean audio, long pts, long nowUs) {
        double lag = (nowUs - toLocal(pts)) / 1000.0;
        Window w = audio ? audioLag : videoLag;
        synchronized (w) {
            w.add(lag);
        }
    }

    void presented(boolean audio, double errorMs) {
        Window w = audio ? audioError : videoError;
        synchronized (w) {
            w.add(errorMs);
        }
    }

    void videoShown(double ptsToDisplayMs) {
        synchronized (videoPts) {
            videoPts.add(ptsToDisplayMs);
        }
    }

    /**
     * Adds clock, need_ms, sync_err_ms and out.video_pts_ms to a stats line. {@code audioMs} and {@code videoMs} are
     * the receiver's own paths (arrival to ear, decoder input to display), NaN when unknown.
     */
    void stats(JSONObject s, JSONObject out, double audioMs, double videoMs) throws JSONException {
        double err, skew, rtt;
        synchronized (this) {
            if (!est.synced()) return;
            err = est.errUs();
            skew = est.skewPpm();
            rtt = est.minRttUs();
        }
        s.put("clock", new JSONObject().put("err_us", Math.round(err)).put("skew_ppm", Math.round(skew * 10) / 10.0)
                .put("rtt_us", Math.round(rtt)));
        double need = Double.NaN;
        if (wantsAudio() && !Double.isNaN(audioMs)) need = need(audioLag, audioMs);
        if (wantsVideo() && !Double.isNaN(videoMs)) {
            double v = need(videoLag, videoMs);
            need = Double.isNaN(need) ? v : Math.max(need, v);
        }
        if (!Double.isNaN(need)) s.put("need_ms", Math.round(need * 10) / 10.0);
        double ae = take(audioError), ve = take(videoError);
        double se = Double.isNaN(ae) ? ve : ae;
        if (!Double.isNaN(se)) s.put("sync_err_ms", Math.round(se * 100) / 100.0);
        if (out != null && !Double.isNaN(ve)) out.put("video_err_ms", Math.round(ve * 100) / 100.0);
        double vp;
        synchronized (videoPts) {
            vp = videoPts.median();
        }
        if (out != null && !Double.isNaN(vp)) out.put("video_pts_ms", Math.round(vp * 10) / 10.0);
    }

    private static double take(Window w) {
        synchronized (w) {
            double m = w.median();
            w.clear();
            return m;
        }
    }

    /** p99 of the packets' lag behind their pts over the last seconds, plus the output path and 3 ms. */
    private static double need(Window lag, double pathMs) {
        double p99;
        synchronized (lag) {
            p99 = lag.quantile(0.99);
        }
        return Double.isNaN(p99) ? Double.NaN : p99 + pathMs + 3;
    }
}
