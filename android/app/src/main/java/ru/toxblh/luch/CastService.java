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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * "Show on TV": what another app shared goes to a receiver the way a computer casts it (SharePlan): photos, a video or
 * a sound, a PDF, a link, YouTube. The files stay where they are and are served from here (ShareServer) while the
 * receiver shows them, over this phone's control connection to it (Link: paired over TLS, so the TV asks only once).
 * A notification says what is on which TV, and turns photos and pages or pauses a video. One show at a time.
 */
public class CastService extends Service {
    private static final String TAG = "Luch";
    private static final String CHANNEL = "cast";
    private static final int NOTIFICATION = 4;
    private static final String ACTION_STOP = "ru.toxblh.luch.CAST_STOP", ACTION_NEXT = "ru.toxblh.luch.CAST_NEXT",
            ACTION_PREV = "ru.toxblh.luch.CAST_PREV", ACTION_TOGGLE = "ru.toxblh.luch.CAST_TOGGLE";
    private static volatile Session current;

    /** What the share screen shows until the TV shows the thing; called on the main thread. */
    interface Progress {
        void status(String text);

        /** Null: it is on the TV. Else why not; {@code forgettable}: the TV's pairing could be forgotten and tried again. */
        void done(String error, boolean forgettable);
    }

    static volatile Progress progress;

