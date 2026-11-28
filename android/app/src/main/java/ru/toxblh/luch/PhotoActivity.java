// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One photo of a {@link PhotoSession} full screen, on black, fitted; left and right on the remote (or a swipe) go to the next. */
public class PhotoActivity extends Activity {
    private final BackGuard backGuard = new BackGuard();
    private static final long MAX_BYTES = 80L * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static PhotoActivity instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private ImageView image;
    private TextView badge, message;
    /** The presenter's pointer and ink over the photo, one layer per photo (PROTOCOL.md 4h). */
    private InkView ink;
    private PhotoSession inked;
    private GestureDetector swipe;
    private int shown = -1;
    private volatile int wanted;

    static void finishFromOutside() {
        PhotoActivity a = instance;
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** The sender turns to another photo of its list ({"t":"photo_index"}, PROTOCOL.md 4a): only the one shown obeys. */
    static void turnTo(MirrorSession.Control control, int index) {
        PhotoSession s = PhotoSession.current;
        PhotoActivity a = instance;
        if (s == null || s.control != control || a == null || index < 0 || index >= s.items.size()) return;
        a.runOnUiThread(() -> a.show(index));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badge = Ui.text(this, "", 16, Ui.TEXT, false);
        badge.setBackground(Ui.round(this, 0xB0121318, 20, 0));
        badge.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        badge.setAlpha(0f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 32);
        root.addView(badge, bp);
        message = Ui.text(this, "", 20, Ui.MUTED, false);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        ink = new InkView(this, out -> InkView.imageFrame(image, ink, out), () -> {
            PhotoSession s = PhotoSession.current;
            return s == null ? null : s.control;
        });
        root.addView(ink, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Android 13+ calls no onBackPressed for this app (it opts into the new back handling): the sender must still
        // hear "back".
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null || Math.abs(vx) < 600 || Math.abs(b.getX() - a.getX()) < Ui.dp(PhotoActivity.this, 80)) {
                    return false;
                }
                go(vx < 0 ? 1 : -1);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhotoSession s = PhotoSession.current;
        if (s == null) {
            finish();
            return;
        }
        ink.toFront();
        show(s.index);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        PhotoSession s = PhotoSession.current;
        if (s != null) show(s.index);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        loader.shutdownNow();
        super.onDestroy();
    }

    /** Back: tell the sender and leave. */
    @Override
    public void onBackPressed() {
        if (!backGuard.confirm(this)) return;
        PhotoSession s = PhotoSession.current;
        if (s != null) {
            s.control.send(PhotoSession.stop("back"));
            PhotoSession.current = null;
        }
        finish();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        swipe.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_MEDIA_NEXT) {
            go(1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            go(-1);
            return true;
        }
        return super.onKeyDown(key, e);
    }

    private void go(int step) {
        PhotoSession s = PhotoSession.current;
        if (s == null) return;
        int to = s.index + step;
        if (to < 0 || to >= s.items.size()) return;
        show(to);
    }

    private void show(int index) {
        PhotoSession s = PhotoSession.current;
        if (s == null) return;
        s.index = index;
        if (s != inked) {
            // A new list from a sender: the ink of the last one goes with it.
            inked = s;
            ink.reset();
            shown = -1;
        }
        ink.setPage(index);
        if (index == shown) return;
        shown = index;
        wanted = index;
        // the sender follows, as for a document's page (a phone's notification says which one is up)
        try {
            s.control.send(new org.json.JSONObject().put("t", "photo_index").put("index", index));
        } catch (org.json.JSONException ignored) {
        }
        message.setText("");
        if (s.items.size() > 1) {
            badge.setText((index + 1) + " / " + s.items.size());
            badge.animate().cancel();
            badge.setAlpha(1f);
            badge.animate().alpha(0f).setStartDelay(1800).setDuration(500).start();
        }
        final PhotoSession.Item item = s.items.get(index);
        loader.execute(() -> {
            Bitmap bmp = null;
            String why = null;
            try {
                bmp = fetch(item);
            } catch (Exception e) {
                why = String.valueOf(e.getMessage());
            }
            final Bitmap result = bmp;
            final String error = why;
            main.post(() -> {
                if (wanted != index || isDestroyed()) return;
                if (result != null) {
                    image.setAlpha(0f);
                    image.setImageBitmap(result);
                    image.animate().alpha(1f).setDuration(220).start();
                } else {
                    image.setImageDrawable(null);
                    message.setText(getString(R.string.photo_failed) + (error == null ? "" : "\n" + error));
                }
            });
            // the neighbour is fetched while this one is looked at
            if (index + 1 < s.items.size()) {
                try {
                    fetch(s.items.get(index + 1));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * A plain HTTP/1.0 GET over a socket. Android's own HTTP stack refuses cleartext, and the link is to a device on this
     * network, so the app does not turn cleartext on for everything just for this.
     */
    private static void download(String link, File file) throws Exception {
        URL u = new URL(link);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress(u.getHost(), port), 4000);
            sock.setSoTimeout(10000);
            sock.getOutputStream().write(("GET " + u.getFile() + " HTTP/1.0\r\nHost: " + u.getHost() + ":" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(sock.getInputStream(), 64 * 1024);
            String status = line(in);
            if (status == null || !status.contains(" 200")) throw new java.io.IOException("sender answered: " + status);
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                // headers are not needed: the body runs to the end of the connection
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new java.io.IOException("file too large");
                    out.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            file.delete();
            throw e;
        }
    }

    private static String line(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new java.io.IOException("header too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Downloads one item (kept in the cache for the next look), decodes it no larger than the screen can use, and turns it upright. */
    private Bitmap fetch(PhotoSession.Item item) throws Exception {
        File dir = new File(getCacheDir(), "photos");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no cache");
        File file = new File(dir, Integer.toHexString(item.url.hashCode()) + ".img");
        if (!file.isFile() || file.length() == 0) {
            download(item.url, file);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) throw new java.io.IOException("not an image");
        int want = Math.min(MAX_SIDE, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 3 / 2);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= want) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
        if (bmp == null) throw new java.io.IOException("cannot decode");
        int rotate = 0;
        try {
            int ori = new android.media.ExifInterface(file.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            rotate = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
        } catch (Exception ignored) {
        }
        if (rotate != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotate);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }
        return bmp;
    }
}
