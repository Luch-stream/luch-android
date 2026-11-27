// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A log file that never grows past two files of {@code maxBytes}: luch.log, and luch.log.1 before it. Lines are queued
 * by the threads that log (the video and audio threads among them) and written by one thread of its own, so no caller
 * waits on the disk; a full queue drops lines and the next written line says how many. Plain Java, for the JVM tests.
 */
final class LogFile {
    /** Each of the two files; 2 MB in all hold many sessions (a stats line every 5 s is about 200 KB an hour). */
    static final int MAX_BYTES = 1 << 20;
    /** Lines waiting at most; a burst beyond it is dropped. */
    static final int QUEUE = 2048;
    static final String NAME = "luch.log", OLDER = "luch.log.1";
    /** A longer line is cut: one message or stack trace must not push out the rest. */
    static final int MAX_LINE = 16 * 1024;

    private final File dir;
    private final int maxBytes;
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicInteger dropped = new AtomicInteger();
    private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.ROOT);
    /** Writes, rotation and reads of the files. */
    private final Object lock = new Object();
    private OutputStream out;
    private long size;

    LogFile(File dir, int maxBytes, int queueSize) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.queue = new ArrayBlockingQueue<>(queueSize);
    }

    /** For the tests: their own time zone. */
    void zone(TimeZone zone) {
        time.setTimeZone(zone);
    }

    void start() {
        Thread t = new Thread(this::writeLoop, "luch-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Queues a line; false when the queue is full and it was dropped. Never blocks. */
    boolean add(long timeMs, char level, String thread, String msg, Throwable tr) {
        if (queue.offer(new Line(timeMs, level, thread, msg, tr))) return true;
        dropped.incrementAndGet();
        return false;
    }

    /** Waits until everything queued so far is in the file, at most {@code ms}. */
    void flush(long ms) {
        CountDownLatch done = new CountDownLatch(1);
        if (!queue.offer(done)) return;
        try {
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        while (true) {
            Object o;
            try {
                o = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (o instanceof Line) {
                int lost = dropped.getAndSet(0);
                Line l = (Line) o;
                if (lost > 0) write(format(l.time, 'W', "luch-log", lost + " lines dropped", null));
                write(format(l.time, l.level, l.thread, l.msg, l.tr));
            } else {
                ((CountDownLatch) o).countDown();
            }
        }
    }

    /** "2026-01-31 20:15:02.123+0100 I luch-video: message", then the stack trace, if any. */
    String format(long timeMs, char level, String thread, String msg, Throwable tr) {
        StringBuilder b = new StringBuilder(64 + (msg == null ? 4 : msg.length()));
        b.append(time.format(new Date(timeMs))).append(' ').append(level).append(' ').append(thread).append(": ")
                .append(msg);
        if (tr != null) {
            StringWriter w = new StringWriter();
            tr.printStackTrace(new PrintWriter(w));
            b.append('\n').append(w.toString().trim());
        }
        if (b.length() > MAX_LINE) b.setLength(MAX_LINE);
        return b.append('\n').toString();
    }

    /**
     * Appends one line, first moving the file to luch.log.1 when the line would take it past maxBytes. Unbuffered: a
     * crash loses nothing already written, and lines come a few a second.
     */
    void write(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            try {
                if (out == null) open();
                if (size > 0 && size + bytes.length > maxBytes) {
                    out.close();
                    File older = new File(dir, OLDER);
                    older.delete();
                    new File(dir, NAME).renameTo(older);
                    open();
                }
                out.write(bytes);
                size += bytes.length;
            } catch (IOException e) {
                closeQuietly();   // the disk is full or gone: try again with the next line
            }
        }
    }

    private void open() throws IOException {
        dir.mkdirs();
        File f = new File(dir, NAME);
        out = new FileOutputStream(f, true);
        size = f.length();
    }

    /**
     * The last {@code maxBytes} of the log, oldest first, starting at a whole line: the end of luch.log.1, then
     * luch.log.
     */
    byte[] tail(int maxBytes) {
        synchronized (lock) {
            File newer = new File(dir, NAME), older = new File(dir, OLDER);
            long fromNewer = Math.min(newer.length(), maxBytes);
            long fromOlder = Math.min(older.length(), maxBytes - fromNewer);
            byte[] all = new byte[(int) (fromOlder + fromNewer)];
            try {
                read(older, fromOlder, all, 0);
                read(newer, fromNewer, all, (int) fromOlder);
            } catch (IOException e) {
                return new byte[0];
            }
            int start = 0;
            if (all.length < older.length() + newer.length()) {   // cut: the first line is only a part of one
                while (start < all.length && all[start] != '\n') start++;
                start = Math.min(start + 1, all.length);
            }
            return start == 0 ? all : java.util.Arrays.copyOfRange(all, start, all.length);
        }
    }

    /** The last {@code n} bytes of {@code f} into {@code to} at {@code at}. */
    private static void read(File f, long n, byte[] to, int at) throws IOException {
        if (n <= 0) return;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.seek(r.length() - n);
            r.readFully(to, at, (int) n);
        }
    }

    private void closeQuietly() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    private static final class Line {
        final long time;
        final char level;
        final String thread, msg;
        final Throwable tr;

        Line(long time, char level, String thread, String msg, Throwable tr) {
            this.time = time;
            this.level = level;
            this.thread = thread;
            this.msg = msg;
            this.tr = tr;
        }
    }
}
