// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * The small things a sender asks of the TV besides a picture (PROTOCOL.md 4d-4f): what plays on the sender, the
 * remote's media keys back to it, the TV's volume, and the TV's own apps.
 */
final class Remote {
    private Remote() {
    }

    // ---- now playing (4d) ---------------------------------------------------------------------------------------

    /** The last {@code now_playing} of the session's sender, or null. */
    static volatile JSONObject nowPlaying;

    static void nowPlaying(JSONObject msg) {
        nowPlaying = msg;
        NowPlayingActivity.refresh();
    }

    /** The {@code media_key} name for a key of the remote, or null when it is not a media key. */
    static String mediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return "play_pause";
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "pause";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "previous";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return "seek_forward";
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return "seek_back";
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "stop";
            default:
                return null;
        }
    }

    /** Sends {@code key} to the sender of the running session; false when there is none. */
    static boolean sendKey(String key) {
        MirrorSession s = MirrorSession.current;
        if (s == null || key == null) return false;
        try {
            s.control.send(new JSONObject().put("t", "media_key").put("key", key));
        } catch (JSONException e) {
            return false;
        }
        return true;
    }

    // ---- volume (4e) ------------------------------------------------------------------------------------------

    /** Percent of {@code max}, rounded; the inverse of {@link #steps}. */
    static int percent(int index, int max) {
        return max <= 0 ? 0 : Math.round(index * 100f / max);
    }

    /** The volume step for a percentage, never 0 for a non-zero percentage. */
    static int steps(int percent, int max) {
        int p = Math.max(0, Math.min(100, percent));
        int n = Math.round(p * max / 100f);
        return p > 0 && n == 0 ? 1 : n;
    }

    /** {"level","muted"}, or null when apps may not set the volume here. */
    static JSONObject volume(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || (android.os.Build.VERSION.SDK_INT >= 21 && am.isVolumeFixed())) return null;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean muted = android.os.Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC);
        try {
            return new JSONObject().put("level", percent(am.getStreamVolume(AudioManager.STREAM_MUSIC), max))
                    .put("muted", muted);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Applies a {@code volume} message; returns the volume after it. */
    static JSONObject setVolume(Context c, JSONObject msg) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int flags = AudioManager.FLAG_SHOW_UI; // the TV's own volume bar shows the change
        if (msg.has("level")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, steps(msg.optInt("level"), max), flags);
        }
        if (msg.has("step")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    msg.optInt("step") > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, flags);
        }
        if (msg.has("muted") && android.os.Build.VERSION.SDK_INT >= 23) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    msg.optBoolean("muted") ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, flags);
        }
        return volume(c);
    }

    // ---- the TV's own apps (4f) ---------------------------------------------------------------------------------

    /** Packages that play YouTube links, the TV app first. */
    static final String[] YOUTUBE = {"com.google.android.youtube.tv", "com.google.android.youtube",
            "com.google.android.apps.youtube.unplugged"};

    static String installed(Context c, String[] packages) {
        PackageManager pm = c.getPackageManager();
        for (String p : packages) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** The {@code apps} of the welcome. */
    static JSONArray apps(Context c) {
        JSONArray a = new JSONArray();
        if (installed(c, YOUTUBE) != null) a.put("youtube");
        return a;
    }

    /** A YouTube video id as it may come: letters, digits, '-' and '_', 6 to 20 of them. */
    static boolean youtubeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{6,20}");
    }

    /** The link the YouTube app opens: the watch page, with the start time when there is one. */
    static String youtubeLink(String id, double start) {
        String s = "https://www.youtube.com/watch?v=" + id;
        if (start >= 1) s += "&t=" + (long) start + "s";
        return s;
    }

    /** Opens {@code open_app}; returns null on success, else the protocol's error code. */
    static String openApp(Context c, JSONObject msg) {
        if (!"youtube".equals(msg.optString("app"))) return "no_app";
        String id = msg.optString("id");
        if (!youtubeId(id)) return "bad_id";
        String pkg = installed(c, YOUTUBE);
        if (pkg == null) return "no_app";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(youtubeLink(id, msg.optDouble("start", 0))))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return null;
        } catch (RuntimeException e) {
            return "no_app";
        }
    }
}
