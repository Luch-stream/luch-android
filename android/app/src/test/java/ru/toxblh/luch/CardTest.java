// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The card and the QR link: the same bytes and text as luchd's find/card.rs. */
public class CardTest {
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    @Test
    public void aReceiversCardIsLuchds() {
        Card c = new Card(Card.RECEIVER, "3f0c2a8e-9d1b-4c7e-8a55-0b6f2d9e1c47", "192.168.1.20", 47800, "0badf00d");
        assertEquals("4c75013f0c2a8ec0a80114bab80badf00d", hex(c.encode()));
        Card back = Card.decode(c.encode());
        assertEquals("3f0c2a8e", back.id);
        assertEquals("192.168.1.20", back.host);
        assertEquals(47800, back.port);
        assertEquals("0badf00d", back.token);
        assertEquals("luch://connect?host=192.168.1.20&port=47800&t=0badf00d", c.link());
    }

    @Test
    public void overBluetoothThereIsNoTokenAndItFits() {
        Card c = new Card(Card.RECEIVER, "3f0c2a8e", "192.168.1.20", 47800, null);
        // Flags (3) + manufacturer data (1 length, 1 type, 2 company, 13 card) = 20 of the 31 bytes.
        assertEquals(13, c.encode().length);
        assertNull(Card.decode(c.encode()).token);
    }

    /** The vector all three sides test (luch-linux, the Mac sender): a receiver with no address, only its channel. */
    @Test
    public void aCardWithAPsm() {
        Card c = new Card(Card.RECEIVER, "0b6f1c2a-0000-4000-8000-000000000000", null, 0, null, 0x0080);
        assertEquals("4c 75 01 0b 6f 1c 2a 00 00 00 00 00 00 00 80".replace(" ", ""), hex(c.encode()));
        // Flags (3) + manufacturer data (1 length, 1 type, 2 company, 15 card) = 22 of the 31 bytes.
        assertEquals(15, c.encode().length);
        Card back = Card.decode(c.encode());
        assertEquals(0x0080, back.psm);
        assertEquals("0b6f1c2a", back.id);
        assertNull(back.host);
        assertEquals(0, back.port);
        assertNull(back.token);
        // With an address the address stays; a sender reading only 13 bytes sees the card it always did.
        Card both = new Card(Card.RECEIVER, "3f0c2a8e", "192.168.1.20", 47800, null, 0x1001);
        assertEquals("4c75013f0c2a8ec0a80114bab81001", hex(both.encode()));
        Card first13 = Card.decode(java.util.Arrays.copyOf(both.encode(), 13));
        assertEquals("192.168.1.20", first13.host);
        assertEquals(0, first13.psm);
        assertEquals(0x1001, Card.decode(both.encode()).psm);
        assertEquals(47800, Card.decode(both.encode()).port);
    }

    @Test
    public void aPsmIsNotAToken() {
        // 17 bytes are the sound's card: bytes 13..15 there are the token's, not a PSM.
        Card sound = Card.decode(new Card(Card.RECEIVER, "3f0c2a8e", "192.168.1.20", 47800, "0badf00d").encode());
        assertEquals(0, sound.psm);
        assertEquals("0badf00d", sound.token);
        // Without an address and without a PSM a receiver's card is still nothing.
        assertNull(Card.decode(new byte[] {0x4c, 0x75, 0x01, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertNull(Card.decode(new byte[] {0x4c, 0x75, 0x01, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    public void aSenderLooking() {
        Card s = Card.decode(new byte[] {0x4c, 0x75, 0x02, (byte) 0x9a, 0x1b, 0x2c, 0x3d});
        assertEquals(Card.SENDER, s.kind);
        assertEquals("9a1b2c3d", s.id);
        assertNull(s.host);
    }

    @Test
    public void strangersAreNotCards() {
        assertNull(Card.decode(new byte[] {0x4c, 0x75}));
        assertNull(Card.decode(new byte[] {0x4c, 0x76, 1, 0, 0, 0, 0}));
        assertNull(Card.decode(new byte[] {0x4c, 0x75, 0x11, 1, 2, 3, 4, 10, 0, 0, 1, 0, 80}));
        assertNull(Card.decode(new byte[] {0x4c, 0x75, 0x01, 1, 2, 3, 4}));
        assertNull(Card.decode(null));
    }

    @Test
    public void theTokenIsUsedUpByAHelloNotByAProbe() {
        String t = Card.Pairing.current();
        assertEquals(8, t.length());
        assertNull(Card.Pairing.check(null, false));
        assertEquals("bad", Card.Pairing.check("00000000".equals(t) ? "00000001" : "00000000", false));
        assertEquals(t, Card.Pairing.current());
        assertEquals("ok", Card.Pairing.check(t.toUpperCase(), true));
        assertEquals("ok", Card.Pairing.check(t, false));
        assertEquals("bad", Card.Pairing.check(t, false));
        assertNotEquals(t, Card.Pairing.current());
    }
}
