// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bluetooth LE channel of "direct" (PROTOCOL.md 4m): an L2CAP server whose PSM goes in the card, each channel
 * bridged to this TV's own TLS control port on loopback, so the sender meets exactly the TLS, pairing and JSON lines
 * of section 3a. The control server knows a bridged connection by the bridge's own local port ({@link #via}) and
 * treats it as the Bluetooth device it is: nothing of loopback is trusted, limits count against that device.
 */
final class BleLink {
    /** Channels open at once, from all devices; one device gets one at a time. */
    static final int MAX_LINKS = 4;
    /** The bridge's loopback port, as the control server sees it as the peer's, to "ble:<device address>". */
    private static final Map<Integer, String> BRIDGES = new ConcurrentHashMap<>();
    private static final Set<String> PEERS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static volatile BluetoothServerSocket server;
    /** The PSM senders open, 0 while not listening. */
    static volatile int psm;

    private BleLink() {
    }

    /**
     * Listens while {@code on} (this device can make a group and Bluetooth lets it), stops otherwise. Called from
     * Nearby's refresh on the main thread.
     */
    @TargetApi(29)
    @SuppressLint("MissingPermission") // can() checked BLUETOOTH_CONNECT on Android 12+
    static void want(Context c, BluetoothAdapter adapter, boolean on) {
        if (!on || adapter == null || !can(c)) {
            stop();
            return;
        }
        if (server != null) return;
        try {
            BluetoothServerSocket ss = adapter.listenUsingInsecureL2capChannel();
            server = ss;
            psm = ss.getPsm();
            Thread t = new Thread(() -> acceptLoop(ss), "luch-l2cap");
            t.setDaemon(true);
            t.start();
            Log.i(LuchService.TAG, "Bluetooth channel for direct, PSM " + psm);
        } catch (IOException | RuntimeException e) {
            Log.w(LuchService.TAG, "no Bluetooth channel for direct: " + e);
            server = null;
            psm = 0;
        }
    }

    static void stop() {
        BluetoothServerSocket ss = server;
        server = null;
        psm = 0;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Whether an L2CAP server may run here: Android 10+, and "Nearby devices" (connect) on 12+. */
    static boolean can(Context c) {
        if (Build.VERSION.SDK_INT < Direct.MIN_SDK) return false;
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** "ble:<device address>" when {@code s} (accepted on the TLS port) is one of our bridges, else null. */
    static String via(Socket s) {
        InetAddress a = s.getInetAddress();
        return a != null && a.isLoopbackAddress() ? BRIDGES.get(s.getPort()) : null;
    }

    @SuppressLint("MissingPermission") // a device's address: BLUETOOTH_CONNECT, checked before listening
    private static void acceptLoop(BluetoothServerSocket ss) {
        while (server == ss) {
            BluetoothSocket bt;
            try {
                bt = ss.accept();
            } catch (IOException e) {
                if (server == ss) Log.w(LuchService.TAG, "Bluetooth channel: " + e);
                break;
            }
            String device = bt.getRemoteDevice().getAddress();
            if (PEERS.size() >= MAX_LINKS || !PEERS.add(device)) {
                closeQuietly(bt);
                continue;
            }
            Thread t = new Thread(() -> bridge(bt, device), "luch-l2cap-link");
            t.setDaemon(true);
            t.start();
        }
        if (server == ss) {
            server = null;
            psm = 0;
        }
    }

    /** Pipes the channel to the TLS port and back until either side ends. */
    private static void bridge(BluetoothSocket bt, String device) {
        Socket loop = new Socket();
        int port = 0;
        try {
            int tls = ControlServer.tlsPort;
            if (tls <= 0) throw new IOException("no TLS port");
            InetAddress lo = InetAddress.getLoopbackAddress();
            // Bound first, so the control server can tell this connection apart before it reads a byte.
            loop.bind(new InetSocketAddress(lo, 0));
            port = loop.getLocalPort();
            BRIDGES.put(port, "ble:" + device);
            loop.connect(new InetSocketAddress(lo, tls), 2_000);
            loop.setTcpNoDelay(true);
            InputStream fromTv = loop.getInputStream();
            OutputStream toSender = bt.getOutputStream();
            Thread back = new Thread(() -> pump(fromTv, toSender, bt, loop), "luch-l2cap-back");
            back.setDaemon(true);
            back.start();
            pump(bt.getInputStream(), loop.getOutputStream(), bt, loop);
        } catch (IOException e) {
            Log.i(LuchService.TAG, "Bluetooth channel ended: " + e);
        } finally {
            closeQuietly(bt);
            closeQuietly(loop);
            if (port != 0) BRIDGES.remove(port);
            PEERS.remove(device);
        }
    }

    private static void pump(InputStream in, OutputStream out, BluetoothSocket bt, Socket loop) {
        byte[] buf = new byte[4096];
        try {
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // either side went
        } finally {
            closeQuietly(bt);
            closeQuietly(loop);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
