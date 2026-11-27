// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A video or a sound a sender asked this device to play itself (PROTOCOL.md, section 4b). Holds what to play and who
 * asked; {@link MediaActivity} plays it and reports back through {@link #state}. One at a time.
 */
final class MediaCast {
    static volatile MediaCast current;

    final MirrorSession.Control control;
    final String sender;
    final boolean direct;
    final String url;
    final String title;
    final Map<String, String> headers;
    final double start;
    final float rate;
    final boolean paused;
    final List<JSONObject> subs = new ArrayList<>();
    /** The sources this receiver may play, biggest first; the first is {@link #url}. The quality menu's choices. */
    final List<JSONObject> qualities = new ArrayList<>();
    /** Commands that came before the player was ready, applied once it is. */
    private final List<JSONObject> early = new ArrayList<>();
    private MediaActivity player;

    private MediaCast(MirrorSession.Control control, String sender, JSONObject msg, String url) {
        this.control = control;
        this.sender = sender;
        this.direct = !"sender".equals(msg.optString("via", "direct"));
        this.url = url;
        this.title = msg.optString("title");
        this.headers = MediaLink.headers(msg.optJSONObject("headers"));
        this.start = Math.max(0, msg.optDouble("start", 0));
        double r = msg.optDouble("rate", 1);
        this.rate = (float) (Double.isNaN(r) || r <= 0 ? 1 : Math.min(4, Math.max(0.25, r)));
        this.paused = msg.optBoolean("paused", false);
    }

    /**
     * Checks the message and every link in it, then opens the player. Runs on the connection's thread: resolving a
     * direct link's host may take a moment.
     */
    static void open(Context context, MirrorSession.Control control, String sender, JSONObject msg)
            throws MediaLink.BadLink, NoSource {
        int[] screen = screen(context);
        List<JSONObject> choices = MediaLink.choices(msg.optJSONArray("src"), Luch.decoders(), screen[0], screen[1]);
        if (choices.isEmpty()) throw new NoSource();
        boolean direct = !"sender".equals(msg.optString("via", "direct"));
        JSONObject src = choices.get(0);
        MediaLink.check(src.optString("url"), sender, direct, MediaLink.SYSTEM_DNS);
        MediaCast m = new MediaCast(control, sender, msg, src.optString("url"));
        m.qualities.add(src);
        for (JSONObject q : choices.subList(1, choices.size())) {
            try {
                MediaLink.check(q.optString("url"), sender, direct, MediaLink.SYSTEM_DNS);
                m.qualities.add(q);
            } catch (MediaLink.BadLink skip) {
                // a quality that may not be fetched is not offered
            }
        }
        JSONArray subs = msg.optJSONArray("subs");
        for (int i = 0; subs != null && i < subs.length() && i < 20; i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null) continue;
            try {
                MediaLink.check(s.optString("url"), sender, direct, MediaLink.SYSTEM_DNS);
                m.subs.add(s);
            } catch (MediaLink.BadLink skip) {
                // a subtitle link that may not be fetched is left out, the video still plays
            }
        }
        MediaCast old = current;
        current = m;
        if (old != null) {
            // Another sender took over: it is told. The same sender (another quality, from the same moment) is not.
            if (old.control != control) old.control.send(PhotoSession.stop("replaced"));
            MediaActivity a = old.player;
            if (a != null) a.runOnUiThread(() -> a.take(m));
        }
        if (old == null || old.player == null) {
            context.startActivity(new Intent(context, MediaActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
    }

    static final class NoSource extends Exception {
        NoSource() {
            super("no source this receiver can play");
        }
    }

    /**
     * The biggest picture worth fetching: the display's largest physical mode (a 4K TV whose UI runs at 1080p still
     * shows 4K video), or the AVC decoder's limit where the modes are unknown.
     */
    static int[] screen(Context context) {
        int w = 0, h = 0;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) {
                for (android.view.Display.Mode m : d.getSupportedModes()) {
                    if ((long) m.getPhysicalWidth() * m.getPhysicalHeight() > (long) w * h) {
                        w = m.getPhysicalWidth();
                        h = m.getPhysicalHeight();
                    }
                }
            }
        }
        if (w > 0) return new int[]{Math.max(w, h), Math.min(w, h)};
        String max = Luch.maxSize();
        int x = max == null ? -1 : max.indexOf('x');
        try {
            if (x > 0) return new int[]{Integer.parseInt(max.substring(0, x)), Integer.parseInt(max.substring(x + 1))};
        } catch (NumberFormatException ignored) {
        }
        return new int[]{1920, 1080};
    }

    /** A {@code media_cmd}: to the player, or kept until it is there. */
    static void command(MirrorSession.Control from, JSONObject cmd) {
        MediaCast m = current;
        if (m == null || m.control != from) return;
        synchronized (m) {
            MediaActivity a = m.player;
            if (a == null) {
                m.early.add(cmd);
                return;
            }
            a.runOnUiThread(() -> a.command(cmd, "sender"));
        }
    }

    /** The player is up; it gets the commands that waited. */
    List<JSONObject> attach(MediaActivity a) {
        synchronized (this) {
            player = a;
            List<JSONObject> out = new ArrayList<>(early);
            early.clear();
            return out;
        }
    }

    void detach(MediaActivity a) {
        synchronized (this) {
            if (player == a) player = null;
        }
    }

    void state(JSONObject s) {
        control.send(s);
    }

    /** The sender said stop, or its connection went. */
    static void closeFrom(MirrorSession.Control control) {
        MediaCast m = current;
        if (m == null || m.control != control) return;
        current = null;
        MediaActivity a = m.player;
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** The player ended it (Back, the end of the file, an error): tells the sender. */
    void endedHere(String reason) {
        if (current == this) current = null;
        try {
            control.send(new JSONObject().put("t", "stop").put("by", "receiver").put("reason", reason));
        } catch (JSONException ignored) {
        }
    }
}
