package ru.toxblh.luch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * A soft amber beam that slowly appears from the icon's centre and widens until it covers the QR code, a wave that keeps
 * running along it into the code, and the code itself, which fades in after the beam and brightens as each wave passes.
 * The code stays a plain square: that is what a camera needs.
 */
final class QrView extends View {
    private static final int[] AMBER = {0xFFFFBA5F, 0xFFFFD9A0, 0xFFE09A3E};
    private static final int OFF = 0xFF3A332D;
    /** Cells between the icon's centre and the code. */
    private static final int LEAD = 40;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path cone = new Path();
    private final Matrix matrix = new Matrix();
    private Shader fade, band;
    private int shaderCell = -1;
    private QrCode code;
    private String text = "";
    /** 0..1 once: the beam and the code appear. */
    private float intro = 1f;
    /** 0..1, repeating: where the wave is on its way. */
    private float phase;
    private ValueAnimator introAnim, pulseAnim;
    private int lead = LEAD;

    QrView(Context context) {
        super(context);
    }

    /** Just the square, with no beam: the back of the flipped card on a phone. */
    void setPlain(boolean plain) {
        lead = plain ? 0 : LEAD;
        requestLayout();
    }

    /** Draws a new code; the slow appearance runs again only when the content changed. */
    void setText(String value) {
        if (value.equals(text)) return;
        text = value;
        code = QrCode.encode(value);
        replay();
    }

    /** The beam and the code appear again, slowly. */
    void replay() {
        if (introAnim != null) introAnim.cancel();
        intro = 0f;
        introAnim = ValueAnimator.ofFloat(0f, 1f);
        introAnim.setDuration(4200);
        introAnim.setStartDelay(300);
        introAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        introAnim.addUpdateListener(a -> {
            intro = (float) a.getAnimatedValue();
            invalidate();
        });
        introAnim.start();
        requestLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(3200);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setInterpolator(new LinearInterpolator());
        pulseAnim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulseAnim != null) pulseAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = MeasureSpec.getSize(h);
        int n = code == null ? 1 : code.size;
        setMeasuredDimension(Math.round(height / (float) n * (n + lead)), height);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (code == null) return;
        int n = code.size;
        float cell = getHeight() / (float) n;
        float qx = lead * cell, yc = getHeight() / 2f, half = n * cell / 2f;
        float iconR = 50f * getResources().getDisplayMetrics().density; // the icon's outer circle
        if (shaderCell != Math.round(cell * 100) + lead) {
            shaderCell = Math.round(cell * 100) + lead;
            // the beam starts at the icon's centre but fades in softly over the first part, so its tip has no hard edge
            float p0 = qx > 0 ? Math.min(.6f, iconR / qx) : 0f;
            if (qx > 0) fade = new LinearGradient(0, 0, qx, 0, new int[] {0x00FFBA5F, 0x70FFBA5F, 0x30FFBA5F, 0x1CFFBA5F},
                    new float[] {0f, p0 * .8f, .72f, 1f}, Shader.TileMode.CLAMP);
            float bw = 7 * cell;
            band = new LinearGradient(-bw, 0, bw, 0, new int[] {0x00FFD9A0, 0x90FFD9A0, 0x00FFD9A0}, new float[] {0f, .5f, 1f},
                    Shader.TileMode.CLAMP);
        }
        float beam = clamp(intro * 2.2f); // the beam is up in the first half of the time
        float waveX = phase * (qx + n * cell * 1.1f);

        // the glow: a few cones of different width on top of each other, so the edge is soft
        paint.setShader(fade);
        for (int k = 0; k < (lead == 0 ? 0 : 6); k++) {
            float s = 1f - k * .13f;
            cone.reset();
            cone.moveTo(0, yc - 2.5f * cell * s);
            cone.lineTo(qx, yc - half * s);
            cone.lineTo(qx, yc + half * s);
            cone.lineTo(0, yc + 2.5f * cell * s);
            cone.close();
            paint.setAlpha((int) (255 * .45f * beam));
            c.drawPath(cone, paint);
        }
        // the wave running along the beam
        if (lead > 0 && waveX < qx + 7 * cell) {
            c.save();
            c.clipPath(cone);
            matrix.setTranslate(waveX, 0);
            band.setLocalMatrix(matrix);
            paint.setShader(band);
            paint.setAlpha((int) (255 * beam));
            c.drawRect(0, 0, qx, getHeight(), paint);
            c.restore();
        }
        paint.setShader(null);

        // the code: fades in after the beam, left to right, and brightens when a wave passes
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float t = clamp((intro * 1.7f - .75f - (x / (float) n) * .55f) / .4f);
                if (t <= 0f) continue;
                float cx = qx + (x + .5f) * cell, cy = (y + .5f) * cell;
                float near = Math.abs(cx - waveX) / (6 * cell);
                float boost = near < 1f ? (1f - near) * (1f - near) : 0f;
                if (code.get(x, y)) {
                    boolean fn = code.isFunction(x, y);
                    paint.setColor(fn ? AMBER[0] : AMBER[(x * 7 + y * 13 + x * y) % 3]);
                    paint.setAlpha((int) (255 * t * (.8f + .2f * boost)));
                    float r = cell * (fn ? .5f : .44f) * (.35f + .65f * t) * (1f + .08f * boost);
                    rect.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawRoundRect(rect, r * .7f, r * .7f, paint);
                } else {
                    paint.setColor(OFF);
                    paint.setAlpha((int) (255 * t));
                    c.drawCircle(cx, cy, cell * .16f * t, paint);
                }
            }
        }
    }
}
