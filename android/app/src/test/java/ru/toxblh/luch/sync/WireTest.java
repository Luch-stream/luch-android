// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The bytes of PROTOCOL.md 4h. luchd/src/sync.rs checks the same vectors. */
public class WireTest {
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Request number 7 sent at local µs 0x0123456789ab. */
    static final String REQUEST = "0300" + "0000" + "0000" + "0000" + "00000007" + "456789ab" + "00000008" + "0000"
            + "0000" + "00000123456789ab";
    /** The reply: t1 as sent, t2 = 1 000 000 µs, t3 = 1 000 150 µs on the sender's clock. */
    static final String REPLY = "0310" + "0000" + "0000" + "0000" + "00000007" + "456789ab" + "00000018" + "0000"
            + "0000" + "00000123456789ab" + "00000000000f4240" + "00000000000f42d6";

    @Test
    public void requestBytes() {
        assertArrayEquals(hex(REQUEST), Wire.request(7, 0x0123456789abL));
    }

    @Test
    public void replyIsParsed() {
        byte[] r = hex(REPLY);
        assertArrayEquals(new long[]{0x0123456789abL, 1_000_000, 1_000_150}, Wire.reply(r, r.length));
        assertNull(Wire.reply(Wire.request(1, 2), Wire.REQUEST_LEN));   // a request is not a reply
        assertNull(Wire.reply(r, r.length - 1));
    }

    @Test
    public void delayIsTheLastHeaderField() {
        byte[] p = new byte[24];
        p[22] = 0x05;
        p[23] = (byte) 0xdc;   // 1500 × 0.1 ms
        assertEquals(150_000, Wire.delayUs(p));
    }

    @Test
    public void ptsUnwrapsToTheNearest() {
        long now = 5L << 32 | 100;
        assertEquals(5L << 32 | 50, Wire.unwrapPts(50, now));
        assertEquals((4L << 32) | 0xfffffff0L, Wire.unwrapPts(0xfffffff0, now));   // just before the wrap
        assertEquals((6L << 32) | 10, Wire.unwrapPts(10, (5L << 32) | 0xffffff00L)); // just after it
    }
}
