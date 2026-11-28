// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * The always-on part: announces this TV as {@code _luch._tcp}, keeps the control port open ({@link ControlServer}) and
 * lists the senders that announce themselves as {@code _luchsrc._tcp}. Started at boot; a foreground service so the
 * low-memory killer leaves it alone. Idle it is one thread blocked in accept() and the system's mDNS doing the rest.
 */
public class LuchService extends Service {
    /** The control port this receiver listens on (47800 unless taken), 0 until started; for on-device tests. */
    static volatile int listening;

    static final String TAG = "Luch";
    private static final String CHANNEL = "luch";

    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.DiscoveryListener discoveryReceivers;
    private ControlServer server;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** NsdManager resolves one service at a time; the rest wait here. */
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;

    static void start(Context context) {
        Compat.startForegroundService(context, new Intent(context, LuchService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Battery, charging and thermal: cached from system callbacks, reported only on change.
        DeviceState.attach(this);
        goForeground();
        nsd = Compat.service(this, NsdManager.class);
        server = new ControlServer(this);
        int port = server.start();
        listening = port;
        if (port <= 0) {
            Luch.setStatus(getString(R.string.status_port_busy));
            return;
        }
        announce(port);
        watchStandby();
        browseSenders();
        browseReceivers();
        // Where mDNS does not get through: the card over Bluetooth LE too, when the TV has it (PROTOCOL.md 1c).
        Nearby.start(this);
        // After install or a firmware update there is no measurement yet: take it once things have settled.
        main.postDelayed(() -> Bench.autoRun(this, ControlServer::broadcastBench), 20_000);
        Updater.schedule(this, main);   // only in the GitHub build
    }

    private android.content.BroadcastReceiver standby;

    /** In guest mode pairings end with the TV session: the screen going off is the TV going to standby. */
    private void watchStandby() {
        standby = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                ControlServer.gate(c).standby();
            }
        };
        registerReceiver(standby, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (registration != null) nsd.unregisterService(registration);
        } catch (RuntimeException ignored) {
        }
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
            if (discoveryReceivers != null) nsd.stopServiceDiscovery(discoveryReceivers);
        } catch (RuntimeException ignored) {
        }
        if (server != null) server.stop();
        if (standby != null) unregisterReceiver(standby);
        Nearby.stop();
        DirectGroup.stop();
        DeviceState.detach(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void goForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_MIN));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            // No channels before Android 8: the priority says the same.
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        Notification n = builder
                .setSmallIcon(R.drawable.ic_luch)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    /** Tries left for a free service name: the name as is, then "name (2)", "name (3)". */
    private int nameTry;

    private void announce(int port) {
        NsdServiceInfo info = new NsdServiceInfo();
        // Android's mDNS wants a name unique across all of the device's services, whatever their type, and the TV's
        // own name is usually taken by its AirPlay or Cast receiver. Senders show the TXT "name", not this.
        String base = Luch.name(this) + " · Luch";
        info.setServiceName(nameTry == 0 ? base : base + " (" + (nameTry + 1) + ")");
        info.setServiceType(Luch.TYPE_RECEIVER);
        info.setPort(port);
        info.setAttribute("v", String.valueOf(Luch.VERSION));
        info.setAttribute("id", Luch.id(this));
        info.setAttribute("name", Luch.name(this));
        info.setAttribute("model", Luch.model());
        info.setAttribute("dec", String.join(",", shortCodecs()));
        // "v" is the plain port's version, which every sender speaks; "tls" is where v1 with pairing is (PROTOCOL.md 1).
        if (ControlServer.tlsPort > 0) info.setAttribute("tls", String.valueOf(ControlServer.tlsPort));
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "announced as " + s.getServiceName() + " port " + port);
                Luch.setStatus(getString(R.string.status_announced, s.getServiceName()));
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int error) {
                Log.w(TAG, "register failed " + error + " for " + s.getServiceName());
                // A name already on the network (another TV, or our own record left by a killed process).
                if (nameTry < 3) {
                    nameTry++;
                    main.postDelayed(() -> announce(port), 1000);
                    return;
                }
                Luch.setStatus(getString(R.string.status_announce_failed, error));
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int error) {
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    private static String[] shortCodecs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String mime : Luch.decoders()) {
            switch (mime) {
                case "video/avc": out.add("avc"); break;
                case "video/hevc": out.add("hevc"); break;
                case "video/av01": out.add("av1"); break;
                case "video/x-vnd.on2.vp9": out.add("vp9"); break;
                default: break;
            }
        }
        return out.toArray(new String[0]);
    }

    private void browseSenders() {
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo s) {
                main.post(() -> {
                    toResolve.add(s);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo s) {
                Log.i(TAG, "sender gone: " + s.getServiceName());
                Luch.gone(s.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "browse failed " + error);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }
        };
        nsd.discoverServices(Luch.TYPE_SENDER, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    /** The other receivers on the network: where this device can show its own screen. */
    private void browseReceivers() {
        discoveryReceivers = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo s) {
                main.post(() -> {
                    toResolve.add(s);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo s) {
                Luch.receiverGone(s.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "browse receivers failed " + error);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }
        };
        nsd.discoverServices(Luch.TYPE_RECEIVER, NsdManager.PROTOCOL_DNS_SD, discoveryReceivers);
    }

    @SuppressWarnings("deprecation")   // resolveService: its replacement needs API 34, this runs from 26
    private void resolveNext() {
        if (resolving || toResolve.isEmpty()) return;
        resolving = true;
        NsdServiceInfo next = toResolve.poll();
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo s, int error) {
                main.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo s) {
                Map<String, byte[]> txt = s.getAttributes();
                String id = text(txt, "id", s.getServiceName());
                String name = text(txt, "name", s.getServiceName());
                String host = s.getHost() == null ? "" : s.getHost().getHostAddress();
                if (s.getServiceType() != null && s.getServiceType().contains("luchsrc")) {
                    Log.i(TAG, "sender nearby: " + name + " " + host + " id=" + id);
                    Luch.nearby(s.getServiceName(), new Luch.Peer(id, name, host));
                } else if (!id.equals(Luch.id(LuchService.this))) {
                    Log.i(TAG, "receiver nearby: " + name + " " + host + ":" + s.getPort() + " id=" + id);
                    Luch.receiver(s.getServiceName(), new Luch.Receiver(id, name, text(txt, "model", ""), host,
                            s.getPort(), text(txt, "dec", ""), number(text(txt, "tls", ""))));
                }
                main.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }
        });
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Map<String, byte[]> txt, String key, String fallback) {
        byte[] b = txt == null ? null : txt.get(key);
        return b == null ? fallback : new String(b, StandardCharsets.UTF_8);
    }
}
