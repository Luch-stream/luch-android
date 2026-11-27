// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * "Transmit with Luch": the photos picked in another app's share sheet go to a receiver full screen. The files stay where
 * they are; this service offers each one at a link only the receiver may open (its own address, a random token) and tells
 * the receiver the list over the control channel (PROTOCOL.md, section 4a). It runs while the receiver shows them.
 */
public class PhotoService extends Service {
    private static final String TAG = "Luch";
    private static final String CHANNEL = "photo";
    private static final String ACTION_STOP = "ru.toxblh.luch.STOP_PHOTOS";
    private static volatile Session current;

    static void start(Context context, Luch.Receiver to, List<Uri> uris) {
        Intent i = new Intent(context, PhotoService.class).putExtra("addr", to.address).putExtra("port", to.port)
                .putExtra("name", to.name);
        ClipData clip = ClipData.newRawUri("photos", uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // the share gave this app the files; the service gets them too
        Compat.startForegroundService(context, i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            Session s = current;
            if (s != null) s.end("stopped", true);
            stopSelf();
            return START_NOT_STICKY;
        }
        ClipData clip = intent.getClipData();
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
        }
        String name = intent.getStringExtra("name");
        if (uris.isEmpty() || intent.getStringExtra("addr") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(name);
        Session old = current;
        if (old != null) old.end("replaced", true);
        Session s = new Session(this, intent.getStringExtra("addr"), intent.getIntExtra("port", Luch.PORT), uris);
        current = s;
        new Thread(s::run, "luch-photos").start();
        return START_NOT_STICKY;
    }

    private void goForeground(String to) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.photo_channel), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PhotoService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = b.setSmallIcon(R.drawable.ic_luch).setContentTitle(getString(R.string.photo_notification, to))
                .setContentText(getString(R.string.photo_stop)).setContentIntent(stop).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4, n, 0x40000000); // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            startForeground(4, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class Session {
        private final PhotoService service;
        private final String address;
        private final int port;
        private final List<Uri> uris;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final String token;
        private Socket control;
        private ServerSocket server;
        private OutputStream out;
        private boolean ended;

        Session(PhotoService service, String address, int port, List<Uri> uris) {
            this.service = service;
            this.address = address;
            this.port = port;
            this.uris = uris;
            byte[] t = new byte[16];
            new SecureRandom().nextBytes(t);
            StringBuilder sb = new StringBuilder();
            for (byte x : t) sb.append(String.format("%02x", x));
            token = sb.toString();
        }

        void run() {
            try {
                talk();
            } catch (Exception e) {
                Log.w(TAG, "photos failed", e);
                final String why = String.valueOf(e.getMessage());
                main.post(() -> Toast.makeText(service, service.getString(R.string.photo_send_failed, why), Toast.LENGTH_LONG).show());
                end("failed", false);
            }
        }

        private void talk() throws Exception {
            control = new Socket();
            control.connect(new InetSocketAddress(address, port), 4000);
            control.setTcpNoDelay(true);
            out = control.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            send(new JSONObject().put("t", "hello").put("v", Luch.VERSION).put("id", Luch.id(service))
                    .put("name", Luch.name(service)).put("app", "luch-android").put("want", new JSONArray().put("photo")));
            control.setSoTimeout(4000);
            JSONObject welcome = null;
            while (welcome == null) {
                JSONObject m = new JSONObject(readLine(in));
                if ("welcome".equals(m.optString("t"))) welcome = m;
            }
            boolean photo = false;
            JSONArray modes = welcome.optJSONArray("modes");
            for (int i = 0; modes != null && i < modes.length(); i++) photo |= "photo".equals(modes.optString(i));
            if (!photo) throw new IOException(service.getString(R.string.photo_unsupported));

            server = new ServerSocket(0, 8);
            Thread accept = new Thread(this::acceptLoop, "luch-photos-http");
            accept.setDaemon(true);
            accept.start();
            // the address this device has towards the receiver, not the first one it finds
            String host = control.getLocalAddress().getHostAddress();
            JSONArray items = new JSONArray();
            for (int i = 0; i < Math.min(uris.size(), PhotoSession.MAX_ITEMS); i++) {
                items.put(new JSONObject().put("url", "http://" + host + ":" + server.getLocalPort() + "/" + token + "/" + i)
                        .put("mime", mime(uris.get(i))).put("name", displayName(uris.get(i))));
            }
            send(new JSONObject().put("t", "photo").put("index", 0).put("items", items));
            // from here on the receiver talks when it has something to say (ping, stop); a quiet 40 s means it is gone
            control.setSoTimeout(40000);
            while (true) {
                JSONObject m = new JSONObject(readLine(in));
                String t = m.optString("t");
                if ("ping".equals(t)) {
                    send(new JSONObject().put("t", "pong").put("n", m.optInt("n")));
                } else if ("stop".equals(t) || "bye".equals(t)) {
                    break;
                } else if ("error".equals(t)) {
                    throw new IOException(m.optString("code") + " " + m.optString("detail"));
                }
            }
            end("receiver closed", false);
        }

        private String readLine(BufferedReader in) throws IOException {
            String l = in.readLine();
            if (l == null) throw new IOException("the receiver closed the connection");
            return l;
        }

        private synchronized void send(JSONObject o) throws IOException {
            if (out == null) return;
            out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void acceptLoop() {
            while (true) {
                try {
                    Socket c = server.accept();
                    Thread t = new Thread(() -> serve(c), "luch-photos-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** GET /token/n, from the receiver's address only; the file is streamed from where the share left it. */
        private void serve(Socket c) {
            try (Socket s = c) {
                s.setSoTimeout(5000);
                if (!s.getInetAddress().equals(InetAddress.getByName(address))) return;
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String request = r.readLine();
                String h;
                while ((h = r.readLine()) != null && !h.isEmpty()) {
                    // the headers say nothing this needs
                }
                OutputStream o = s.getOutputStream();
                String[] parts = request == null ? new String[0] : request.split(" ");
                String[] path = parts.length > 1 ? parts[1].split("/") : new String[0];
                int index = -1;
                if (parts.length > 1 && path.length == 3 && token.equals(path[1])) {
                    try {
                        index = Integer.parseInt(path[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (index < 0 || index >= uris.size()) {
                    o.write("HTTP/1.0 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                Uri uri = uris.get(index);
                try (AssetFileDescriptor afd = service.getContentResolver().openAssetFileDescriptor(uri, "r");
                     InputStream in = afd.createInputStream()) {
                    String length = afd.getLength() >= 0 ? "Content-Length: " + afd.getLength() + "\r\n" : "";
                    o.write(("HTTP/1.0 200 OK\r\nContent-Type: " + mime(uri) + "\r\n" + length + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    o.flush();
                }
            } catch (Exception e) {
                Log.i(TAG, "photo link: " + e);
            }
        }

        private String mime(Uri uri) {
            String t = service.getContentResolver().getType(uri);
            return t != null && t.startsWith("image/") ? t : "image/jpeg";
        }

        private String displayName(Uri uri) {
            try (Cursor c = service.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) {
            }
            return "";
        }

        void end(String why, boolean tellReceiver) {
            synchronized (this) {
                if (ended) return;
                ended = true;
            }
            Log.i(TAG, "photos ended: " + why);
            try {
                if (tellReceiver) send(new JSONObject().put("t", "stop"));
            } catch (Exception ignored) {
            }
            try {
                if (server != null) server.close();
            } catch (IOException ignored) {
            }
            try {
                if (control != null) control.close();
            } catch (IOException ignored) {
            }
            if (current == this) current = null;
            main.post(service::stopSelf);
        }
    }
}
