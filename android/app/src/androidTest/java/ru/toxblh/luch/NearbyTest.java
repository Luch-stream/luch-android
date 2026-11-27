// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * Being found where mDNS does not get through (PROTOCOL.md 1), on a device: the Bluetooth path degrades cleanly
 * where there is no radio or no permission, a probe hello leaves no trace, the pairing token works, and the card
 * the TV would say by sound decodes. Nothing is played and nothing goes on the air from a real radio: the
 * emulator's Bluetooth, if it has one, is simulated.
 */
@RunWith(AndroidJUnit4.class)
public class NearbyTest {
    private static final List<String> CLEAN = Arrays.asList(Nearby.ADVERTISING, Nearby.NO_BLUETOOTH, Nearby.OFF,
            Nearby.NO_PERMISSION, Nearby.UNSUPPORTED, Nearby.NO_ADDRESS);

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void bluetoothDegradesCleanly() {
        LuchService.start(context);
        String why = Nearby.unavailable(context);
        Log.i("LuchTest", "Bluetooth here: " + (why == null ? "can advertise" : why));
        assertTrue(why, why == null || CLEAN.contains(why));
        Nearby.start(context);
        long until = SystemClock.uptimeMillis() + 3000;
        while (Nearby.STOPPED.equals(Nearby.state) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        SystemClock.sleep(500); // a start that fails says so in a callback
        String state = Nearby.state;
        Log.i("LuchTest", "Nearby state: " + state);
        assertTrue(state, CLEAN.contains(state) || state.startsWith("failed_"));
        if (why != null) assertEquals(why, state);
        // The receiver itself is untouched by it.
        assertTrue(LuchService.listening > 0);
        // A sender looking, as if heard over Bluetooth: the status screen will say so.
        Nearby.looking("9a1b2c3d");
        assertTrue(System.currentTimeMillis() - Nearby.lookingAt < 1000);
    }

    /** With "Nearby devices" allowed (Android 12+): on the air, or a plain reason why not; never a crash. */
    @Test
    public void withThePermissionItAdvertisesOrSaysWhyNot() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            ui.grantRuntimePermission(context.getPackageName(), "android.permission.BLUETOOTH_ADVERTISE");
            ui.grantRuntimePermission(context.getPackageName(), "android.permission.BLUETOOTH_SCAN");
        }
        LuchService.start(context);
        Nearby.start(context);
        SystemClock.sleep(1500);
        String state = Nearby.state;
        Log.i("LuchTest", "Nearby state with the permission: " + state);
        assertNotEquals(Nearby.NO_PERMISSION, state);
        assertTrue(state, CLEAN.contains(state) || state.startsWith("failed_"));
        Nearby.stop();
        SystemClock.sleep(300);
        assertEquals(Nearby.STOPPED, Nearby.state);
    }

    @Test
    public void aProbeIsAnsweredAndLeavesNoTrace() throws Exception {
        LuchService.start(context);
        int before = Luch.connected().size();
        String token = Card.Pairing.current();
        TestSender s = TestSender.connect(10_000);
        s.send(new JSONObject().put("t", "hello").put("v", 0).put("id", "probe-test").put("name", "Linux Laptop")
                .put("probe", true).put("token", token));
        JSONObject w = s.next(5_000);
        assertEquals("welcome", w.getString("t"));
        assertTrue(w.getBoolean("probe"));
        assertEquals("ok", w.getString("token"));
        assertEquals(Luch.id(context), w.getString("id"));
        s.send(new JSONObject().put("t", "bye"));
        s.close();
        SystemClock.sleep(300);
        assertEquals(before, Luch.connected().size());
        assertEquals("a probe does not use the token up", token, Card.Pairing.current());

        // The real hello with the token: taken once, then a new one is shown.
        TestSender real = TestSender.connect(10_000);
        real.send(new JSONObject().put("t", "hello").put("v", 0).put("id", "pair-test").put("name", "Linux Laptop")
                .put("token", token));
        JSONObject w2 = real.next(5_000);
        assertEquals("ok", w2.getString("token"));
        assertFalse(w2.has("probe"));
        assertNotEquals(token, Card.Pairing.current());
        real.close();
    }

    @Test
    public void whatTheTvWouldSayDecodes() {
        LuchService.start(context);
        long until = SystemClock.uptimeMillis() + 10_000;
        while (LuchService.listening <= 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        short[] pcm = Nearby.spoken(context, Chirp.RATE);
        assertNotNull("the emulator has an address", pcm);
        float[] x = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) x[i] = pcm[i] / 32768f;
        List<byte[]> heard = Chirp.demodulate(x);
        assertEquals(1, heard.size());
        Card c = Card.decode(heard.get(0));
        assertEquals(Card.shortId(Luch.id(context)), c.id);
        assertEquals(Compat.ipv4(), c.host);
        assertEquals(LuchService.listening, c.port);
        assertEquals(Card.Pairing.current(), c.token);
        // The same card, without the token, is what goes on the air.
        byte[] air = Nearby.card(context);
        assertArrayEquals(Arrays.copyOf(heard.get(0), 13), air);
    }
}
