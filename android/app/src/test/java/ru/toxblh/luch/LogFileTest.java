// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TimeZone;

public class LogFileTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File f) throws IOException {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    /** "line 07 ......\n": 20 bytes each. */
    private static String line(int i) {
        return String.format("line %02d .........\n", i);
    }

    @Test
    public void aLineIsTimeLevelThreadAndMessage() {
        LogFile f = new LogFile(tmp.getRoot(), 1000, 4);
        f.zone(TimeZone.getTimeZone("GMT+01:00"));
        assertEquals("2026-01-31 20:15:02.123+0100 I luch-video: decoder reset: new surface\n",
                f.format(1769886902123L, 'I', "luch-video", "decoder reset: new surface", null));
        String withStack = f.format(0, 'W', "main", "render", new IllegalStateException("gone"));
        assertTrue(withStack, withStack.contains(" W main: render\njava.lang.IllegalStateException: gone\n\tat "));
        assertTrue(withStack.endsWith("\n"));
    }

    @Test
    public void aLongLineIsCut() {
        LogFile f = new LogFile(tmp.getRoot(), 1 << 20, 4);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < LogFile.MAX_LINE; i++) huge.append('x');
        assertEquals(LogFile.MAX_LINE + 1, f.format(0, 'I', "t", huge.toString(), null).length());
    }

    @Test
    public void twoFilesAtMostTheOlderMovedAside() throws IOException {
        File dir = tmp.getRoot();
        LogFile f = new LogFile(dir, 100, 4);   // five lines a file
        for (int i = 0; i < 12; i++) f.write(line(i));
        // 0-4 filled the first file, 5-9 the next one, which then moved aside for 10-11; 0-4 are gone.
        StringBuilder older = new StringBuilder(), newer = new StringBuilder();
        for (int i = 5; i < 10; i++) older.append(line(i));
        for (int i = 10; i < 12; i++) newer.append(line(i));
        assertEquals(older.toString(), read(new File(dir, LogFile.OLDER)));
        assertEquals(newer.toString(), read(new File(dir, LogFile.NAME)));
        assertEquals(2, dir.list().length);
    }

    @Test
    public void aNewProcessGoesOnInTheSameFile() throws IOException {
        File dir = tmp.getRoot();
        new LogFile(dir, 100, 4).write(line(0));
        LogFile again = new LogFile(dir, 100, 4);
        for (int i = 1; i < 6; i++) again.write(line(i));
        assertEquals(line(0) + line(1) + line(2) + line(3) + line(4), read(new File(dir, LogFile.OLDER)));
        assertEquals(line(5), read(new File(dir, LogFile.NAME)));
    }

    @Test
    public void theTailIsTheNewestWholeLinesOldestFirst() {
        LogFile f = new LogFile(tmp.getRoot(), 100, 4);
        for (int i = 0; i < 8; i++) f.write(line(i));   // 0-4 in luch.log.1, 5-7 in luch.log
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 8; i++) all.append(line(i));
        assertEquals(all.toString(), new String(f.tail(1 << 20), StandardCharsets.UTF_8));
        // 70 bytes reach into line 4 (from luch.log.1): only 5-7 are whole.
        assertEquals(line(5) + line(6) + line(7), new String(f.tail(70), StandardCharsets.UTF_8));
        // Inside luch.log alone.
        assertEquals(line(7), new String(f.tail(30), StandardCharsets.UTF_8));
        assertEquals(0, new LogFile(tmp.getRoot().toPath().resolve("none").toFile(), 100, 4).tail(100).length);
    }

    @Test
    public void theWriterThreadWritesWhatIsQueued() throws IOException {
        LogFile f = new LogFile(tmp.getRoot(), 1 << 20, 16);
        f.start();
        for (int i = 0; i < 5; i++) assertTrue(f.add(0, 'I', "t", "n" + i, null));
        f.flush(5000);
        String text = read(new File(tmp.getRoot(), LogFile.NAME));
        for (int i = 0; i < 5; i++) assertTrue(text, text.contains(" I t: n" + i + "\n"));
        assertTrue(text.indexOf("n0") < text.indexOf("n4"));
    }

    @Test
    public void aFullQueueDropsAndSaysSo() throws IOException {
        LogFile f = new LogFile(tmp.getRoot(), 1 << 20, 2);
        assertTrue(f.add(0, 'I', "t", "kept 1", null));
        assertTrue(f.add(0, 'I', "t", "kept 2", null));
        assertFalse(f.add(0, 'I', "t", "dropped", null));   // the caller does not wait
        f.start();
        f.flush(5000);
        String text = read(new File(tmp.getRoot(), LogFile.NAME));
        assertTrue(text, text.contains("luch-log: 1 lines dropped\n"));
        assertTrue(text.contains("kept 1") && text.contains("kept 2"));
        assertFalse(text.contains(": dropped"));
    }
}
