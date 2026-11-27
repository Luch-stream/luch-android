// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pointer, the ink and the blank screen of PROTOCOL.md 4h, drawn over a screen's picture at the screen's own
 * resolution. Laid over the whole screen; the screen tells where its picture is drawn now ({@link Frame}), so a zoomed,
 * scrolled or cropped picture keeps its ink in place. Messages come on a connection's thread: the model is locked, and
 * the view only asks for the next frame, so nothing is ever queued.
 */
final class InkView extends View {
    /** Where the picture is drawn, in this view's coordinates; false while there is none. */
    interface Frame {
        boolean frame(RectF out);
    }

    /** Who may draw on this screen: the control of what it shows, or null. */
    interface Owner {
        MirrorSession.Control owner();
    }

    /** The screen in front, the only one that takes pointer and ink messages. */
    private static volatile InkView front;

    final Ink model = new Ink();
    private final Frame frame;
    private final Owner owner;
    private final RectF rect = new RectF(), drawn = new RectF();
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glowAt = new Matrix();
    /** Paths of the strokes, made again only when a stroke grows or the picture moves. */
    private Map<Ink.Stroke, Cached> paths = new IdentityHashMap<>();

    private static final class Cached {
        final Path path = new Path();
        int points;
    }

    /** Repaints when the picture moved under the ink (a scroll, a crop sliding in) though nothing told this view. */
    private final ViewTreeObserver.OnPreDrawListener follow = this::follow;

    private boolean follow() {
        if (frame.frame(rect) && !rect.equals(drawn)) invalidate();
        return true;
    }

    InkView(Context c, Frame frame, Owner owner) {
        super(c);
        this.frame = frame;
        this.owner = owner;
        setWillNotDraw(false);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(0xFFFF2A1F);
        // A glow of unit radius around 0, 0, moved and scaled to the pointer as it is drawn: no shader per frame.
        glow.setShader(new RadialGradient(0, 0, 1, new int[]{0x99FF3020, 0x44FF3020, 0x00FF3020},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    /** The screen that takes the messages now, or null (for the on-device tests). */
    static InkView front() {
        return front;
    }

    /** Where the picture is on the display, in screen pixels (for the on-device tests, against a screenshot). */
    boolean pictureOnScreen(RectF out) {
        if (!frame.frame(out)) return false;
        int[] at = new int[2];
        getLocationOnScreen(at);
        out.offset(at[0], at[1]);
        return true;
    }

    /** This screen takes the messages from now on (it came to the front). */
    void toFront() {
        front = this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(follow);
        front = this;
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(follow);
        if (front == this) front = null;
        super.onDetachedFromWindow();
    }

    /**
     * A pointer, ink or blank message from {@code from}: obeyed only when it comes from the sender of what the front
     * screen shows, ignored otherwise. A blank is told back, so the sender's view of it stays right.
     */
    static void deliver(MirrorSession.Control from, JSONObject msg) {
        InkView v = front;
        if (v == null || from == null || v.owner.owner() != from) return;
        if (v.model.apply(msg)) v.postInvalidateOnAnimation();
        if ("blank".equals(msg.optString("t"))) tellBlank(from, v.model.blank());
    }

    /** The screen went black or came back: the sender is told whoever did it. */
    static void tellBlank(MirrorSession.Control to, boolean on) {
        if (to == null) return;
        try {
            to.send(new JSONObject().put("t", "blank").put("on", on));
        } catch (JSONException ignored) {
        }
    }

    /** Blank on or off from this screen (a clicker's B key); the sender is told. */
    void setBlank(boolean on) {
        if (model.setBlank(on)) invalidate();
        tellBlank(owner.owner(), on);
    }

    /** Another page or photo: its own strokes. A blank screen ends, and the sender hears of it. */
    void setPage(int key) {
        if (model.setPage(key)) tellBlank(owner.owner(), false);
        invalidate();
    }

    /** Something new is shown: nothing on it yet. */
    void reset() {
        model.reset();
        invalidate();
    }

    /** The drawn rectangle of an ImageView's picture, in {@code overlay}'s coordinates (scrolling included). */
    static boolean imageFrame(ImageView image, View overlay, RectF out) {
        Drawable d = image.getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0 || image.getWidth() <= 0) return false;
        out.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        image.getImageMatrix().mapRect(out);
        out.offset(image.getPaddingLeft(), image.getPaddingTop());
        return offset(image, overlay, out);
    }

    /** A view's own bounds (a SurfaceView the size of the video) in {@code overlay}'s coordinates. */
    static boolean viewFrame(View v, View overlay, RectF out) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        out.set(0, 0, v.getWidth(), v.getHeight());
        return offset(v, overlay, out);
    }

    private static boolean offset(View v, View overlay, RectF out) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        overlay.getLocationInWindow(b);
        out.offset(a[0] - b[0], a[1] - b[1]);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        synchronized (model) {
            if (model.blank()) {
                c.drawColor(Color.BLACK);
                return;
            }
            if (!frame.frame(rect)) return;
            if (!rect.equals(drawn)) {
                paths.clear();
                drawn.set(rect);
            }
            drawStrokes(c, model.strokes());
            if (model.pointerShown()) drawPointer(c, rect.left + model.pointerX() * rect.width(),
                    rect.top + model.pointerY() * rect.height());
        }
    }

