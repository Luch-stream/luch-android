// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The QR code with the card's link, drawn as the TV draws it (round light dots on the dark card, QrView) into a
 * PGM picture. luchd's find/qr.rs reads that very picture back (luchd/tests/receiver-qr.pgm): both ends of the QR
 * path, written apart.
 */
public class QrCodeTest {
    static final String LINK = new Card(Card.RECEIVER, "3f0c2a8e", "192.168.1.20", 47800, "0badf00d").link();

    @Test
    public void theLinkFitsASmallCode() throws IOException {
        QrCode code = QrCode.encode(LINK);
        assertEquals("luch://connect?host=192.168.1.20&port=47800&t=0badf00d", LINK);
        // Version 6 (41 modules) at error correction H: a third of the dots may be lost, as on the TV.
        assertEquals(41, code.size);
        assertTrue(code.get(0, 0) && code.get(6, 6) && !code.get(7, 7));
        int cell = 6, quiet = 4, size = (code.size + 2 * quiet) * cell;
        byte[] px = new byte[size * size];
        java.util.Arrays.fill(px, (byte) 40);
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (!code.get(x, y)) continue;
                float r = cell * (code.isFunction(x, y) ? .5f : .44f);
                for (int dy = 0; dy < cell; dy++) {
                    for (int dx = 0; dx < cell; dx++) {
                        float fx = dx + .5f - cell / 2f, fy = dy + .5f - cell / 2f;
                        if (fx * fx + fy * fy <= r * r) {
                            px[((y + quiet) * cell + dy) * size + (x + quiet) * cell + dx] = (byte) 210;
                        }
                    }
                }
            }
        }
        File out = new File("build/receiver-qr.pgm");
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(("P5\n" + size + " " + size + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            f.write(px);
        }
        assertTrue(out.length() > size * size);
    }
}
