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
                    PowerManager pm = Compat.service(app, PowerManager.class);
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
        batteryManager = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        Intent sticky = app.registerReceiver(receiver, filter);
        if (sticky != null) applyBattery(sticky);

        PowerManager pm = Compat.service(app, PowerManager.class);
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
        PowerManager pm = Compat.service(app, PowerManager.class);
        if (pm != null && thermalListener instanceof PowerManager.OnThermalStatusChangedListener) {
            try {
                pm.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
            } catch (RuntimeException ignored) {
            }
        }
        thermalListener = null;
    }

    /**
     * The battery's percent, or -1 when there is none. A TV on mains power often reports a level too, with no battery
     * present ({@code EXTRA_PRESENT} false): that is no battery at all, not one at that level.
     */
    static int batteryPercent(boolean present, int level, int scale) {
        return present && scale > 0 && level >= 0 ? Math.round(level * 100f / scale) : -1;
    }

    /**
     * On power: plugged in, whatever the status says (a full battery, or a TV's "not charging" and "unknown"), or no
     * battery at all. Sent as {@code charging}; a sender counts "on battery" only with a battery and not this.
     */
    static boolean onPower(boolean present, int status, int plugged) {
        return !present || plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static void applyBattery(Intent intent) {
        boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean plugged = onPower(present, status, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
        // Read the counter once per broadcast, not on a timer: it makes the 100 mAh tolerance possible.
        int microAmpHours = -1;
        if (batteryManager != null) {
            try {
                microAmpHours = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            } catch (RuntimeException ignored) {
            }
        }
        synchronized (LOCK) {
            battery = batteryPercent(present, level, scale);
            chargeMah = present && microAmpHours > 0 ? microAmpHours / 1000 : -1;
            charging = plugged;
        }
    }

    /**
     * Fraction of the thermal envelope in use: 1 means severe throttling. Cache for ten seconds
     * so welcome and stats do not query devices more often than the API supports.
     */
    private static long headroomAt = -10_000;
    private static double cachedHeadroom = -1;

    static synchronized double headroom(Context context) {
        if (Build.VERSION.SDK_INT < 30) return -1;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - headroomAt < 10_000) return cachedHeadroom;
        headroomAt = now;
        PowerManager pm = Compat.service(context, PowerManager.class);
        if (pm == null) return -1;
        try {
            float value = pm.getThermalHeadroom(10);
            return cachedHeadroom = Float.isNaN(value) ? -1 : value;
        } catch (RuntimeException e) {
            return cachedHeadroom = -1;
        }
    }

    static boolean isHot(double headroom) {
        synchronized (LOCK) {
            return headroom >= 0.7 || "moderate".equals(thermal) || "severe".equals(thermal)
                    || "critical".equals(thermal) || "emergency".equals(thermal) || "shutdown".equals(thermal);
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
