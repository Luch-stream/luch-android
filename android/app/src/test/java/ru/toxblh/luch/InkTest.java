// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The pointer and ink model of PROTOCOL.md 4h, as the sender's messages change it. */
public class InkTest {
    private static JSONObject msg(String json) throws Exception {
        return new JSONObject(json);
    }

    private static JSONObject stroke(Object id, double... points) throws Exception {
        JSONArray a = new JSONArray();
        for (double p : points) a.put(p);
        return new JSONObject().put("t", "ink").put("do", "stroke").put("id", id).put("points", a);
    }

    @Test
    public void pointerMovesToTheNewestPlaceAndHides() throws Exception {
        Ink ink = new Ink();
        assertFalse(ink.pointerShown());
        assertTrue(ink.apply(msg("{\"t\":\"pointer\",\"x\":0.25,\"y\":0.75}")));
        assertTrue(ink.pointerShown());
        assertEquals(0.25f, ink.pointerX(), 0);
        assertEquals(0.75f, ink.pointerY(), 0);
        assertFalse("the same place again changes nothing", ink.apply(msg("{\"t\":\"pointer\",\"x\":0.25,\"y\":0.75}")));
        assertTrue(ink.apply(msg("{\"t\":\"pointer\",\"x\":-3,\"y\":7}")));
        assertEquals("clamped", 0f, ink.pointerX(), 0);
        assertEquals(1f, ink.pointerY(), 0);
        assertFalse("no coordinates: ignored", ink.apply(msg("{\"t\":\"pointer\"}")));
        assertTrue(ink.apply(msg("{\"t\":\"pointer\",\"show\":false}")));
        assertFalse(ink.pointerShown());
        assertFalse(ink.apply(msg("{\"t\":\"pointer\",\"show\":false}")));
    }

    @Test
    public void colours() {
        assertEquals(0xFFE53935, Ink.color("#e53935", 0));
        assertEquals(0x80FFEB3B, Ink.color("#ffeb3b80", 0));
        assertEquals(7, Ink.color("red", 7));
        assertEquals(7, Ink.color("#12345", 7));
        assertEquals(7, Ink.color("#zzzzzz", 7));
        assertEquals(7, Ink.color(null, 7));
    }

    @Test
    public void aStrokeGrowsByItsIdAndKeepsItsFirstLook() throws Exception {
        Ink ink = new Ink();
        assertTrue(ink.apply(stroke(7, 0.1, 0.1, 0.2, 0.2).put("color", "#00ff00").put("width", 0.01)));
        assertTrue(ink.apply(stroke(7, 0.3, 0.3).put("color", "#ff0000").put("width", 0.5)));
        assertEquals(1, ink.strokes().size());
        Ink.Stroke s = ink.strokes().get(0);
        assertEquals(3, s.count());
        assertEquals(0xFF00FF00, s.color);
        assertEquals(0.01f, s.width, 1e-6);
        assertEquals(0.3f, s.points[4], 1e-6);
        // "7" as a string is the same stroke: ids are compared as text
        ink.apply(stroke("7", 0.4, 0.4));
        assertEquals(4, ink.strokes().get(0).count());
        ink.apply(msg("{\"t\":\"ink\",\"do\":\"stroke\",\"id\":8,\"points\":[0.5,0.5,2,\"x\",0.6]}"));
        Ink.Stroke t = ink.strokes().get(1);
        assertEquals("a broken pair is skipped, an odd one dropped", 1, t.count());
        assertEquals(Ink.DEFAULT_COLOR, t.color);
        assertEquals(Ink.DEFAULT_WIDTH, t.width, 1e-6);
    }

    @Test
    public void undoAndClear() throws Exception {
        Ink ink = new Ink();
        assertFalse(ink.apply(msg("{\"t\":\"ink\",\"do\":\"undo\"}")));
        ink.apply(stroke(1, 0.1, 0.1));
        ink.apply(stroke(2, 0.2, 0.2));
        ink.apply(stroke(3, 0.3, 0.3));
        assertTrue(ink.apply(msg("{\"t\":\"ink\",\"do\":\"undo\"}")));
        assertEquals(2, ink.strokes().size());
        assertEquals("2", ink.strokes().get(1).id);
        assertTrue(ink.apply(msg("{\"t\":\"ink\",\"do\":\"clear\"}")));
        assertEquals(0, ink.strokes().size());
        assertFalse(ink.apply(msg("{\"t\":\"ink\",\"do\":\"clear\"}")));
        assertFalse(ink.apply(msg("{\"t\":\"ink\",\"do\":\"paint\"}")));
    }

    @Test
    public void limitsKeepTheNewest() throws Exception {
        Ink ink = new Ink();
        double[] many = new double[2 * (Ink.MAX_MSG_POINTS + 100)];
        java.util.Arrays.fill(many, 0.5);
        ink.apply(stroke(1, many));
        assertEquals(Ink.MAX_MSG_POINTS, ink.strokes().get(0).count());
        for (int i = 0; i < 20; i++) ink.apply(stroke(1, many));
        assertEquals(Ink.MAX_STROKE_POINTS, ink.strokes().get(0).count());
        for (int i = 2; i <= Ink.MAX_STROKES + 5; i++) ink.apply(stroke(i, 0.1, 0.1));
        assertEquals(Ink.MAX_STROKES, ink.strokes().size());
        assertEquals("the oldest went first", "6", ink.strokes().get(0).id);
    }

    @Test
    public void everyPageHasItsOwnStrokesAndATurnEndsBlank() throws Exception {
        Ink ink = new Ink();
        ink.apply(stroke(1, 0.1, 0.1));
        assertFalse(ink.setPage(3));
        assertEquals(0, ink.strokes().size());
        ink.apply(stroke(2, 0.2, 0.2));
        ink.apply(msg("{\"t\":\"ink\",\"do\":\"clear\"}"));
        ink.setPage(0);
        assertEquals("clear takes only the page shown", 1, ink.strokes().size());
        assertTrue(ink.apply(msg("{\"t\":\"blank\",\"on\":true}")));
        assertFalse(ink.apply(msg("{\"t\":\"blank\",\"on\":true}")));
        assertTrue(ink.blank());
        ink.apply(msg("{\"t\":\"pointer\",\"x\":0.5,\"y\":0.5}"));
        assertFalse("blank hides the pointer", ink.pointerShown());
        assertFalse("the same page is no turn", ink.setPage(0));
        assertTrue(ink.blank());
        assertTrue("a turn ends blank and says so", ink.setPage(1));
        assertFalse(ink.blank());
        assertTrue(ink.pointerShown());
        ink.reset();
        assertEquals(0, ink.page());
        assertEquals(0, ink.strokes().size());
        assertFalse(ink.pointerShown());
    }
}
