// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Crash and error reports, kept on the TV until a sender that asks for them ({@code "want":["reports"]} in its hello)
 * takes them (PROTOCOL.md, section 6). There is no server: the sender shows them to its user, who decides whether to
 * file them. Reports hold no logs and no addresses or names: only what failed, where, and on what hardware.
 */
final class Reports {
    /** Kept at most; the oldest go first. A repeat of the same failure only counts up. */
    static final int MAX = 10;
    private static final int MAX_STACK = 8 * 1024;
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    /** Two or more colons between hex groups ("fe80::1", "::1", "2a02:6b8:0:1::5"), not "File.java:12". */
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:.])(?=[0-9a-fA-F:]*[0-9a-fA-F])[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}(?![\\w:])");

    private Reports() {
    }

    /** Records crashes of any thread before the system kills the app. */
    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                crash(app, t, e);
                Log.e(LuchService.TAG, "crash in " + t.getName(), e);
                Log.flush(500);   // the log's own thread must write it before the process goes
            } catch (Throwable ignored) {
                // never let the report hide the crash
            }
            if (next != null) next.uncaughtException(t, e);
        });
    }

    static void crash(Context context, Thread thread, Throwable e) {
        save(context, "crash", String.valueOf(e), e, new JSONObject(), thread.getName());
    }

    /** A failure the app survived but the person noticed (no picture, no sound). {@code e} may be null. */
    static void error(Context context, String what, Throwable e, JSONObject details) {
        save(context, "error", what, e, details == null ? new JSONObject() : details, Thread.currentThread().getName());
    }

    /** Reports not yet taken, oldest first. */
    static synchronized JSONArray pending(Context context) {
        JSONArray out = new JSONArray();
        for (File f : files(context)) {
            try {
                out.put(new JSONObject(new String(Compat.readFile(f), StandardCharsets.UTF_8)));
            } catch (IOException | JSONException e) {
                f.delete();   // unreadable: of no use to anyone
            }
        }
        return out;
    }

    /** The sender has them: forget these. */
    static synchronized void taken(Context context, JSONArray ids) {
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.optString(i);
            if (!id.matches("[0-9a-f-]{36}")) continue;
            for (File f : files(context)) if (f.getName().endsWith("-" + id + ".json")) f.delete();
        }
    }

    /** Hides addresses: exception messages carry them ("failed to connect to /192.168.1.5"). */
    static String scrub(String s) {
        if (s == null) return null;
        return IPV6.matcher(IPV4.matcher(s).replaceAll("<ip>")).replaceAll("<ip>");
    }

    private static synchronized void save(Context context, String kind, String what, Throwable e, JSONObject details,
                                          String thread) {
        try {
            String stack = e == null ? "" : scrub(stackOf(e));
            if (stack.length() > MAX_STACK) stack = stack.substring(0, MAX_STACK);
            String signature = kind + "|" + scrub(what) + "|" + firstFrames(stack);
            for (File f : files(context)) {
                JSONObject old = new JSONObject(new String(Compat.readFile(f), StandardCharsets.UTF_8));
                if (signature.equals(old.optString("signature"))) {
                    old.put("count", old.optInt("count", 1) + 1).put("last_time", System.currentTimeMillis());
                    write(f, old);
                    return;
                }
            }
            String id = UUID.randomUUID().toString();
            JSONObject r = new JSONObject()
                    .put("id", id)
                    .put("kind", kind)
                    .put("time", System.currentTimeMillis())
                    .put("count", 1)
                    .put("what", scrub(what))
                    .put("thread", thread)
                    .put("stack", stack)
                    .put("details", details)
                    .put("app", appVersion(context))
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("hardware", Build.HARDWARE)
                    .put("signature", signature);
            // The time leads the name: file times are only to the second on older Android, the name keeps the order.
            write(new File(dir(context), String.format(java.util.Locale.ROOT, "%013d-%s.json",
                    System.currentTimeMillis(), id)), r);
            File[] all = files(context);
            for (int i = 0; i < all.length - MAX; i++) all[i].delete();
            Log.i(LuchService.TAG, "report kept: " + kind + " " + r.optString("what"));
        } catch (IOException | JSONException ex) {
            Log.w(LuchService.TAG, "could not keep a report", ex);
        }
    }

    private static String stackOf(Throwable e) {
        StringWriter w = new StringWriter();
        e.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    /** The exception lines and the first frames: what tells two failures apart. */
    private static String firstFrames(String stack) {
        StringBuilder b = new StringBuilder();
        int frames = 0;
        for (String line : stack.split("\n")) {
            if (line.trim().startsWith("at ")) {
                if (++frames > 3) continue;
            }
            b.append(line.trim()).append('\n');
        }
        return b.toString();
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static File dir(Context context) {
        File d = new File(context.getApplicationContext().getFilesDir(), "reports");
        d.mkdirs();
        return d;
    }

    /** Oldest first. */
    private static File[] files(Context context) {
        File[] f = dir(context).listFiles((d, name) -> name.endsWith(".json"));
        if (f == null) return new File[0];
        Arrays.sort(f, (a, b) -> a.getName().compareTo(b.getName()));
        return f;
    }

    private static void write(File f, JSONObject o) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + tmp);
    }
}
