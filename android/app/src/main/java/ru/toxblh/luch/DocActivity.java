// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A PDF from a sender (PROTOCOL.md 4g), one page at a time, drawn at the screen's own resolution. Left/right turn the
 * page, OK switches between the whole page and the page's width (up/down then scroll), Back stops.
 */
public class DocActivity extends Activity {
    static final long MAX_BYTES = 200L * 1024 * 1024;

    /** What a sender asked to show; one at a time. */
    static final class Doc {
        final MirrorSession.Control control;
        final String url, name;
        volatile int page;
        volatile int pages = -1;

        Doc(MirrorSession.Control control, String url, String name, int page) {
            this.control = control;
            this.url = url;
            this.name = name;
            this.page = page;
        }
    }

    static volatile Doc current;
    private static volatile DocActivity instance;

    /** Checks the link (on the sender only), then opens the screen; doc_ready follows once the file is in. */
    static void open(Context c, MirrorSession.Control control, String sender, JSONObject msg) throws MediaLink.BadLink {
        String url = msg.optString("url");
        MediaLink.check(url, sender, false, MediaLink.SYSTEM_DNS);
        Doc d = new Doc(control, url, msg.optString("name"), Math.max(0, msg.optInt("page", 0)));
        Doc old = current;
        current = d;
        if (old != null && old.control != control) old.control.send(PhotoSession.stop("replaced"));
        DocActivity a = instance;
        if (a != null) a.runOnUiThread(a::load);
        else c.startActivity(new Intent(c, DocActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    static void closeFrom(MirrorSession.Control control) {
        Doc d = current;
        if (d == null || d.control != control) return;
        current = null;
        DocActivity a = instance;
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** The sender turned a page. */
    static void turnTo(MirrorSession.Control control, int page) {
        Doc d = current;
        DocActivity a = instance;
        if (d == null || d.control != control || a == null) return;
        a.runOnUiThread(() -> a.show(page));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScrollView scroll;
    private ImageView image;
    private TextView badge, message;
    private GestureDetector swipe;
    private PdfRenderer renderer;
    private ParcelFileDescriptor fd;
    private Doc loaded;
    private boolean byWidth;
    private int shown = -1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(48, 48, 52));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scroll.addView(image, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badge = Ui.text(this, "", 16, Ui.TEXT, false);
        badge.setBackground(Ui.round(this, 0xB0121318, 20, 0));
        badge.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 24);
        root.addView(badge, bp);
        message = Ui.text(this, getString(R.string.media_loading), 22, Ui.MUTED, false);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null || Math.abs(vx) < 600 || Math.abs(vx) < Math.abs(vy)) return false;
                turn(vx < 0 ? 1 : -1);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleFit();
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        load();
    }

    /** Fetches the current document to a file (PdfRenderer needs to seek) and opens it. */
    private void load() {
        Doc d = current;
        if (d == null) {
            finish();
            return;
        }
        message.setText(getString(R.string.media_loading));
        message.setVisibility(android.view.View.VISIBLE);
        worker.execute(() -> {
            String error = null;
            PdfRenderer r = null;
            ParcelFileDescriptor f = null;
            try {
                File file = new File(getCacheDir(), "doc.pdf");
                fetch(d.url, file);
                f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                r = new PdfRenderer(f);
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
                Log.w(LuchService.TAG, "doc: " + e);
            }
            final PdfRenderer renderer = r;
            final ParcelFileDescriptor file = f;
            final String why = error;
            main.post(() -> opened(d, renderer, file, why));
        });
    }

    private void opened(Doc d, PdfRenderer r, ParcelFileDescriptor f, String error) {
        if (d != current || isDestroyed()) {
            closeQuietly(r, f);
            return;
        }
        if (r == null) {
            try {
                d.control.send(new JSONObject().put("t", "error").put("code", "doc_failed").put("for", "doc")
                        .put("detail", error));
            } catch (JSONException ignored) {
            }
            current = null;
            finish();
            return;
        }
        closeQuietly(renderer, fd);
        renderer = r;
        fd = f;
        loaded = d;
        d.pages = r.getPageCount();
        message.setVisibility(android.view.View.GONE);
        try {
            d.control.send(new JSONObject().put("t", "doc_ready").put("pages", d.pages));
        } catch (JSONException ignored) {
        }
        shown = -1;
        show(d.page);
    }

    private static void fetch(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("document too big");
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    /** The page size in pixels for this screen: the whole page fitted, or the page's width filling the screen. */
    static int[] target(int pageW, int pageH, int screenW, int screenH, boolean byWidth) {
        float scale = byWidth ? (float) screenW / pageW : Math.min((float) screenW / pageW, (float) screenH / pageH);
        // A bitmap no bigger than 4 screens: a very tall page by its width would not fit in memory.
        long max = 4L * screenW * screenH;
        if ((long) (pageW * scale) * (long) (pageH * scale) > max) {
            scale = (float) Math.sqrt((double) max / ((long) pageW * pageH));
        }
        return new int[]{Math.max(1, Math.round(pageW * scale)), Math.max(1, Math.round(pageH * scale))};
    }

    private void show(int page) {
        PdfRenderer r = renderer;
        Doc d = loaded;
        if (r == null || d == null) return;
        int p = Math.max(0, Math.min(page, r.getPageCount() - 1));
        if (p == shown) return;
        shown = p;
        d.page = p;
        int sw = getWindow().getDecorView().getWidth(), sh = getWindow().getDecorView().getHeight();
        if (sw <= 0 || sh <= 0) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            sw = m.widthPixels;
            sh = m.heightPixels;
        }
        Bitmap bmp;
        try (PdfRenderer.Page pg = r.openPage(p)) {
            int[] size = target(pg.getWidth(), pg.getHeight(), sw, sh, byWidth);
            bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (RuntimeException e) {
            Log.w(LuchService.TAG, "doc page " + p + ": " + e);
            return;
        }
        image.setImageBitmap(bmp);
        image.setScaleType(byWidth ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.FIT_CENTER);
        scroll.scrollTo(0, 0);
        badge.setText((p + 1) + " / " + r.getPageCount());
        badge.animate().cancel();
        badge.setAlpha(1f);
        badge.animate().alpha(0f).setStartDelay(1800).setDuration(500).start();
        try {
            d.control.send(new JSONObject().put("t", "doc_page").put("page", p));
        } catch (JSONException ignored) {
        }
    }

    private void turn(int step) {
        show(shown + step);
    }

    private void toggleFit() {
        byWidth = !byWidth;
        int p = shown;
        shown = -1;
        show(p);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                turn(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                turn(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) toggleFit();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!byWidth) turn(1);
                else scroll.smoothScrollBy(0, getWindow().getDecorView().getHeight() / 3);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!byWidth) turn(-1);
                else scroll.smoothScrollBy(0, -getWindow().getDecorView().getHeight() / 3);
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        swipe.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        back();
    }

    private void back() {
        Doc d = current;
        if (d != null) {
            d.control.send(PhotoSession.stop("back"));
            current = null;
        }
        finish();
    }

    private static void closeQuietly(PdfRenderer r, ParcelFileDescriptor f) {
        try {
            if (r != null) r.close();
            if (f != null) f.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        worker.shutdownNow();
        closeQuietly(renderer, fd);
        renderer = null;
        super.onDestroy();
    }
}
