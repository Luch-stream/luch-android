// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.Intent;
import android.view.ViewGroup;
import android.net.wifi.WifiManager;
import android.view.Display;
import android.os.Build;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
    /** What the picture is actually being rendered into, for the stats overlay. */
    private static volatile String surfaceInfo = "?";
    /** Always-visible "the device is hot" badge, independent of the debug overlay. */
    private TextView hotBadge;
    /** The session this screen is showing; a newer one must not be stopped by this screen's teardown. */
    private MirrorSession session;
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
        touchMode = prefs().getInt("touch_mode", TOUCH_DIRECT);
        if (Build.VERSION.SDK_INT >= 26) {
            rootView.setOnCapturedPointerListener((v, e) -> captured(e));
        }
        stats.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        shown = this;
        requestFastDisplay();

        TextView badge = new TextView(this);
        // Just the icon: the text version was too loud for a permanent badge.
        badge.setText("🔥");
        badge.setContentDescription(getString(R.string.hot_badge));
        badge.setTextSize(22);
        badge.setTextColor(0xFFFFD9A0);
        badge.setBackgroundColor(0xCC3A2A12);
        badge.setPadding(pad, pad / 3, pad, pad / 3);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.setMargins(0, pad * 2, pad * 2, 0);
        badge.setVisibility(View.GONE);
        root.addView(badge, badgeParams);
        hotBadge = badge;
        bindSession();
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
        cropHandler.postDelayed(this::analyze, 90); // ~10 looks a second: a swiped-in photo is known within a quarter of a second
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

    private int[] lastPixels;

    private void onFrame(android.graphics.Bitmap bmp, int vw, int vh) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        // How much the picture moved since the last look: a swipe between two photos moves all of it, a toolbar a little.
        long diff = 0;
        if (lastPixels != null && lastPixels.length == px.length) {
            for (int i = 0; i < px.length; i += 3) {
                int a = px[i], b = lastPixels[i];
                int da = (((a >> 16) & 0xFF) + ((a >> 8) & 0xFF) + (a & 0xFF)) - (((b >> 16) & 0xFF) + ((b >> 8) & 0xFF) + (b & 0xFF));
                diff += Math.abs(da) / 3;
            }
            diff /= Math.max(1, px.length / 3);
        }
        lastPixels = px;
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
        // The same bars twice running and the picture still: a swipe is under way while it moves and its edges slide, so
        // neither the half-way state nor the slow end of the slide is taken; the one that stays is.
        boolean still = diff < 6 || (cropSeen != null && near(cropSeen, want, vw * .004f, vh * .004f));
        if (cropSeen != null && still && near(cropSeen, want, vw * .01f, vh * .01f)) cropSeenCount++;
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
        android.util.Log.i(LuchService.TAG, "crop apply " + java.util.Arrays.toString(to));
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
        cropAnim.setDuration(170);
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
        InputLink link = link();
        InputWire held = link == null ? null : link.wire();
        if (held != null) {
            // nothing stays pressed on the sender while the screen is away
            held.releaseAll(SystemClock.uptimeMillis(), InputLink.now());
            link.kick();
            mouseButtons = 0;
        }
        stopped = true;
        handler.removeCallbacks(closeIfStillStopped);
        MirrorSession s = MirrorSession.current;
        if (isFinishing()) closeIfStillStopped.run();
        else if (s != null && MirrorSession.canStayAway()) s.setAway(true);
        else handler.postDelayed(closeIfStillStopped, STOP_GRACE_MS);
        super.onStop();
    }

    /** Ask the panel for its highest refresh rate: the display adds up to a frame period on top of decode. */
    private void requestFastDisplay() {
        if (Build.VERSION.SDK_INT < 23) return;
        Display d = getWindowManager().getDefaultDisplay();
        if (d == null) return;
        int fastest = 0;
        int fastestMode = 0;
        int nativeWidth = d.getMode().getPhysicalWidth();
        int nativeHeight = d.getMode().getPhysicalHeight();
        for (Display.Mode mode : d.getSupportedModes()) {
            int hz = Math.round(mode.getRefreshRate());
            // The panel's native width at its highest rate: a mode the system can switch to without a resize.
            if (hz > fastest && mode.getPhysicalWidth() == nativeWidth
                    && mode.getPhysicalHeight() == nativeHeight) {
                fastest = hz;
                fastestMode = mode.getModeId();
            }
        }
        if (fastest <= 0) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredRefreshRate = fastest;
        lp.preferredDisplayModeId = fastestMode;
        getWindow().setAttributes(lp);
    }

    /** Turns the always-on heat warning on or off; the debug overlay is a separate thing. */
    static void setHot(boolean hot) {
        MirrorActivity a = shown;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.hotBadge != null) a.hotBadge.setVisibility(hot ? View.VISIBLE : View.GONE);
        });
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindSession();
    }

    /** Points this screen at the current session and hands it the surface again after a rebuild. */
    private void bindSession() {
        session = MirrorSession.current;
        MirrorSession s = session;
        if (s == null) {
            finish();
            return;
        }
        cropHandler.removeCallbacksAndMessages(null);
        analyzing = false;
        if (cropAnim != null) cropAnim.cancel();
        cropNow[2] = cropNow[3] = 0;
        cropWanted = cropSeen = null;
        lastPixels = null;
        cropSeenCount = 0;
        fit(rootView.getWidth(), rootView.getHeight());
        SurfaceHolder holder = video.getHolder();
        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            s.setSurface(holder.getSurface());
        }
    }

    @Override
    protected void onDestroy() {
        cropHandler.removeCallbacksAndMessages(null);
        if (cropAnim != null) cropAnim.cancel();
        handler.removeCallbacks(closeIfStillStopped);
        if (shown == this) {
            shown = null;
            hotBadge = null;
            surfaceInfo = "?";
        }
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

    /**
     * Without a remote (a touch screen), a tap does what OK does, unless the sender may be controlled from here
     * (PROTOCOL.md 4h): then touches go to it, and a three-finger tap brings up the panel.
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isMouse(e)) {
            if (mouse(e)) return true;
        } else if (threeFingers(e)) {
            return true;
        } else {
            InputLink link = link();
            boolean stylus = toolOf(e, 0) != InputWire.TOOL_FINGER;
            if (link != null && link.use(stylus ? "stylus" : "touch")) {
                if (stylus || touchMode == TOUCH_DIRECT) direct(e);
                else touchpad(e);
                return true;
            }
            if (link != null && link.waiting()) return true;   // asked, no answer yet: the tap is not the panel's
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            // The black statistics in the corner go first, the next tap brings up the panel.
            if (statsOn) toggleStats();
            else togglePanel();
            return true;
        }
        return super.onTouchEvent(e);
    }

    // ---- input for the sender (PROTOCOL.md 4h) ------------------------------------------------------------------

    static final int TOUCH_DIRECT = 0, TOUCH_PAD = 1;
    /** Direct: a touch is where it is seen. Touchpad: fingers move the pointer, as on a laptop. */
    private int touchMode;
    private Touchpad touchpad;
    private boolean threeDown;
    private long threeAt;
    private int mouseButtons;
    private boolean captured;
    /** Gamepads by device id: their slot in the record and what they hold. */
    private final android.util.SparseIntArray padSlots = new android.util.SparseIntArray();
    private final android.util.SparseArray<InputWire.Pad> pads = new android.util.SparseArray<>();
    private final android.util.SparseIntArray padKeys = new android.util.SparseIntArray();
    private android.hardware.input.InputManager.InputDeviceListener padListener;

    private InputLink link() {
        MirrorSession s = session;
        return s == null || s.ended() ? null : s.input;
    }

    /** The wire to change for {@code kind}, null when its input does not go out (and then, the first time, asks). */
    private InputWire wire(String kind) {
        InputLink link = link();
        return link != null && link.use(kind) ? link.wire() : null;
    }

    private void sent() {
        InputLink link = link();
        if (link != null) link.kick();
    }

    /** The picture's place in the window: what is seen of the video frame, bars and crop left out. */
    private final int[] where = new int[2];

    private float nx(float wx) {
        video.getLocationInWindow(where);
        return video.getWidth() <= 0 ? 0 : (wx - where[0]) / video.getWidth();
    }

    private float ny(float wy) {
        video.getLocationInWindow(where);
        return video.getHeight() <= 0 ? 0 : (wy - where[1]) / video.getHeight();
    }

    private static int toolOf(MotionEvent e, int i) {
        switch (e.getToolType(i)) {
            case MotionEvent.TOOL_TYPE_STYLUS: return InputWire.TOOL_STYLUS;
            case MotionEvent.TOOL_TYPE_ERASER: return InputWire.TOOL_ERASER;
            default: return InputWire.TOOL_FINGER;
        }
    }

    private static boolean isMouse(MotionEvent e) {
        return e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                || (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    /** A three-finger tap opens the panel when touches go to the sender; the fingers are let go there. */
    private boolean threeFingers(MotionEvent e) {
        int action = e.getActionMasked();
        InputLink link = link();
        if (link == null || !link.granted()) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 3) {
            threeDown = true;
            threeAt = e.getEventTime();
            InputWire w = link.wire();
            if (w != null) {
                w.setContacts(java.util.Collections.emptyList(), InputLink.now());
                if (w.buttons() != 0) w.setButtons(0, InputLink.now());
                link.kick();
            }
            return true;
        }
        if (!threeDown) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            threeDown = false;
            if (action == MotionEvent.ACTION_UP && e.getEventTime() - threeAt < 400) {
                releaseCapture();
                togglePanel();
            }
        }
        return true;
    }

    /** Direct mode, and the stylus in either mode: every contact down now, where it is on the picture. */
    private void direct(MotionEvent e) {
        InputWire w = link().wire();
        int action = e.getActionMasked();
        java.util.List<InputWire.Contact> now = new java.util.ArrayList<>();
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            int gone = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i != gone) now.add(contact(e, i, false));
            }
        }
        w.setContacts(now, InputLink.now());
        sent();
    }

    private InputWire.Contact contact(MotionEvent e, int i, boolean hover) {
        int tool = toolOf(e, i);
        int pressure = 0;   // a finger's "pressure" is the size of its touch on most screens: not worth sending
        if (tool != InputWire.TOOL_FINGER) {
            pressure = InputWire.norm(Math.min(1f, e.getPressure(i)));
            if (Build.VERSION.SDK_INT >= 23 && (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                tool |= InputWire.TOOL_BUTTON;
            }
            if (hover) tool |= InputWire.TOOL_HOVER;
        }
        return new InputWire.Contact(e.getPointerId(i), tool, InputWire.norm(nx(e.getX(i))),
                InputWire.norm(ny(e.getY(i))), pressure);
    }

    /** Touchpad mode: the middle of the fingers drives a {@link Touchpad}. */
    private void touchpad(MotionEvent e) {
        if (touchpad == null) {
            touchpad = new Touchpad(new Touchpad.Out() {
                @Override
                public void moveBy(double dx, double dy) {
                    InputWire w = link().wire();
                    if (w != null) w.moveBy(dx, dy, InputLink.now());
                    sent();
                }

                @Override
                public void wheel(double dy, double dx) {
                    InputWire w = link().wire();
                    if (w != null) w.wheel(dy, dx, true, InputLink.now());
                    sent();
                }

                @Override
                public void buttons(int b) {
                    InputLink l = link();
                    InputWire w = l == null ? null : l.wire();
                    if (w != null) w.setButtons(b, InputLink.now());
                    sent();
                }

                @Override
                public void later(Runnable r, long ms) {
                    handler.postDelayed(r, ms);
                }
            }, 8 * getResources().getDisplayMetrics().density);
        }
        InputLink link = link();
        MirrorSession s = session;
        int areaW = link.areaWidth() > 0 ? link.areaWidth() : s.videoWidth();
        touchpad.setGain(video.getWidth() > 0 ? areaW / (double) video.getWidth() : 1);
        int action = e.getActionMasked();
        int gone = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        float cx = 0, cy = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == gone) continue;
            cx += e.getX(i);
            cy += e.getY(i);
            n++;
        }
        if (n > 0) {
            cx /= n;
            cy /= n;
        }
        long t = e.getEventTime();
        switch (action) {
            case MotionEvent.ACTION_DOWN: touchpad.start(t, cx, cy); break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP: touchpad.fingers(t, n, cx, cy); break;
            case MotionEvent.ACTION_MOVE: touchpad.move(t, cx, cy); break;
            case MotionEvent.ACTION_UP: touchpad.end(t, false); break;
            case MotionEvent.ACTION_CANCEL: touchpad.end(t, true); break;
            default: break;
        }
    }

    /** A mouse: captured (Android 8+), it moves the sender's pointer by its motion; else by where it points. */
    private boolean mouse(MotionEvent e) {
        InputWire w = wire("mouse");
        if (w == null) return false;
        int action = e.getActionMasked();
        if (Build.VERSION.SDK_INT >= 26 && !captured && !panelShown()) {
            rootView.setFocusable(true);
            rootView.setFocusableInTouchMode(true);
            rootView.requestFocus();
            rootView.requestPointerCapture();
        }
        int now = InputLink.now();
        if (action == MotionEvent.ACTION_SCROLL) {
            w.wheel(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120, e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120, false, now);
        } else if (action != MotionEvent.ACTION_HOVER_EXIT) {
            w.moveTo(nx(e.getX()), ny(e.getY()), now);
        }
        int b = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : e.getButtonState() & 0x1f;
        if (b != mouseButtons) {
            mouseButtons = b;
            w.setButtons(b, now);
        }
        sent();
        return true;
    }

    /** A captured mouse (Android 8+): relative motion, nothing of the system's pointer on the screen. */
    private boolean captured(MotionEvent e) {
        InputWire w = wire("mouse");
        if (w == null) {
            releaseCapture();
            return false;
        }
        int now = InputLink.now();
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) {
            w.wheel(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120, e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120, false, now);
        } else {
            float dx = 0, dy = 0;
            for (int h = 0; h < e.getHistorySize(); h++) {
                dx += e.getHistoricalX(h);
                dy += e.getHistoricalY(h);
            }
            dx += e.getX();
            dy += e.getY();
            if (dx != 0 || dy != 0) w.moveBy(dx, dy, now);
        }
        int b = e.getButtonState() & 0x1f;
        if (b != mouseButtons) {
            mouseButtons = b;
            w.setButtons(b, now);
        }
        sent();
        return true;
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        captured = hasCapture;
    }

    private void releaseCapture() {
        if (Build.VERSION.SDK_INT >= 26 && captured) rootView.releasePointerCapture();
    }

    private boolean panelShown() {
        return panel.getVisibility() == View.VISIBLE;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (isJoystick(e) && action == MotionEvent.ACTION_MOVE) {
            if (padAxes(e)) return true;
        } else if (isMouse(e)) {
            if (mouse(e)) return true;
        } else if (toolOf(e, 0) != InputWire.TOOL_FINGER && (action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_EXIT)) {
            // a stylus near the screen: the sender sees where it would touch
            InputLink link = link();
            if (link != null && link.granted()) {
                java.util.List<InputWire.Contact> now = new java.util.ArrayList<>();
                if (action != MotionEvent.ACTION_HOVER_EXIT) now.add(contact(e, 0, true));
                link.wire().setContacts(now, InputLink.now());
                link.kick();
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    private static boolean isJoystick(MotionEvent e) {
        return (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** A gamepad: a device with sticks. A remote's D-pad is not one, and stays the receiver's. */
    private static boolean isPad(KeyEvent e) {
        InputDevice d = e.getDevice();
        boolean sticks = d != null && (d.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        return KeyEvent.isGamepadButton(e.getKeyCode()) || (sticks && KeyMap.padButton(e.getKeyCode()) >= 0);
    }

    /** Tests only: the emulator has no keyboard but the virtual one, which takes injected keys. */
    static volatile boolean virtualIsKeyboard;

    /** A real keyboard with letters on it: a remote that calls itself a keyboard has no A, Z and Space. */
    private static boolean isKeyboard(KeyEvent e) {
        InputDevice d = e.getDevice();
        if (d != null && d.isVirtual() && virtualIsKeyboard) return true;
        if (d == null || d.isVirtual() || d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false;
        boolean[] has = d.hasKeys(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_SPACE);
        return has[0] && has[1] && has[2];
    }

    private int padSlot(int deviceId) {
        int slot = padSlots.get(deviceId, -1);
        if (slot >= 0) return slot;
        boolean[] taken = new boolean[InputWire.MAX_PADS];
        for (int i = 0; i < padSlots.size(); i++) taken[padSlots.valueAt(i)] = true;
        for (int i = 0; i < taken.length; i++) {
            if (!taken[i]) {
                padSlots.put(deviceId, i);
                InputWire.Pad p = new InputWire.Pad();
                p.connected = true;
                pads.put(deviceId, p);
                watchPads();
                return i;
            }
        }
        return -1;
    }

    private boolean padAxes(MotionEvent e) {
        InputWire w = wire("gamepad");
        if (w == null) return false;
        int slot = padSlot(e.getDeviceId());
        if (slot < 0) return false;
        InputWire.Pad p = pads.get(e.getDeviceId());
        p.lx = KeyMap.stick(e.getAxisValue(MotionEvent.AXIS_X));
        p.ly = KeyMap.stick(e.getAxisValue(MotionEvent.AXIS_Y));
        p.rx = KeyMap.stick(e.getAxisValue(MotionEvent.AXIS_Z));
        p.ry = KeyMap.stick(e.getAxisValue(MotionEvent.AXIS_RZ));
        p.lt = KeyMap.trigger(Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)));
        p.rt = KeyMap.trigger(Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)));
        InputDevice d = e.getDevice();
        boolean hat = d != null && d.getMotionRange(MotionEvent.AXIS_HAT_X, e.getSource()) != null;
        p.buttons = KeyMap.hat(padKeys.get(e.getDeviceId()), e.getAxisValue(MotionEvent.AXIS_HAT_X),
                e.getAxisValue(MotionEvent.AXIS_HAT_Y), hat);
        w.pad(slot, p, SystemClock.uptimeMillis(), InputLink.now());
        sent();
        return true;
    }

    private boolean padKey(KeyEvent e) {
        int bit = KeyMap.padButton(e.getKeyCode());
        if (bit < 0) return false;
        InputWire w = wire("gamepad");
        if (w == null) return false;
        int slot = padSlot(e.getDeviceId());
        if (slot < 0) return false;
        int keys = padKeys.get(e.getDeviceId());
        keys = e.getAction() == KeyEvent.ACTION_DOWN ? keys | 1 << bit : keys & ~(1 << bit);
        padKeys.put(e.getDeviceId(), keys);
        InputWire.Pad p = pads.get(e.getDeviceId());
        p.buttons = (p.buttons & KeyMap.PAD_DPAD & ~keys) | keys;   // a hat's D-pad stays as the axes left it
        w.pad(slot, p, SystemClock.uptimeMillis(), InputLink.now());
        sent();
        return true;
    }

    /** An unplugged gamepad is said to be gone, so the sender removes its own. */
    private void watchPads() {
        if (padListener != null) return;
        android.hardware.input.InputManager im = Compat.service(this, android.hardware.input.InputManager.class);
        if (im == null) return;
        padListener = new android.hardware.input.InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
            }

            @Override
            public void onInputDeviceChanged(int id) {
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                int slot = padSlots.get(id, -1);
                if (slot < 0) return;
                padSlots.delete(id);
                padKeys.delete(id);
                pads.remove(id);
                InputLink link = link();
                InputWire w = link == null ? null : link.wire();
                if (w == null) return;
                w.pad(slot, new InputWire.Pad(), SystemClock.uptimeMillis(), InputLink.now());
                link.kick();
            }
        };
        im.registerInputDeviceListener(padListener, handler);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return super.dispatchKeyEvent(e);
        if (isPad(e)) {
            if (padKey(e)) return true;
        } else if (isKeyboard(e) && !KeyMap.staysHere(e.getKeyCode())) {
            int code = KeyMap.evdev(e.getKeyCode(), e.getScanCode());
            InputWire w = code > 0 ? wire("keyboard") : null;
            if (w != null) {
                if (e.getRepeatCount() == 0) {   // the sender repeats a held key itself
                    w.key(code, e.getAction() == KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis(), InputLink.now());
                    sent();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void setTouchMode(int mode) {
        touchMode = mode;
        prefs().edit().putInt("touch_mode", mode).apply();
        refreshPanel();
    }

    /** What became of the sender's control from here, as a toast (any thread). */
    static void inputChanged(String what, String sender) {
        MirrorActivity a = shown;
        if (a == null) return;
        a.runOnUiThread(() -> {
            int text;
            switch (what) {
                case InputLink.ASKING: text = R.string.input_asking; break;
                case InputLink.GRANTED: text = R.string.input_granted; break;
                case InputLink.DENIED: text = R.string.input_denied; break;
                default: text = R.string.input_off; break;
            }
            if (!InputLink.GRANTED.equals(what)) a.releaseCapture();
            android.widget.Toast.makeText(a, a.getString(text, sender), android.widget.Toast.LENGTH_SHORT).show();
            a.refreshPanel();
        });
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
        if (s != null && s.input.senderWants && !s.input.denied()) {
            col.addView(Ui.chips(this, new String[] {getString(R.string.touch_direct), getString(R.string.touch_pad)},
                    touchMode, this::setTouchMode), Ui.below(this, 8));
        }
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

    /** The remote's media keys go to the sender's player (PROTOCOL.md 4d). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String key = Remote.mediaKey(keyCode);
        if (key != null) {
            if (event.getRepeatCount() == 0) Remote.sendKey(key);
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        session = s;
        if (s == null) return;
        Surface surface = holder.getSurface();
        // Do not pin the panel to the stream rate: at its top refresh the compositor presents a finished
        // frame at the next 1/120 s boundary instead of waiting for the next 1/60 s one.
        s.setSurface(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Display d = getWindowManager().getDefaultDisplay();
        int hz = d == null ? 0 : Math.round(d.getRefreshRate());
        surfaceInfo = width + "x" + height + (hz > 0 ? "@" + hz + "Hz" : "");
    }

    /** Rendering surface size and refresh rate, or "?" before the surface exists. */
    static String surfaceInfo() {
        return surfaceInfo;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        MirrorSession s = session;
        if (s != null) s.setSurface(null);
    }
}
