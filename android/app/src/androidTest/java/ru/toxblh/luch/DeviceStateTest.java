// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeviceStateTest {
    @Test
    public void thermalEnvelopeRisesTowardThrottling() throws Exception {
        // No live service callbacks in this test: verify the direction and unknown-value handling.
        java.lang.reflect.Field field = DeviceState.class.getDeclaredField("thermal");
        field.setAccessible(true);
        String old = (String) field.get(null);
        try {
            field.set(null, "none");
            assertFalse(DeviceState.isHot(-1));
            assertFalse(DeviceState.isHot(0));
            assertFalse(DeviceState.isHot(0.69));
            assertTrue(DeviceState.isHot(0.7));
            assertTrue(DeviceState.isHot(1.2));
            field.set(null, "severe");
            assertTrue(DeviceState.isHot(-1)); // thermal status still warns without headroom support
        } finally {
            field.set(null, old);
        }
    }

    @Test
    public void deviceAndDisplayCapabilitiesWorkOnOlderAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> DeviceState.attach(context));
        JSONObject hw = Caps.collect(context);
        assertNotNull(hw.optJSONObject("device"));
        JSONObject display = hw.getJSONObject("display");
        assertTrue(display.has("current"));
        assertTrue(display.getInt("refresh") > 0);
        assertTrue(display.has("type"));
    }
}
