// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

/**
 * The latency lab's pattern (PROTOCOL.md, section 4h), as the sender's luch-latency/src/pattern.rs defines it: a QR
 * code saying who drew it, over two rows of cells that count the frames in Gray code. Everything is in units of the
 * QR code's side, its top-left corner at the origin, so a camera's analyser that finds the code finds every cell.
 */
final class LatencyPattern {
    /** The white box around the pattern: left, top, right, bottom. */
    static final float[] BOX = {-0.15f, -0.15f, 1.55f, 2.2f};
    /** Top and bottom of each row of cells. */
    static final float[][] ROWS = {{1.15f, 1.55f}, {1.65f, 2.05f}};
    static final int PER_ROW = 7;
    static final float CELL_W = 0.2f;
    static final int BITS = 12;
    /** Cell 0 is always white and cell 1 always black, then the bits, the highest first. */
    static final int CELLS = 2 + BITS;
    /** The receiver's pattern: its QR code is this share of the screen's height, in the bottom right corner. */
    static final float SIDE = 0.3f;
    static final float MARGIN = 0.03f;

    private LatencyPattern() {
    }

    static int gray(long n) {
        int v = (int) (n & ((1 << BITS) - 1));
        return v ^ (v >> 1);
    }

    static boolean cellDark(int cell, long n) {
        if (cell == 0) return false;
        if (cell == 1) return true;
        return ((gray(n) >> (BITS - 1 - (cell - 2))) & 1) == 1;
    }

    /** Cell {@code i}: left, top, right, bottom. */
    static float[] cell(int i) {
        int row = i / PER_ROW, col = i % PER_ROW;
        return new float[] {col * CELL_W, ROWS[row][0], (col + 1) * CELL_W, ROWS[row][1]};
    }

    /** Where the QR code's top-left corner goes on a screen {@code w} by {@code h} pixels; its side is SIDE * h. */
    static float[] origin(int w, int h) {
        float unit = SIDE * h;
        return new float[] {w - MARGIN * h - BOX[2] * unit, h - MARGIN * h - BOX[3] * unit};
    }

    /**
     * What the receiver's QR code says: {@code LUCH1;R;key=value;...}. Short keys keep the code small and its modules
     * big for the camera; empty values are left out, and {@code ;} never appears in a value.
     */
    static String payload(String app, String model, String android, String decoder, String displayMode, double hz,
            String surface, boolean gameMode) {
        StringBuilder b = new StringBuilder("LUCH1;R");
        field(b, "v", app);
        field(b, "mo", model);
        field(b, "a", android);
        field(b, "d", decoder);
        field(b, "dm", displayMode);
        field(b, "hz", hz > 0 ? String.format(java.util.Locale.ROOT, "%.2f", hz) : "");
        field(b, "s", surface);
        field(b, "g", gameMode ? "1" : "");
        return b.toString();
    }

    private static void field(StringBuilder b, String key, String value) {
        if (value == null || value.isEmpty() || value.equals("?")) return;
        b.append(';').append(key).append('=').append(value.replace(';', ',').replace('\n', ','));
    }
}
