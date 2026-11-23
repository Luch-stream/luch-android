// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.Intent;
import android.view.ViewGroup;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The picture of a mirroring session: a full-screen SurfaceView (not a TextureView: one copy and one frame less),
 * Wi-Fi held in low-latency mode while it is up. Back ends the session; Home or another app on top only mutes it (Android 8+).
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {
    private static volatile MirrorActivity shown;
    private WifiManager.WifiLock wifiLock;
    private TextView stats;
    private boolean statsOn;
    private SurfaceView video;
    private FrameLayout rootView;
    /** How the picture meets the screen: 0 fit with bars, 1 fill and crop the edges, 2 stretch, 3 fit what is shown without its own black bars. */
    private int fitMode;
    private static final int MODES = 4;
    /** Mode 3: the part of the video that is not black, in video pixels (x, y, w, h), as drawn now and as last settled on. */
    private final float[] cropNow = new float[4];
    private float[] cropWanted;
    private float[] cropSeen;
    private int cropSeenCount;
    private android.animation.ValueAnimator cropAnim;
    private final android.os.Handler cropHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean analyzing;
    /** A tap on the picture (a touch screen) brings up from whom it comes and the figures; another tap hides it. */
    private android.widget.ScrollView panel;
    private final Luch.Listener panelListener = () -> runOnUiThread(this::refreshPanel);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A sender may start while the TV's screen is asleep (issue #1): come up over it and turn it on.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // Ask the display for its game mode: the least picture processing, the least delay (Moonlight does the same).
        if (Build.VERSION.SDK_INT >= 30) getWindow().setPreferMinimalPostProcessing(true);
        // Never chosen on this screen: a phone running Luch (a gallery on its side has black bars of its own) is shown
        // without them, anything else fitted. A choice made with Up or in the panel stays.
        MirrorSession first = MirrorSession.current;
        fitMode = prefs().getInt("fit_mode", first != null && first.senderApp.startsWith("luch-android") ? 3 : 0);
        SurfaceView view = new SurfaceView(this);
        video = view;
        view.getHolder().addCallback(this);
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(0xFF000000);   // the bars beside a picture that does not fill the screen
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // The picture keeps its proportions on any screen (a phone is not 16:9): fit it in the window, bars on the sides.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit(r - l, b - t));
        // Moonlight-style statistics in the corner, toggled with OK (or Info / Menu) and remembered.
        stats = new TextView(this);
        stats.setTypeface(Typeface.MONOSPACE);
        stats.setTextSize(13);
        stats.setTextColor(0xFFFFFFFF);
        stats.setBackgroundColor(0x99000000);
        int pad = Math.round(10 * getResources().getDisplayMetrics().density);
        stats.setPadding(pad, pad / 2, pad, pad / 2);
        stats.setText("…");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(pad * 2, pad * 2, 0, 0);
        root.addView(stats, lp);
        panel = new android.widget.ScrollView(this);
        panel.setBackgroundColor(0xF2121318);
        panel.setVisibility(View.GONE);
        panel.setOnClickListener(v -> togglePanel());   // a tap on the panel itself returns to the picture
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        statsOn = prefs().getBoolean("stats", false);
        stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        shown = this;
        if (MirrorSession.current == null) finish();
    }

    /** The picture keeps its proportions on any screen: fit it in the window, bars on the sides. */
    private void fit(int pw, int ph) {
        MirrorSession s = MirrorSession.current;
        if (s == null || pw <= 0 || ph <= 0 || s.videoWidth() <= 0 || s.videoHeight() <= 0) return;
        float kw = pw / (float) s.videoWidth(), kh = ph / (float) s.videoHeight();
        int w, h, mx = 0, my = 0;
        if (fitMode == 3) {
            // the picture without the black bars the sender's own screen had around it (a gallery on a phone on its side)
            float vw = s.videoWidth(), vh = s.videoHeight();
            float cx = 0, cy = 0, cw = vw, ch = vh;
            if (cropNow[2] > 0 && cropNow[3] > 0) {
                cx = cropNow[0];
                cy = cropNow[1];
                cw = cropNow[2];
                ch = cropNow[3];
            }
            float k = Math.min(pw / cw, ph / ch);
            w = Math.round(vw * k);
            h = Math.round(vh * k);
            // the view is centred in the window; the margins move it so that the middle of the kept part is in the middle
            mx = Math.round((vw / 2f - (cx + cw / 2f)) * k);
            my = Math.round((vh / 2f - (cy + ch / 2f)) * k);
        } else if (fitMode == 2) {
            w = pw;
            h = ph;
        } else {
            float k = fitMode == 1 ? Math.max(kw, kh) : Math.min(kw, kh);
            w = Math.round(s.videoWidth() * k);
            h = Math.round(s.videoHeight() * k);
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) video.getLayoutParams();
        android.util.Log.i(LuchService.TAG, "picture " + w + "x" + h + " of " + pw + "x" + ph + ", video " + s.videoWidth() + "x"
                + s.videoHeight() + ", mode " + fitMode);
        if (p.width != w || p.height != h || p.leftMargin != mx || p.topMargin != my) {
            p.width = w;
            p.height = h;
            p.leftMargin = mx;
            p.rightMargin = 0;
            p.topMargin = my;
            p.bottomMargin = 0;
            video.post(video::requestLayout);
        }
        if (fitMode == 3) startAnalysis();
    }

    /** Looks at the picture a few times a second and keeps the part of it that is not black. API 26+ (PixelCopy). */
    private void startAnalysis() {
        if (analyzing || Build.VERSION.SDK_INT < 26) return;
        analyzing = true;
        cropHandler.postDelayed(this::analyze, 400);
    }

    private void analyze() {
        analyzing = false;
        MirrorSession s = MirrorSession.current;
        Surface surface = video.getHolder().getSurface();
        if (fitMode != 3 || s == null || surface == null || !surface.isValid() || s.videoWidth() <= 0 || s.videoHeight() <= 0) {
            if (fitMode == 3) startAnalysis();
            return;
        }
        final int bw = 96, bh = Math.max(16, Math.round(96f * s.videoHeight() / s.videoWidth()));
        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(video, bmp, result -> {
                if (result == android.view.PixelCopy.SUCCESS) onFrame(bmp, s.videoWidth(), s.videoHeight());
                bmp.recycle();
                startAnalysis();
            }, cropHandler);
        } catch (RuntimeException e) {
            startAnalysis();
        }
    }

    private void onFrame(android.graphics.Bitmap bmp, int vw, int vh) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        final int dark = 28;
        int top = -1, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            boolean lit = false;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int luma = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                if (luma > dark) {
                    lit = true;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
            if (lit) {
                if (top < 0) top = y;
                bottom = y;
            }
        }
        if (top < 0 || right < left) return; // all black: nothing to say
        // a little more than the edge, and never less than a quarter of the picture in either direction
        left = Math.max(0, left - 1);
        top = Math.max(0, top - 1);
        right = Math.min(w - 1, right + 1);
        bottom = Math.min(h - 1, bottom + 1);
        float[] want = {left * vw / (float) w, top * vh / (float) h, (right - left + 1) * vw / (float) w,
                (bottom - top + 1) * vh / (float) h};
        if (want[2] < vw * .25f || want[3] < vh * .25f || (want[2] > vw * .96f && want[3] > vh * .96f)) {
            want = new float[] {0, 0, 0, 0}; // no bars worth cutting
        }
        // the same answer twice in a row before the picture moves, so a bar or a toolbar coming and going does not make it breathe
        if (cropSeen != null && near(cropSeen, want, vw * .03f, vh * .03f)) cropSeenCount++;
        else {
            cropSeen = want;
            cropSeenCount = 1;
        }
        if (cropSeenCount >= 2 && (cropWanted == null || !near(cropWanted, want, vw * .03f, vh * .03f))) {
            cropWanted = want;
            animateCrop(want);
        }
    }

    private static boolean near(float[] a, float[] b, float dx, float dy) {
        return Math.abs(a[0] - b[0]) <= dx && Math.abs(a[1] - b[1]) <= dy && Math.abs(a[2] - b[2]) <= dx && Math.abs(a[3] - b[3]) <= dy;
    }

    private void animateCrop(float[] to) {
        MirrorSession s = MirrorSession.current;
        if (s == null) return;
        final float[] from = cropNow.clone();
        final float[] end = to[2] > 0 ? to : new float[] {0, 0, s.videoWidth(), s.videoHeight()};
        if (from[2] <= 0) {
            from[0] = 0;
            from[1] = 0;
            from[2] = s.videoWidth();
            from[3] = s.videoHeight();
        }
        if (cropAnim != null) cropAnim.cancel();
        cropAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        cropAnim.setDuration(380);
        cropAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        cropAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 4; i++) cropNow[i] = from[i] + (end[i] - from[i]) * t;
            fit(rootView.getWidth(), rootView.getHeight());
        });
        cropAnim.start();
    }

    private void setFitMode(int mode) {
        fitMode = mode;
        if (mode != 3) {
            cropNow[2] = 0;
            cropNow[3] = 0;
            cropWanted = null;
            cropSeen = null;
        }
        prefs().edit().putInt("fit_mode", mode).apply();
        fit(rootView.getWidth(), rootView.getHeight());
        refreshPanel();
    }

    /** The sender started again with another size (it turned): the screen stays, the new session draws on it. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        MirrorSession s = MirrorSession.current;
        if (s == null) return;
        fit(rootView.getWidth(), rootView.getHeight());
        Surface surface = video.getHolder().getSurface();
        if (surface != null && surface.isValid()) s.setSurface(surface);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WifiManager wifi = Compat.service(getApplicationContext(), WifiManager.class);
        if (wifi != null && wifiLock == null) {
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wifi.createWifiLock(mode, "luch-mirror");
            wifiLock.acquire();
        }
    }

    @Override
    protected void onPause() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Luch.addListener(panelListener);
        stopped = false;
        handler.removeCallbacks(closeIfStillStopped);
        MirrorSession s = MirrorSession.current;
        if (s != null) s.setAway(false);
    }

    /**
     * Leaving the screen (Home, another app): where the session can go on without it (Android 8+), it does, with the
     * sound muted and the decoder drawing on a stand-in surface, until the screen is opened again or 15 minutes pass.
     * Back ends it. Elsewhere it ends after a short grace, as a screen started while the TV was asleep is stopped and
     * started again as the TV wakes up and its lock screen passes (issue #1).
     */
    @Override
    protected void onStop() {
        Luch.removeListener(panelListener);
        android.util.Log.i(LuchService.TAG, "mirror screen stopped, finishing=" + isFinishing());
        stopped = true;
        handler.removeCallbacks(closeIfStillStopped);
        MirrorSession s = MirrorSession.current;
        if (isFinishing()) closeIfStillStopped.run();
        else if (s != null && MirrorSession.canStayAway()) s.setAway(true);
        else handler.postDelayed(closeIfStillStopped, STOP_GRACE_MS);
        super.onStop();
    }

    /** How long a stopped screen may take to come back before the session counts as closed. */
    private static final long STOP_GRACE_MS = 1_000;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean stopped;
    private final Runnable closeIfStillStopped = () -> {
        if (!stopped) return;
        MirrorSession s = MirrorSession.current;
        if (s != null && !s.ended()) s.stopByReceiver(MirrorSession.SCREEN_CLOSED);
        if (!isFinishing()) finish();
    };

    @Override
    protected void onDestroy() {
        cropHandler.removeCallbacksAndMessages(null);
        if (cropAnim != null) cropAnim.cancel();
        handler.removeCallbacks(closeIfStillStopped);
        if (shown == this) shown = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        MirrorSession s = MirrorSession.current;
        if (s != null) s.stopByReceiver(MirrorSession.BY_BACK);
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("luch", MODE_PRIVATE);
    }

    /** Without a remote (a touch screen), a tap does what OK does. */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            // The black statistics in the corner go first, the next tap brings up the panel.
            if (statsOn) toggleStats();
            else togglePanel();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshPanel();
    }

    private void refreshPanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        MirrorSession s = MirrorSession.current;
        MirrorSession.Live live = s == null ? null : s.live();
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, pad, pad, pad);
        if (live != null) {
            java.util.List<Luch.Peer> peers = Luch.connected();
            col.addView(Ui.flowCard(this, peers.isEmpty() ? null : peers.get(0), live, Ui.isTv(this), Luch.name(this)));
            col.addView(Ui.stats(this, live), Ui.below(this, 10));
        }
        col.addView(Ui.chips(this, fitNames(), fitMode, this::setFitMode), Ui.below(this, 12));
        android.widget.Button stop = Ui.button(this, getString(R.string.action_stop), true, v -> onBackPressed());
        col.addView(stop, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        ((android.widget.LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = Ui.dp(this, 10);
        panel.removeAllViews();
        panel.addView(col);
    }

    private String[] fitNames() {
        return new String[] {getString(R.string.fit_fit), getString(R.string.fit_fill), getString(R.string.fit_stretch),
                getString(R.string.fit_smart)};
    }

    private void toggleStats() {
        statsOn = !statsOn;
        stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        prefs().edit().putBoolean("stats", statsOn).apply();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            setFitMode((fitMode + 1) % MODES);
            android.widget.Toast.makeText(this, fitNames()[fitMode], android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            toggleStats();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Whether the mirror screen exists (created and not yet destroyed). */
    static boolean isShown() {
        return shown != null;
    }

    /** New figures, once a second, from the session's stats thread. */
    static void showStats(String text) {
        MirrorActivity a = shown;
        if (a != null) a.runOnUiThread(() -> {
            if (a.statsOn) a.stats.setText(text);
        });
    }

    /** The session ended from the other side: close the screen. */
    static void sessionEnded() {
        MirrorActivity a = shown;
        if (a != null) a.runOnUiThread(a::finish);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        MirrorSession s = MirrorSession.current;
        if (s == null) return;
        Surface surface = holder.getSurface();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                surface.setFrameRate(s.fps(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (RuntimeException ignored) {
            }
        }
        s.setSurface(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        MirrorSession s = MirrorSession.current;
        if (s != null) s.setSurface(null);
    }
}
