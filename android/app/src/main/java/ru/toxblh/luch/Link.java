// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * This phone's control connection to a receiver, as a sender (PROTOCOL.md 3, 3a): TLS with this phone's own
 * certificate when the receiver has a TLS port, paired once with the code the TV shows and pinned from then on, so a
 * paired phone is let in without a question on the TV; the plain port, asked about on the TV every time, only for a
 * receiver without TLS that was never paired. A paired receiver is never reached over the plain port: no TLS where
 * there was TLS is another device under its name. The protocol part is plain Java over any socket (JVM tests play
 * the receiver); {@link #open} is the Android part.
 */
final class Link implements Closeable {
    /** The sender's key in the keystore, apart from the receiver's own. */
    static final String ALIAS = "luch-sender";
    /** How long a plain connection waits for its Allow on the TV (the TV asks for 60 s). */
    static final long ALLOW_WAIT_MS = 65_000;
    /** How long a pairing may wait for the person: the Allow (60 s), then the code (2 min). */
    private static final long PAIR_WAIT_MS = 190_000;
    private static final long ANSWER_MS = 8_000;

    /** What a connection needs from the person. */
    interface Ui {
        /** The TV asks its person first: a plain connection's Allow, or a pairing's Allow while something plays. */
        void allowOnTv();

        /** The six digits the TV shows, typed on this phone; null when the person gave up. */
        String code() throws InterruptedException;
    }

    /** The receivers' pinned certificates, by receiver id: SHA-256 of the DER, in hex. */
    interface Pins {
        String get(String receiverId);

        void put(String receiverId, String fingerprint);
    }

    /** The receiver (or the person) said no; {@code reason} as in pair_refused, or "not_allowed", "other_device". */
    static final class Refused extends IOException {
        final String reason;
        final int retryS;

        Refused(String reason, String detail, int retryS) {
            super(detail == null || detail.isEmpty() ? reason : detail);
            this.reason = reason;
            this.retryS = retryS;
        }
    }

    /** The receiver answered a request with an error. */
    static final class Failure extends IOException {
        final String code;

        Failure(String code, String detail) {
            super(detail == null || detail.isEmpty() ? code : code + ": " + detail);
            this.code = code;
        }
    }

    final boolean secure;
    final byte[] ownFp, peerFp;
    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;
    private final ArrayDeque<JSONObject> held = new ArrayDeque<>();
    /** The receiver's welcome; whether it let this connection in (paired, or a request of a plain one answered). */
    volatile JSONObject welcome;
    volatile boolean letIn;
    /** Whether this connection paired just now (the person typed the code). */
    boolean pairedNow;
    /** Whether {@link #next} returns pongs too: for a sender that pings to measure the round trip. */
    volatile boolean passPongs;

    /** Over {@code s}; {@code peerFp} is the receiver's certificate as seen in the TLS handshake, null on the plain port. */
    Link(Socket s, byte[] ownFp, byte[] peerFp) throws IOException {
        this.socket = s;
        this.secure = peerFp != null;
        this.ownFp = ownFp;
        this.peerFp = peerFp;
        s.setTcpNoDelay(true);
        out = s.getOutputStream();
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Says {@code hello} (its t, id, name, app and want; v and pair are set here) and goes through the pairing when the
     * receiver asks for it; returns the welcome. {@code receiverId}: whose pin this connection is held to.
     */
    JSONObject hello(JSONObject hello, String receiverId, Pins pins, Ui ui)
            throws IOException, JSONException, InterruptedException {
        String pinned = pins.get(receiverId);
        if (!secure && pinned != null) {
            throw new Refused("other_device", "this TV was paired, and now it has no encryption: another device?", 0);
        }
        if (secure && pinned != null && !pinned.equals(Pairing.hex(peerFp))) {
            throw new Refused("other_device", "not the certificate this TV was paired with: another device?", 0);
        }
        hello.put("v", secure ? Luch.VERSION_TLS : Luch.VERSION);
        if (secure && pinned == null) hello.put("pair", true);
        send(hello);
        long wait = ANSWER_MS;
        while (true) {
            JSONObject m = next(wait);
            switch (m.optString("t")) {
                case "welcome":
                    welcome = m;
                    if (secure) {
                        letIn = true;
                        if (pairedNow) {
                            pins.put(receiverId, Pairing.hex(peerFp));
                            String id = m.optString("id", receiverId);
                            if (!id.equals(receiverId)) pins.put(id, Pairing.hex(peerFp));
                        }
                    }
                    return m;
                case "pair":
                    wait = PAIR_WAIT_MS;
                    if ("allow".equals(m.optString("step"))) {
                        ui.allowOnTv();
                    } else if ("code".equals(m.optString("step"))) {
                        String code = ui.code();
                        if (code == null) throw new Refused("cancelled", null, 0);
                        typeCode(code);
                        wait = ANSWER_MS;
                    }
                    break;
                case "paired":
                    pairedNow = true;
                    break;
                case "pair_refused":
                    throw new Refused(m.optString("reason"), null, m.optInt("retry_s"));
                case "error":
                    throw new Failure(m.optString("code"), m.optString("detail"));
                default:
                    break;
            }
        }
    }

    /** The sender's half of the code check: six rounds, each committed by both sides before the sender opens. */
    private void typeCode(String code) throws IOException, JSONException {
        Pairing p = new Pairing(Pairing.SENDER, code, ownFp, peerFp);
        for (int r = 0; r < Pairing.DIGITS; r++) {
            send(new JSONObject().put("t", "pair_commit").put("round", r).put("c", p.commit(r)));
            JSONObject c = pairing(next(ANSWER_MS), "pair_commit", r);
            send(new JSONObject().put("t", "pair_open").put("round", r).put("n", p.open(r)));
            JSONObject o = pairing(next(ANSWER_MS), "pair_open", r);
            if (!p.check(r, c.optString("c"), o.optString("n"))) {
                // The TV committed to another digit, or to other certificates: someone in the middle.
                throw new Refused("mismatch", "the TV's answer does not match: not the TV that shows the code", 0);
            }
        }
    }

    private static JSONObject pairing(JSONObject m, String t, int round) throws IOException {
        if ("pair_refused".equals(m.optString("t"))) {
            throw new Refused(m.optString("reason"), null, m.optInt("retry_s"));
        }
        if (!t.equals(m.optString("t")) || m.optInt("round", -1) != round) throw new IOException("pairing: " + m);
        return m;
    }

    /**
     * Sends {@code msg} and returns the receiver's {@code ok} answer. A plain connection's request is refused until the
     * person allows it on the TV: it is sent again every second meanwhile, and a refusal on the TV closes the
     * connection.
     */
    JSONObject request(JSONObject msg, String ok, Ui ui) throws IOException, JSONException {
        String t = msg.optString("t");
        send(msg);
        long deadline = System.currentTimeMillis() + ALLOW_WAIT_MS;
        java.util.List<JSONObject> others = new java.util.ArrayList<>();
        try {
            return awaitAnswer(msg, t, ok, ui, deadline, others);
        } finally {
            held.addAll(others);   // something else that came meanwhile (a media_state): for whoever reads next
        }
    }

    private JSONObject awaitAnswer(JSONObject msg, String t, String ok, Ui ui, long deadline, java.util.List<JSONObject> others)
            throws IOException, JSONException {
        long again = 0;
        boolean asked = false;
        String why = null;
        while (true) {
            long now = System.currentTimeMillis();
            if (again > 0 && now >= again) {
                if (now > deadline) throw new Refused("timeout", "nobody answered on the TV", 0);
                send(msg);
                again = 0;
                continue;
            }
            JSONObject m;
            try {
                // media_ready may come only once the TV has opened the stream (up to 30 s)
                m = next(again > 0 ? again - now : Math.max(35_000, deadline - now));
            } catch (SocketTimeoutException quiet) {
                if (again > 0) continue;
                throw quiet;
            } catch (EOFException closed) {
                if (why != null) throw new Refused("not_allowed", why, 0);
                throw closed;
            }
            String mt = m.optString("t");
            if (mt.equals(ok)) {
                letIn = true;
                return m;
            }
            if ("error".equals(mt) && (m.optString("for", t).equals(t))) {
                if ("not_allowed".equals(m.optString("code")) && !secure && !letIn) {
                    why = m.optString("detail");
                    if (!asked) ui.allowOnTv();
                    asked = true;
                    again = System.currentTimeMillis() + 1000;
                    continue;
                }
                throw new Failure(m.optString("code"), m.optString("detail"));
            }
            others.add(m);
        }
    }

    /**
     * The next message other than ping and pong (a ping is answered; pongs with {@link #passPongs}), waiting at most
     * {@code ms}:
     * {@link SocketTimeoutException} when nothing came, {@link EOFException} when the receiver closed.
     */
    JSONObject next(long ms) throws IOException {
        JSONObject h = held.poll();
        if (h != null) return h;
        long until = System.currentTimeMillis() + ms;
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("no answer");
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
            String line = in.readLine();
            if (line == null) throw new EOFException("the receiver closed the connection");
            if (line.isEmpty()) continue;
            JSONObject m;
            try {
                m = new JSONObject(line);
            } catch (JSONException e) {
                continue;
            }
            String t = m.optString("t");
            if ("ping".equals(t)) {
                try {
                    send(new JSONObject().put("t", "pong").put("n", m.optInt("n")));
                } catch (JSONException ignored) {
                    // cannot happen
                }
                continue;
            }
            if (passPongs || !"pong".equals(t)) return m;
        }
    }

    synchronized void send(JSONObject o) throws IOException {
        out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** This phone's address towards the receiver: where it serves what it shares. */
    InetAddress localAddress() {
        return socket.getLocalAddress();
    }

    InetAddress peerAddress() {
        return socket.getInetAddress();
    }

    /** Says bye and closes. */
    @Override
    public void close() {
        try {
            send(new JSONObject().put("t", "bye"));
        } catch (IOException | JSONException ignored) {
            // gone already
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    // ---- the Android part ----

    /** A hello from this phone, wanting {@code want}. */
    static JSONObject helloFrom(Context context, String... want) throws JSONException {
        JSONArray w = new JSONArray();
        for (String x : want) w.put(x);
        return new JSONObject().put("t", "hello").put("id", Luch.id(context)).put("name", Luch.name(context))
                .put("app", "luch-android").put("want", w);
    }

    /** The pins, kept with the app's preferences. */
    static Pins pins(Context context) {
        SharedPreferences p = context.getSharedPreferences("luch_pins", Context.MODE_PRIVATE);
        return new Pins() {
            @Override
            public String get(String receiverId) {
                return p.getString(receiverId, null);
            }

            @Override
            public void put(String receiverId, String fingerprint) {
                p.edit().putString(receiverId, fingerprint).apply();
            }
        };
    }

    /** Forgets the pairing with a receiver (it was reset, and has a new certificate). */
    static void forget(Context context, String receiverId) {
        context.getSharedPreferences("luch_pins", Context.MODE_PRIVATE).edit().remove(receiverId).apply();
    }

    /**
     * A connection to {@code to}: TLS when it has a TLS port (from its TXT record, else asked with a probe on the plain
     * port, which shows nothing on the TV), else the plain port, unless it was paired.
     */
    static Link open(Context context, Luch.Receiver to) throws IOException {
        boolean pinned = pins(context).get(to.id) != null;
        int tls = to.tlsPort > 0 ? to.tlsPort : probeTls(context, to);
        if (tls > 0) {
            try {
                return tls(context, to.address, tls);
            } catch (IOException | GeneralSecurityException e) {
                if (pinned) throw e instanceof IOException ? (IOException) e : new IOException(e);
                Log.w(LuchService.TAG, "TLS to " + to.name + " failed, the plain port then: " + e);
            }
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(to.address, to.port), 4000);
        return new Link(s, null, null);
    }

    @android.annotation.SuppressLint("CustomX509TrustManager")   // pinned by fingerprint in hello(), as Identity says
    private static Link tls(Context context, String host, int port) throws IOException, GeneralSecurityException {
        Identity me = Identity.load(context, ALIAS);
        byte[][] seen = new byte[1][];
        TrustManager pin = new Identity.AnyCertificate() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                super.checkServerTrusted(chain, authType);
                // judged by its fingerprint against the pin, in hello(): there is no CA on a LAN
                seen[0] = Identity.fingerprint(chain[0]);
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{Identity.keyManager(me.key, me.cert)}, new TrustManager[]{pin}, null);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 4000);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setSoTimeout(8000);
        s.startHandshake();
        if (seen[0] == null) throw new IOException("no certificate from the receiver");
        return new Link(s, me.fingerprint, seen[0]);
    }

    /** The receiver's TLS port from its welcome to a probe (PROTOCOL.md 1b): 0 for an older one, or none answering. */
    private static int probeTls(Context context, Luch.Receiver to) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(to.address, to.port), 3000);
            Link l = new Link(s, null, null);
            l.send(helloFrom(context).put("v", Luch.VERSION).put("probe", true));
            JSONObject w = l.next(3000);
            return "welcome".equals(w.optString("t")) ? w.optInt("tls_port") : 0;
        } catch (IOException | JSONException e) {
            return 0;
        }
    }
}
