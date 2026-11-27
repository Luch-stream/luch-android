package ru.toxblh.luch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Lets a sender put this TV to sleep ({@code {"t":"sleep"}}, PROTOCOL.md section 3).
 *
 * <p>An ordinary app cannot: {@code PowerManager.goToSleep} needs the system's DEVICE_POWER. An accessibility
 * service may ask for the "lock screen" global action (Android 9+), and the system carries it out as
 * {@code goToSleep(GO_TO_SLEEP_REASON_ACCESSIBILITY)}: on a TV that is the same standby as the remote's power key.
 * The service listens to no events and reads no windows; it only exists so the action can be asked for. The person
 * turns it on once in the accessibility settings ({@link #openSettings}).
 */
public class SleepService extends AccessibilityService {
    private static volatile SleepService running;

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** Whether this Android has the action at all (9+). */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** "on" (ready), "off" (the person has not turned the service on) or "no" (before Android 9). */
    static String state(Context c) {
        if (!supported()) return "no";
        return enabled(c) ? "on" : "off";
    }

    /** Turned on in the accessibility settings (and so bound by the system, or about to be). */
    static boolean enabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = component(c);
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(list);
        for (String s : split) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    /** Puts the device to sleep; false when the service is not running or Android is older than 9. */
    static boolean sleep() {
        SleepService s = running;
        if (s == null || Build.VERSION.SDK_INT < 28) return false;
        boolean ok = s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        Log.i(LuchService.TAG, "sleep asked by a sender: " + ok);
        return ok;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, SleepService.class);
    }

    // ---- taking the person to the switch ------------------------------------------------------------------------

    /** When {@link #openSettings} last tried a page that may close at once (see there). */
    private static long triedDirectAt;

    /**
     * Opens the page where this service is turned on, as close to its own switch as this Android allows:
     * <ul>
     * <li>Phones and tablets, Android 11+: {@code ACCESSIBILITY_DETAILS_SETTINGS} with our component, the service's
     * own page. TV settings do not handle it.</li>
     * <li>Google TV (TvSettings): {@code ACCESSIBILITY_SHORTCUT_SERVICES_TV_OEM_LINK} with our
     * {@code AccessibilityServiceInfo} in {@code accessibilityServiceInfo}, the service's own page. It is exported for
     * OEM links; in the older ("classic") TvSettings flavour it exists but closes at once, which
     * {@link #returnedTooSoon} catches.</li>
     * <li>Otherwise the accessibility list, where the person picks Luch.</li>
     * </ul>
     */
    static void openSettings(Activity a) {
        if (Build.VERSION.SDK_INT >= 30 && !Ui.isTv(a)) {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component(a).flattenToString());
            if (start(a, i)) return;
        }
        AccessibilityServiceInfo info = info(a);
        if (info != null) {
            Intent i = new Intent("android.settings.ACCESSIBILITY_SHORTCUT_SERVICES_TV_OEM_LINK")
                    .putExtra("accessibilityServiceInfo", info);
            if (start(a, i)) {
                triedDirectAt = SystemClock.elapsedRealtime();
                return;
            }
        }
        openList(a);
    }

    /**
     * Call from onResume: true (and the accessibility list opened instead) when the page {@link #openSettings} chose
     * closed by itself straight away rather than after the person used it.
     */
    static boolean returnedTooSoon(Activity a) {
        long at = triedDirectAt;
        triedDirectAt = 0;
        if (at == 0 || SystemClock.elapsedRealtime() - at > 1500 || enabled(a)) return false;
        openList(a);
        return true;
    }

    private static void openList(Activity a) {
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        // Phone Settings scroll to and highlight the entry with this key; others ignore it.
        String key = component(a).flattenToString();
        android.os.Bundle args = new android.os.Bundle();
        args.putString(":settings:fragment_args_key", key);
        list.putExtra(":settings:fragment_args_key", key).putExtra(":settings:show_fragment_args", args);
        start(a, list);
    }

    private static AccessibilityServiceInfo info(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return null;
        List<AccessibilityServiceInfo> all = am.getInstalledAccessibilityServiceList();
        if (all == null) return null;
        ComponentName me = component(c);
        for (AccessibilityServiceInfo i : all) {
            if (i.getResolveInfo() == null || i.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo si = i.getResolveInfo().serviceInfo;
            if (me.equals(new ComponentName(si.packageName, si.name))) return i;
        }
        return null;
    }

    private static boolean start(Activity a, Intent i) {
        if (i.resolveActivity(a.getPackageManager()) == null) return false;
        try {
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) { // not exported to us after all, or the page refused the extras
            Log.w(LuchService.TAG, "cannot open " + i.getAction() + ": " + e);
            return false;
        }
    }
}
