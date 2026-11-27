// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The input state of one mirroring session and its datagrams (PROTOCOL.md, section 4h). The records carry state, not
 * events: every contact down now, running totals of relative motion and wheel, the gamepads as they are; only keys go as
 * numbered events, repeated for a while. So a lost datagram loses nothing but an in-between position. The UI thread
 * changes the state, the sending thread encodes it; both under this object's lock. No Android classes: tested on the JVM.
 */
final class InputWire {
    static final int KIND = 3, VERSION = 0, HEADER = 20, MAX_DATAGRAM = 1400;
    static final int T_TOUCH = 1, T_POINTER = 2, T_KEYS = 3, T_GAMEPAD = 4;
    static final int TOOL_FINGER = 1, TOOL_STYLUS = 2, TOOL_ERASER = 3, TOOL_BUTTON = 0x40, TOOL_HOVER = 0x80;
    static final int MAX_CONTACTS = 16, MAX_PADS = 4;
    /** Pointer buttons, as in the record. */
    static final int LEFT = 1, RIGHT = 2, MIDDLE = 4, BACK = 8, FORWARD = 16;
    /** How long a key event is repeated, and how many are kept at most. */
    static final long KEY_REPEAT_MS = 300;
    static final int MAX_KEY_EVENTS = 32, MAX_HELD = 16;

    /** One contact on the screen, in picture coordinates 0…65535. */
    static final class Contact {
        final int id, tool, x, y, pressure;

        Contact(int id, int tool, int x, int y, int pressure) {
            this.id = id & 15;
            this.tool = tool & 0xff;
            this.x = clamp16(x);
            this.y = clamp16(y);
            this.pressure = clamp16(pressure);
        }
    }

    /** One gamepad as it is now. */
    static final class Pad {
        boolean connected;
        int buttons;
        short lx, ly, rx, ry;
        int lt, rt;
        /** When it was unplugged: the record says so for a while, then it is forgotten. */
        long goneAt;

        boolean neutral() {
            return buttons == 0 && Math.abs(lx) < 2048 && Math.abs(ly) < 2048 && Math.abs(rx) < 2048 && Math.abs(ry) < 2048
                    && lt < 2048 && rt < 2048;
        }
    }

    private static final class KeyEvent {
        final int number, code;
        final boolean down;
        final long at;

        KeyEvent(int number, int code, boolean down, long at) {
            this.number = number;
            this.code = code;
            this.down = down;
            this.at = at;
        }
    }

    private final long token;
    private int seq;
    private int changedUs;

    private boolean touchUsed;
    private List<Contact> contacts = new ArrayList<>();

    private boolean pointerUsed, absValid, smooth;
    private int buttons, x, y;
    /** Running totals; the fractions are kept until they make a whole unit. */
    private int relX, relY, wheelY, wheelX;
    private double relXf, relYf, wheelYf, wheelXf;

    private boolean keysUsed;
    private int nextKey;
    private final ArrayDeque<KeyEvent> keyEvents = new ArrayDeque<>();
    private final LinkedHashSet<Integer> held = new LinkedHashSet<>();

    private final Pad[] pads = new Pad[MAX_PADS];

    InputWire(long token) {
        this.token = token;
    }

    long token() {
        return token;
    }

