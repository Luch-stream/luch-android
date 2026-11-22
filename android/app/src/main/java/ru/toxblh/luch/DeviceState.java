// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Battery, charging, thermal and power-save state for senders.
 *
 * Event-driven only: values are cached from system callbacks (a sticky battery broadcast, screen
 * on/off, power-save and thermal listeners) and are sent <em>when they change</em>. Nothing here
 * polls the fuel gauge, so reporting costs no battery of its own.
 */
final class DeviceState {
    private static final Object LOCK = new Object();

    /** Report a battery change only when the charge counter moved by this much. */
    private static final int TOLERANCE_MAH = 100;
    /** Fallback when the device exposes no charge counter: percentage points. */
    private static final int TOLERANCE_PERCENT = 3;

    private static int battery = -1;
    private static int chargeMah = -1;
    private static boolean charging;
    private static boolean powerSave;
    private static boolean interactive = true;
    private static String thermal = "none";

    // Last values handed to a sender, so `changed()` can report deltas only.
    private static int sentBattery = Integer.MIN_VALUE;
    private static int sentChargeMah = Integer.MIN_VALUE;
    private static boolean sentCharging;
    private static boolean sentPowerSave;
    private static boolean sentInteractive = true;
    private static String sentThermal = "";

    private static BroadcastReceiver receiver;
    private static BatteryManager batteryManager;
    /** PowerManager.OnThermalStatusChangedListener on API 29+, kept as Object so older devices never resolve it. */
    private static Object thermalListener;

    private DeviceState() {
    }

    static void attach(Context context) {
        if (receiver != null) return;
        Context app = context.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    applyBattery(intent);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    synchronized (LOCK) { interactive = true; }
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    synchronized (LOCK) { interactive = false; }
                } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                    PowerManager pm = app.getSystemService(PowerManager.class);
                    if (pm != null) synchronized (LOCK) { powerSave = pm.isPowerSaveMode(); }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        // A sticky broadcast hands back the current battery state immediately: no polling needed.
        batteryManager = app.getSystemService(BatteryManager.class);
        Intent sticky = app.registerReceiver(receiver, filter);
        if (sticky != null) applyBattery(sticky);

        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null) {
            synchronized (LOCK) {
                powerSave = pm.isPowerSaveMode();
                interactive = pm.isInteractive();
            }
            if (Build.VERSION.SDK_INT >= 29) attachThermal(pm);
        }
    }

    static void detach(Context context) {
        Context app = context.getApplicationContext();
        if (receiver != null) {
            try {
                app.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            receiver = null;
        }
        if (thermalListener != null && Build.VERSION.SDK_INT >= 29) detachThermal(app);
    }

    @android.annotation.TargetApi(29)
    private static void attachThermal(PowerManager pm) {
        synchronized (LOCK) { thermal = thermalName(pm.getCurrentThermalStatus()); }
        PowerManager.OnThermalStatusChangedListener listener = status -> {
            synchronized (LOCK) { thermal = thermalName(status); }
        };
        thermalListener = listener;
        pm.addThermalStatusListener(listener);
    }

    @android.annotation.TargetApi(29)
    private static void detachThermal(Context app) {
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null && thermalListener instanceof PowerManager.OnThermalStatusChangedListener) {
            try {
                pm.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
            } catch (RuntimeException ignored) {
            }
        }
        thermalListener = null;
    }

    private static void applyBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean plugged = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        // Read the counter once per broadcast, not on a timer: it makes the 100 mAh tolerance possible.
        int microAmpHours = -1;
        if (batteryManager != null) {
            try {
                microAmpHours = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            } catch (RuntimeException ignored) {
            }
        }
        synchronized (LOCK) {
            battery = scale > 0 && level >= 0 ? Math.round(level * 100f / scale) : -1;
            chargeMah = microAmpHours > 0 ? microAmpHours / 1000 : -1;
            charging = plugged;
        }
    }

    /**
     * How much thermal headroom is left before throttling: 1 = cool, below ~0.3 means the device is
     * about to slow down. A cheap binder getter, sampled with the stats rather than on its own timer.
     */
    static double headroom(Context context) {
        if (Build.VERSION.SDK_INT < 30) return -1;
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) return -1;
        try {
            float value = pm.getThermalHeadroom(10);
            return Float.isNaN(value) ? -1 : value;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** One-time reading for `welcome.hw.device`. */
    static JSONObject snapshot() {
        return snapshot(null);
    }

    static JSONObject snapshot(Context context) {
        JSONObject out = new JSONObject();
        Double headroomValue = context == null ? null : DeviceState.headroom(context);
        if (headroomValue != null && headroomValue < 0) headroomValue = null;
        synchronized (LOCK) {
            try {
                if (battery >= 0) out.put("battery", battery);
                if (chargeMah >= 0) out.put("charge_mah", chargeMah);
                out.put("charging", charging);
                out.put("thermal", thermal);
                if (headroomValue != null) out.put("headroom", headroomValue);
                out.put("power_save", powerSave);
                out.put("interactive", interactive);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** Fields that changed since the previous call; empty when nothing did. Call once per stats tick. */
    static JSONObject changed() {
        JSONObject out = new JSONObject();
        synchronized (LOCK) {
            try {
                boolean batteryMoved = chargeMah >= 0 && sentChargeMah != Integer.MIN_VALUE
                        && Math.abs(chargeMah - sentChargeMah) >= TOLERANCE_MAH;
                boolean percentMoved = chargeMah < 0 && sentBattery != Integer.MIN_VALUE
                        && battery >= 0 && Math.abs(battery - sentBattery) >= TOLERANCE_PERCENT;
                boolean first = sentChargeMah == Integer.MIN_VALUE && sentBattery == Integer.MIN_VALUE;
                if (battery >= 0 && (first || batteryMoved || percentMoved)) {
                    out.put("battery", battery);
                    if (chargeMah >= 0) out.put("charge_mah", chargeMah);
                    sentBattery = battery;
                    sentChargeMah = chargeMah;
                }
                // Plugging or unplugging the cable is worth a message on its own, whatever the level.
                if (charging != sentCharging) {
                    out.put("charging", charging);
                    sentCharging = charging;
                }
                if (!thermal.equals(sentThermal)) {
                    out.put("thermal", thermal);
                    sentThermal = thermal;
                }
                if (powerSave != sentPowerSave) {
                    out.put("power_save", powerSave);
                    sentPowerSave = powerSave;
                }
                if (interactive != sentInteractive) {
                    out.put("interactive", interactive);
                    sentInteractive = interactive;
                }
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static String thermalName(int status) {
        if (Build.VERSION.SDK_INT < 29) return "none";
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }
}
