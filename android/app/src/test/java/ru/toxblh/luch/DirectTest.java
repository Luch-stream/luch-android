// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** "Direct" (PROTOCOL.md 4m): its answers as senders read them, the group's name and passphrase, its lifetime. */
public class DirectTest {
    private long now = 1_700_000_000_000L;

    @Test
    public void theAnswerAsSendersReadIt() throws Exception {
        Direct.Group g = new Direct.Group("DIRECT-k3-Luch-7QX2", "abcdEFGH12345678", 5745, "192.168.49.1");
        // Through the wire and back, as a sender's JSON parser sees it.
        JSONObject a = new JSONObject(Direct.answer(g, 47800, 47802).toString());
        assertEquals("direct", a.getString("t"));
        assertEquals("DIRECT-k3-Luch-7QX2", a.getString("ssid"));
        assertEquals("abcdEFGH12345678", a.getString("pass"));
        assertEquals(5745, a.getInt("freq"));
        assertEquals("192.168.49.1", a.getString("addr"));
        assertEquals(47800, a.getInt("port"));
        assertEquals(47802, a.getInt("tls_port"));
        assertEquals(7, a.length());
        // The system did not say the band: 0, still a number.
        assertEquals(0, new JSONObject(Direct.answer(new Direct.Group("DIRECT-ab", "12345678", 0, "192.168.49.1"),
                47800, 47802).toString()).getInt("freq"));
    }

    @Test
    public void errorsAsSendersReadThem() throws Exception {
        for (String code : new String[] {"denied", "no_p2p", "not_allowed", "busy", "timeout"}) {
            JSONObject e = new JSONObject(Direct.error(code, "why").toString());
            assertEquals("error", e.getString("t"));
            assertEquals("direct", e.getString("for"));
            assertEquals(code, e.getString("code"));
            assertEquals("why", e.getString("detail"));
        }
    }

    @Test
    public void namesAndPassphrasesAreFreshAndValid() {
        Random r = new Random(1);
        Set<String> names = new HashSet<>(), passes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String ssid = Direct.ssid(r), pass = Direct.passphrase(r);
            assertTrue(ssid, ssid.matches("DIRECT-[A-Za-z0-9]{2}-Luch-[A-Za-z0-9]{4}"));
            assertTrue(ssid, Direct.validSsid(ssid));
            assertTrue(pass, pass.matches("[A-Za-z0-9]{16}"));
            assertTrue(pass, Direct.validPass(pass));
            names.add(ssid);
            passes.add(pass);
        }
        assertEquals(500, passes.size());
        assertTrue(names.size() > 490);
        assertNotEquals(Direct.passphrase(), Direct.passphrase());
    }

    @Test
    public void whatAndroidTakes() {
        assertTrue(Direct.validSsid("DIRECT-xy"));
        assertFalse(Direct.validSsid("DIRECT-x"));
        assertFalse(Direct.validSsid("Luch-DIRECT-xy"));
        assertFalse(Direct.validSsid("DIRECT-xy-" + "a".repeat(23)));
        assertTrue(Direct.validSsid("DIRECT-xy-" + "a".repeat(22)));
        assertFalse(Direct.validSsid(null));
        assertTrue(Direct.validPass("12345678"));
        assertTrue(Direct.validPass("a".repeat(63)));
        assertFalse(Direct.validPass("1234567"));
        assertFalse(Direct.validPass("a".repeat(64)));
        assertFalse(Direct.validPass("1234567\n"));
        assertFalse(Direct.validPass("пароль-пароль"));
        assertFalse(Direct.validPass(null));
    }

    @Test
    public void modesOnlyWhenThisDeviceCanMakeAGroup() {
        assertNull(Direct.why(29, true, true, true));
        assertNull(Direct.why(34, true, true, true));
        assertTrue(Direct.withMode(new JSONArray().put("mirror"), null).toString().contains("\"direct\""));
        String[] whys = {
                Direct.why(28, true, true, true),
                Direct.why(34, false, true, true),
                Direct.why(34, true, false, true),
                Direct.why(30, true, false, true),
                Direct.why(34, true, true, false),
        };
        for (String why : whys) {
            assertTrue(why != null && !why.isEmpty());
            assertEquals(why, "[\"mirror\"]", Direct.withMode(new JSONArray().put("mirror"), why).toString());
        }
        // The detail says which permission: the one that version asks for.
        assertTrue(Direct.why(33, true, false, true).contains("Nearby Wi-Fi devices"));
        assertTrue(Direct.why(32, true, false, true).contains("Location"));
    }

    @Test
    public void permissionsByVersion() {
        assertArrayEquals(new String[0], Direct.permissions(28));
        assertArrayEquals(new String[] {"android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"}, Direct.permissions(29));
        assertArrayEquals(new String[] {"android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION", "android.permission.BLUETOOTH_CONNECT"},
                Direct.permissions(32));
        assertArrayEquals(new String[] {"android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.BLUETOOTH_CONNECT"}, Direct.permissions(33));
    }

    @Test
    public void aGroupNobodyJoinsGoesAfterTwoMinutes() {
        Direct.Lifetime life = new Direct.Lifetime(() -> now);
        now += Direct.LINGER_MS - 1;
        assertFalse(life.over());
        now += 1;
        assertTrue(life.over());
    }

    @Test
    public void itStaysWhileAConnectionIsOverItAndTwoMinutesAfterTheLast() {
        Direct.Lifetime life = new Direct.Lifetime(() -> now);
        now += 30_000;
        life.arrived();
        life.arrived();
        now += 60 * 60_000L;   // an hour of showing
        assertFalse(life.over());
        life.left();
        now += 10 * 60_000L;
        assertFalse("one is still open", life.over());
        life.left();
        now += Direct.LINGER_MS - 1;
        assertFalse(life.over());
        now += 1;
        assertTrue(life.over());
        life.left();   // a stray end changes nothing
        assertTrue(life.over());
    }

    @Test
    public void aSecondSenderGetsItsTimeToJoin() {
        Direct.Lifetime life = new Direct.Lifetime(() -> now);
        now += 100_000;
        life.handedOut();
        now += Direct.LINGER_MS - 1;
        assertFalse(life.over());
        now += 1;
        assertTrue(life.over());
        assertEquals(0, life.leftMs());
    }

    @Test
    public void oneQuestionAtATime() {
        Direct.Asks asks = new Direct.Asks(() -> now);
        assertNull(asks.take("fp1", "ble:A"));
        assertEquals("busy", asks.take("fp2", "ble:B"));
        asks.answered("fp1", "ble:A", false);
        assertNull(asks.take("fp2", "ble:B"));
        asks.answered("fp2", "ble:B", false);
        assertNull("allowed or closed: asked again", asks.take("fp1", "ble:A"));
        asks.answered("fp1", "ble:A", false);
    }

    @Test
    public void aRefusedSenderIsNotAskedAgainForAWhile() {
        Direct.Asks asks = new Direct.Asks(() -> now);
        assertNull(asks.take("fp1", "ble:A"));
        asks.answered("fp1", "ble:A", true);
        assertEquals("denied", asks.take("fp1", "ble:A"));
        assertEquals("a new key, the same device", "denied", asks.take("fp9", "ble:A"));
        assertEquals("the same key, another device", "denied", asks.take("fp1", "ble:Z"));
        assertNull("someone else", asks.take("fp2", "ble:B"));
        asks.answered("fp2", "ble:B", false);
        now += Direct.DENIED_MS;
        assertNull(asks.take("fp1", "ble:A"));
    }
}
