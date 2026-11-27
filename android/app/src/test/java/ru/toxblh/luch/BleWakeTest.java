// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

public class BleWakeTest {
    @After
    public void free() {
        BleWake.release();
    }

    /** The request as luch-linux's macOS WakeRelay.request builds it. */
    private static JSONObject request(Object company, String data, Object ms) throws Exception {
        JSONObject m = new JSONObject().put("t", "ble_wake").put("company", company).put("data", data);
        return ms == null ? m : m.put("ms", ms);
    }

    private static BleWake.Refused refused(JSONObject msg) {
        try {
            BleWake.parse(msg);
        } catch (BleWake.Refused r) {
            return r;
        }
        fail("taken: " + msg);
        return null;
    }

    private static String refusal(JSONObject msg) {
        return refused(msg).code;
    }

    @Test
    public void takesTheRemotesDataAsSent() throws Exception {
        BleWake w = BleWake.parse(request(70, "7e80890102030405", 2000));
        assertArrayEquals(new byte[] {0x7e, (byte) 0x80, (byte) 0x89, 1, 2, 3, 4, 5}, w.data);
        assertEquals(2000, w.ms);
        assertArrayEquals(new byte[] {(byte) 0xab, (byte) 0xcd}, BleWake.parse(request(70, "ABcd", 2000)).data);
    }

    @Test
    public void holdIsCapped() throws Exception {
        assertEquals(BleWake.HOLD_MS, BleWake.parse(request(70, "7e80", null)).ms);
        assertEquals(BleWake.MAX_MS, BleWake.parse(request(70, "7e80", 60_000)).ms);
        assertEquals(BleWake.MIN_MS, BleWake.parse(request(70, "7e80", 0)).ms);
        assertEquals(BleWake.MIN_MS, BleWake.parse(request(70, "7e80", -5)).ms);
        assertEquals(BleWake.HOLD_MS, BleWake.parse(request(70, "7e80", "long")).ms);
    }

    @Test
    public void onlyMediatekData() throws Exception {
        assertEquals("bad_request", refusal(request(0x004c, "7e80", 2000)));   // not someone else's company
        assertEquals("bad_request", refusal(new JSONObject().put("t", "ble_wake").put("data", "7e80")));
    }

    @Test
    public void dataMustBeHexAndFitOneAdvertisement() throws Exception {
        assertEquals("bad_request", refusal(request(70, "", 2000)));
        assertEquals("bad_request", refusal(request(70, "7e8", 2000)));
        assertEquals("bad_request", refusal(request(70, "7e:80", 2000)));
        assertEquals("bad_request", refusal(request(70, "zz80", 2000)));
        assertEquals("bad_request", refusal(request(70, "7e", 2000)));
        assertEquals("bad_request", refusal(request(70, "00".repeat(BleWake.MAX_BYTES + 1), 2000)));
        assertEquals(BleWake.MAX_BYTES, BleWake.parse(request(70, "00".repeat(BleWake.MAX_BYTES), 2000)).data.length);
        assertEquals("bad_request", refusal(new JSONObject().put("t", "ble_wake").put("company", 70)));
    }

    @Test
    public void modesOnlyWhenThisDeviceCanAdvertise() {
        assertTrue(BleWake.withMode(new JSONArray().put("mirror"), null).toString().contains("\"ble_wake\""));
        for (String why : new String[] {Nearby.NO_PERMISSION, Nearby.NO_BLUETOOTH, Nearby.OFF, Nearby.UNSUPPORTED}) {
            JSONArray modes = BleWake.withMode(new JSONArray().put("mirror"), why);
            assertEquals(why, "[\"mirror\"]", modes.toString());
        }
    }

    @Test
    public void whyNotAsTheSenderReadsIt() {
        assertNull(BleWake.code(null));
        assertEquals("not_allowed", BleWake.code(Nearby.NO_PERMISSION));
        assertEquals("no_ble", BleWake.code(Nearby.NO_BLUETOOTH));
        assertEquals("no_ble", BleWake.code(Nearby.OFF));
        assertEquals("no_ble", BleWake.code(Nearby.UNSUPPORTED));
    }

    @Test
    public void answersOnTheWire() throws Exception {
        JSONObject ok = BleWake.ok();
        assertEquals("ble_wake", ok.getString("t"));
        assertTrue(ok.getBoolean("ok"));
        JSONObject err = BleWake.error("not_allowed", BleWake.detail("not_allowed"));
        assertEquals("error", err.getString("t"));
        assertEquals("ble_wake", err.getString("for"));
        assertEquals("not_allowed", err.getString("code"));
        assertFalse(err.getString("detail").isEmpty());
        JSONObject bad = refused(request(70, "7e", 2000)).answer();
        assertEquals("bad_request", bad.getString("code"));
        assertEquals("ble_wake", bad.getString("for"));
    }

    @Test
    public void oneAtATime() {
        assertTrue(BleWake.take());
        assertFalse(BleWake.take());
        BleWake.release();
        assertTrue(BleWake.take());
    }
}