    /** "0123456789abcdef" from the grant; 0 for anything else. */
    static long parseToken(String hex) {
        if (hex == null || hex.length() != 16) return 0;
        try {
            return new java.math.BigInteger(hex, 16).longValue();   // Long.parseUnsignedLong is Android 8+
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int clamp16(long v) {
        return (int) Math.max(0, Math.min(65535, v));
    }

    /** A position across the picture, 0…1, as the record's 0…65535. */
    static int norm(float f) {
        return clamp16(Math.round(f * 65535f));
    }

    private void touched(int us) {
        changedUs = us;
    }

    // ---- what the UI thread changes ---------------------------------------------------------------------------

    synchronized void setContacts(List<Contact> now, int us) {
        touchUsed = true;
        contacts = new ArrayList<>(now.subList(0, Math.min(now.size(), MAX_CONTACTS)));
        touched(us);
    }

    synchronized void setButtons(int b, int us) {
        pointerUsed = true;
        buttons = b & 0x1f;
        touched(us);
    }

    synchronized int buttons() {
        return buttons;
    }

    synchronized void moveTo(float nx, float ny, int us) {
        pointerUsed = true;
        absValid = true;
        x = norm(nx);
        y = norm(ny);
        touched(us);
    }

    /** Relative motion in logical pixels of the sender's area (the record counts 1/16 of one). */
    synchronized void moveBy(double dx, double dy, int us) {
        pointerUsed = true;
        relXf += dx * 16;
        relYf += dy * 16;
        int ix = (int) relXf, iy = (int) relYf;
        relX += ix;
        relY += iy;
        relXf -= ix;
        relYf -= iy;
        touched(us);
    }

    /** Wheel motion, 120 per notch, positive down / right; {@code smooth} for a touchpad's two fingers. */
    synchronized void wheel(double dy, double dx, boolean smooth, int us) {
        pointerUsed = true;
        this.smooth = smooth;
        wheelYf += dy;
        wheelXf += dx;
        int iy = (int) wheelYf, ix = (int) wheelXf;
        wheelY += iy;
        wheelX += ix;
        wheelYf -= iy;
        wheelXf -= ix;
        touched(us);
    }

    /** A key went down or up (an evdev code); repeats of a held key are the sender's business, not ours. */
    synchronized void key(int code, boolean down, long nowMs, int us) {
        if (code <= 0 || code > 0x2ff) return;
        if (down == held.contains(code)) return;
        keysUsed = true;
        if (down) {
            if (held.size() >= MAX_HELD) return;
            held.add(code);
        } else {
            held.remove(code);
        }
        keyEvents.addLast(new KeyEvent(nextKey, code, down, nowMs));
        nextKey = (nextKey + 1) & 0xffff;
        while (keyEvents.size() > MAX_KEY_EVENTS) keyEvents.removeFirst();
        touched(us);
    }

    synchronized void pad(int index, Pad p, long nowMs, int us) {
        if (index < 0 || index >= MAX_PADS) return;
        Pad c = new Pad();
        c.connected = p.connected;
        c.buttons = p.buttons;
        c.lx = p.lx;
        c.ly = p.ly;
        c.rx = p.rx;
        c.ry = p.ry;
        c.lt = clamp16(p.lt);
        c.rt = clamp16(p.rt);
        Pad was = pads[index];
        c.goneAt = c.connected ? 0 : was != null && !was.connected ? was.goneAt : nowMs;
        pads[index] = c;
        touched(us);
    }

    /** Everything let go: the screen is leaving, or control was taken back. */
    synchronized void releaseAll(long nowMs, int us) {
        if (!contacts.isEmpty()) contacts = new ArrayList<>();
        buttons = 0;
        for (Integer k : new ArrayList<>(held)) key(k, false, nowMs, us);
        for (Pad p : pads) {
            if (p == null) continue;
            p.buttons = 0;
            p.lx = p.ly = p.rx = p.ry = 0;
            p.lt = p.rt = 0;
        }
        touched(us);
    }

    /** Anything held or moving: the state is sent again every 50 ms while this is so. */
    synchronized boolean active(long nowMs) {
        if (!contacts.isEmpty() || buttons != 0 || !held.isEmpty()) return true;
        KeyEvent last = keyEvents.peekLast();
        if (last != null && nowMs - last.at < KEY_REPEAT_MS) return true;
        for (Pad p : pads) if (p != null && (!p.connected || !p.neutral())) return true;
        return false;
    }

    // ---- the datagram -----------------------------------------------------------------------------------------

    /** The next datagram, with every record of a device used in this session. */
    synchronized byte[] encode(long nowMs) {
        ByteBuffer b = ByteBuffer.allocate(MAX_DATAGRAM).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) KIND).put((byte) VERSION).putShort((short) 0).putInt(seq++).putInt(changedUs).putLong(token);
        if (touchUsed) {
            b.put((byte) T_TOUCH).put((byte) (1 + contacts.size() * 8)).put((byte) contacts.size());
            for (Contact c : contacts) {
                b.put((byte) c.id).put((byte) c.tool).putShort((short) c.x).putShort((short) c.y).putShort((short) c.pressure);
            }
        }
        if (pointerUsed) {
            b.put((byte) T_POINTER).put((byte) 22).put((byte) buttons).put((byte) ((absValid ? 1 : 0) | (smooth ? 2 : 0)))
                    .putShort((short) x).putShort((short) y).putInt(relX).putInt(relY).putInt(wheelY).putInt(wheelX);
        }
        if (keysUsed) {
            for (Iterator<KeyEvent> it = keyEvents.iterator(); it.hasNext(); ) {
                if (nowMs - it.next().at >= KEY_REPEAT_MS) it.remove();
            }
            int n = keyEvents.size();
            int first = n == 0 ? nextKey : keyEvents.peekFirst().number;
            b.put((byte) T_KEYS).put((byte) (3 + n * 3 + 1 + held.size() * 2)).putShort((short) first).put((byte) n);
            for (KeyEvent e : keyEvents) b.putShort((short) e.code).put((byte) (e.down ? 1 : 0));
            b.put((byte) held.size());
            for (int code : held) b.putShort((short) code);
        }
        for (int i = 0; i < MAX_PADS; i++) {
            Pad p = pads[i];
            if (p != null && !p.connected && nowMs - p.goneAt >= KEY_REPEAT_MS) pads[i] = p = null;
            if (p == null) continue;
            b.put((byte) T_GAMEPAD).put((byte) 18).put((byte) i).put((byte) (p.connected ? 1 : 0)).putInt(p.buttons)
                    .putShort(p.lx).putShort(p.ly).putShort(p.rx).putShort(p.ry)
                    .putShort((short) p.lt).putShort((short) p.rt);
        }
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }
}
