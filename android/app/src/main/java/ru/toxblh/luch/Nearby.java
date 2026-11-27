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
import android.os.ParcelUuid;

import java.util.Collections;

/**
 * Being found where mDNS does not get through (PROTOCOL.md 1c, 1d): this TV's card over Bluetooth LE (with the PSM
 * of {@link BleLink}'s channel when "direct" works here, 4m), a sender "looking for Luch" heard the same way, the
 * card said by sound when the person asks for it, and another TV's remote sent for a sender that cannot advertise
 * (4l).
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
    /** Between start and stop; the card comes back after a wake only then. */
    private static boolean started;
    /** While another TV's wake is on the air, the card is not: many controllers keep one advertisement at a time. */
    private static boolean quiet;
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
            started = true;
            MAIN.removeCallbacks(REFRESH);
            REFRESH.run();
        });
    }

    static void stop() {
        MAIN.post(() -> {
            started = false;
            MAIN.removeCallbacks(REFRESH);
            stopAdvertising();
            stopListening();
            BleLink.stop();
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

    /**
     * This TV's card as advertised: no pairing token over the air, the PSM of the "direct" channel when it listens
     * (4m). Null with neither an IPv4 address nor a PSM.
     */
    static byte[] card(Context c) {
        String ip = Compat.ipv4();
        int port = LuchService.listening > 0 ? LuchService.listening : Luch.PORT;
        int psm = BleLink.psm;
        if (Card.ipv4(ip) == null) {
            if (psm == 0) return null;
            ip = null;
            port = 0;
        }
        return new Card(Card.RECEIVER, Luch.id(c), ip, port, null, psm).encode();
    }

    /** Puts the current card on the air again when the address changed, Bluetooth came on, or a sender looked. */
    private static void refresh() {
        if (app == null || quiet) return;
        boolean wantEager = System.currentTimeMillis() - lookingAt < EAGER_MS;
        String why = unavailable(app);
        // The channel for "direct" first: its PSM goes in the card.
        BleLink.want(app, why == null ? adapter(app) : null, why == null && DirectGroup.unavailable(app) == null);
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
        // A PSM in the card: a sender connects to open the channel of "direct" (4m).
        boolean connectable = BleLink.psm != 0;
        BluetoothAdapter a = adapter(app);
        advertiser = a == null ? null : a.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            state = UNSUPPORTED;
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(fast ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY : AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(connectable)
                .setTimeout(0)
                .build();
        // Flags (3 bytes) and the card as manufacturer data (4 + 13, or 4 + 15 with a PSM): 20 or 22 of the 31 bytes;
        // no name, it would not fit.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(Card.COMPANY, c)
                .build();
        advertising = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings s) {
                state = ADVERTISING;
                Log.i(LuchService.TAG, "advertising over Bluetooth" + (fast ? ", fast" : "")
                        + (connectable ? ", connectable" : ""));
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

    // ---------- waking another TV (PROTOCOL.md 4l) ----------

    /** The answer to a wake: a null code once it was on the air for its time, else an error code and detail. */
    interface WakeDone {
        void done(String code, String detail);
    }

    /** The Human Interface Device service, as the remote's own advertisement lists it. */
    private static final ParcelUuid HID = ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");

    /**
     * Puts a TV remote's power-key advertisement on the air for {@code ms}, as luchd's wake.rs does: connectable,
     * discoverable, the HID service and the MediaTek data, ten times a second and loud. A MoGo 4 in standby ignored
     * that data alone, non-connectable. luchd also gives the keyboard appearance (0x03c1); Android has no public call
     * for an advertisement's appearance, so it goes without.
     */
    @SuppressLint("MissingPermission") // unavailable() checked BLUETOOTH_ADVERTISE on Android 12+
    static void wake(Context context, byte[] data, int ms, WakeDone done) {
        MAIN.post(() -> {
            String code = BleWake.code(unavailable(context));
            BluetoothAdapter a = code == null ? adapter(context) : null;
            BluetoothLeAdvertiser adv = a == null ? null : a.getBluetoothLeAdvertiser();
            if (adv == null) {
                if (code == null) code = "no_ble";
                done.done(code, BleWake.detail(code));
                return;
            }
            quiet = true;
            stopAdvertising();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData ad = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(HID)
                    .addManufacturerData(BleWake.MEDIATEK, data)
                    .build();
            Wake w = new Wake(adv, ms, done);
            MAIN.postDelayed(w, START_MS);
            try {
                adv.startAdvertising(settings, ad, w);
            } catch (RuntimeException e) {
                // SecurityException: the permission was taken back; IllegalStateException: Bluetooth went off
                String c = e instanceof SecurityException ? "not_allowed" : "no_ble";
                w.end(c, BleWake.detail(c));
            }
        });
    }

    /** How long Bluetooth has to say the advertisement started. */
    private static final long START_MS = 3_000;

    /** One wake on the air: its callbacks and its end, which answers exactly once (on the main thread). */
    private static final class Wake extends AdvertiseCallback implements Runnable {
        private final BluetoothLeAdvertiser adv;
        private final int ms;
        private final WakeDone done;
        private boolean on, ended;

        Wake(BluetoothLeAdvertiser adv, int ms, WakeDone done) {
            this.adv = adv;
            this.ms = ms;
            this.done = done;
        }

        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            if (ended) {
                stop();   // too late: already answered, it must not stay on the air
                return;
            }
            on = true;
            MAIN.removeCallbacks(this);
            MAIN.postDelayed(this, ms);
        }

        @Override
        public void onStartFailure(int error) {
            end("no_ble", "advertising failed: " + error);
        }

        /** The hold is over, or Bluetooth never said it started. */
        @Override
        public void run() {
            if (on) {
                end(null, null);
            } else {
                end("no_ble", "Bluetooth did not start advertising");
            }
        }

        void end(String code, String detail) {
            if (ended) return;
            ended = true;
            MAIN.removeCallbacks(this);
            stop();
            quiet = false;
            if (started) refresh();
            done.done(code, detail);
        }

        @SuppressLint("MissingPermission")
        private void stop() {
            try {
                adv.stopAdvertising(this);
            } catch (RuntimeException ignored) {
                // Bluetooth already off
            }
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
