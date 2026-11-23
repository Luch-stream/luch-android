// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Two entries in the share sheet of other apps (Google Photos, Synology Photos, a gallery), one class:
 * "Transmit with Luch" asks where and starts showing the screen there (Android lets the person pick one app, so the gallery
 * can be paged through on the phone while the receiver follows); "Show photos with Luch" sends the chosen photos full
 * screen ({@link PhotoService}). Opens over the app it was shared from.
 */
public class ShareActivity extends Activity {
    private static final int REQUEST_CAPTURE = 78;
    private Luch.Receiver pending;
    private boolean photos;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        photos = getIntent().getComponent() != null && getIntent().getComponent().getClassName().endsWith("SharePhotoActivity");
        List<Uri> uris = uris(getIntent());
        if (photos && uris.isEmpty()) {
            finish();
            return;
        }
        if (!photos && (MirrorSession.current != null || Sender.active())) {
            Toast.makeText(this, MirrorSession.current != null ? R.string.busy_receiving : R.string.busy_sending, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (Luch.enabled(this)) LuchService.start(this); // the list of receivers is kept by the always-on service
        final List<Luch.Receiver> receivers = Luch.receivers();
        if (receivers.isEmpty()) {
            Toast.makeText(this, R.string.share_none, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String[] names = new String[receivers.size()];
        for (int i = 0; i < names.length; i++) names[i] = receivers.get(i).name + "  ·  " + receivers.get(i).address;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.share_pick)
                .setItems(names, (d, which) -> {
                    if (photos) {
                        PhotoService.start(this, receivers.get(which), uris);
                        finish();
                    } else {
                        // Android asks what to share (on 14+ one app or the whole screen); the answer starts the capture
                        pending = receivers.get(which);
                        android.media.projection.MediaProjectionManager m =
                                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
                    }
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CAPTURE && pending != null && result == RESULT_OK && data != null) {
            SenderService.start(this, result, data, pending.id,
                    getSharedPreferences("luch", MODE_PRIVATE).getString("sender_codec", "auto"));
        }
        pending = null;
        finish(); // back to the gallery it came from
    }

    private static List<Uri> uris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        ClipData clip = intent.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            if (clip.getItemAt(i).getUri() != null) out.add(clip.getItemAt(i).getUri());
        }
        if (out.isEmpty()) {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) out.add(one);
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) out.addAll(many);
        }
        return out;
    }
}
