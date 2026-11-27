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
 * "Report a problem" on the status screen: one file with this device's log (the last MB), about.txt, the crash
 * reports kept here and, when this phone shows its screen on a receiver now, that receiver's log, for the person to
 * send wherever they like (a chat, an issue). The share sheet takes it; a TV without one gets it saved in Download/.
 * A build with a report DSN also offers "Send to the developers" (ReportUpload), once the file is made and the person
 * has seen what is in it; nothing goes anywhere by itself.
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

    /** What {@link #build} made: the file, what is in it, and the same texts for "Send to the developers". */
    private static final class Made {
        final File zip;
        final ReportUpload.Report report = new ReportUpload.Report();
        final java.util.List<String> files = new java.util.ArrayList<>();
        boolean hidden;

        Made(File zip) {
            this.zip = zip;
        }
    }

    private static void make(Activity activity, String what, boolean hide) {
        Context app = activity.getApplicationContext();
        Made made;
        try {
            made = build(app, what, hide);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(LuchService.TAG, "report failed", e);
            tell(activity, app.getString(R.string.report_failed, String.valueOf(e.getMessage())));
            return;
        }
        Log.i(LuchService.TAG, "report made: " + made.zip.length() + " bytes, " + made.files
                + (hide ? ", addresses and names hidden" : ""));
        ReportUpload.Dsn dsn = ReportUpload.Dsn.parse(BuildConfig.REPORT_DSN);
        if (dsn == null) {
            shareOrSave(activity, made.zip, what);
            return;
        }
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) ready(activity, dsn, made, what);
        });
    }

    /** What is in the file, then the choice: send it to the developers, or share it as without a DSN. */
    private static void ready(Activity activity, ReportUpload.Dsn dsn, Made made, String what) {
        String files = android.text.TextUtils.join(", ", made.files);
        String message = activity.getString(R.string.report_ready, made.zip.getName(),
                String.valueOf(Math.max(1, made.zip.length() / 1024)), files,
                activity.getString(made.hidden ? R.string.report_hidden : R.string.report_kept));
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.report_problem)
                .setMessage(message)
                .setPositiveButton(R.string.report_send, (d, w) -> upload(activity, dsn, made, what))
                .setNeutralButton(R.string.report_share, (d, w) ->
                        new Thread(() -> shareOrSave(activity, made.zip, what), "luch-report").start())
                .setNegativeButton(R.string.rename_cancel, null)
                .show();
    }

    /** The send itself, with its progress and a Cancel; then the number, or why not and the other ways. */
    private static void upload(Activity activity, ReportUpload.Dsn dsn, Made made, String what) {
        int pad = Ui.dp(activity, 24);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(pad, Ui.dp(activity, 16), pad, 0);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ReportUpload up = new ReportUpload(dsn);
        AlertDialog progress = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.report_sending)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.rename_cancel, (d, w) -> new Thread(up::cancel, "luch-report-cancel").start())
                .show();
        new Thread(() -> {
            ReportUpload.Sent sent = null;
            ReportUpload.Failure failure = null;
            try {
                made.report.zip = read(made.zip);
                sent = up.send(made.report, ReportUpload.newEventId(), new Date(), (done, total) ->
                        activity.runOnUiThread(() -> bar.setProgress((int) (done * 1000 / Math.max(1, total)))));
                Log.i(LuchService.TAG, "report sent as " + sent.number
                        + (sent.notAttached == null ? "" : ", without the file: " + sent.notAttached));
            } catch (ReportUpload.Failure f) {
                failure = f;
                Log.w(LuchService.TAG, "report not sent: " + f.getMessage());
            } catch (IOException e) {
                failure = new ReportUpload.Failure(ReportUpload.Why.NETWORK, 0, String.valueOf(e.getMessage()));
            }
            ReportUpload.Sent ok = sent;
            ReportUpload.Failure bad = failure;
            activity.runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (activity.isFinishing() || ok == null && up.cancelled()) return;   // cancelled: nothing went
                if (ok != null) sent(activity, ok);
                else notSent(activity, bad, made.zip, what);
            });
        }, "luch-report-send").start();
    }

    /** "Sent. Report number LUCH-…", with Copy: the number is how the person and we find the report again. */
    private static void sent(Activity activity, ReportUpload.Sent sent) {
        String message = activity.getString(R.string.report_sent, sent.number);
        if (sent.notAttached != null) message += "\n\n" + activity.getString(R.string.report_sent_text_only);
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.report_problem)
                .setMessage(message)
                .setPositiveButton(R.string.report_copy, (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Luch", sent.number));
                    android.widget.Toast.makeText(activity, activity.getString(R.string.report_copied, sent.number),
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    /** Why it did not go, and the file's other ways out: the share sheet (when there is one) and Download/. */
    private static void notSent(Activity activity, ReportUpload.Failure f, File zip, String what) {
        String why;
        switch (f.why) {
            case TOO_BIG:
                why = activity.getString(R.string.report_why_big);
                break;
            case TOO_MANY:
                why = activity.getString(R.string.report_why_many);
                break;
            case REFUSED:
                why = activity.getString(R.string.report_why_refused, status(f));
                break;
            case SERVER:
                why = activity.getString(R.string.report_why_server, status(f));
                break;
            default:
                why = activity.getString(R.string.report_why_network, f.detail);
                break;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.report_problem)
                .setMessage(activity.getString(R.string.report_not_sent, why))
                .setPositiveButton(R.string.report_save, (d, w) ->
                        new Thread(() -> saveAndTell(activity, zip), "luch-report").start())
                .setNegativeButton(android.R.string.cancel, null);
        Intent chooser = chooser(activity.getApplicationContext(), zip, what);
        if (chooser != null) {
            b.setNeutralButton(R.string.report_share, (d, w) -> {
                if (!activity.isFinishing()) activity.startActivity(chooser);
            });
        }
        b.show();
    }

    private static String status(ReportUpload.Failure f) {
        return (f.status > 0 ? "HTTP " + f.status : "") + (f.status > 0 && !f.detail.isEmpty() ? ": " : "") + f.detail;
    }

    /** The share sheet with the file; null when nothing on this device can take it (a TV, often). */
    private static Intent chooser(Context app, File zip, String what) {
        Uri uri = ReportProvider.uri(app, zip);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, app.getString(R.string.report_subject))
                .putExtra(Intent.EXTRA_TEXT, description(app, what))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));   // the grant reaches the app picked in the chooser
        Intent chooser = Intent.createChooser(send, app.getString(R.string.report_problem));
        PackageManager pm = app.getPackageManager();
        if (pm.queryIntentActivities(send, 0).isEmpty() || chooser.resolveActivity(pm) == null) return null;
        return chooser;
    }

    /** The share sheet, or on a device without one the file in Download/ and the person told where. Off the UI thread. */
    private static void shareOrSave(Activity activity, File zip, String what) {
        Intent chooser = chooser(activity.getApplicationContext(), zip, what);
        if (chooser != null) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) activity.startActivity(chooser);
            });
            return;
        }
        saveAndTell(activity, zip);
    }

    private static void saveAndTell(Activity activity, File zip) {
        Context app = activity.getApplicationContext();
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

    /**
     * Luch-report-YYYY-MM-DD-HHMM.zip: about.txt, luch.log, receiver-N.log (the receiver this phone shows its screen
     * on, asked over that session) and reports.json (when there are any).
     */
    private static Made build(Context context, String what, boolean hide) throws IOException, JSONException {
        Sender.Session session = Sender.active() ? Sender.current : null;
        Redact redact = hide ? names(context, session) : null;
        File dir = ReportProvider.dir(context);
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) old[i].delete();   // one report at a time is enough
        String when = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date());
        Made made = new Made(new File(dir, "Luch-report-" + when + ".zip"));
        made.hidden = hide;
        String log = new String(Log.tail(LOG_BYTES), StandardCharsets.UTF_8);
        JSONArray reports = Reports.pending(context);
        java.util.List<ReceiverLogs.Part> receivers = receivers(session);
        ReportUpload.Report r = made.report;
        r.what = hidden(redact, what.trim());
        r.about = hidden(redact, about(context, what, reports.length(), ReceiverLogs.about(receivers)));
        r.logs.put("luch.log", hidden(redact, log));
        for (int i = 0; i < receivers.size(); i++) {
            String l = receivers.get(i).log;
            if (l != null) r.logs.put(ReceiverLogs.file(i), hidden(redact, l));
        }
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(made.zip))) {
            entry(z, made, "about.txt", r.about);
            for (java.util.Map.Entry<String, String> l : r.logs.entrySet()) entry(z, made, l.getKey(), l.getValue());
            if (reports.length() > 0) entry(z, made, "reports.json", hidden(redact, reports.toString(2)));
        }
        r.zipName = made.zip.getName();
        r.version = BuildConfig.VERSION_NAME;
        r.osVersion = Build.VERSION.RELEASE;
        r.manufacturer = Build.MANUFACTURER;
        r.model = Build.MODEL;
        int kind = Ui.kind(context);
        r.family = kind == 2 ? "TV" : kind == 1 ? "tablet" : "phone";
        r.tags.put("flavor", BuildConfig.FLAVOR);
        r.tags.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
        r.tags.put("device", Luch.model());
        r.tags.put("role", session != null ? "sender" : "receiver");
        r.tags.put("codec", codec(session));
        return made;
    }

    /**
     * The receivers this phone sees, each with its log or why not. Only the one it shows its screen on now is asked,
     * over that session, which the receiver has let in; a new connection from here would show Allow on its TV.
     */
    private static java.util.List<ReceiverLogs.Part> receivers(Sender.Session session) {
        java.util.List<Luch.Receiver> seen = new java.util.ArrayList<>(Luch.receivers());
        if (session != null && !contains(seen, session.to)) seen.add(0, session.to);
        java.util.List<ReceiverLogs.Part> parts = new java.util.ArrayList<>();
        for (Luch.Receiver x : seen) {
            boolean inUse = session != null && same(x, session.to);
            ReceiverLogs.Part p = new ReceiverLogs.Part(x.name, x.model, x.address + ":" + x.port, inUse);
            if (inUse) {
                try {
                    p.log = session.receiverLog(ReceiverLogs.MAX_KB);
                    p.note = "log taken (" + Math.max(1, p.log.length() / 1024) + " KB)";
                } catch (ReceiverLogs.Failure f) {
                    p.note = f.getMessage();
                }
            } else {
                p.note = ReceiverLogs.NOT_ASKED;
            }
            parts.add(p);
        }
        return parts;
    }

    private static boolean contains(java.util.List<Luch.Receiver> list, Luch.Receiver r) {
        for (Luch.Receiver x : list) if (same(x, r)) return true;
        return false;
    }

    private static boolean same(Luch.Receiver a, Luch.Receiver b) {
        if (a.id != null && !a.id.isEmpty() && b.id != null && !b.id.isEmpty()) return a.id.equals(b.id);
        return a.address.equals(b.address) && a.port == b.port;
    }

    /** The codec of what plays now: this phone's picture on a receiver, else the last one shown here. */
    private static String codec(Sender.Session session) {
        Sender.Live live = session == null ? null : session.live;
        if (live != null) return live.codec;
        try {
            String start = MirrorSession.lastStart;
            JSONObject video = start == null ? null : new JSONObject(start).optJSONObject("video");
            return video == null ? "" : video.optString("codec");
        } catch (JSONException e) {
            return "";
        }
    }

    private static byte[] read(File f) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream((int) f.length());
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    /** This device is "This device"; the computers it knows, "Computer 1", 2...; other receivers, "Receiver 1"... */
    private static Redact names(Context context, Sender.Session session) {
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
        java.util.List<String> receivers = new java.util.ArrayList<>();
        if (session != null) {
            receivers.add(session.to.name);
            JSONObject w = session.welcome;
            if (w != null) receivers.add(w.optString("name"));
        }
        for (Luch.Receiver x : Luch.receivers()) receivers.add(x.name);
        n = 1;
        for (String x : receivers) if (r.name(x, "Receiver " + n)) n++;
        return r;
    }

    private static String hidden(Redact r, String text) {
        return r == null ? text : r.apply(text);
    }

    private static String about(Context context, String what, int reports, String receivers) throws JSONException {
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
                .block("Receivers this phone can show its screen on", receivers)
                .text();
    }

    /** The short text that goes with the file: what happened, and on what. */
    private static String description(Context context, String what) {
        return (what.trim().isEmpty() ? "" : what.trim() + "\n\n") + "Luch " + BuildConfig.VERSION_NAME + ", Android "
                + Build.VERSION.RELEASE + ", " + Luch.model();
    }

    private static void entry(ZipOutputStream z, Made made, String name, String text) throws IOException {
        made.files.add(name);
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
