// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The screen of a sound-only session (PROTOCOL.md 4d): the sender's cover, title, artist and progress, dimmed after
 * a while. OK plays or pauses on the sender, left/right go to the previous/next track, the media keys go through, Back
 * ends the session.
 */
public class NowPlayingActivity extends Activity {
    private final BackGuard backGuard = new BackGuard();
    private static final long DIM_MS = 15_000;
    private static volatile NowPlayingActivity instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView art;
    private TextView title, artist, info;
    private MediaActivity.Bar bar;
    private LinearLayout box;
    private String shownArt;
    private long receivedAt;
    private final Runnable dim = () -> box.animate().alpha(0.25f).setDuration(1500).start();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress();
            main.postDelayed(this, 1000);
        }
    };

    static void show(Context c) {
        c.startActivity(new Intent(c, NowPlayingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    static void refresh() {
        NowPlayingActivity a = instance;
        if (a != null) a.runOnUiThread(a::update);
    }

    static void sessionEnded() {
        NowPlayingActivity a = instance;
        if (a != null) a.runOnUiThread(a::finish);
    }

    static boolean isShown() {
        return instance != null;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 48);
        box.setPadding(pad, pad, pad, pad);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(this, Ui.CARD_HIGH, 24, 0));
        art.setClipToOutline(true);
        int side = Ui.dp(this, 280);
        box.addView(art, new LinearLayout.LayoutParams(side, side));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(this, 40), 0, 0, 0);
        title = Ui.text(this, "", 34, Ui.TEXT, true);
        artist = Ui.text(this, "", 22, Ui.MUTED, false);
        info = Ui.text(this, "", 16, Ui.AMBER, false);
        bar = new MediaActivity.Bar(this);
        text.addView(title);
        text.addView(artist);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18));
        bp.topMargin = Ui.dp(this, 24);
        text.addView(bar, bp);
        text.addView(info);
        box.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        if (MirrorSession.current == null) {
            finish();
            return;
        }
        update();
        main.post(tick);
    }

    private void update() {
        JSONObject n = Remote.nowPlaying;
        receivedAt = android.os.SystemClock.uptimeMillis();
        String t = n == null ? "" : n.optString("title");
        title.setText(t.isEmpty() ? getString(R.string.now_playing_sound) : t);
        String who = n == null ? "" : n.optString("artist");
        String album = n == null ? "" : n.optString("album");
        artist.setText(album.isEmpty() ? who : who.isEmpty() ? album : who + " · " + album);
        String app = n == null ? "" : n.optString("app");
        String state = n == null ? "" : n.optString("state");
        info.setText(("paused".equals(state) ? getString(R.string.media_paused) + (app.isEmpty() ? "" : "  ·  ") : "") + app);
        String a = n == null ? "" : n.optString("art");
        if (!a.equals(shownArt)) {
            shownArt = a;
            art.setImageBitmap(null);
            if (!a.isEmpty()) loadArt(a);
        }
        progress();
        wake();
    }

    private void progress() {
        JSONObject n = Remote.nowPlaying;
        double dur = n == null ? 0 : n.optDouble("dur", 0);
        double pos = n == null ? 0 : n.optDouble("pos", 0);
        if (n != null && "playing".equals(n.optString("state"))) {
            pos += (android.os.SystemClock.uptimeMillis() - receivedAt) / 1000.0;
        }
        bar.setVisibility(dur > 0 ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        if (dur > 0) bar.set((float) (Math.min(pos, dur) / dur));
    }

    private void loadArt(String url) {
        new Thread(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 1;
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // no cover: the grey square stays
            }
            final Bitmap result = b;
            runOnUiThread(() -> {
                if (url.equals(shownArt) && !isDestroyed()) art.setImageBitmap(result);
            });
        }, "luch-art").start();
    }

    private void wake() {
        box.animate().cancel();
        box.setAlpha(1f);
        main.removeCallbacks(dim);
        main.postDelayed(dim, DIM_MS);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        String k = Remote.mediaKey(key);
        if (k == null) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) k = "play_pause";
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) k = "previous";
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) k = "next";
        }
        if (k != null) {
            if (e.getRepeatCount() == 0) Remote.sendKey(k);
            wake();
            return true;
        }
        wake();
        return super.onKeyDown(key, e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) Remote.sendKey("play_pause");
        wake();
        return true;
    }

    @Override
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (!backGuard.confirm(this)) return;
        MirrorSession s = MirrorSession.current;
        if (s != null) s.stopByReceiver(MirrorSession.BY_BACK);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
