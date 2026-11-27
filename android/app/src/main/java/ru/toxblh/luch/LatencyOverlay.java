// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;

/**
 * The latency lab on the TV (PROTOCOL.md, section 4j): the receiver's own pattern over the mirrored picture, its
 * counter going up once per refresh of this screen. A camera filming the TV sees it go through the same compositor and
 * panel as the picture, so it tells the TV's refresh, how evenly it draws and who it is.
 */
final class LatencyOverlay extends View implements Choreographer.FrameCallback {
    /** Refreshes counted, also those this view missed: a clock, not a frame number. */
    private long count;
    private long lastNanos;
    private final long[] deltas = new long[120];
    private int deltaCount;
    private double periodNanos = 1e9 / 60;
    private String text = "";
    private Bitmap qr;
    private long nextMeta;
    private boolean running;
    private final Paint white = new Paint(), black = new Paint(), digits = new Paint(), plain = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    LatencyOverlay(Context context) {
        super(context);
        white.setColor(0xFFFFFFFF);
        black.setColor(0xFF000000);
        digits.setColor(0xFFFFFFFF);
        digits.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        digits.setAntiAlias(true);
        plain.setFilterBitmap(false); // QR modules stay sharp squares when scaled up
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        if (lastNanos != 0) {
            long d = nanos - lastNanos;
            deltas[deltaCount++ % deltas.length] = d;
            long[] sorted = java.util.Arrays.copyOf(deltas, Math.min(deltaCount, deltas.length));
            java.util.Arrays.sort(sorted);
            periodNanos = sorted[sorted.length / 2];
            count += Math.max(1, Math.round(d / periodNanos));
        }
        lastNanos = nanos;
        if (nanos >= nextMeta) {
            nextMeta = nanos + 2_000_000_000L;
            refreshMeta();
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** What this TV is and how it shows the picture, in the QR code; redrawn only when it changes. */
    private void refreshMeta() {
        MirrorSession s = MirrorSession.current;
        MirrorSession.Live live = s == null ? null : s.live();
        Display d = getDisplay();
        String mode = "";
        if (d != null && Build.VERSION.SDK_INT >= 23) {
            Display.Mode m = d.getMode();
            mode = m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + Math.round(m.getRefreshRate());
        }
        String t = LatencyPattern.payload(BuildConfig.VERSION_NAME, Build.MANUFACTURER + " " + Build.MODEL,
                Build.VERSION.RELEASE, live == null ? "" : live.decoder, mode, 1e9 / periodNanos,
                MirrorActivity.surfaceInfo(), Build.VERSION.SDK_INT >= 30);
        if (t.equals(text)) return;
        text = t;
        QrCode code = QrCode.encodeLow(t);
        Bitmap b = Bitmap.createBitmap(code.size, code.size, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) b.setPixel(x, y, code.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
        }
        qr = b;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (qr == null || w == 0 || h == 0) return;
        float unit = LatencyPattern.SIDE * h;
        float[] o = LatencyPattern.origin(w, h);
        float[] box = LatencyPattern.BOX;
        c.drawRect(snap(o[0] + box[0] * unit), snap(o[1] + box[1] * unit), snap(o[0] + box[2] * unit),
                snap(o[1] + box[3] * unit), white);
        src.set(0, 0, qr.getWidth(), qr.getHeight());
        dst.set(snap(o[0]), snap(o[1]), snap(o[0] + unit), snap(o[1] + unit));
        c.drawBitmap(qr, src, dst, plain);
        for (int i = 0; i < LatencyPattern.CELLS; i++) {
            float[] r = LatencyPattern.cell(i);
            c.drawRect(snap(o[0] + r[0] * unit), snap(o[1] + r[1] * unit), snap(o[0] + r[2] * unit),
                    snap(o[1] + r[3] * unit), LatencyPattern.cellDark(i, count) ? black : white);
        }
        // The count for people, left of the pattern.
        digits.setTextSize(0.09f * h);
        String n = String.valueOf(count % 100000);
        float tw = digits.measureText("00000");
        float x = o[0] + box[0] * unit - 0.03f * h - tw, y = h - LatencyPattern.MARGIN * h;
        c.drawRect(x - 0.01f * h, y - 0.1f * h, x + tw + 0.01f * h, y + 0.01f * h, black);
        c.drawText(n, x + tw - digits.measureText(n), y - 0.015f * h, digits);
    }

    private static float snap(float v) {
        return Math.round(v);
    }
}
