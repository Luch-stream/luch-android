// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The control channel of PROTOCOL.md: one JSON object per line, on two ports. The TLS port (v1) knows each sender by
 * its certificate: a paired one gets the welcome at once, an unknown one is asked about on the TV and pairs with the
 * code shown there (section 3a). The plain port (v0) keeps older senders working: each connection is asked about on
 * the TV, and until the person allows it, only hello and pings are answered. A thread per connection: senders are a
 * handful.
 */
final class ControlServer {
    private static final int MAX_LINE = 64 * 1024;
    private static final int IDLE_MS = 30_000;
    private static final int PING_MS = 10_000;
    static final int TLS_PORT = 47802;
    /** How long the TV asks "allow?", and how long the person has to type the code on the sender. */
    static final long ASK_MS = 60_000, CODE_MS = 120_000;
    /** What anyone may send before being let in. */
    private static final java.util.List<String> FREE = java.util.Arrays.asList("hello", "ping", "pong", "bye", "error");
    /** Who is asked about a "direct" request on the TV (PROTOCOL.md 4m). */
    private static final Direct.Asks DIRECT_ASKS = new Direct.Asks(System::currentTimeMillis);

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Socket> open = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Control channels of the connected senders, for results nobody asked for on that channel (auto bench). */
    static final Set<MirrorSession.Control> SENDERS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private ServerSocket socket, tlsSocket;
    private volatile boolean running;
    /** The TLS port, 0 while there is none (the keystore failed: then only the plain port works). */
    static volatile int tlsPort;
    private Identity identity;

    /** Who is paired; tests put in one with a clock of their own. */
    static volatile Gate gate;

    static Gate gate(Context context) {
        Gate g = gate;
        if (g != null) return g;
        synchronized (ControlServer.class) {
            if (gate == null) {
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences("luch_pairs", Context.MODE_PRIVATE);
                gate = new Gate(System::currentTimeMillis, new Gate.Store() {
                    @Override
                    public String load() {
                        return prefs.getString("gate", "");
                    }

                    @Override
                    public void save(String json) {
                        prefs.edit().putString("gate", json).apply();
                    }
                });
            }
            return gate;
        }
    }

    ControlServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Listens on the default port, or any free one if it is taken; returns the port, or -1. */
    int start() {
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            try {
                socket.bind(new InetSocketAddress(Luch.PORT));
            } catch (IOException taken) {
                socket = new ServerSocket(0);
            }
        } catch (IOException e) {
            Log.w(LuchService.TAG, "listen failed", e);
            return -1;
        }
        running = true;
        watchVolume();
        Thread accept = new Thread(() -> acceptLoop(socket, false), "luch-accept");
        accept.setDaemon(true);
        accept.start();
        startTls();
        return socket.getLocalPort();
    }

    /** The TLS port, with this TV's own key from the keystore; asks every sender for its certificate. */
    private void startTls() {
        try {
            identity = Identity.load(context, Identity.ALIAS);
            javax.net.ssl.SSLServerSocket ss = (javax.net.ssl.SSLServerSocket)
                    identity.tls().getServerSocketFactory().createServerSocket();
            ss.setReuseAddress(true);
            try {
                ss.bind(new InetSocketAddress(TLS_PORT));
            } catch (IOException taken) {
                ss.bind(new InetSocketAddress(0));
            }
            ss.setNeedClientAuth(true);
            tlsSocket = ss;
            tlsPort = ss.getLocalPort();
            Thread accept = new Thread(() -> acceptLoop(ss, true), "luch-accept-tls");
            accept.setDaemon(true);
            accept.start();
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
            Log.w(LuchService.TAG, "no TLS port, older senders only", e);
            tlsPort = 0;
        }
    }

    void stop() {
        running = false;
        tlsPort = 0;
        for (ServerSocket ss : new ServerSocket[]{socket, tlsSocket}) {
            try {
                if (ss != null) ss.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket s : open) closeQuietly(s);
    }

    private void acceptLoop(ServerSocket server, boolean secure) {
        while (running) {
            try {
                Socket s = server.accept();
                open.add(s);
                Thread t = new Thread(() -> serve(s, secure), "luch-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(LuchService.TAG, "accept", e);
            }
        }
    }

    /** Whether the sender's hello lists {@code what} in its "want". */
    private static boolean wants(JSONObject hello, String what) {
        org.json.JSONArray want = hello.optJSONArray("want");
        for (int i = 0; want != null && i < want.length(); i++) if (what.equals(want.optString(i))) return true;
        return false;
    }

    private void serve(Socket s, boolean secure) {
        String peerId = null;
        String peerName = null;
        String peerApp = "";
        boolean peerInput = false;
        // Over Bluetooth (BleLink's bridge on loopback): the device, not 127.0.0.1, is who this is and what counts.
        String ble = secure ? BleLink.via(s) : null;
        String address = ble != null ? ble : s.getInetAddress().getHostAddress();
        // Over a Wi-Fi Direct group of ours: it stays while this connection is open.
        Direct.Lifetime overGroup = DirectGroup.arrived(s.getLocalAddress());
        // This connection paired just now: its code on the TV was the person's yes to a "direct" request too.
        boolean freshPair = false;
        java.util.concurrent.atomic.AtomicReference<DirectAsk> askingDirect = new java.util.concurrent.atomic.AtomicReference<>();
        MirrorSession.Control control = null;
        // Let in: paired (TLS), or allowed on the TV for this connection (plain). Set from the prompt's thread.
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean();
        // The TV's question about an older sender, while it is up; whoever takes it out ends it.
        java.util.concurrent.atomic.AtomicReference<PairPrompt> asking = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            s.setTcpNoDelay(true);
            byte[] fp = null;
            if (secure) {
                // A client that never finishes the handshake does not keep a thread; Bluetooth is slower.
                s.setSoTimeout(ble != null ? 20_000 : 5_000);
                javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) s;
                tls.startHandshake();
                try {
                    fp = Identity.fingerprint((java.security.cert.X509Certificate) tls.getSession().getPeerCertificates()[0]);
                } catch (java.security.cert.CertificateEncodingException | ClassCastException e) {
                    throw new IOException("unreadable certificate", e);
                }
            }
            s.setSoTimeout(PING_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            // Callers may be on the main thread (the screen closing): the write goes to a thread of its own.
            control = msg -> WRITER.execute(() -> {
                try {
                    send(out, msg);
                } catch (IOException ignored) {
                    // the connection is going; its reader ends the session
                }
            });
            long lastHeard = System.currentTimeMillis();
            int pings = 0;
            while (running) {
                String line;
                try {
                    line = readLine(in);
                } catch (SocketTimeoutException quiet) {
                    if (System.currentTimeMillis() - lastHeard > IDLE_MS) break;
                    send(out, new JSONObject().put("t", "ping").put("n", ++pings));
                    continue;
                }
                if (line == null) break;
                lastHeard = System.currentTimeMillis();
                if (line.isEmpty()) continue;
                JSONObject msg;
                try {
                    msg = new JSONObject(line);
                } catch (JSONException bad) {
                    send(out, new JSONObject().put("t", "error").put("code", "bad_json"));
                    continue;
                }
                String t = msg.optString("t");
                if (!allowed.get() && !FREE.contains(t)) {
                    send(out, new JSONObject().put("t", "error").put("code", "not_allowed").put("for", t)
                            .put("detail", "allow it on the TV first"));
                    continue;
                }
                if (ble != null && !FREE.contains(t) && !Direct.MODE.equals(t)) {
                    // Over Bluetooth only the way to the network: nothing to show, no addresses to send media to.
                    send(out, new JSONObject().put("t", "error").put("code", "not_allowed").put("for", t)
                            .put("detail", "over Bluetooth only direct; join the network first"));
                    continue;
                }
                switch (t) {
                    case "hello": {
                        if (peerId != null) break;   // one hello per connection
                        // A probe is discovery asking who is here (PROTOCOL.md 1b): it gets the welcome and goes, and
                        // the TV says nothing about it. A pairing token from the QR code or the sound is checked
                        // either way, and used up by a real hello only (1e).
                        boolean probe = msg.optBoolean("probe");
                        String token = Card.Pairing.check(msg.optString("token", null), probe);
                        if (probe) {
                            JSONObject w = welcome(secure).put("probe", true);
                            if (token != null) w.put("token", token);
                            send(out, w);
                            break;
                        }
                        peerId = msg.optString("id", address);
                        if (control != null) SENDER_IDS.put(control, peerId);
                        peerName = msg.optString("name", address);
                        peerApp = msg.optString("app");
                        peerInput = wants(msg, "input");
                        boolean known = secure && gate(context).isPaired(Pairing.hex(fp));
                        if (secure && !admit(s, in, out, fp, peerId, peerName, address)) return;
                        freshPair = secure && !known;
                        JSONObject w = welcome(secure);
                        if (token != null) w.put("token", token);
                        send(out, w);
                        MirrorSession.Control ctl = control;
                        Luch.Peer peer = new Luch.Peer(peerId, peerName, address);
                        Runnable letIn = () -> {
                            allowed.set(true);
                            SENDERS.add(ctl);
                            Bench.autoRun(context, ControlServer::broadcastBench);
                            Luch.connected(peer);
                            say(context.getString(R.string.toast_connected, peer.name));
                        };
                        if (ble != null) {
                            // Only here to ask for "direct": not listed as connected, no bench, no reports.
                            allowed.set(true);
                        } else if (secure) {
                            letIn.run();
                            // Crash reports only to a paired sender: they say what this TV is and runs.
                            if (wants(msg, "reports")) {
                                org.json.JSONArray reports = Reports.pending(context);
                                if (reports.length() > 0) {
                                    send(out, new JSONObject().put("t", "reports").put("items", reports));
                                }
                            }
                        } else if (token != null) {
                            // The code from this TV's screen or speaker: someone in the room, as good as an Allow.
                            letIn.run();
                        } else {
                            // An older sender: the welcome as before, then the person decides on the TV.
                            askOld(s, out, peerName, address, letIn, asking);
                        }
                        Log.i(LuchService.TAG, "hello from " + peerName + " " + address + " app=" + msg.optString("app")
                                + (secure ? " (TLS)" : " (plain)") + (token == null ? "" : " token=" + token));
                        break;
                    }
                    case "ping":
                        send(out, new JSONObject().put("t", "pong").put("n", msg.optInt("n")));
                        break;
                    case "pong":
                    case "stats":
                        break;
                    case "reports_taken":
                        Reports.taken(context, msg.optJSONArray("ids"));
                        break;
                    case "error":
                        // The sender's answer to something of ours; answering it would only bounce errors back.
                        Log.i(LuchService.TAG, "sender says: " + msg);
                        break;
                    case "input": {
                        // The grant for this sender's session (4i); another sender's has no say over it.
                        MirrorSession m = MirrorSession.current;
                        if (m != null && m.control == control) m.input.onGrant(msg);
                        break;
                    }
                    case "probe":
                        // The acoustic calibration (PROTOCOL.md 4k): only as a dry run, and only when turned on.
                        send(out, Calibration.answer(context, msg));
                        break;
                    case "offset": {
                        MirrorSession m = MirrorSession.current;
                        if (m != null && m.control == control) m.onOffset(msg);
                        break;
                    }
                    case "sender_stats": {
                        MirrorSession m = MirrorSession.current;
                        if (m != null && m.control == control) m.onSenderStats(msg);
                        break;
                    }
                    case "start": {
                        if (Sender.active()) {
                            // This device is showing its own screen: one direction at a time.
                            send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", "start"));
                            break;
                        }
                        final Socket sock = s;
                        final MirrorSession.Control c = control;
                        final boolean sec = secure;
                        final String app = peerApp, name = peerName;
                        final boolean input = peerInput;
                        takeScreen(out, control, name, "start", msg,
                                m -> startSession(sock, out, c, sec, app, input, name, m));
                        break;
                    }
                    case "photo": {
                        if (Sender.active()) {
                            send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", "photo"));
                            break;
                        }
                        final MirrorSession.Control c = control;
                        takeScreen(out, control, peerName, "photo", msg, m -> showPhotos(out, c, address, m));
                        break;
                    }
                    case "media": {
                        if (Sender.active()) {
                            send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", "media"));
                            break;
                        }
                        final MirrorSession.Control c = control;
                        final String name = peerName;
                        takeScreen(out, control, peerName, "media", msg, m -> playMedia(out, c, address, name, m));
                        break;
                    }
                    case "media_cmd":
                        MediaCast.command(control, msg);
                        break;
                    case "now_playing": {
                        MirrorSession m = MirrorSession.current;
                        if (m == null || m.control != control) break; // only the sender of the running session
                        String art = msg.optString("art");
                        if (!art.isEmpty()) {
                            try {
                                MediaLink.check(art, address, false, MediaLink.SYSTEM_DNS);
                            } catch (MediaLink.BadLink bad) {
                                msg.remove("art");
                            }
                        }
                        Remote.nowPlaying(msg);
                        break;
                    }
                    case "volume": {
                        JSONObject v = Remote.setVolume(context, msg);
                        if (v == null) {
                            send(out, new JSONObject().put("t", "error").put("code", "no_volume").put("for", "volume"));
                        } else {
                            broadcastVolume(v);
                        }
                        break;
                    }
                    case "open_app": {
                        String err = Remote.openApp(context, msg);
                        if (err == null) {
                            send(out, new JSONObject().put("t", "app_opened").put("app", msg.optString("app")));
                        } else {
                            send(out, new JSONObject().put("t", "error").put("code", err).put("for", "open_app"));
                        }
                        break;
                    }
                    case "doc": {
                        if (Sender.active()) {
                            send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", "doc"));
                            break;
                        }
                        final MirrorSession.Control c = control;
                        takeScreen(out, control, peerName, "doc", msg, m -> showDoc(out, c, address, m));
                        break;
                    }
                    case "doc_page":
                        DocActivity.turnTo(control, msg.optInt("page"));
                        break;
                    case "pointer":
                    case "ink":
                    case "blank":
                        // Frequent and never answered (PROTOCOL.md 4h): only the sender of what is shown is obeyed.
                        InkView.deliver(control, msg);
                        break;
                    case "latency":
                        // The latency lab (PROTOCOL.md 4j): our own pattern over the mirrored picture.
                        MirrorActivity.setLatencyLab(control, msg.optBoolean("on"));
                        break;
                    case "stop":
                        MirrorSession.stopFromSender(control);
                        PhotoSession.closeFrom(control);
                        MediaCast.closeFrom(control);
                        DocActivity.closeFrom(control);
                        break;
                    case "bench": {
                        // Takes the hardware decoders for a few seconds: not while mirroring or already measuring.
                        if (MirrorSession.current != null || Bench.running()) {
                            send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", "bench"));
                            break;
                        }
                        MirrorSession.Control reply = control;
                        new Thread(() -> {
                            JSONObject r = Bench.run(context);
                            try {
                                reply.send(r.put("t", "bench_result"));
                            } catch (JSONException ignored) {
                            }
                        }, "luch-bench").start();
                        break;
                    }
                    case "sleep":
                        // Standby, as the remote's power key (SleepService). Answered first: the network may go
                        // with the screen.
                        if (SleepService.state(context).equals("on")) {
                            send(out, new JSONObject().put("t", "sleeping"));
                            main.post(() -> {
                                if (!SleepService.sleep()) Log.w(LuchService.TAG, "sleep: the service is not running");
                            });
                        } else {
                            send(out, new JSONObject().put("t", "error").put("code", "sleep_unavailable")
                                    .put("for", "sleep").put("sleep", SleepService.state(context)));
                        }
                        break;
                    case BleWake.MODE: {
                        // Another TV's remote, for a sender that cannot advertise itself (PROTOCOL.md 4l).
                        String why = BleWake.code(Nearby.unavailable(context));
                        if (why != null) {
                            send(out, BleWake.error(why, BleWake.detail(why)));
                            break;
                        }
                        BleWake w;
                        try {
                            w = BleWake.parse(msg);
                        } catch (BleWake.Refused r) {
                            send(out, r.answer());
                            break;
                        }
                        if (!BleWake.take()) {
                            send(out, BleWake.error("busy", "already sending one"));
                            break;
                        }
                        Log.i(LuchService.TAG, "waking a TV by Bluetooth for " + peerName + ", " + w.ms + " ms");
                        MirrorSession.Control reply = control;
                        Nearby.wake(context, w.data, w.ms, (code, detail) -> {
                            BleWake.release();
                            reply.send(code == null ? BleWake.ok() : BleWake.error(code, detail));
                        });
                        break;
                    }
                    case Direct.MODE: {
                        if (!secure) {
                            send(out, Direct.error("not_allowed", "only a paired sender, over TLS"));
                            break;
                        }
                        String why = DirectGroup.unavailable(context);
                        if (why != null) {
                            send(out, Direct.error("no_p2p", why));
                            break;
                        }
                        Log.i(LuchService.TAG, "direct asked by " + peerName + (ble != null ? " over Bluetooth" : ""));
                        if (freshPair) {
                            freshPair = false;
                            handOutGroup(control);
                            break;
                        }
                        String fpHex = Pairing.hex(fp);
                        String no = DIRECT_ASKS.take(fpHex, address);
                        if (no != null) {
                            send(out, Direct.error(no, "busy".equals(no) ? "the TV is asking about another one"
                                    : "refused on the TV a moment ago"));
                            break;
                        }
                        DirectAsk ask = new DirectAsk(control, fpHex, address, askingDirect);
                        askingDirect.set(ask);
                        ask.show(peerName);
                        break;
                    }
                    case "bye":
                        return;
                    default:
                        send(out, new JSONObject().put("t", "error").put("code", "unknown").put("for", t));
                        break;
                }
            }
        } catch (IOException | JSONException e) {
            Log.i(LuchService.TAG, "connection " + address + " ended: " + e);
        } finally {
            PairPrompt left = asking.getAndSet(null);
            if (left != null) {
                left.dismiss();   // the sender left while the TV was asking about it
                gate(context).promptEnded();
            }
            DirectAsk unanswered = askingDirect.getAndSet(null);
            if (unanswered != null) unanswered.end(false, null);   // the sender left while the TV was asking
            if (overGroup != null) overGroup.left();
            open.remove(s);
            closeQuietly(s);
            if (control != null) {
                SENDERS.remove(control);
                MirrorSession.stopFromSender(control);
                PhotoSession.closeFrom(control);
                MediaCast.closeFrom(control);
                DocActivity.closeFrom(control);
                MirrorActivity.setLatencyLab(control, false);
            }
            if (peerId != null && allowed.get() && ble == null) Luch.disconnected(peerId);
        }
    }

    // ---- who may come in (PROTOCOL.md, section 3a) ----

    /** Whether something is on the screen for a sender now: then an unknown one is asked about before any code. */
    private static boolean playing() {
        return MirrorSession.current != null || MediaCast.current != null || PhotoSession.current != null
                || DocActivity.current != null;
    }

    /**
     * A TLS sender's way in: paired, or asked about on the TV and paired with the code shown there. False: refused
     * (and told why); the connection is then closed by the caller.
     */
    private boolean admit(Socket s, BufferedReader in, OutputStream out, byte[] fpBytes, String id, String name,
            String address) throws IOException, JSONException {
        Gate g = gate(context);
        String fp = Pairing.hex(fpBytes);
        Gate.Verdict v = g.check(fp, address);
        if (v == Gate.Verdict.PAIRED) return true;
        if (v != Gate.Verdict.PROMPT) {
            Log.i(LuchService.TAG, "pairing from " + address + " not asked: " + v);
            refuse(out, v == Gate.Verdict.BANNED ? "banned" : "busy", g.retryMs(fp, address));
            return false;
        }
        java.util.concurrent.LinkedBlockingQueue<String> answers = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicBoolean codeShown = new java.util.concurrent.atomic.AtomicBoolean();
        // The next sender may be asked about as soon as the person has answered, not once this thread notices.
        java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable endPrompt = () -> {
            if (ended.compareAndSet(false, true)) g.promptEnded();
        };
        PairPrompt prompt = null;
        try {
            String code = Pairing.newCode();
            boolean askFirst = playing();
            prompt = PairPrompt.show(context, name, false, askFirst ? null : code, new PairPrompt.Answer() {
                @Override
                public void allow() {
                    answers.add("allow");
                }

                @Override
                public void deny() {
                    g.strike(fp, address);
                    stopAsking("denied");
                }

                @Override
                public void close() {
                    stopAsking("dismissed");
                }

                /** While the code is up the connection's thread waits on the sender: end it from here. */
                private void stopAsking(String reason) {
                    endPrompt.run();
                    answers.add(reason);
                    if (codeShown.get()) {
                        WRITER.execute(() -> {
                            refuseQuietly(out, reason);
                            closeQuietly(s);
                        });
                    }
                }
            });
            if (askFirst) {
                send(out, new JSONObject().put("t", "pair").put("step", "allow"));
                String a = awaitAnswer(s, in, answers);
                if (!"allow".equals(a)) {
                    refuse(out, a == null ? "timeout" : a, 0);
                    return false;
                }
                prompt.showCode(code);
            }
            // From here a "deny" on the TV closes the connection; one that came just before is seen here.
            codeShown.set(true);
            if (!answers.isEmpty()) {
                refuse(out, answers.peek(), 0);
                return false;
            }
            send(out, new JSONObject().put("t", "pair").put("step", "code").put("digits", Pairing.DIGITS));
            boolean right;
            try {
                right = rounds(s, in, out, new Pairing(Pairing.RECEIVER, code, fpBytes, identity.fingerprint));
            } catch (SocketTimeoutException slow) {
                refuse(out, "timeout", 0);
                return false;
            }
            if (!answers.isEmpty()) return false;   // denied on the TV meanwhile
            if (!right) {
                g.strike(fp, address);
                refuse(out, "wrong_code", g.retryMs(fp, address));
                return false;
            }
            g.pair(fp, id, name);
            send(out, new JSONObject().put("t", "paired"));
            say(context.getString(R.string.toast_paired, name));
            Log.i(LuchService.TAG, "paired with " + name + " " + address);
            return true;
        } catch (IOException e) {
            if (!answers.isEmpty()) return false;   // denied or closed on the TV: already told
            throw e;
        } finally {
            if (prompt != null) prompt.dismiss();
            endPrompt.run();
            if (!s.isClosed()) s.setSoTimeout(PING_MS);
        }
    }

    /** The first answer on the TV, or null after ASK_MS or when the sender goes away meanwhile. */
    private static String awaitAnswer(Socket s, BufferedReader in, java.util.concurrent.BlockingQueue<String> answers)
            throws IOException {
        s.setSoTimeout(300);
        long until = System.currentTimeMillis() + ASK_MS;
        while (answers.isEmpty() && System.currentTimeMillis() < until) {
            try {
                String line = readLine(in);
                if (line == null || line.contains("\"bye\"")) return null;   // the sender gave up
            } catch (SocketTimeoutException quiet) {
                // the person is still deciding
            }
        }
        return answers.poll();
    }

    /** The code check, one round per digit, the sender opening first (Pairing). */
    private static boolean rounds(Socket s, BufferedReader in, OutputStream out, Pairing p)
            throws IOException, JSONException {
        for (int r = 0; r < Pairing.DIGITS; r++) {
            // The first commitment comes when the person has typed the code; the rest follow at once.
            s.setSoTimeout(r == 0 ? (int) CODE_MS : 5_000);
            JSONObject c = expect(in, out, "pair_commit", r);
            send(out, new JSONObject().put("t", "pair_commit").put("round", r).put("c", p.commit(r)));
            JSONObject o = expect(in, out, "pair_open", r);
            if (!p.check(r, c.optString("c"), o.optString("n"))) return false;
            send(out, new JSONObject().put("t", "pair_open").put("round", r).put("n", p.open(r)));
        }
        return true;
    }

    /** The next pairing message, which must be {@code type} for {@code round}; pings are answered on the way. */
    private static JSONObject expect(BufferedReader in, OutputStream out, String type, int round)
            throws IOException, JSONException {
        while (true) {
            String line = readLine(in);
            if (line == null) throw new IOException("the sender left while pairing");
            if (line.isEmpty()) continue;
            JSONObject m = new JSONObject(line);
            String t = m.optString("t");
            if (t.equals("ping")) {
                send(out, new JSONObject().put("t", "pong").put("n", m.optInt("n")));
                continue;
            }
            if (t.equals(type) && m.optInt("round", -1) == round) return m;
            throw new IOException("pairing: expected " + type + " " + round + ", got " + t);
        }
    }

    /** {"t":"pair_refused"}: why the sender is not let in, and when it may ask again. */
    private static void refuse(OutputStream out, String reason, long retryMs) throws IOException, JSONException {
        JSONObject m = new JSONObject().put("t", "pair_refused").put("reason", reason);
        if (retryMs > 0) m.put("retry_s", (retryMs + 999) / 1000);
        send(out, m);
    }

    private static void refuseQuietly(OutputStream out, String reason) {
        try {
            refuse(out, reason, 0);
        } catch (IOException | JSONException ignored) {
            // gone already
        }
    }

    /**
     * An older sender on the plain port: it cannot pair, so the TV asks about every connection of it, and "allow"
     * lets that connection in. Refusals count against its address; a refused connection is closed.
     */
    private void askOld(Socket s, OutputStream out, String name, String address, Runnable letIn,
            java.util.concurrent.atomic.AtomicReference<PairPrompt> asking) {
        Gate g = gate(context);
        Gate.Verdict v = g.check(null, address);
        if (v != Gate.Verdict.PROMPT) {
            long wait = g.retryMs(null, address);
            WRITER.execute(() -> {
                try {
                    send(out, new JSONObject().put("t", "error").put("code", "not_allowed")
                            .put("detail", v == Gate.Verdict.BANNED
                                    ? "refused on the TV; try again in " + (wait + 59_999) / 60_000 + " min"
                                    : "the TV is asking about someone else; try again in " + (wait + 999) / 1000 + " s"));
                } catch (IOException | JSONException ignored) {
                }
                closeQuietly(s);
            });
            return;
        }
        Runnable timeout = () -> {
            PairPrompt p = asking.get();
            if (p != null) p.pressBack();
        };
        asking.set(PairPrompt.show(context, name, true, null, new PairPrompt.Answer() {
            @Override
            public void allow() {
                end();
                letIn.run();
            }

            @Override
            public void deny() {
                g.strike(null, address);
                end();
                refuseOld("refused on the TV");
            }

            @Override
            public void close() {
                end();
                refuseOld("not allowed on the TV");
            }

            private void end() {
                main.removeCallbacks(timeout);
                PairPrompt p = asking.getAndSet(null);
                if (p == null) return;
                p.dismiss();
                g.promptEnded();
            }

            private void refuseOld(String why) {
                WRITER.execute(() -> {
                    try {
                        send(out, new JSONObject().put("t", "error").put("code", "not_allowed").put("detail", why));
                    } catch (IOException | JSONException ignored) {
                    }
                    closeQuietly(s);
                });
            }
        }));
        main.postDelayed(timeout, ASK_MS);
    }

    // ---- "direct": a Wi-Fi Direct group for a sender with no network to this TV (PROTOCOL.md 4m) ----

    /** The group, or why not, to {@code reply}; the answer comes once the group is up. */
    private void handOutGroup(MirrorSession.Control reply) {
        DirectGroup.get(context, (g, code, detail) -> reply.send(g != null
                ? Direct.answer(g, LuchService.listening, tlsPort) : Direct.error(code, detail)));
    }

    /** One "direct" request asked about on the TV: Allow, Deny, Back or no answer in ASK_MS, answered once. */
    private final class DirectAsk implements PairPrompt.Answer, Runnable {
        private final MirrorSession.Control reply;
        private final String fp, address;
        private final java.util.concurrent.atomic.AtomicReference<DirectAsk> holder;
        private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile PairPrompt prompt;

        DirectAsk(MirrorSession.Control reply, String fp, String address,
                java.util.concurrent.atomic.AtomicReference<DirectAsk> holder) {
            this.reply = reply;
            this.fp = fp;
            this.address = address;
            this.holder = holder;
        }

        void show(String name) {
            prompt = PairPrompt.ask(context, name, context.getString(R.string.direct_ask, name), this);
            main.postDelayed(this, ASK_MS);
        }

        @Override
        public void allow() {
            if (end(false, null)) handOutGroup(reply);
        }

        @Override
        public void deny() {
            end(true, "refused on the TV");
        }

        @Override
        public void close() {
            end(false, "closed on the TV");
        }

        /** Nobody answered. */
        @Override
        public void run() {
            if (end(false, null)) reply.send(Direct.error("timeout", "nobody answered on the TV"));
        }

        /** Takes the card down; {@code denied} non-null is told to the sender. False when it had ended already. */
        boolean end(boolean deny, String denied) {
            if (!ended.compareAndSet(false, true)) return false;
            main.removeCallbacks(this);
            holder.compareAndSet(this, null);
            PairPrompt p = prompt;
            if (p != null) p.dismiss();
            DIRECT_ASKS.answered(fp, address, deny);
            if (denied != null) reply.send(Direct.error("denied", denied));
            return true;
        }
    }

    /** The volume, after a change from a sender or from the remote, to every connected sender. */
    static void broadcastVolume(JSONObject v) {
        try {
            JSONObject msg = new JSONObject(v.toString()).put("t", "volume");
            for (MirrorSession.Control c : SENDERS) c.send(msg);
        } catch (JSONException ignored) {
        }
    }

    /** Volume changes made with the remote, passed on (at most every 200 ms, the bar sends many). */
    private void watchVolume() {
        volumeTell = () -> {
            JSONObject v = Remote.volume(context);
            if (v != null && !SENDERS.isEmpty()) broadcastVolume(v);
        };
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return;
                main.removeCallbacks(volumeTell);
                main.postDelayed(volumeTell, 200);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(r, f);
    }

    private Runnable volumeTell;

    /** A finished bench goes to every connected sender. */
    static void broadcastBench(JSONObject result) {
        try {
            JSONObject msg = new JSONObject(result.toString()).put("t", "bench_result");
            for (MirrorSession.Control c : SENDERS) c.send(msg);
        } catch (JSONException ignored) {
        }
    }

    private JSONObject welcome(boolean secure) throws JSONException {
        return new JSONObject()
                .put("t", "welcome")
                // v1 over TLS; an older sender on the plain port hears v0 and where the TLS port is.
                .put("v", secure ? Luch.VERSION_TLS : Luch.VERSION)
                .put("tls_port", tlsPort)
                .put("id", Luch.id(context))
                .put("name", Luch.name(context))
                .put("model", Luch.model())
                .put("decoders", new JSONArray(Luch.decoders()))
                .put("max", Luch.maxSize())
                .put("modes", Direct.withMode(BleWake.withMode(new JSONArray().put("mirror").put("photo").put("media")
                        .put("audio").put("doc").put("ink").put("latency"), Nearby.unavailable(context)),
                        DirectGroup.unavailable(context)))
                // The TV's own apps it can open with a link (PROTOCOL.md, section 4f).
                .put("apps", Remote.apps(context))
                // What "media" plays here (PROTOCOL.md, section 4b).
                .put("media_types", new JSONArray(java.util.Arrays.asList(MediaLink.TYPES)))
                // Whether {"t":"sleep"} works here: "on", "off" (not turned on in Accessibility), "no" (Android < 9).
                .put("sleep", SleepService.state(context))
                // The music volume, when apps may set it (4e).
                .put("volume", Remote.volume(context) == null ? JSONObject.NULL : Remote.volume(context))
                // Android version, and what works worse on it than on 8.0+ (PROTOCOL.md, section 5).
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("limits", Caps.limits())
                // Playing in step with other devices (PROTOCOL.md, section 4k).
                .put("sync", SyncSession.capabilities(Calibration.enabled(context)))
                // What the hardware says it can do, and what it measured (null until the bench has run).
                .put("hw", Caps.collect(context))
                // What could control the sender from here (4i).
                .put("input", Caps.input(context))
                .put("touch_points", Caps.touchPoints(context))
                .put("bench", Bench.cached(context) == null ? JSONObject.NULL : Bench.cached(context));
    }

    /** A line of at most MAX_LINE characters; longer ones end the connection. */
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }


    /** A mirroring or sound-only session from this connection (PROTOCOL.md 4, 4d). */
    private void startSession(Socket s, OutputStream out, MirrorSession.Control control, boolean secure, String peerApp,
            boolean peerInput, String peerName, JSONObject msg) throws IOException, JSONException {
        if ("audio".equals(msg.optString("mode")) && msg.optJSONObject("audio") == null) {
            send(out, new JSONObject().put("t", "error").put("code", "start_failed")
                    .put("detail", "mode audio needs an audio block"));
            return;
        }
        if (!"mirror".equals(msg.optString("mode")) && !"audio".equals(msg.optString("mode"))) {
            send(out, new JSONObject().put("t", "error").put("code", "start_failed")
                    .put("detail", "mode " + msg.optString("mode") + " not supported"));
            return;
        }
        try {
            msg.put("_app", peerApp).put("_input", peerInput).put("_name", peerName);
            // Over TLS the media is sealed with a key of this session, given only in the ready.
            byte[] key = secure ? MediaCrypt.newKey() : null;
            int[] ports = MirrorSession.start(context, control, msg, s.getInetAddress(), key);
            JSONObject ready = new JSONObject().put("t", "ready")
                    .put("video_port", ports[0]).put("audio_port", ports[1]);
            if (key != null) {
                ready.put("crypt", "aes-128-gcm")
                        .put("key", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP));
            }
            send(out, ready);
            Log.i(LuchService.TAG, "mirror from " + peerName + ": " + msg);
        } catch (IOException | RuntimeException e) {
            send(out, new JSONObject().put("t", "error").put("code", "start_failed")
                    .put("detail", String.valueOf(e)));
        }
    }

    private void showPhotos(OutputStream out, MirrorSession.Control control, String address, JSONObject msg)
            throws IOException, JSONException {
        try {
            PhotoSession.open(context, control, address, msg);
            send(out, new JSONObject().put("t", "photo_ready"));
        } catch (IOException | RuntimeException e) {
            send(out, new JSONObject().put("t", "error").put("code", "photo_failed")
                    .put("detail", String.valueOf(e.getMessage())));
        }
    }

    private void playMedia(OutputStream out, MirrorSession.Control control, String address, String peerName,
            JSONObject msg) throws IOException, JSONException {
        try {
            MediaCast.open(context, control, address, msg);
            send(out, new JSONObject().put("t", "media_ready"));
            Log.i(LuchService.TAG, "media from " + peerName);
        } catch (MediaLink.BadLink e) {
            send(out, new JSONObject().put("t", "error").put("code", "bad_link").put("for", "media")
                    .put("detail", e.getMessage()));
        } catch (MediaCast.NoSource | RuntimeException e) {
            send(out, new JSONObject().put("t", "error").put("code", "media_failed").put("for", "media")
                    .put("detail", String.valueOf(e.getMessage())));
        }
    }

    private void showDoc(OutputStream out, MirrorSession.Control control, String address, JSONObject msg)
            throws IOException, JSONException {
        try {
            DocActivity.open(context, control, address, msg);
        } catch (MediaLink.BadLink e) {
            send(out, new JSONObject().put("t", "error").put("code", "bad_link").put("for", "doc")
                    .put("detail", e.getMessage()));
        }
    }

    // ---- who may take the screen ----------------------------------------------------------------------------

    /** The computer (its hello's id) behind each connection: one computer's mirror and cast connections are one. */
    private static final java.util.Map<MirrorSession.Control, String> SENDER_IDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    interface Show {
        void run(JSONObject msg) throws IOException, JSONException;
    }

    /** The connection whose show is on the screen (mirroring, photos, a video, a document), or null. */
    static MirrorSession.Control onScreen() {
        MirrorSession m = MirrorSession.current;
        if (m != null && !m.ended()) return m.control;
        PhotoSession p = PhotoSession.current;
        if (p != null) return p.control;
        MediaCast c = MediaCast.current;
        if (c != null) return c.control;
        DocActivity.Doc d = DocActivity.current;
        return d == null ? null : d.control;
    }

    /** What is on the screen, by the message that started it: "start", "photo", "media", "doc" or null. */
    static String onScreenKind() {
        MirrorSession m = MirrorSession.current;
        if (m != null && !m.ended()) return "start";
        if (PhotoSession.current != null) return "photo";
        if (MediaCast.current != null) return "media";
        return DocActivity.current != null ? "doc" : null;
    }

    /** Whether two connections are the same computer's. */
    static boolean sameSender(MirrorSession.Control a, MirrorSession.Control b) {
        if (a == b) return true;
        String x = SENDER_IDS.get(a);
        return x != null && x.equals(SENDER_IDS.get(b));
    }

    /**
     * A show from {@code control}: with nothing on the screen, or with this computer's own show on it, it simply goes
     * on (the old one ends: the same person wants something else shown). Another computer's show is on: the TV asks
     * whether to let this one take over; Deny, Back or no answer is "busy", as before.
     */
    private void takeScreen(OutputStream out, MirrorSession.Control control, String name, String what, JSONObject msg,
            Show show) throws IOException, JSONException {
        MirrorSession.Control shown = onScreen();
        if (shown == null || sameSender(shown, control)) {
            // The same connection's same kind of show replaces itself (another quality, a new screen mode, the next
            // photos): that goes through as before. Anything else of this computer's ends what is shown first.
            if (shown != null && !(shown == control && what.equals(onScreenKind()))) clearScreen();
            show.run(msg);
            return;
        }
        PairPrompt.ask(context, name, context.getString(R.string.takeover_ask, name), new PairPrompt.Answer() {
            @Override
            public void allow() {
                new Thread(() -> {
                    try {
                        clearScreen();
                        show.run(msg);
                    } catch (IOException | JSONException ignored) {
                        // the connection went while the TV was asking
                    }
                }, "luch-takeover").start();
            }

            @Override
            public void deny() {
                busy();
            }

            @Override
            public void close() {
                busy();
            }

            private void busy() {
                try {
                    send(out, new JSONObject().put("t", "error").put("code", "busy").put("for", what)
                            .put("detail", "refused on the TV"));
                } catch (IOException | JSONException ignored) {
                }
            }
        });
    }

    /** Ends what is on the screen to make room; a show of another connection is told it was replaced. */
    private static void clearScreen() {
        MirrorSession m = MirrorSession.current;
        if (m != null && !m.ended()) m.stopByReceiver("replaced");
        PhotoSession p = PhotoSession.current;
        if (p != null) {
            tellReplaced(p.control);
            PhotoSession.closeFrom(p.control);
        }
        MediaCast c = MediaCast.current;
        if (c != null) {
            tellReplaced(c.control);
            MediaCast.closeFrom(c.control);
        }
        DocActivity.Doc d = DocActivity.current;
        if (d != null) {
            tellReplaced(d.control);
            DocActivity.closeFrom(d.control);
        }
    }

    private static void tellReplaced(MirrorSession.Control c) {
        try {
            c.send(new JSONObject().put("t", "stop").put("by", "receiver").put("reason", "replaced"));
        } catch (JSONException ignored) {
        }
    }

    private static void send(OutputStream out, JSONObject msg) throws IOException {
        synchronized (out) {
            out.write((msg.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void say(String text) {
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
