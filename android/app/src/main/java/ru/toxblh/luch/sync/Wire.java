// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The sync parts of the media packet (PROTOCOL.md 4 and 4h): the playout delay D in the header's last u16, and kind 3
 * clock packets. luchd has the same layout (luchd/src/sync.rs) and both test the same bytes.
 */
public final class Wire {
    public static final int HEADER = 24;
    public static final int KIND_CLOCK = 3;
    public static final int FLAG_TIMELINE = 8, FLAG_REPLY = 16;
    /** A request: the header and t1. A reply: the header and t1, t2, t3. */
    public static final int REQUEST_LEN = HEADER + 8, REPLY_LEN = HEADER + 24;

    private Wire() {
    }

    /** The playout delay of a media packet, µs (0: as soon as possible, as before). */
    public static long delayUs(byte[] packet) {
        return ((packet[22] & 0xffL) << 8 | (packet[23] & 0xffL)) * 100;
    }

    /** A clock request from this receiver: probe number {@code seq}, sent at local µs {@code t1}. */
    public static byte[] request(int seq, long t1) {
        ByteBuffer b = ByteBuffer.allocate(REQUEST_LEN).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) KIND_CLOCK).put((byte) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putInt(seq).putInt((int) t1).putInt(8).putShort((short) 0).putShort((short) 0).putLong(t1);
        return b.array();
    }

    /** A reply's {t1, t2, t3}, or null when {@code p} is not a reply to us. */
    public static long[] reply(byte[] p, int len) {
        if (len < REPLY_LEN || (p[0] & 0xff) != KIND_CLOCK || (p[1] & FLAG_REPLY) == 0) return null;
        ByteBuffer b = ByteBuffer.wrap(p, HEADER, 24).order(ByteOrder.BIG_ENDIAN);
        return new long[]{b.getLong(), b.getLong(), b.getLong()};
    }

    /**
     * The full 64-bit pts of a packet's low 32 bits, the one nearest to {@code senderNowUs} (the sender's clock now,
     * from the estimator): the header's pts wraps every 71 minutes.
     */
    public static long unwrapPts(int pts32, long senderNowUs) {
        long low = pts32 & 0xffffffffL;
        long base = senderNowUs & ~0xffffffffL;
        long c = base | low;
        if (c - senderNowUs > 0x80000000L) c -= 0x100000000L;
        else if (senderNowUs - c > 0x80000000L) c += 0x100000000L;
        return c;
    }
}
