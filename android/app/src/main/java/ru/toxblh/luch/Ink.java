// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a presenter points at and draws over the picture (PROTOCOL.md 4h), in fractions of the picture, so it maps onto
 * wherever the picture is drawn. Plain Java: {@link InkView} draws it, the JVM tests check it. Every method locks the
 * model; the view draws under the same lock.
 */
final class Ink {
    static final int MAX_MSG_POINTS = 2000;
    static final int MAX_STROKE_POINTS = 20000;
    static final int MAX_STROKES = 500;
    static final float DEFAULT_WIDTH = 0.004f;
    static final int DEFAULT_COLOR = 0xFFE53935;

    /** One line drawn with the pen: points as x, y pairs in fractions of the picture. */
    static final class Stroke {
        final String id;
        final int color;
        /** In fractions of the picture's width. */
        final float width;
        float[] points = new float[64];
        /** Floats used in {@link #points}: twice the number of points. */
        int size;

        Stroke(String id, int color, float width) {
            this.id = id;
            this.color = color;
            this.width = width;
        }

        int count() {
            return size / 2;
        }

        /** Appends the pairs of {@code a} (at most {@code max} of them), clamped; a pair that is not a number is skipped. */
        void append(JSONArray a, int max) {
            int pairs = Math.min(a.length() / 2, max);
            for (int i = 0; i < pairs && size < 2 * MAX_STROKE_POINTS; i++) {
                double x = a.optDouble(2 * i), y = a.optDouble(2 * i + 1);
                if (Double.isNaN(x) || Double.isNaN(y)) continue;
                if (size + 2 > points.length) points = java.util.Arrays.copyOf(points, points.length * 2);
                points[size++] = clamp(x);
                points[size++] = clamp(y);
            }
        }
    }

    private final Map<Integer, List<Stroke>> pages = new HashMap<>();
    private int page;
    private boolean pointer, blank;
    private float px, py;

    synchronized boolean pointerShown() {
        return pointer && !blank;
    }

    synchronized float pointerX() {
        return px;
    }

    synchronized float pointerY() {
        return py;
    }

    synchronized boolean blank() {
        return blank;
    }

    synchronized int page() {
        return page;
    }

    /** The strokes of the page shown, newest last. Read under the model's lock: it is the live list. */
    synchronized List<Stroke> strokes() {
        List<Stroke> s = pages.get(page);
        if (s == null) {
            s = new ArrayList<>();
            pages.put(page, s);
        }
        return s;
    }

    /** Applies a pointer, ink or blank message; true when what is drawn changed. */
    synchronized boolean apply(JSONObject m) {
        switch (m.optString("t")) {
            case "pointer":
                return pointer(m);
            case "ink":
                return ink(m);
            case "blank":
                return setBlank(m.optBoolean("on", false));
            default:
                return false;
        }
    }

    private boolean pointer(JSONObject m) {
        if (!m.optBoolean("show", true)) {
            boolean was = pointer;
            pointer = false;
            return was;
        }
        double x = m.optDouble("x"), y = m.optDouble("y");
        if (Double.isNaN(x) || Double.isNaN(y)) return false;
        float nx = clamp(x), ny = clamp(y);
        boolean changed = !pointer || nx != px || ny != py;
        pointer = true;
        px = nx;
        py = ny;
        return changed;
    }

    private boolean ink(JSONObject m) {
        List<Stroke> list = strokes();
        switch (m.optString("do")) {
            case "stroke": {
                String id = m.optString("id");
                JSONArray points = m.optJSONArray("points");
                Stroke s = find(list, id);
                if (s == null) {
                    double w = m.optDouble("width", DEFAULT_WIDTH);
                    if (Double.isNaN(w) || w <= 0) w = DEFAULT_WIDTH;
                    s = new Stroke(id, color(m.optString("color"), DEFAULT_COLOR), (float) Math.min(0.1, Math.max(0.0005, w)));
                    list.add(s);
                    while (list.size() > MAX_STROKES) list.remove(0);
                }
                int before = s.size;
                if (points != null) s.append(points, MAX_MSG_POINTS);
                return s.size != before || before == 0;
            }
            case "undo":
                if (list.isEmpty()) return false;
                list.remove(list.size() - 1);
                return true;
            case "clear":
                if (list.isEmpty()) return false;
                list.clear();
                return true;
            default:
                return false;
        }
    }

    /** The stroke with this id on the page shown; the one being drawn is almost always the newest. */
    private static Stroke find(List<Stroke> list, String id) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) return list.get(i);
        return null;
    }

    /** Covers the screen with black, or uncovers it; true when it changed. */
    synchronized boolean setBlank(boolean on) {
        boolean changed = blank != on;
        blank = on;
        return changed;
    }

    /**
     * Shows the strokes of another page (a document page, a photo). A turn of the page also ends a blank screen;
     * true when it did, so the sender can be told.
     */
    synchronized boolean setPage(int key) {
        if (key == page) return false;   // the same page drawn again (another fit): not a turn
        page = key;
        boolean wasBlank = blank;
        blank = false;
        return wasBlank;
    }

    /** Something new is shown: no strokes, no pointer, nothing blank. */
    synchronized void reset() {
        pages.clear();
        page = 0;
        pointer = false;
        blank = false;
    }

    static float clamp(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }

    /** "#rrggbb" or "#rrggbbaa" (CSS order) as an Android ARGB int; {@code fallback} for anything else. */
    static int color(String s, int fallback) {
        if (s == null || !s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return fallback;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            if (s.length() == 7) return (int) (0xFF000000L | v);
            return (int) (((v & 0xFF) << 24) | (v >>> 8));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
