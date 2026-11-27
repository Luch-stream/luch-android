// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MediaCryptTest {
    private static final byte[] KEY = new byte[16];

    static {
        for (int i = 0; i < 16; i++) KEY[i] = (byte) i;
    }

    /** Video, IDR, one shard, seq 7, pts 1000, host latency 0.5 ms; len counts the tag. */
    private static byte[] header(int idx, int len) {
        return ByteBuffer.allocate(MirrorSession.HEADER).put((byte) 1).put((byte) 1).putShort((short) idx)
                .putShort((short) 1).putShort((short) 0).putInt(7).putInt(1000).putInt(len).putShort((short) 5)
                .putShort((short) 0).array();
    }

    private static final byte[] PLAIN = "luch frame".getBytes(StandardCharsets.US_ASCII);

    /** The same bytes as luchd's mirror::tests::a_frame_is_sealed_as_the_receiver_opens_it (luch-linux). */
    @Test
    public void sealedTheSameOnEveryImplementation() throws Exception {
        byte[] sealed = new MediaCrypt(KEY).seal(1, 7, MediaCrypt.aad(header(0, PLAIN.length + 16), 0), PLAIN);
        assertEquals(VECTOR, Pairing.hex(sealed));
    }

    static final String VECTOR = "c88e092ce36d77f6a5954720c3a59966db75e601486ef8e09989";

    @Test
    public void opensInPlaceWhateverTheShardIndex() throws Exception {
        MediaCrypt c = new MediaCrypt(KEY);
        byte[] sealed = c.seal(1, 7, MediaCrypt.aad(header(0, PLAIN.length + 16), 0), PLAIN);
        byte[] buf = new byte[64];
        System.arraycopy(sealed, 0, buf, 3, sealed.length);
        // The receiver takes the header of whichever shard came first.
        int n = c.open(1, 7, MediaCrypt.aad(header(5, sealed.length), 0), buf, 3, sealed.length);
        assertEquals(PLAIN.length, n);
        byte[] out = new byte[n];
        System.arraycopy(buf, 3, out, 0, n);
        assertArrayEquals(PLAIN, out);
    }

    @Test
    public void anythingChangedDoesNotOpen() throws Exception {
        MediaCrypt c = new MediaCrypt(KEY);
        byte[] aad = MediaCrypt.aad(header(0, PLAIN.length + 16), 0);
        byte[] sealed = c.seal(1, 7, aad, PLAIN);
        byte[] flipped = sealed.clone();
        flipped[2] ^= 1;
        assertEquals("a changed byte", -1, c.open(1, 7, aad, flipped, 0, flipped.length));
        assertEquals("another frame number", -1, c.open(1, 8, aad, sealed.clone(), 0, sealed.length));
        assertEquals("audio is not video", -1, c.open(2, 7, aad, sealed.clone(), 0, sealed.length));
        byte[] notIdr = aad.clone();
        notIdr[1] = 0;
        assertEquals("the IDR flag is authenticated", -1, c.open(1, 7, notIdr, sealed.clone(), 0, sealed.length));
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;
        assertEquals("another key", -1, new MediaCrypt(otherKey).open(1, 7, aad, sealed.clone(), 0, sealed.length));
        assertEquals("shorter than a tag", -1, c.open(1, 7, aad, new byte[8], 0, 8));
    }
}