    static void start(Context context, Luch.Receiver to, List<Uri> uris, String text) {
        Intent i = new Intent(context, CastService.class).putExtra("id", to.id).putExtra("name", to.name)
                .putExtra("addr", to.address).putExtra("port", to.port).putExtra("tls", to.tlsPort)
                .putExtra(Intent.EXTRA_TEXT, text);
        if (!uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("shared", uris.get(0));
            for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);   // the share gave this app the files; the service gets them too
        }
        Compat.startForegroundService(context, i);
    }

    /** The share screen gave up waiting (Cancel). */
    static void cancel() {
        Session s = current;
        if (s != null) s.stop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Session s = current;
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (action != null) {
            if (s == null) {
                stopSelf();
            } else if (ACTION_STOP.equals(action)) {
                s.stop();
            } else {
                s.button(action);
            }
            return START_NOT_STICKY;
        }
        Luch.Receiver to = new Luch.Receiver(intent.getStringExtra("id"), intent.getStringExtra("name"), "",
                intent.getStringExtra("addr"), intent.getIntExtra("port", Luch.PORT), "", intent.getIntExtra("tls", 0));
        List<Uri> uris = new ArrayList<>();
        ClipData clip = intent.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
        }
        if (s != null) s.stop();
        Session next = new Session(this, to, uris, intent.getStringExtra(Intent.EXTRA_TEXT));
        current = next;
        next.notifyState();
        new Thread(next::run, "luch-cast").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Why {@code tv} shows nothing, in words. */
    static String problem(Context c, SharePlan.Problem p, String tv) {
        switch (p) {
            case NO_LINK: return c.getString(R.string.cast_no_link);
            case WEB_PAGE: return c.getString(R.string.cast_web_page);
            case NO_MODE: return c.getString(R.string.cast_no_mode, tv);
            case NO_YOUTUBE: return c.getString(R.string.cast_no_youtube, tv);
            default: return c.getString(R.string.cast_unsupported);
        }
    }

    /** A shared file's type and name, as the app it came from tells them. */
    static SharePlan.Shared describe(Context c, Uri uri) {
        String name = "";
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) name = q.getString(0);
        } catch (RuntimeException ignored) {
            // some apps' providers answer no queries
        }
        if (name.isEmpty()) name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        return new SharePlan.Shared(c.getContentResolver().getType(uri), name);
    }

    /** What was shared, as files. */
    static List<SharePlan.Shared> describe(Context c, List<Uri> uris) {
        List<SharePlan.Shared> out = new ArrayList<>();
        for (Uri u : uris) out.add(describe(c, u));
        return out;
    }

    /** A shared file, served from where the share left it: a seek opens it again from that byte. */
    private static ShareServer.Source file(Context c, Uri uri) {
        long length = -1;
        try (AssetFileDescriptor afd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) length = afd.getLength();
        } catch (IOException | RuntimeException ignored) {
            // asked again when fetched
        }
        if (length < 0) {
            try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (q != null && q.moveToFirst() && !q.isNull(0)) length = q.getLong(0);
            } catch (RuntimeException ignored) {
                // unknown then: sent whole, without seeking
            }
        }
        final long size = length;
        return new ShareServer.Source() {
            @Override
            public long length() {
                return size;
            }

            @Override
            public InputStream open(long from) throws IOException {
                InputStream in = c.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("cannot open " + uri);
                for (long left = from; left > 0; ) {
                    long n = in.skip(left);   // a file's stream seeks
                    if (n <= 0) {
                        if (in.read() < 0) break;
                        n = 1;
                    }
                    left -= n;
                }
                return in;
            }
        };
    }

    /** A picture or a PDF behind a link, relayed: the receiver fetches those from the sender only (PROTOCOL.md 4a). */
    private static ShareServer.Source relay(String url) {
        return new ShareServer.Source() {
            @Override
            public long length() {
                return -1;
            }

            @Override
            public InputStream open(long from) throws IOException {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                return c.getInputStream();
            }
        };
    }

    /** The Content-Type a link's server says, null when it says nothing (or cannot be reached from here). */
    private static String typeOf(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            return c.getResponseCode() < 400 ? c.getContentType() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final class Session implements Link.Ui {
        private final CastService service;
        private final Luch.Receiver to;
        private final List<Uri> uris;
        private final String text;
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile Link link;
        private ShareServer server;
        private volatile boolean ended, showing;
        private SharePlan plan;
        private JSONObject shown;
        /** The photo or page up now, and how many there are (0: not counted). */
        private volatile int index, count;
        private volatile boolean paused;
        private volatile String status;
        /** An older receiver turns photos only by a new list, and stops the old one on this connection meanwhile. */
        private boolean oldPhotos, replacing;

        Session(CastService service, Luch.Receiver to, List<Uri> uris, String text) {
            this.service = service;
            this.to = to;
            this.uris = uris;
            this.text = text;
            status = service.getString(R.string.cast_connecting, to.name);
        }

        void run() {
            try {
                plan = SharePlan.of(describe(service, uris), text);
                if (plan.needsType()) plan = plan.refine(typeOf(plan.things.get(0).url));
                if (plan.kind == SharePlan.Kind.NOTHING || plan.kind == SharePlan.Kind.MIRROR) {
                    fail(problem(plan.problem), false);
                    return;
                }
                link = Link.open(service, to);
                if (ended) return;
                String mode = plan.mode();
                JSONObject welcome = link.hello(mode == null ? Link.helloFrom(service) : Link.helloFrom(service, mode),
                        to.id, Link.pins(service), this);
                SharePlan.Problem p = plan.check(welcome);
                if (p != SharePlan.Problem.NONE) {
                    fail(problem(p), false);
                    return;
                }
                shown = plan.message(links());
                JSONObject ready = link.request(shown, plan.readyType(), this);
                showing = true;
                count = plan.kind == SharePlan.Kind.PHOTOS ? plan.things.size() : ready.optInt("pages", 0);
                status = null;
                if (plan.skipped > 0) say(service.getResources().getQuantityString(R.plurals.cast_skipped, plan.skipped, plan.skipped));
                post(null, false);
                if (plan.kind == SharePlan.Kind.YOUTUBE) {
                    say(service.getString(R.string.cast_youtube, to.name));
                    end();
                    return;
                }
                notifyState();
                listen();
            } catch (Link.Refused r) {
                fail("cancelled".equals(r.reason) ? null : refusal(r), "other_device".equals(r.reason));
            } catch (Link.Failure f) {
                fail("busy".equals(f.code) ? service.getString(R.string.cast_busy, to.name)
                        : service.getString(R.string.cast_failed, f.getMessage()), false);
            } catch (InterruptedException e) {
                fail(null, false);
            } catch (Exception e) {
                if (!ended) Log.w(TAG, "cast failed", e);
                fail(ended ? null : service.getString(R.string.cast_failed, String.valueOf(e.getMessage())), false);
            }
        }

        /** Where the receiver fetches each thing: from here, or (a media link) from the internet itself. */
        private List<String> links() throws IOException {
            List<String> out = new ArrayList<>();
            for (SharePlan.Thing t : plan.things) {
                if (t.isLink() && plan.kind == SharePlan.Kind.MEDIA) {
                    out.add(null);
                    continue;
                }
                if (server == null) server = new ShareServer(link.localAddress(), link.peerAddress());
                ShareServer.Source src = t.isLink() ? relay(t.url) : file(service, uris.get(t.index));
                out.add(server.share(src, t.mime.isEmpty() ? "application/octet-stream" : t.mime, t.name,
                        link.localAddress().getHostAddress()));
            }
            return out;
        }

        /** What the receiver says while it shows the thing; a quiet 40 s means it is gone. */
        private void listen() throws IOException, JSONException {
            while (!ended) {
                JSONObject m;
                try {
                    m = link.next(40_000);
                } catch (SocketTimeoutException | EOFException gone) {
                    break;
                }
                switch (m.optString("t")) {
                    case "photo_index":
                        index = m.optInt("index");
                        notifyState();
                        break;
                    case "doc_page":
                        index = m.optInt("page");
                        notifyState();
                        break;
                    case "media_state": {
                        String st = m.optString("state");
                        paused = "paused".equals(st);
                        notifyState();
                        break;
                    }
                    case "stop":
                        if (replacing && "replaced".equals(m.optString("reason"))) {
                            replacing = false;   // the old list, ended by the new one
                            break;
                        }
                        end();
                        return;
                    case "error":
                        if ("unknown".equals(m.optString("code")) && "photo_index".equals(m.optString("for"))) {
                            oldPhotos = true;
                            turnPhotos();
                        }
                        break;
                    default:
                        break;
                }
            }
            end();
        }

        /** A notification's button, on the main thread: the message goes from a thread of its own. */
        void button(String action) {
            new Thread(() -> {
                try {
                    if (link == null || !showing) return;
                    int step = ACTION_NEXT.equals(action) ? 1 : ACTION_PREV.equals(action) ? -1 : 0;
                    if (ACTION_TOGGLE.equals(action)) {
                        link.send(new JSONObject().put("t", "media_cmd").put("do", "toggle"));
                        paused = !paused;
                    } else if (plan.kind == SharePlan.Kind.PHOTOS && step != 0) {
                        int to = Math.max(0, Math.min(count - 1, index + step));
                        if (to == index) return;
                        index = to;
                        turnPhotos();
                    } else if (plan.kind == SharePlan.Kind.DOC && step != 0) {
                        int to = Math.max(0, count > 0 ? Math.min(count - 1, index + step) : index + step);
                        if (to == index) return;
                        index = to;
                        link.send(new JSONObject().put("t", "doc_page").put("page", to));
                    }
                    notifyState();
                } catch (IOException | JSONException e) {
                    Log.i(TAG, "cast button: " + e);
                }
            }, "luch-cast-button").start();
        }

        private void turnPhotos() throws IOException, JSONException {
            if (!oldPhotos) {
                link.send(new JSONObject().put("t", "photo_index").put("index", index));
                return;
            }
            synchronized (this) {
                replacing = true;
                link.send(new JSONObject(shown.toString()).put("index", index));
            }
        }

        // ---- Link.Ui ----

        @Override
        public void allowOnTv() {
            status = service.getString(R.string.cast_allow, to.name);
            post(status);
            notifyState();
        }

        @Override
        public String code() throws InterruptedException {
            status = service.getString(R.string.cast_code, to.name);
            post(status);
            String c = PairCode.ask(service, to.name);
            status = service.getString(R.string.cast_connecting, to.name);
            post(status);
            return c;
        }

        // ---- the notification and the share screen ----

        void notifyState() {
            main.post(() -> {
                if (current != this) return;
                Notification n = notification();
                if (Build.VERSION.SDK_INT >= 34) {
                    service.startForeground(NOTIFICATION, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    service.startForeground(NOTIFICATION, n);
                }
            });
        }

        @SuppressWarnings("deprecation")   // addAction(icon, …): the one that works from Android 5
        private Notification notification() {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                ((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(
                        new NotificationChannel(CHANNEL, service.getString(R.string.cast_channel),
                                NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(service, CHANNEL);
            } else {
                b = new Notification.Builder(service);
            }
            b.setSmallIcon(R.drawable.ic_luch).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentTitle(service.getString(R.string.cast_notification, to.name));
            String line = status;
            if (line == null && plan != null) {
                if (plan.kind == SharePlan.Kind.PHOTOS && count > 1) {
                    line = service.getString(R.string.cast_photo_of, index + 1, count);
                } else if (plan.kind == SharePlan.Kind.DOC) {
                    line = count > 0 ? service.getString(R.string.cast_page_of, index + 1, count)
                            : service.getString(R.string.cast_page, index + 1);
                } else if (!plan.things.isEmpty()) {
                    line = plan.things.get(0).name + (paused ? " · " + service.getString(R.string.media_paused) : "");
                }
            }
            if (line != null) b.setContentText(line);
            if (showing && plan != null) {
                boolean pages = plan.kind == SharePlan.Kind.DOC || plan.kind == SharePlan.Kind.PHOTOS && count > 1;
                if (pages) b.addAction(android.R.drawable.ic_media_previous, service.getString(R.string.cast_prev), action(ACTION_PREV));
                if (plan.kind == SharePlan.Kind.MEDIA) {
                    b.addAction(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                            service.getString(paused ? R.string.cast_play : R.string.cast_pause), action(ACTION_TOGGLE));
                }
                if (pages) b.addAction(android.R.drawable.ic_media_next, service.getString(R.string.cast_next), action(ACTION_NEXT));
            }
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, service.getString(R.string.action_stop_send),
                    action(ACTION_STOP));
            return b.build();
        }

        private PendingIntent action(String a) {
            return PendingIntent.getService(service, a.hashCode(), new Intent(service, CastService.class).setAction(a),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }

        private void post(String text) {
            main.post(() -> {
                Progress p = progress;
                if (p != null && current == this) p.status(text);
            });
        }

        private void post(String error, boolean forgettable) {
            main.post(() -> {
                Progress p = progress;
                if (p != null) {
                    p.done(error, forgettable);
                } else if (error != null) {
                    Toast.makeText(service, error, Toast.LENGTH_LONG).show();
                }
            });
        }

        private void say(String text) {
            main.post(() -> Toast.makeText(service, text, Toast.LENGTH_LONG).show());
        }

        private void fail(String error, boolean forgettable) {
            post(error, forgettable);
            end();
        }

        // ---- the end ----

        /** Stop from here: the receiver is told, then everything closes. */
        void stop() {
            new Thread(() -> {
                Link l = link;
                if (l != null && showing) {
                    try {
                        l.send(new JSONObject().put("t", "stop"));
                    } catch (IOException | JSONException ignored) {
                        // gone already
                    }
                }
                end();
            }, "luch-cast-stop").start();
        }

        private void end() {
            synchronized (this) {
                if (ended) return;
                ended = true;
            }
            Link l = link;
            if (l != null) l.close();
            if (server != null) server.close();
            main.post(() -> {
                if (current != this) return;
                current = null;
                service.stopForeground(true);
                service.stopSelf();
            });
        }

        // ---- words ----

        private String problem(SharePlan.Problem p) {
            return CastService.problem(service, p, to.name);
        }

        private String refusal(Link.Refused r) {
            switch (r.reason) {
                case "denied": return service.getString(R.string.cast_denied, to.name);
                case "dismissed":
                case "timeout": return service.getString(R.string.cast_no_answer, to.name);
                case "wrong_code": return service.getString(R.string.cast_wrong_code);
                case "banned": return service.getString(R.string.cast_banned, to.name, Math.max(1, (r.retryS + 59) / 60));
                case "busy": return service.getString(R.string.cast_asking, to.name);
                case "other_device": return service.getString(R.string.cast_other_device, to.name);
                case "mismatch": return service.getString(R.string.cast_mismatch);
                default: return service.getString(R.string.cast_failed, r.getMessage());
            }
        }
    }
}
