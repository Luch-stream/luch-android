// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/** The input datagram, byte for byte as PROTOCOL.md 4i has it. */
public class InputWireTest {
    private static final long TOKEN = 0x0123456789abcdefL;

    private static int u16(byte[] b, int at) {
        return ByteBuffer.wrap(b).getShort(at) & 0xffff;
    }

    private static int i32(byte[] b, int at) {
        return ByteBuffer.wrap(b).getInt(at);
    }

    @Test
    public void headerCarriesKindSequenceTimeAndToken() {
        InputWire w = new InputWire(InputWire.parseToken("0123456789abcdef"));
        w.setContacts(Collections.emptyList(), 123456);
        byte[] a = w.encode(0), b = w.encode(0);
        assertEquals(3, a[0]);
        assertEquals(0, a[1]);
        assertEquals(0, u16(a, 2));
        assertEquals(0, i32(a, 4));
        assertEquals(1, i32(b, 4));
        assertEquals(123456, i32(a, 8));
        assertEquals(TOKEN, ByteBuffer.wrap(a).getLong(12));
        // nothing used but the touch screen: one empty touch record
        assertArrayEquals(new byte[] {1, 1, 0}, Arrays.copyOfRange(a, 20, a.length));
    }

    @Test
    public void tokenMustBeSixteenHexDigits() {
        assertEquals(TOKEN, InputWire.parseToken("0123456789abcdef"));
        assertEquals(-1L, InputWire.parseToken("ffffffffffffffff"));
        assertEquals(0, InputWire.parseToken("123"));
        assertEquals(0, InputWire.parseToken("0123456789abcdeg"));
        assertEquals(0, InputWire.parseToken(null));
    }

    @Test
    public void nothingUsedNoRecords() {
        assertEquals(InputWire.HEADER, new InputWire(TOKEN).encode(0).length);
    }

    @Test
    public void touchRecordListsEveryContact() {
        InputWire w = new InputWire(TOKEN);
        w.setContacts(Arrays.asList(new InputWire.Contact(0, InputWire.TOOL_FINGER, 0, 65535, 0),
                new InputWire.Contact(17, InputWire.TOOL_STYLUS | InputWire.TOOL_HOVER, 32768, 70000, 40000)), 0);
        byte[] p = w.encode(0);
        assertEquals(1, p[20]);
        assertEquals(17, p[21]);
        assertEquals(2, p[22]);
        assertEquals(0, p[23]);
        assertEquals(1, p[24]);
        assertEquals(0, u16(p, 25));
        assertEquals(65535, u16(p, 27));
        assertEquals(0, u16(p, 29));
        assertEquals(1, p[31]);   // id 17 is kept in 0…15
        assertEquals(0x82, p[32] & 0xff);
        assertEquals(32768, u16(p, 33));
        assertEquals(65535, u16(p, 35));   // clamped to the picture
        assertEquals(40000, u16(p, 37));
        assertEquals(39, p.length);
    }

    @Test
    public void normalizedPositions() {
        assertEquals(0, InputWire.norm(-0.2f));
        assertEquals(0, InputWire.norm(0));
        assertEquals(32768, InputWire.norm(0.5f));
        assertEquals(65535, InputWire.norm(1));
        assertEquals(65535, InputWire.norm(1.5f));
    }

    @Test
    public void pointerCarriesRunningTotals() {
        InputWire w = new InputWire(TOKEN);
        w.moveBy(1.5, -2, 0);
        w.moveBy(0.25, 0, 0);
        w.wheel(120, -60, false, 0);
        w.setButtons(InputWire.LEFT | InputWire.FORWARD, 0);
        w.moveTo(0.25f, 0.75f, 0);
        byte[] p = w.encode(0);
        assertEquals(2, p[20]);
        assertEquals(22, p[21]);
        assertEquals(17, p[22]);
        assertEquals(1, p[23]);   // x/y valid, a wheel of notches
        assertEquals(16384, u16(p, 24));
        assertEquals(49151, u16(p, 26));
        assertEquals(28, i32(p, 28));   // 1.75 px in 1/16
        assertEquals(-32, i32(p, 32));
        assertEquals(120, i32(p, 36));
        assertEquals(-60, i32(p, 40));
        assertEquals(44, p.length);
        // a second datagram repeats the totals, it does not add to them
        byte[] q = w.encode(0);
        assertEquals(28, i32(q, 28));
        w.wheel(1, 0, true, 0);
        assertEquals(3, w.encode(0)[23]);
    }

    @Test
    public void fractionsAreKeptUntilTheyMakeAUnit() {
        InputWire w = new InputWire(TOKEN);
        for (int i = 0; i < 16; i++) w.moveBy(1 / 32.0, 0, 0);
        assertEquals(8, i32(w.encode(0), 28));
        for (int i = 0; i < 4; i++) w.wheel(0.25, 0, true, 0);
        assertEquals(1, i32(w.encode(0), 36));
    }

