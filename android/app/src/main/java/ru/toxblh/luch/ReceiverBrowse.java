// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The receivers nearby while "Show on TV" is up on a phone whose own receiver is switched off: the always-on service,
 * which keeps that list otherwise, is not running then, and switching it on would announce the phone as a TV.
 */
final class ReceiverBrowse {
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private final List<String> found = new ArrayList<>();
    private final String ownId;
    private boolean resolving, stopped;
    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
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
            Log.w(LuchService.TAG, "share: browse failed " + error);
        }

        @Override
        public void onStopDiscoveryFailed(String type, int error) {
        }
    };

    private ReceiverBrowse(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        ownId = Luch.id(context);
    }

    static ReceiverBrowse start(Context context) {
        ReceiverBrowse b = new ReceiverBrowse(context);
        b.nsd.discoverServices(Luch.TYPE_RECEIVER, NsdManager.PROTOCOL_DNS_SD, b.listener);
        return b;
    }

    /**
     * Stops looking. {@code forget}: what it found leaves the list, as nothing would say when it goes; kept while
     * the screen is about to be shown on one of them (SenderService finds its receiver there).
     */
    void stop(boolean forget) {
        stopped = true;
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (RuntimeException ignored) {
            // not started
        }
        if (forget) for (String name : found) Luch.receiverGone(name);
    }

    @SuppressWarnings("deprecation")   // resolveService: its replacement needs API 34
    private void resolveNext() {
        if (resolving || stopped || toResolve.isEmpty()) return;
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo s, int error) {
                main.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo s) {
                main.post(() -> {
                    resolving = false;
                    Map<String, byte[]> txt = s.getAttributes();
                    String id = text(txt, "id", s.getServiceName());
                    if (!stopped && !id.equals(ownId) && s.getHost() != null) {
                        int tls;
                        try {
                            tls = Integer.parseInt(text(txt, "tls", "0").trim());
                        } catch (NumberFormatException e) {
                            tls = 0;
                        }
                        found.add(s.getServiceName());
                        Luch.receiver(s.getServiceName(), new Luch.Receiver(id, text(txt, "name", s.getServiceName()),
                                text(txt, "model", ""), s.getHost().getHostAddress(), s.getPort(), text(txt, "dec", ""), tls));
                    }
                    resolveNext();
                });
            }
        });
    }

    private static String text(Map<String, byte[]> txt, String key, String fallback) {
        byte[] b = txt == null ? null : txt.get(key);
        return b == null ? fallback : new String(b, StandardCharsets.UTF_8);
    }
}
