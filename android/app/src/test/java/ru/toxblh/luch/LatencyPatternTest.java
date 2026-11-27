// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The receiver's latency lab pattern against the sender's reference, luch-latency/src/pattern.rs (PROTOCOL.md 4h). */
public class LatencyPatternTest {
    /** The sender's encoder's rows for this text (pattern.rs, GOLDEN): both must draw the very same code. */
    private static final String GOLDEN_TEXT = "LUCH1;R;v=1.0;mo=Living room TV;a=14;hz=60.0";
    private static final String[] GOLDEN = {
            "11111110010011011011001111111",
            "10000010001110010011001000001",
            "10111010111010000001001011101",
            "10111010011100111000001011101",
            "10111010001001111011001011101",
            "10000010010001011101101000001",
            "11111110101010101010101111111",
            "00000000111011010101000000000",
            "11101111101101000101111000100",
            "01010000001100100110101101110",
            "10100111110001100110101000111",
            "11101000100101011100110011001",
            "10110011100011110100001001001",
            "00001001010110100000101101010",
            "11011011111110000100000111111",
            "11001000111011110111001101000",
            "11000111001101101101111101011",
            "00111101000101101000111101100",
            "10000111011001000100110111011",
            "01110001111101000111100010010",
            "10111110000011110101111111110",
            "00000000111111100001100011010",
            "11111110111110101000101010011",
            "10000010101011001110100011010",
            "10111010101111100110111111010",
            "10111010010100011100000011111",
            "10111010100011001011000011001",
            "10000010101101011100101110010",
            "11111110110001100000110111011",
    };

    @Test
    public void qrCodeIsTheSendersBitForBit() {
        QrCode code = QrCode.encodeLow(GOLDEN_TEXT);
        String[] rows = new String[code.size];
        for (int y = 0; y < code.size; y++) {
            StringBuilder b = new StringBuilder();
            for (int x = 0; x < code.size; x++) b.append(code.get(x, y) ? '1' : '0');
            rows[y] = b.toString();
        }
        assertArrayEquals(GOLDEN, rows);
    }

    @Test
    public void grayCodeChangesOneCellPerFrameAndWraps() {
        for (long n = 0; n < 4096 + 3; n++) {
            int changed = 0;
            for (int c = 2; c < LatencyPattern.CELLS; c++) {
                if (LatencyPattern.cellDark(c, n) != LatencyPattern.cellDark(c, n + 1)) changed++;
            }
            assertEquals("from " + n, 1, changed);
            assertTrue(LatencyPattern.cellDark(1, n) && !LatencyPattern.cellDark(0, n));
        }
        // 4095 to 4096 (shown as 0): only the top bit, cell 2.
        assertTrue(LatencyPattern.cellDark(2, 4095) && !LatencyPattern.cellDark(2, 4096));
    }

    @Test
    public void layoutIsTheSenders() {
        assertArrayEquals(new float[] {-0.15f, -0.15f, 1.55f, 2.2f}, LatencyPattern.BOX, 0f);
        // Cell 7 starts the second row; cell 13, the last bit, ends it.
        assertArrayEquals(new float[] {0f, 1.65f, 0.2f, 2.05f}, LatencyPattern.cell(7), 1e-6f);
        assertArrayEquals(new float[] {1.2f, 1.65f, 1.4f, 2.05f}, LatencyPattern.cell(13), 1e-6f);
        // 1920x1080: the QR code is 324 pixels, its box ends 32 pixels from the right and bottom edges.
        float[] o = LatencyPattern.origin(1920, 1080);
        assertEquals(1920 - 0.03f * 1080 - 1.55f * 324, o[0], 1e-3f);
        assertEquals(1080 - 0.03f * 1080 - 2.2f * 324, o[1], 1e-3f);
    }

    @Test
    public void payloadSaysWhoDrewItAndLeavesOutWhatIsUnknown() {
        assertEquals("LUCH1;R;v=0.9;mo=Living room TV;a=14;d=c2.android.hevc.decoder;dm=1920x1080@60;hz=59.94;s=1920x1080@60Hz;g=1",
                LatencyPattern.payload("0.9", "Living room TV", "14", "c2.android.hevc.decoder", "1920x1080@60", 59.94,
                        "1920x1080@60Hz", true));
        assertEquals("LUCH1;R;v=0.9;mo=a,b", LatencyPattern.payload("0.9", "a;b", "", null, "", 0, "?", false));
    }
}
