// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AboutTest {
    @Test
    public void whatHappenedFirstThenFactsThenBlocks() {
        String text = new About("Luch problem report, 2026-01-31 20:15 +0100", "  HEVC stutters\nafter a minute ")
                .fact("Luch", "0.4 (github, release)")
                .fact("Android", "14 (sdk 34)")
                .fact("Name", "")
                .fact("Limits", null)
                .fact("Decoders", java.util.Arrays.asList("video/avc", "video/hevc"))
                .block("Bench", null)
                .block("Last session", "start {\"t\":\"start\"}\nstats {\"fps\":60}")
                .fact("Crash reports kept", 0)
                .text();
        assertEquals("Luch problem report, 2026-01-31 20:15 +0100\n"
                + "\n"
                + "What happened:\n"
                + "  HEVC stutters\n"
                + "  after a minute\n"
                + "\n"
                + "Luch: 0.4 (github, release)\n"
                + "Android: 14 (sdk 34)\n"
                + "Decoders: [video/avc, video/hevc]\n"
                + "\n"
                + "Last session:\n"
                + "  start {\"t\":\"start\"}\n"
                + "  stats {\"fps\":60}\n"
                + "\n"
                + "Crash reports kept: 0\n", text);
    }

    @Test
    public void nothingWrittenIsSaid() {
        assertEquals("T\n\nWhat happened:\n  (nothing written)\n", new About("T", " \n ").text());
        assertEquals("T\n\nWhat happened:\n  (nothing written)\n", new About("T", null).text());
    }

    @Test
    public void aFactStaysOnOneLine() {
        assertEquals("T\n\nWhat happened:\n  x\n\nLimits: a b\n", new About("T", "x").fact("Limits", "a\nb").text());
    }
}
