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
 * The control channel of PROTOCOL.md: TCP, one JSON object per line. v0 answers hello with welcome, keeps the
 * connection alive with ping/pong and says on screen who connected. A thread per connection: senders are a handful.
 */
final class ControlServer {
    private static final int MAX_LINE = 64 * 1024;
    private static final int IDLE_MS = 30_000;
    private static final int PING_MS = 10_000;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    /** Control channels of the connected senders, for results nobody asked for on that channel (auto bench). */
    static final Set<MirrorSession.Control> SENDERS = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private ServerSocket socket;
    private volatile boolean running;

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
        Thread accept = new Thread(this::acceptLoop, "luch-accept");
        accept.setDaemon(true);
        accept.start();
        return socket.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        for (Socket s : open) closeQuietly(s);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = socket.accept();
                open.add(s);
                Thread t = new Thread(() -> serve(s), "luch-conn");
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

    private void serve(Socket s) {
        String peerId = null;
        String peerName = null;
        String address = s.getInetAddress().getHostAddress();
        MirrorSession.Control control = null;
        try {
            s.setTcpNoDelay(true);
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
                switch (t) {
                    case "hello":
                        peerId = msg.optString("id", address);
                        peerName = msg.optString("name", address);
                        send(out, welcome());
                        SENDERS.add(control);
                        if (wants(msg, "reports")) {
                            org.json.JSONArray reports = Reports.pending(context);
                            if (reports.length() > 0) {
                                send(out, new JSONObject().put("t", "reports").put("items", reports));
                            }
                        }
                        Bench.autoRun(context, ControlServer::broadcastBench);
                        Luch.connected(new Luch.Peer(peerId, peerName, address));
                        say(context.getString(R.string.toast_connected, peerName));
                        Log.i(LuchService.TAG, "hello from " + peerName + " " + address + " app=" + msg.optString("app"));
                        break;
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
                    case "sender_stats": {
                        MirrorSession m = MirrorSession.current;
                        if (m != null && m.control == control) m.onSenderStats(msg);
                        break;
                    }
                    case "start":
                        if (!"mirror".equals(msg.optString("mode"))) {
                            send(out, new JSONObject().put("t", "error").put("code", "start_failed")
                                    .put("detail", "mode " + msg.optString("mode") + " not supported"));
                            break;
                        }
                        try {
                            int[] ports = MirrorSession.start(context, control, msg);
                            send(out, new JSONObject().put("t", "ready")
                                    .put("video_port", ports[0]).put("audio_port", ports[1]));
                            Log.i(LuchService.TAG, "mirror from " + peerName + ": " + msg);
                        } catch (IOException | RuntimeException e) {
                            send(out, new JSONObject().put("t", "error").put("code", "start_failed")
                                    .put("detail", String.valueOf(e)));
                        }
                        break;
                    case "stop":
                        MirrorSession.stopFromSender(control);
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
            open.remove(s);
            closeQuietly(s);
            if (control != null) {
                SENDERS.remove(control);
                MirrorSession.stopFromSender(control);
            }
            if (peerId != null) Luch.disconnected(peerId);
        }
    }

    /** A finished bench goes to every connected sender. */
    static void broadcastBench(JSONObject result) {
        try {
            JSONObject msg = new JSONObject(result.toString()).put("t", "bench_result");
            for (MirrorSession.Control c : SENDERS) c.send(msg);
        } catch (JSONException ignored) {
        }
    }

    private JSONObject welcome() throws JSONException {
        return new JSONObject()
                .put("t", "welcome")
                .put("v", Luch.VERSION)
                .put("id", Luch.id(context))
                .put("name", Luch.name(context))
                .put("model", Luch.model())
                .put("decoders", new JSONArray(Luch.decoders()))
                .put("max", Luch.maxSize())
                .put("modes", new JSONArray().put("mirror"))
                // What the hardware says it can do, and what it measured (null until the bench has run).
                .put("hw", Caps.collect(context))
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
