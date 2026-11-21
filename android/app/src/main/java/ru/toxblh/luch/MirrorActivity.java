// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
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
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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
        setContentView(root);
        statsOn = prefs().getBoolean("stats", false);
        stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        shown = this;
        if (MirrorSession.current == null) finish();
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
            toggleStats();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void toggleStats() {
        statsOn = !statsOn;
        stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        prefs().edit().putBoolean("stats", statsOn).apply();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
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
