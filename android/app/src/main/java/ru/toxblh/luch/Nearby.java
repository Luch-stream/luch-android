// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;

/**
 * Being found where mDNS does not get through (PROTOCOL.md 1c, 1d): this TV's card over Bluetooth LE, a sender
 * "looking for Luch" heard the same way, and the card said by sound when the person asks for it.
 *
 * Bluetooth is a bonus, never a condition: no adapter, Bluetooth off, no permission or no advertising support
 * (many TVs before Android 8) all leave {@link #state} saying so and everything else as it was.
 */
final class Nearby {
    static final String ADVERTISING = "advertising";
    static final String NO_BLUETOOTH = "no_bluetooth";
    static final String OFF = "bluetooth_off";
    static final String NO_PERMISSION = "no_permission";
    static final String UNSUPPORTED = "unsupported";
    static final String NO_ADDRESS = "no_address";
    static final String STOPPED = "stopped";

    /** What the advertising is doing, for the status screen and the tests. */
    static volatile String state = STOPPED;
    /** When a sender nearby last said it was looking for a receiver (0: never). */
    static volatile long lookingAt;

    /** A sender looking makes this TV advertise ten times a second for a while, instead of once a second. */
    private static final long EAGER_MS = 120_000;
    private static final long REFRESH_MS = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @SuppressLint("StaticFieldLeak") // the application's context, never an activity's
    private static Context app;
    private static BluetoothLeAdvertiser advertiser;
    private static AdvertiseCallback advertising;
    private static BluetoothLeScanner scanner;
    private static ScanCallback scanning;
    private static byte[] onAir;
    private static boolean eager;
    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            refresh();
            MAIN.postDelayed(this, REFRESH_MS);
        }
    };

    private Nearby() {
    }

    /** Starts advertising (and listening for senders looking, where Android lets an app do it without location). */
    static void start(Context context) {
        MAIN.post(() -> {
            app = context.getApplicationContext();
            MAIN.removeCallbacks(REFRESH);
            REFRESH.run();
        });
    }

    static void stop() {
        MAIN.post(() -> {
            MAIN.removeCallbacks(REFRESH);
            stopAdvertising();
            stopListening();
            state = STOPPED;
        });
    }

    /** Why this TV cannot advertise, or null when it can. */
    static String unavailable(Context c) {
        if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE")
                != PackageManager.PERMISSION_GRANTED) {
            return NO_PERMISSION;
        }
        BluetoothAdapter a = adapter(c);
        if (a == null) return NO_BLUETOOTH;
        try {
            if (!a.isEnabled()) return OFF;
            if (a.getBluetoothLeAdvertiser() == null) return UNSUPPORTED;
        } catch (SecurityException e) {
            return NO_PERMISSION;
        }
        return null;
    }

    private static BluetoothAdapter adapter(Context c) {
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return null;
        BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** This TV's card as advertised: no pairing token over the air. Null without an IPv4 address. */
    static byte[] card(Context c) {
        String ip = Compat.ipv4();
        int port = LuchService.listening > 0 ? LuchService.listening : Luch.PORT;
        if (Card.ipv4(ip) == null) return null;
        return new Card(Card.RECEIVER, Luch.id(c), ip, port, null).encode();
    }

    /** Puts the current card on the air again when the address changed, Bluetooth came on, or a sender looked. */
    private static void refresh() {
        if (app == null) return;
        boolean wantEager = System.currentTimeMillis() - lookingAt < EAGER_MS;
        String why = unavailable(app);
        byte[] c = why == null ? card(app) : null;
        if (why == null && c == null) why = NO_ADDRESS;
        if (why != null) {
            stopAdvertising();
            stopListening();
            state = why;
            return;
        }
        if (advertising == null || !java.util.Arrays.equals(c, onAir) || wantEager != eager) {
            stopAdvertising();
            advertise(c, wantEager);
        }
        if (scanning == null) listen();
    }

    @SuppressLint("MissingPermission") // unavailable() checked BLUETOOTH_ADVERTISE on Android 12+
    private static void advertise(byte[] c, boolean fast) {
        BluetoothAdapter a = adapter(app);
        advertiser = a == null ? null : a.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            state = UNSUPPORTED;
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(fast ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY : AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build();
        // Flags (3 bytes) and the card as manufacturer data (4 + 13): 20 of the 31 bytes; no name, it would not fit.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(Card.COMPANY, c)
                .build();
        advertising = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings s) {
                state = ADVERTISING;
                Log.i(LuchService.TAG, "advertising over Bluetooth" + (fast ? ", fast" : ""));
            }

            @Override
            public void onStartFailure(int error) {
                state = "failed_" + error;
                Log.w(LuchService.TAG, "Bluetooth advertising failed: " + error);
            }
        };
        onAir = c;
        eager = fast;
        try {
            advertiser.startAdvertising(settings, data, advertising);
        } catch (RuntimeException e) {
            // SecurityException on a TV that took the permission back, IllegalStateException when Bluetooth went off
            state = e instanceof SecurityException ? NO_PERMISSION : OFF;
            advertising = null;
        }
    }

    @SuppressLint("MissingPermission")
    private static void stopAdvertising() {
        if (advertiser != null && advertising != null) {
            try {
                advertiser.stopAdvertising(advertising);
            } catch (RuntimeException ignored) {
                // Bluetooth already off
            }
        }
        advertising = null;
        onAir = null;
    }

    /**
     * Senders looking for a receiver (their card, kind 2). Only on Android 12+, where BLUETOOTH_SCAN can promise
     * not to locate anyone; before it, a scan needs the location permission, which a TV app should not ask for.
     */
    @SuppressLint("MissingPermission")
    private static void listen() {
        if (Build.VERSION.SDK_INT < 31 || app.checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        BluetoothAdapter a = adapter(app);
        scanner = a == null ? null : a.getBluetoothLeScanner();
        if (scanner == null) return;
        // Only "Lu" and kind 2 get through the controller's filter: the TV is not woken for every phone nearby.
        byte[] want = {'L', 'u', Card.SENDER};
        byte[] mask = {(byte) 0xFF, (byte) 0xFF, 0x0F};
        ScanFilter filter = new ScanFilter.Builder().setManufacturerData(Card.COMPANY, want, mask).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        scanning = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                ScanRecord r = result.getScanRecord();
                Card c = r == null ? null : Card.decode(r.getManufacturerSpecificData(Card.COMPANY));
                if (c != null && c.kind == Card.SENDER) MAIN.post(() -> looking(c.id));
            }
        };
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanning);
        } catch (RuntimeException e) {
            scanning = null;
        }
    }

    @SuppressLint("MissingPermission")
    private static void stopListening() {
        if (scanner != null && scanning != null) {
            try {
                scanner.stopScan(scanning);
            } catch (RuntimeException ignored) {
                // Bluetooth already off
            }
        }
        scanning = null;
    }

    /** A sender nearby is looking: advertise eagerly for a while and let the status screen say so. */
    static void looking(String senderId) {
        boolean fresh = System.currentTimeMillis() - lookingAt > EAGER_MS;
        lookingAt = System.currentTimeMillis();
        if (fresh) {
            Log.i(LuchService.TAG, "a sender nearby is looking: " + senderId);
            refresh();
            Luch.notifyListeners();
        }
    }

    // ---------- sound ----------

    /** How many times the card is said, and the pause between: one clap or a door can take a whole frame. */
    static final int REPEATS = 3;
    static final float GAP_S = 0.4f;

    /** The samples of the card said by sound, with its pairing token, at the output's own rate. */
    static short[] spoken(Context c, int rate) {
        String ip = Compat.ipv4();
        int port = LuchService.listening > 0 ? LuchService.listening : Luch.PORT;
        if (Card.ipv4(ip) == null) return null;
        byte[] card = new Card(Card.RECEIVER, Luch.id(c), ip, port, Card.Pairing.current()).encode();
        float[] frame = Chirp.synth(card, Chirp.BANDS[0], 0.5f, rate);
        int gap = Math.round(GAP_S * rate);
        float[] all = new float[REPEATS * (frame.length + gap)];
        for (int i = 0; i < REPEATS; i++) System.arraycopy(frame, 0, all, i * (frame.length + gap), frame.length);
        return Chirp.pcm(all);
    }

    /** Says this TV's card through its speaker, about 8 s, at the volume it is set to. Only when the person asks. */
    @SuppressWarnings("deprecation") // the AudioTrack constructor: AudioTrack.Builder is Android 6
    static boolean say(Context c) {
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        if (rate != 44100) rate = Chirp.RATE;
        short[] pcm = spoken(c, rate);
        if (pcm == null) return false;
        final int r = rate;
        new Thread(() -> {
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, r, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
            try {
                t.write(pcm, 0, pcm.length);
                t.play();
                Thread.sleep(pcm.length * 1000L / r + 200);
            } catch (InterruptedException | IllegalStateException e) {
                Log.w(LuchService.TAG, "could not say the address: " + e);
            } finally {
                t.release();
            }
        }, "luch-say").start();
        return true;
    }
}
