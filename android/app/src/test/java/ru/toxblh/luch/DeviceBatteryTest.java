// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.BatteryManager;

import org.junit.Test;

/** What the battery broadcast becomes for senders: a TV on mains power is not "on battery". */
public class DeviceBatteryTest {
    private static final int CHARGING = BatteryManager.BATTERY_STATUS_CHARGING,
            DISCHARGING = BatteryManager.BATTERY_STATUS_DISCHARGING,
            NOT_CHARGING = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            FULL = BatteryManager.BATTERY_STATUS_FULL, UNKNOWN = BatteryManager.BATTERY_STATUS_UNKNOWN;
    private static final int UNPLUGGED = 0, AC = BatteryManager.BATTERY_PLUGGED_AC,
            USB = BatteryManager.BATTERY_PLUGGED_USB;

    @Test
    public void aTvWithoutABatteryHasNoneAndIsOnPower() {
        // As mains-powered TVs report it: a level, "not charging" or "unknown", nothing plugged, no battery present.
        assertEquals(-1, DeviceState.batteryPercent(false, 100, 100));
        assertEquals(-1, DeviceState.batteryPercent(false, 50, 100));
        assertTrue(DeviceState.onPower(false, NOT_CHARGING, UNPLUGGED));
        assertTrue(DeviceState.onPower(false, UNKNOWN, UNPLUGGED));
    }

    @Test
    public void aProjectorOnItsOwnBatteryIsOnBattery() {
        // A MoGo away from the wall: present, discharging, unplugged.
        assertEquals(64, DeviceState.batteryPercent(true, 64, 100));
        assertFalse(DeviceState.onPower(true, DISCHARGING, UNPLUGGED));
        assertFalse(DeviceState.onPower(true, NOT_CHARGING, UNPLUGGED));
    }

    @Test
    public void pluggedInIsOnPowerWhateverTheStatus() {
        assertTrue(DeviceState.onPower(true, CHARGING, AC));
        assertTrue(DeviceState.onPower(true, FULL, AC));
        // Some devices stop charging near full and say so while the cable is in.
        assertTrue(DeviceState.onPower(true, NOT_CHARGING, AC));
        assertTrue(DeviceState.onPower(true, UNKNOWN, USB));
        // An older status-only reading still counts.
        assertTrue(DeviceState.onPower(true, CHARGING, UNPLUGGED));
    }

    @Test
    public void theLevelIsAPercentOfItsScale() {
        assertEquals(50, DeviceState.batteryPercent(true, 1, 2));
        assertEquals(-1, DeviceState.batteryPercent(true, -1, 100));
        assertEquals(-1, DeviceState.batteryPercent(true, 50, 0));
    }
}
