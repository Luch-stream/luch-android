// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch.sync;

/**
 * When to show a decoded picture of pts p at {@code T = local(p + D) + offset − panel} (docs: sync, 6.5).
 *
 * <p>{@code MediaCodec.releaseOutputBuffer(index, t)} shows a frame "at the VSYNC at or after" t. Asking for
 * {@code T − P/2} lands it within ±P/2 of T. The call is best made about two periods before (the documented practice);
 * a frame due later than that waits, and one due more than 900 ms ahead is not held at all (the platform takes
 * release times only within a second). Late frames: shown at once while interactive, dropped while watching when a
 * newer one is already decoded. Compressed frames wait too, so the decoder never holds more than a few: they go in
 * when their picture is due within the decode time and two periods.
 */
public final class VideoScheduler {
    /** Release with this time to drop the frame. */
    public static final long DROP = Long.MIN_VALUE;
    public static final long MAX_AHEAD_NS = 900_000_000L;

    private VideoScheduler() {
    }

    /** The time to pass to releaseOutputBuffer for a frame due at {@code targetNs}: T − P/2, or now when late. */
    public static long releaseNs(long targetNs, long nowNs, long periodNs, boolean watching, boolean newerDecoded) {
        long at = targetNs - periodNs / 2;
        if (at >= nowNs) return Math.min(at, nowNs + MAX_AHEAD_NS);
        if (watching && newerDecoded) return DROP;
        return nowNs;
    }

    /** Until when to keep a decoded frame before releasing it: two periods ahead of its release time. */
    public static long holdUntilNs(long releaseNs, long periodNs) {
        return releaseNs - 2 * periodNs;
    }

    /** When to give a compressed frame due at {@code targetNs} to the decoder. */
    public static long feedNs(long targetNs, long decodeNs, long periodNs) {
        return targetNs - decodeNs - 3 * periodNs;
    }
}
