// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A phone showing its screen over TLS seals each frame the way a receiver opens it (PROTOCOL.md 4, sealed media). */
public class SenderFrameTest {
    private static List<byte[]> packets(MediaCrypt c, int seq, byte[] data) throws Exception {
        byte[] buf = new byte[Sender.HEADER + Sender.SHARD];
        List<byte[]> out = new ArrayList<>();
        Sender.frame(buf, c, seq, true, 123_456_789L, 42, data, n -> out.add(Arrays.copyOf(buf, n)));
        return out;
    }

    private static byte[] frame() {
        byte[] d = new byte[5000];
        for (int i = 0; i < d.length; i++) d[i] = (byte) (i * 7);
        return d;
    }

    @Test
    public void plainFramesAreCutIntoShards() throws Exception {
        List<byte[]> p = packets(null, 9, frame());
        assertEquals(4, p.size());
        ByteBuffer h = ByteBuffer.wrap(p.get(2));
        assertEquals(1, h.get(0));
        assertEquals(1, h.get(1));
        assertEquals(2, h.getShort(2));
        assertEquals(4, h.getShort(4));
        assertEquals(9, h.getInt(8));
        assertEquals(5000, h.getInt(16));
        assertEquals(42, h.getShort(20));
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] x : p) joined.write(x, Sender.HEADER, x.length - Sender.HEADER);
        assertArrayEquals(frame(), joined.toByteArray());
    }

    /** Joined and opened as MirrorSession does: the header of any shard is the associated data, the tag counts. */
    @Test
    public void sealedFramesOpenOnTheReceiver() throws Exception {
        byte[] key = MediaCrypt.newKey();
        List<byte[]> p = packets(new MediaCrypt(key), 77, frame());
        ByteBuffer h = ByteBuffer.wrap(p.get(p.size() - 1));
        int len = h.getInt(16);
        assertEquals(5000 + MediaCrypt.TAG_BYTES, len);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] x : p) joined.write(x, Sender.HEADER, x.length - Sender.HEADER);
        byte[] wire = joined.toByteArray();
        assertEquals(len, wire.length);
        assertTrue(!Arrays.equals(Arrays.copyOf(wire, 5000), frame()));
        MediaCrypt rx = new MediaCrypt(key);
        int n = rx.open(1, 77, MediaCrypt.aad(p.get(p.size() - 1), 0), wire, 0, wire.length);
        assertEquals(5000, n);
        assertArrayEquals(frame(), Arrays.copyOf(wire, n));
        // under another sequence number (a replayed frame) it does not open
        byte[] again = joined.toByteArray();
        assertEquals(-1, rx.open(1, 78, MediaCrypt.aad(p.get(0), 0), again, 0, again.length));
    }
}
