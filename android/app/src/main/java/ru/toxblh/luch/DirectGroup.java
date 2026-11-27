// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The Wi-Fi Direct group of "direct" (PROTOCOL.md 4m): made when a sender was allowed, with a fresh name and
 * passphrase, 5 GHz when the chip can; one at a time, handed to every sender allowed while it is up, and removed
 * {@link Direct#LINGER_MS} after the last connection over it ends. Everything here runs on the main thread.
 */
final class DirectGroup {
    /** The answer to {@link #get}: the group, or an error code and its detail. */
    interface Done {
        void done(Direct.Group group, String code, String detail);
    }

    /** How long the system has to bring a group up, how often it is asked, how often the group's time is looked at. */
    private static final long FORM_MS = 20_000, POLL_MS = 500, CHECK_MS = 5_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable POLL = DirectGroup::poll, CHECK = DirectGroup::check;

    @SuppressLint("StaticFieldLeak") // the application's context
    private static Context app;
    private static WifiP2pManager p2p;
    private static WifiP2pManager.Channel channel;
    private static Direct.Group up;
    /** The group owner's address, where connections over the group arrive; read from the connections' threads. */
    private static volatile InetAddress owner;
    private static volatile Direct.Lifetime life;
    private static final List<Done> waiting = new ArrayList<>();
    private static long formingSince;

    private DirectGroup() {
    }

    /** Why this device cannot make a group, as a no_p2p detail; null when it can. */
    static String unavailable(Context c) {
        int sdk = Build.VERSION.SDK_INT;
        boolean feature = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        String perm = sdk >= Direct.NEARBY_WIFI_SDK ? "android.permission.NEARBY_WIFI_DEVICES"
                : "android.permission.ACCESS_FINE_LOCATION";
        boolean permitted = sdk < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean on = wifi != null && wifi.isWifiEnabled();
        return Direct.why(sdk, feature, permitted, on);
    }

    /** Whether asking for direct's permissions makes sense here: Android 10+ with Wi-Fi Direct and Bluetooth LE. */
    static boolean worthAsking(Context c) {
        PackageManager pm = c.getPackageManager();
        return Build.VERSION.SDK_INT >= Direct.MIN_SDK && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
                && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /** The group for one more allowed sender: the one up, or a new one. {@code done} is called on the main thread. */
    static void get(Context context, Done done) {
        MAIN.post(() -> {
            app = context.getApplicationContext();
            if (up != null) {
                life.handedOut();
                done.done(up, null, null);
                return;
            }
            waiting.add(done);
            if (waiting.size() == 1) form();
        });
    }

    /** The lifetime a connection arriving at {@code local} counts in (call {@code left()} on it when it ends), or null. */
    static Direct.Lifetime arrived(InetAddress local) {
        Direct.Lifetime l = life;
        InetAddress o = owner;
        if (l == null || o == null || !o.equals(local)) return null;
        l.arrived();
        return l;
    }

    /** The receiver stops: no group stays behind it. */
    static void stop() {
        MAIN.post(() -> {
            if (up != null || channel != null) remove("the receiver stopped");
        });
    }

    @TargetApi(29)
    @SuppressLint("MissingPermission") // unavailable() checked NEARBY_WIFI_DEVICES or the location permission
    private static void form() {
        String why = unavailable(app);
        p2p = why == null ? (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE) : null;
        if (p2p == null) {
            fail("no_p2p", why == null ? "this device has no Wi-Fi Direct" : why);
            return;
        }
        channel = p2p.initialize(app, Looper.getMainLooper(), () -> {
            if (up != null || !waiting.isEmpty()) remove("Wi-Fi Direct went away");
        });
        formingSince = System.currentTimeMillis();
        create(0);
    }

    /**
     * Tries a group of our own name and passphrase on 5 GHz, then on any band, then whatever the system makes
     * (its own name and passphrase, read back and handed out as they are).
     */
    @TargetApi(29)
    @SuppressLint("MissingPermission")
    private static void create(int attempt) {
        WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(LuchService.TAG, "Wi-Fi Direct group asked for (" + (attempt == 0 ? "5 GHz"
                        : attempt == 1 ? "any band" : "the system's own") + ")");
                MAIN.post(POLL);
            }

            @Override
            public void onFailure(int reason) {
                Log.w(LuchService.TAG, "Wi-Fi Direct group failed: " + reason + ", attempt " + attempt);
                if (reason == WifiP2pManager.BUSY) {
                    fail("busy", "Wi-Fi Direct is busy on this device (another group or connection)");
                } else if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    fail("no_p2p", "this device has no Wi-Fi Direct");
                } else if (attempt < 2) {
                    create(attempt + 1);
                } else {
                    fail("no_p2p", "Wi-Fi Direct could not make a group (error " + reason + ")");
                }
            }
        };
        try {
            if (attempt < 2) {
                WifiP2pConfig config = new WifiP2pConfig.Builder()
                        .setNetworkName(Direct.ssid())
                        .setPassphrase(Direct.passphrase())
                        .setGroupOperatingBand(attempt == 0 ? WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                                : WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .build();
                p2p.createGroup(channel, config, listener);
            } else {
                p2p.createGroup(channel, listener);
            }
        } catch (SecurityException e) {
            fail("no_p2p", "Wi-Fi Direct is not allowed for Luch on this device");
        } catch (RuntimeException e) {
            if (attempt < 2) create(attempt + 1);
            else fail("no_p2p", "Wi-Fi Direct could not make a group: " + e.getMessage());
        }
    }

    /** Asks until the group is up with its address, or FORM_MS has passed. */
    @TargetApi(29)
    @SuppressLint("MissingPermission")
    private static void poll() {
        if (channel == null || waiting.isEmpty()) return;
        if (System.currentTimeMillis() - formingSince > FORM_MS) {
            fail("timeout", "the Wi-Fi Direct group did not come up");
            return;
        }
        try {
            p2p.requestGroupInfo(channel, g -> {
                if (g == null || !g.isGroupOwner()) {
                    MAIN.postDelayed(POLL, POLL_MS);
                    return;
                }
                p2p.requestConnectionInfo(channel, info -> {
                    if (info == null || !info.groupFormed || info.groupOwnerAddress == null) {
                        MAIN.postDelayed(POLL, POLL_MS);
                        return;
                    }
                    Direct.Group group = new Direct.Group(g.getNetworkName(), g.getPassphrase(), g.getFrequency(),
                            info.groupOwnerAddress.getHostAddress());
                    if (!Direct.validPass(group.pass)) {
                        fail("no_p2p", "the system did not give the group's passphrase");
                        return;
                    }
                    owner = info.groupOwnerAddress;
                    life = new Direct.Lifetime(System::currentTimeMillis);
                    up = group;
                    Log.i(LuchService.TAG, "Wi-Fi Direct group up, " + group.freq + " MHz");
                    List<Done> all = new ArrayList<>(waiting);
                    waiting.clear();
                    for (Done d : all) d.done(group, null, null);
                    MAIN.postDelayed(CHECK, CHECK_MS);
                });
            });
        } catch (SecurityException e) {
            fail("no_p2p", "Wi-Fi Direct is not allowed for Luch on this device");
        }
    }

    /** Removes the group once its time is over, and forgets it when it went away by itself. */
    @SuppressLint("MissingPermission")
    private static void check() {
        if (up == null) return;
        if (life.over()) {
            remove("nobody uses it any more");
            return;
        }
        try {
            p2p.requestGroupInfo(channel, g -> {
                if (up == null) return;
                if (g == null) {
                    remove("it went away");
                } else {
                    MAIN.postDelayed(CHECK, CHECK_MS);
                }
            });
        } catch (RuntimeException e) {
            remove("it cannot be looked at: " + e);
        }
    }

    /** Takes the group down (when there is one) and lets go of Wi-Fi Direct. */
    private static void remove(String why) {
        if (why != null) Log.i(LuchService.TAG, "Wi-Fi Direct group removed: " + why);
        if (p2p != null && channel != null) {
            try {
                p2p.removeGroup(channel, null);
            } catch (RuntimeException ignored) {
                // already gone with Wi-Fi
            }
            if (Build.VERSION.SDK_INT >= 27) channel.close();
        }
        channel = null;
        up = null;
        owner = null;
        life = null;
        MAIN.removeCallbacks(POLL);
        MAIN.removeCallbacks(CHECK);
        if (!waiting.isEmpty()) fail("no_p2p", "Wi-Fi Direct went away");
    }

    /** Answers everyone waiting with an error, and lets go of Wi-Fi Direct when no group is up. */
    private static void fail(String code, String detail) {
        List<Done> all = new ArrayList<>(waiting);
        waiting.clear();
        if (up == null && channel != null) remove(null);
        for (Done d : all) d.done(null, code, detail);
    }
}
