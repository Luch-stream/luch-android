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
 * Wi-Fi held in low-latency mode while it is up. Back, Home or another app on top ends the session.
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
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
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

    /** Leaving the screen in any way ends the session, so the sender stops capturing. */
    @Override
    protected void onStop() {
        android.util.Log.i(LuchService.TAG, "mirror screen stopped, finishing=" + isFinishing());
        MirrorSession s = MirrorSession.current;
        if (s != null && !s.ended()) s.stopByUser();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        MirrorSession s = MirrorSession.current;
        if (s != null) s.stopByUser();
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("luch", MODE_PRIVATE);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            statsOn = !statsOn;
            stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
            prefs().edit().putBoolean("stats", statsOn).apply();
            return true;
        }
        return super.onKeyUp(keyCode, event);
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