    private void drawStrokes(Canvas c, List<Ink.Stroke> strokes) {
        Map<Ink.Stroke, Cached> kept = new IdentityHashMap<>();
        for (Ink.Stroke s : strokes) {
            if (s.count() == 0) continue;
            Cached p = paths.get(s);
            if (p == null) p = new Cached();
            if (p.points != s.count()) {
                smooth(p.path, s, rect);
                p.points = s.count();
            }
            kept.put(s, p);
            pen.setColor(s.color);
            pen.setStrokeWidth(Math.max(1f, s.width * rect.width()));
            if (s.count() == 1) {
                // A tap of the pen: a dot as wide as the line.
                pen.setStyle(Paint.Style.FILL);
                c.drawCircle(rect.left + s.points[0] * rect.width(), rect.top + s.points[1] * rect.height(),
                        pen.getStrokeWidth() / 2, pen);
                pen.setStyle(Paint.Style.STROKE);
            } else {
                c.drawPath(p.path, pen);
            }
        }
        paths = kept;   // strokes taken away (undo, clear, another page) leave the cache with them
    }

    /** A smooth line through the points: quadratic curves from one midpoint to the next, the points as controls. */
    private static void smooth(Path path, Ink.Stroke s, RectF r) {
        path.rewind();
        float[] p = s.points;
        float w = r.width(), h = r.height();
        int n = s.count();
        path.moveTo(r.left + p[0] * w, r.top + p[1] * h);
        for (int i = 1; i < n - 1; i++) {
            float x = r.left + p[2 * i] * w, y = r.top + p[2 * i + 1] * h;
            float nx = r.left + p[2 * i + 2] * w, ny = r.top + p[2 * i + 3] * h;
            path.quadTo(x, y, (x + nx) / 2, (y + ny) / 2);
        }
        path.lineTo(r.left + p[2 * n - 2] * w, r.top + p[2 * n - 1] * h);
    }

    private void drawPointer(Canvas c, float x, float y) {
        // Sized by the picture, not the screen: a dot that reads from the back of the room on any TV.
        float core = Math.max(4f, 0.008f * Math.min(rect.width(), rect.height()));
        float halo = core * 3.2f;
        glowAt.setScale(halo, halo);
        glowAt.postTranslate(x, y);
        glow.getShader().setLocalMatrix(glowAt);
        c.drawCircle(x, y, halo, glow);
        c.drawCircle(x, y, core, dot);
        dot.setColor(0xFFFFE0D8);
        c.drawCircle(x, y, core * 0.45f, dot);
        dot.setColor(0xFFFF2A1F);
    }
}
