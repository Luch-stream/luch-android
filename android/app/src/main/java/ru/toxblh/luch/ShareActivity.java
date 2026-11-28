// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Two entries in the share sheet of other apps, one class. "Show on TV" sends what was shared to a receiver as a
 * computer casts it ({@link CastService}: photos, a video or a sound, a PDF, a link, YouTube); an office file, which
 * the receiver cannot draw, is opened here while this screen is shown there. "Luch: show this screen" asks where and
 * shows the screen (Android lets the person pick one app, so a gallery can be paged through on the phone while the
 * receiver follows). The only receiver around is taken at once; with several, the one used last is on top. Opens over
 * the app it was shared from, and stays until the TV shows the thing, so a pairing code can be typed meanwhile.
 */
public class ShareActivity extends Activity implements CastService.Progress {
    private static final int REQUEST_CAPTURE = 78, REQUEST_AUDIO = 79;
    private static final long LOOK_MS = 6000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Luch.Receiver pending;
    /** Showing this screen instead of casting; {@code view}: the file to open here meanwhile. */
    private boolean mirror;
    private Uri view;
    private String viewType;
    private List<Uri> uris;
    private String text;
    private AlertDialog dialog;
    private ReceiverBrowse browse;
    private boolean picking, mirrored;
    private final Luch.Listener changed = () -> main.post(this::choose);
    private final Runnable giveUp = () -> {
        if (pending != null || picking) return;
        Toast.makeText(this, R.string.share_none, Toast.LENGTH_LONG).show();
        finish();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mirror = getIntent().getComponent() != null && getIntent().getComponent().getClassName().endsWith("ShareMirrorActivity");
        uris = uris(getIntent());
        text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (!mirror) {
            SharePlan plan = SharePlan.of(CastService.describe(this, uris), text);
            if (plan.kind == SharePlan.Kind.NOTHING) {
                Toast.makeText(this, CastService.problem(this, plan.problem, ""), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (plan.kind == SharePlan.Kind.MIRROR) {
                mirror = true;
                SharePlan.Thing t = plan.things.get(0);
                view = uris.get(t.index);
                viewType = t.mime;
            }
        }
        if (mirror && (MirrorSession.current != null || Sender.active())) {
            Toast.makeText(this, MirrorSession.current != null ? R.string.busy_receiving : R.string.busy_sending, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // The list of receivers is kept by the always-on service; with the receiver off, by a look of its own.
        if (Luch.enabled(this)) {
            LuchService.start(this);
        } else {
            browse = ReceiverBrowse.start(this);
        }
        Luch.addListener(changed);
        main.postDelayed(giveUp, LOOK_MS);
        choose();
    }

    /** The only receiver at once, a list of several, or a wait while none is found yet. */
    private void choose() {
        if (pending != null || picking || isFinishing()) return;
        List<Luch.Receiver> found = ReceiverChoice.order(Luch.receivers(), last());
        if (found.isEmpty()) {
            if (dialog == null) dialog = progress(getString(R.string.share_looking));
            return;
        }
        dismiss();
        Luch.Receiver only = ReceiverChoice.auto(found);
        if (only != null) {
            go(only);
            return;
        }
        picking = true;
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) {
            Luch.Receiver r = found.get(i);
            names[i] = r.id.equals(last()) ? getString(R.string.share_last, r.name) : r.name;
        }
        dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.share_pick)
                .setItems(names, (d, which) -> go(found.get(which)))
                .setOnCancelListener(d -> finish())
                .show();
    }

    private String last() {
        return getSharedPreferences("luch", MODE_PRIVATE).getString("share_last", null);
    }

    private void go(Luch.Receiver to) {
        pending = to;
        Luch.removeListener(changed);
        main.removeCallbacks(giveUp);
        getSharedPreferences("luch", MODE_PRIVATE).edit().putString("share_last", to.id).apply();
        if (mirror) {
            // Android asks what to share (on 14+ one app or the whole screen); the answer starts the capture
            if (android.os.Build.VERSION.SDK_INT >= 29
                    && getSharedPreferences("luch", MODE_PRIVATE).getBoolean("send_audio", true)
                    && checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {"android.permission.RECORD_AUDIO"}, REQUEST_AUDIO);
            } else {
                askCapture();
            }
            return;
        }
        CastService.progress = this;
        CastService.start(this, to, uris, text);
        dismiss();
        dialog = progress(getString(R.string.cast_connecting, to.name));
    }

    private AlertDialog progress(String message) {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (pending != null && !mirror) CastService.cancel();
                    finish();
                })
                .setOnCancelListener(d -> {
                    if (pending != null && !mirror) CastService.cancel();
                    finish();
                })
                .show();
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    // ---- CastService.Progress ----

    @Override
    public void status(String text) {
        if (dialog != null && !isFinishing()) dialog.setMessage(text);
    }

    @Override
    public void done(String error, boolean forgettable) {
        if (CastService.progress == this) CastService.progress = null;
        dismiss();
        if (error == null || isFinishing()) {
            finish();   // back to the app it came from
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(error)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish());
        if (forgettable && pending != null) {
            Luch.Receiver to = pending;
            b.setNeutralButton(R.string.cast_forget, (d, w) -> {
                Link.forget(this, to.id);
                dialog = null;
                go(to);
            });
        }
        dialog = b.show();
    }

    // ---- showing this screen ----

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_AUDIO && pending != null) askCapture();
    }

    private void askCapture() {
        android.media.projection.MediaProjectionManager m =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CAPTURE && pending != null && result == RESULT_OK && data != null) {
            SenderService.start(this, result, data, pending.id,
                    getSharedPreferences("luch", MODE_PRIVATE).getString("sender_codec", "auto"));
            mirrored = true;
            if (view != null) openHere();
        }
        pending = null;
        finish(); // back to the gallery it came from
    }

    /** The document, in this phone's own viewer, which the receiver now shows with the rest of the screen. */
    private void openHere() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(view, viewType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cast_no_viewer, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        Luch.removeListener(changed);
        main.removeCallbacks(giveUp);
        if (CastService.progress == this) CastService.progress = null;
        if (browse != null) browse.stop(!mirrored);
        dismiss();
        super.onDestroy();
    }

    private static List<Uri> uris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        ClipData clip = intent.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            if (clip.getItemAt(i).getUri() != null) out.add(clip.getItemAt(i).getUri());
        }
        if (out.isEmpty()) {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (many != null) out.addAll(many);
            } else {
                Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) out.add(one);
            }
        }
        return out;
    }
}
