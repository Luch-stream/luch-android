// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.os.Build;

import java.io.File;

/**
 * Luch's log: each line goes to logcat as before and also to a file of our own (LogFile), the one a sender gets with
 * {"t":"logs"} (PROTOCOL.md 6a) and the one in a phone's "Report a problem". Named like {@code android.util.Log} on
 * purpose: the app's classes use this one by not importing Android's, so the call sites stay as they were. A line is
 * only put in a queue here; a thread of its own writes it, and a full queue drops it rather than wait.
 */
final class Log {
    private static volatile LogFile file;

    private Log() {
    }

    /** Opens the file in the app's own files (logs/), once per process, with a line saying what runs. */
    static synchronized void start(Context context) {
        if (file != null) return;
        LogFile f = new LogFile(new File(context.getFilesDir(), "logs"), LogFile.MAX_BYTES, LogFile.QUEUE);
        f.start();
        file = f;
        i(LuchService.TAG, "Luch " + BuildConfig.VERSION_NAME + " (" + BuildConfig.FLAVOR + ") started: Android "
                + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + "), " + Luch.model() + ", "
                + Build.HARDWARE + ", " + java.util.TimeZone.getDefault().getID());
    }

    static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
        add('I', msg, null);
    }

    static void i(String tag, String msg, Throwable tr) {
        android.util.Log.i(tag, msg, tr);
        add('I', msg, tr);
    }

    static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
        add('W', msg, null);
    }

    static void w(String tag, String msg, Throwable tr) {
        android.util.Log.w(tag, msg, tr);
        add('W', msg, tr);
    }

    static void e(String tag, String msg, Throwable tr) {
        android.util.Log.e(tag, msg, tr);
        add('E', msg, tr);
    }

    /** The last {@code maxBytes} of the log, whole lines; empty before start. */
    static byte[] tail(int maxBytes) {
        LogFile f = file;
        return f == null ? new byte[0] : f.tail(maxBytes);
    }

    /** Waits up to {@code ms} for the queue to be written: a crash is about to end the process. */
    static void flush(long ms) {
        LogFile f = file;
        if (f != null) f.flush(ms);
    }

    private static void add(char level, String msg, Throwable tr) {
        LogFile f = file;
        if (f != null) f.add(System.currentTimeMillis(), level, Thread.currentThread().getName(), msg, tr);
    }
}
