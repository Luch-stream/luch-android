// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Report a problem" on the status screen: one file with this device's log (the last MB), about.txt and the crash
 * reports kept here, for the person to send wherever they like (a chat, an issue). Nothing is sent by Luch itself.
 * The share sheet takes it; a TV without one gets it saved in Download/.
 */
final class ProblemReport {
    static final String NAME_PATTERN = "Luch-report-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip";
    private static final int LOG_BYTES = 1 << 20;

    private ProblemReport() {
    }

    /** The form: what happened, and whether to hide addresses and names (on unless the person unticks it). */
    static void ask(Activity activity) {
        int pad = Ui.dp(activity, 24);
        EditText what = new EditText(activity);
        what.setHint(R.string.report_what);
        what.setMinLines(3);
        what.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        CheckBox hide = new CheckBox(activity);
        hide.setText(R.string.report_hide);
        hide.setChecked(true);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(activity, 8), pad, 0);
        int wide = ViewGroup.LayoutParams.MATCH_PARENT, high = ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(what, new LinearLayout.LayoutParams(wide, high));
        box.addView(hide, new LinearLayout.LayoutParams(wide, high));
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.report_problem)
                .setMessage(R.string.report_message)
                .setView(box)
                .setPositiveButton(R.string.report_make, (d, w) -> {
                    String text = what.getText().toString();
                    boolean hidden = hide.isChecked();
                    new Thread(() -> make(activity, text, hidden), "luch-report").start();
                })
                .setNegativeButton(R.string.rename_cancel, null)
                .show();
    }

    private static void make(Activity activity, String what, boolean hide) {
        Context app = activity.getApplicationContext();
        File zip;
        try {
            zip = build(app, what, hide);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(LuchService.TAG, "report failed", e);
            tell(activity, app.getString(R.string.report_failed, String.valueOf(e.getMessage())));
            return;
        }
        Log.i(LuchService.TAG, "report made: " + zip.length() + " bytes"
                + (hide ? ", addresses and names hidden" : ""));
        Uri uri = ReportProvider.uri(app, zip);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, app.getString(R.string.report_subject))
                .putExtra(Intent.EXTRA_TEXT, description(app, what))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));   // the grant reaches the app picked in the chooser
        Intent chooser = Intent.createChooser(send, app.getString(R.string.report_problem));
        PackageManager pm = app.getPackageManager();
        if (!pm.queryIntentActivities(send, 0).isEmpty() && chooser.resolveActivity(pm) != null) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) activity.startActivity(chooser);
            });
            return;
        }
        // A TV may have nothing to share with: the file goes to Download/, and the person is told where.
        String message;
        try {
            message = app.getString(R.string.report_saved, save(app, zip));
        } catch (IOException | RuntimeException e) {
            Log.w(LuchService.TAG, "report not saved", e);
            message = app.getString(R.string.report_failed, String.valueOf(e.getMessage()));
        }
        tell(activity, message);
    }

    private static void tell(Activity activity, String message) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.report_problem)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    /** Luch-report-YYYY-MM-DD-HHMM.zip: luch.log, about.txt and reports.json (when there are any). */
    private static File build(Context context, String what, boolean hide) throws IOException, JSONException {
        Redact redact = hide ? names(context) : null;
        File dir = ReportProvider.dir(context);
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) old[i].delete();   // one report at a time is enough
        String when = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date());
        File zip = new File(dir, "Luch-report-" + when + ".zip");
        String log = new String(Log.tail(LOG_BYTES), StandardCharsets.UTF_8);
        JSONArray reports = Reports.pending(context);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(z, "about.txt", hidden(redact, about(context, what, reports.length())));
            entry(z, "luch.log", hidden(redact, log));
            if (reports.length() > 0) entry(z, "reports.json", hidden(redact, reports.toString(2)));
        }
        return zip;
    }

    /** This device is "This device"; the computers it knows, "Computer 1", 2...; other receivers, "Receiver 1"... */
    private static Redact names(Context context) {
        Redact r = new Redact();
        // Unnamed, a device goes by its model: that stays, it is what the report is about.
        for (String own : new String[]{Luch.customName(context), Luch.systemName(context)}) {
            if (!own.equals(Build.MODEL)) r.name(own, "This device");
        }
        java.util.List<String> computers = new java.util.ArrayList<>();
        for (Gate.Sender s : ControlServer.gate(context).paired()) computers.add(s.name);
        for (Luch.Peer p : Luch.connected()) computers.add(p.name);
        for (Luch.Peer p : Luch.nearby()) computers.add(p.name);
        int n = 1;
        for (String c : computers) if (r.name(c, "Computer " + n)) n++;
        n = 1;
        for (Luch.Receiver x : Luch.receivers()) if (r.name(x.name, "Receiver " + n)) n++;
        return r;
    }

    private static String hidden(Redact r, String text) {
        return r == null ? text : r.apply(text);
    }

    private static String about(Context context, String what, int reports) throws JSONException {
        Gate gate = ControlServer.gate(context);
        JSONObject bench = Bench.cached(context);
        String start = MirrorSession.lastStart, stats = MirrorSession.lastStats;
        return new About("Luch problem report, " + new SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.ROOT)
                .format(new Date()), what)
                .fact("Luch", BuildConfig.VERSION_NAME + " (" + BuildConfig.FLAVOR + ", " + BuildConfig.BUILD_TYPE
                        + ")")
                .fact("Android", Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")")
                .fact("Device", Luch.model() + ", " + (Ui.kind(context) == 2 ? "TV" : Ui.kind(context) == 1 ? "tablet"
                        : "phone"))
                .fact("Hardware", Build.HARDWARE + " / " + Build.BOARD)
                .fact("Name", Luch.name(context))
                .fact("Decoders", Luch.decoders())
                .fact("Largest picture", Luch.maxSize())
                .fact("Limits", Caps.limits())
                .fact("Paired computers", gate.paired().size() + (gate.guest() ? ", guest mode" : ""))
                .fact("Crash reports kept", reports)
                .block("What the receiver can do (welcome hw)", Caps.collect(context).toString(2))
                .block("Bench", bench == null ? null : bench.toString(2))
                .block("Last session", start == null ? null
                        : "start " + start + (stats == null ? "" : "\nstats " + stats))
                .text();
    }

    /** The short text that goes with the file: what happened, and on what. */
    private static String description(Context context, String what) {
        return (what.trim().isEmpty() ? "" : what.trim() + "\n\n") + "Luch " + BuildConfig.VERSION_NAME + ", Android "
                + Build.VERSION.RELEASE + ", " + Luch.model();
    }

    private static void entry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** Where a device without a share sheet keeps it: Download/ (Android 10+), else the app's own external folder. */
    private static String save(Context context, File zip) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, zip.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("no Download folder");
            try (OutputStream out = context.getContentResolver().openOutputStream(u)) {
                copy(zip, out);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + zip.getName();
        }
        // Download/ itself needs the storage permission before Android 10; this folder does not.
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new IOException("no shared storage");
        File to = new File(dir, zip.getName());
        try (OutputStream out = new FileOutputStream(to)) {
            copy(zip, out);
        }
        return to.getAbsolutePath();
    }

    private static void copy(File from, OutputStream out) throws IOException {
        if (out == null) throw new IOException("cannot write");
        try (InputStream in = new FileInputStream(from)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