    @Test
    public void keyEventsAreNumberedRepeatedAndExpire() {
        InputWire w = new InputWire(TOKEN);
        w.key(30, true, 1000, 0);    // A down
        w.key(30, true, 1010, 0);    // a repeat: not an event
        w.key(42, true, 1020, 0);    // Shift down
        w.key(30, false, 1100, 0);   // A up
        byte[] p = w.encode(1200);
        assertEquals(3, p[20]);
        assertEquals(3 + 3 * 3 + 1 + 2, p[21]);
        assertEquals(0, u16(p, 22));   // the first event's number
        assertEquals(3, p[24]);
        assertEquals(30, u16(p, 25));
        assertEquals(1, p[27]);
        assertEquals(42, u16(p, 28));
        assertEquals(1, p[30]);
        assertEquals(30, u16(p, 31));
        assertEquals(0, p[33]);
        assertEquals(1, p[34]);   // held: Shift
        assertEquals(42, u16(p, 35));
        // 300 ms on, the first two are no longer repeated; the numbering goes on from the oldest left
        byte[] q = w.encode(1350);
        assertEquals(2, u16(q, 22));
        assertEquals(1, q[24]);
        assertEquals(30, u16(q, 25));
        // later still: no events, the next number, and what is held
        byte[] r = w.encode(2000);
        assertEquals(3, u16(r, 22));
        assertEquals(0, r[24]);
        assertEquals(1, r[25]);
        assertEquals(42, u16(r, 26));
    }

    @Test
    public void atMostThirtyTwoKeyEvents() {
        InputWire w = new InputWire(TOKEN);
        for (int i = 0; i < 40; i++) w.key(30, i % 2 == 0, 1000, 0);
        byte[] p = w.encode(1000);
        assertEquals(8, u16(p, 22));
        assertEquals(32, p[24]);
        assertTrue((p[21] & 0xff) <= 255);
    }

    @Test
    public void activeWhileAnythingIsHeld() {
        InputWire w = new InputWire(TOKEN);
        assertFalse(w.active(0));
        w.setContacts(Collections.singletonList(new InputWire.Contact(0, 1, 1, 1, 0)), 0);
        assertTrue(w.active(0));
        w.setContacts(Collections.emptyList(), 0);
        assertFalse(w.active(0));
        w.key(30, true, 0, 0);
        assertTrue(w.active(10_000));
        w.key(30, false, 10_000, 0);
        assertTrue("the release is still being repeated", w.active(10_100));
        assertFalse(w.active(10_400));
        InputWire.Pad p = new InputWire.Pad();
        p.connected = true;
        p.lx = 20000;
        w.pad(0, p, 0, 0);
        assertTrue(w.active(20_000));
        w.releaseAll(20_000, 0);
        assertFalse(w.active(21_000));
    }

    @Test
    public void gamepadRecordAndUnplugging() {
        InputWire w = new InputWire(TOKEN);
        InputWire.Pad p = new InputWire.Pad();
        p.connected = true;
        p.buttons = 1 | 1 << 14;
        p.lx = -32768;
        p.ly = 100;
        p.rx = 32767;
        p.ry = -1;
        p.lt = 65535;
        p.rt = 7;
        w.pad(1, p, 0, 0);
        byte[] b = w.encode(0);
        assertArrayEquals(new byte[] {4, 18, 1, 1, 0, 0, 0x40, 1, (byte) 0x80, 0, 0, 100, 0x7f, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 7}, Arrays.copyOfRange(b, 20, b.length));
        w.pad(1, new InputWire.Pad(), 1000, 0);
        byte[] gone = w.encode(1100);
        assertEquals(0, gone[23]);   // unplugged, said while it repeats
        assertTrue(w.active(1100));
        assertEquals(InputWire.HEADER, w.encode(1300).length);   // then forgotten
    }

    @Test
    public void releaseAllLetsGoOfEverything() {
        InputWire w = new InputWire(TOKEN);
        w.setContacts(Collections.singletonList(new InputWire.Contact(3, 1, 1, 1, 0)), 0);
        w.setButtons(InputWire.RIGHT, 0);
        w.key(57, true, 0, 0);
        w.releaseAll(100, 0);
        byte[] p = w.encode(100);
        assertEquals(0, p[22]);              // no contacts
        assertEquals(0, p[23 + 2]);          // no buttons
        int keys = 23 + 2 + 22;
        assertEquals(3, p[keys]);
        assertEquals(2, p[keys + 4]);        // the down and the up of Space
        assertEquals(0, p[keys + 5 + 6]);    // nothing held
    }
}
