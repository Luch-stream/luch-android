// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Input of one mirroring session to its sender (PROTOCOL.md, section 4i): the permission (asked once per kind of
 * device, granted or refused by the sender's person) and the thread that sends the state. The thread has a mailbox of
 * one: it always encodes the newest state, so a change that comes while one is being sent replaces nothing but a
 * position nobody needs any more.
 */
final class InputLink {
    /** What the screen shows a toast for. */
    static final String ASKING = "asking", GRANTED = "granted", DENIED = "denied", OFF = "off";
    static final long REPEAT_MS = 50;
    static final int TAIL = 3;

    private final MirrorSession.Control control;
    /** The sender said "input" in its hello: without that nothing is asked and the screen keeps its taps. */
    final boolean senderWants;
    final String senderName;

    private final Set<String> asked = new HashSet<>();
    private volatile boolean denied;
    private volatile InputWire wire;
    private volatile Set<String> accept = new HashSet<>();
    private volatile int areaW, areaH;

    private volatile DatagramSocket socket;
    private volatile InetAddress peer;
    private volatile int peerPort;

    private final Object lock = new Object();
    private boolean kicked;
    private volatile boolean closed;
    private Thread thread;
    /** Datagrams sent, for the tests. */
    volatile int sent;

    InputLink(MirrorSession.Control control, boolean senderWants, String senderName) {
        this.control = control;
        this.senderWants = senderWants;
        this.senderName = senderName == null || senderName.isEmpty() ? "?" : senderName;
    }

    /** Where input goes: from the video socket, to where the video comes from (known at its first packet). */
    void setPeer(DatagramSocket socket, InetAddress address, int port) {
        this.socket = socket;
        this.peerPort = port;
        this.peer = address;
        kick();
    }

    boolean granted() {
        return wire != null;
    }

    boolean denied() {
        return denied;
    }

    /** The state to change, null while input may not go out. */
    InputWire wire() {
        return wire;
    }

    boolean accepts(String what) {
        return accept.contains(what);
    }

    /** Logical pixels of the sender's area across the picture, 0 when not said. */
    int areaWidth() {
        return areaW;
    }

    int areaHeight() {
        return areaH;
    }

    /**
     * The person used a device of {@code kind} ("touch", "stylus", "keyboard", "mouse", "gamepad"). Returns whether its
     * input goes out now; the first use of a kind asks the sender.
     */
    boolean use(String kind) {
        if (wire != null) return true;
        if (!senderWants || denied) return false;
        boolean first;
        synchronized (asked) {
            first = asked.add(kind);
        }
        if (first) {
            try {
                control.send(new JSONObject().put("t", "input_request").put("devices", new JSONArray().put(kind)));
            } catch (JSONException ignored) {
            }
            MirrorActivity.inputChanged(ASKING, senderName);
        }
        return false;
    }

    /** Whether a request is out and not answered: the screen does not act on touches meanwhile. */
    boolean waiting() {
        synchronized (asked) {
            return wire == null && !denied && !asked.isEmpty();
        }
    }

    /** {"t":"input","grant":...} from the sender of this session. */
    void onGrant(JSONObject msg) {
        String grant = msg.optString("grant");
        if ("once".equals(grant) || "always".equals(grant)) {
            Set<String> a = new HashSet<>();
            JSONArray arr = msg.optJSONArray("accept");
            for (int i = 0; arr != null && i < arr.length(); i++) a.add(arr.optString(i));
            JSONArray area = msg.optJSONArray("area");
            areaW = area == null ? 0 : area.optInt(0);
            areaH = area == null ? 0 : area.optInt(1);
            accept = a;
            denied = false;
            boolean fresh = wire == null;
            long token = InputWire.parseToken(msg.optString("token"));
            // The same grant again (a kind added) goes on with the same numbers: a new start would look old to the sender.
            if (fresh || wire.token() != token) wire = new InputWire(token);
            start();
            if (fresh) MirrorActivity.inputChanged(GRANTED, senderName);
        } else if ("no".equals(grant)) {
            denied = true;
            wire = null;
            MirrorActivity.inputChanged(DENIED, senderName);
        } else if ("off".equals(grant)) {
            InputWire w = wire;
            if (w != null) {
                w.releaseAll(SystemClock.uptimeMillis(), now());
                sendNow(w);
            }
            wire = null;
            synchronized (asked) {
                asked.clear();   // a later use may ask again
            }
            MirrorActivity.inputChanged(OFF, senderName);
        }
    }

    /** The state changed: send it now. */
    void kick() {
        synchronized (lock) {
            kicked = true;
            lock.notifyAll();
        }
    }

    /**
     * Let everything go (the screen is leaving) and stop sending. The last state goes from the thread (the caller may be
     * the main thread); if the socket is gone first, the sender's watchdog lets go after 500 ms.
     */
    void close() {
        InputWire w = wire;
        if (w != null) w.releaseAll(SystemClock.uptimeMillis(), now());
        synchronized (lock) {
            closed = true;
            kicked = w != null;
            lock.notifyAll();
        }
    }

    /** The receiver's clock for the datagram's time field, µs (low 32 bits). */
    static int now() {
        return (int) (SystemClock.uptimeMillis() * 1000);
    }

    private synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "luch-input");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        int tail = 0;
        boolean repeat = false;
        while (true) {
            InputWire w;
            synchronized (lock) {
                while (!kicked && !closed) {
                    w = wire;
                    boolean keepGoing = w != null && (tail > 0 || w.active(SystemClock.uptimeMillis()));
                    try {
                        if (keepGoing) {
                            lock.wait(REPEAT_MS);
                            if (!kicked) {
                                repeat = true;
                                break;
                            }
                        } else {
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed && !kicked) return;
                if (kicked) repeat = false;
                kicked = false;
            }
            w = wire;
            if (w == null) {
                tail = 0;
                continue;
            }
            sendNow(w);
            if (closed) return;
            if (w.active(SystemClock.uptimeMillis())) tail = TAIL;
            else if (repeat) tail = Math.max(0, tail - 1);
            else tail = TAIL;
        }
    }

    private void sendNow(InputWire w) {
        DatagramSocket s = socket;
        InetAddress to = peer;
        if (s == null || to == null) return;
        byte[] b = w.encode(SystemClock.uptimeMillis());
        if (b.length == InputWire.HEADER) return;   // nothing used yet: nothing to say
        try {
            s.send(new DatagramPacket(b, b.length, to, peerPort));
            sent++;
        } catch (IOException e) {
            Log.i(LuchService.TAG, "input: " + e);
        }
    }
}
