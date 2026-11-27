// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays a {@link MediaCast} full screen with the platform's MediaPlayer (no libraries in the APK): progressive files
 * and HLS, the receiver's own pick of quality. The remote: OK or Play/Pause toggles, left/right skip, up shows the
 * controls, down (or Menu, or a long OK) the buttons for sound track, subtitles, quality and speed, Back stops. Every change goes back as {@code media_state}.
 */
public class MediaActivity extends Activity implements SurfaceHolder.Callback {
    private final BackGuard backGuard = new BackGuard();
    static final float[] RATES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final long REPORT_MS = 5000, OSD_MS = 3000, LOAD_MS = 30_000;
    private static final int MAX_SUB_BYTES = 2 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaCast cast;
    private MediaPlayer player;
    private boolean prepared, surfaceReady, ended;
    private String state = "loading";
    private float rate = 1f;
    private boolean wantPlaying = true;
    /** Where to start or come back to, in ms (a new video's {@code start}, or where it was when the screen went). */
    private long resumeAt;
    private int videoW, videoH;
    private String lastBy = "receiver";
    /** Our ids of the subtitle tracks the sender listed, in the order they were added to the player. */
    private final List<String> externalSubs = new ArrayList<>();
    private final List<String> externalLabels = new ArrayList<>();
    private int firstExternal = -1;
    private int subTrack = -1;

    private FrameLayout root;
    private SurfaceView surface;
    /** The presenter's pointer and ink over the video (PROTOCOL.md 4h). */
    private InkView ink;
    private TextView subtitle, message, title, time, badge;
    private Bar bar;
    private LinearLayout osd;
    /** Seeking held on the remote: where it will go once the key is let go, or -1. */
    private long seekTarget = -1;
    private AlertDialog menu;
    private PlayerBar choices;
    /** The screen while it exists, for tests. */
    static volatile MediaActivity shown;
    /** The source playing when the quality menu picked another; null for the cast's own pick. */
    private String quality;
    /** A subtitle to switch on again once a new quality has loaded its subtitles. */
    private String subAfterSwitch;
    private static final long BAR_MS = 8000;
    private final Runnable closeBar = this::closeBar;

    private final Runnable reporter = new Runnable() {
        @Override
        public void run() {
            if ("playing".equals(state)) report(null);
            main.postDelayed(this, REPORT_MS);
        }
    };
    /** A link that never answers would leave "loading" up for ever. */
    private final Runnable loadTimeout = () -> {
        if ("loading".equals(state) && !prepared) fail("did not load in " + LOAD_MS / 1000 + " s");
    };
    private final Runnable hideOsd = () -> {
        if (seekTarget < 0 && "playing".equals(state) && !choices.isOpen()) {
            osd.animate().alpha(0f).setDuration(300).start();
        }
    };
    private final Runnable tickOsd = new Runnable() {
        @Override
        public void run() {
            updateOsd();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        subtitle = Ui.text(this, "", 26, Color.WHITE, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(6, 0, 2, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = Ui.dp(this, 64);
        root.addView(subtitle, sp);

        message = Ui.text(this, "", 22, Ui.MUTED, false);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        osd = new LinearLayout(this);
        osd.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 32);
        osd.setPadding(pad, pad, pad, pad);
        osd.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE0000000, 0x00000000}));
        title = Ui.text(this, "", 22, Ui.TEXT, true);
        osd.addView(title);
        bar = new Bar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18));
        bp.topMargin = Ui.dp(this, 12);
        osd.addView(bar, bp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        time = Ui.text(this, "", 16, Ui.MUTED, false);
        row.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        badge = Ui.text(this, "", 16, Ui.AMBER, true);
        row.addView(badge);
        osd.addView(row);
        choices = new PlayerBar(this);
        choices.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 16);
        osd.addView(choices, cp);
        root.addView(osd, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        ink = new InkView(this, out -> InkView.viewFrame(surface, ink, out), () -> {
            MediaCast c = MediaCast.current;
            return c == null ? null : c.control;
        });
        root.addView(ink, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        shown = this;

        MediaCast m = MediaCast.current;
        if (m == null) {
            finish();
            return;
        }
        // The app opts into the new back handling (Android 13+), where onBackPressed is no longer called.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        take(m);
        main.postDelayed(reporter, REPORT_MS);
        main.post(tickOsd);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        MediaCast m = MediaCast.current;
        if (m != null && m != cast) take(m);
    }

    /** Starts {@code m}, replacing whatever played. */
    void take(MediaCast m) {
        if (cast != null) cast.detach(this);
        cast = m;
        ink.reset();
        releasePlayer();
        externalSubs.clear();
        externalLabels.clear();
        firstExternal = -1;
        subTrack = -1;
        quality = null;
        subAfterSwitch = null;
        ended = false;
        rate = m.rate;
        wantPlaying = !m.paused;
        resumeAt = (long) (m.start * 1000);
        videoW = videoH = 0;
        title.setText(m.title);
        message.setText(m.title.isEmpty() ? getString(R.string.media_loading) : m.title + "\n" + getString(R.string.media_loading));
        subtitle.setText("");
        state = "loading";
        lastBy = "sender";
        report(null);
        showOsd();
        main.removeCallbacks(loadTimeout);
        main.postDelayed(loadTimeout, LOAD_MS);
        if (surfaceReady) open();
        for (JSONObject c : m.attach(this)) command(c, "sender");
    }

    private void open() {
        MediaCast m = cast;
        if (m == null || player != null) return;
        MediaPlayer p = new MediaPlayer();
        player = p;
        prepared = false;
        try {
            p.setDisplay(surface.getHolder());
            p.setScreenOnWhilePlaying(true);
            Map<String, String> headers = m.headers;
            p.setDataSource(this, Uri.parse(quality != null ? quality : m.url), headers);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                message.setText("");
                if (resumeAt > 0) seek(resumeAt);
                applyRate();
                if (wantPlaying) mp.start();
                setState(wantPlaying ? "playing" : "paused");
                loadSubs(m);
            });
            p.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoW = w;
                videoH = h;
                fit();
            });
            p.setOnCompletionListener(mp -> {
                if (mp != player || ended) return;
                ended = true;
                lastBy = "receiver";
                setState("ended");
                main.postDelayed(() -> {
                    if (cast != null) cast.endedHere("ended");
                    finish();
                }, 1500);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                fail("player error " + what + "/" + extra);
                return true;
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) message.setText(getString(R.string.media_loading));
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) message.setText("");
                return false;
            });
            p.setOnSeekCompleteListener(mp -> report(null));
            p.setOnTimedTextListener((mp, text) -> subtitle.setText(text == null ? "" : text.getText()));
            p.prepareAsync();
        } catch (Exception e) {
            fail(String.valueOf(e));
        }
    }

    private void fail(String detail) {
        Log.w(LuchService.TAG, "media: " + detail);
        message.setText(getString(R.string.media_failed));
        lastBy = "receiver";
        state = "error";
        report(detail);
        MediaCast m = cast;
        main.postDelayed(() -> {
            if (m != null && m == cast) m.endedHere("error");
            finish();
        }, 3000);
    }

    /** Fetches the sender's subtitles (WebVTT made SubRip) and adds them; they start switched off. */
    private void loadSubs(MediaCast m) {
        if (m.subs.isEmpty()) {
            report(null);
            return;
        }
        new Thread(() -> {
            List<String[]> files = new ArrayList<>();
            for (int i = 0; i < m.subs.size(); i++) {
                JSONObject s = m.subs.get(i);
                try {
                    String text = fetchText(s.optString("url"), m.headers);
                    if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1); // a byte order mark
                    boolean vtt = text.startsWith("WEBVTT")
                            || "text/vtt".equals(s.optString("mime"));
                    File f = new File(getCacheDir(), "sub" + i + ".srt");
                    boolean ass = text.contains("[Events]") && text.contains("Dialogue:");
                    String srt = ass ? MediaLink.assToSrt(text) : vtt ? MediaLink.vttToSrt(text) : text;
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(srt.getBytes(StandardCharsets.UTF_8));
                    }
                    String label = s.optString("label", s.optString("lang", "#" + (i + 1)));
                    files.add(new String[]{f.getPath(), s.optString("id", "s" + i), label});
                } catch (Exception e) {
                    Log.w(LuchService.TAG, "subtitle " + i + ": " + e);
                }
            }
            main.post(() -> {
                MediaPlayer p = player;
                if (p == null || cast != m || !prepared) return;
                firstExternal = trackCount();
                for (String[] f : files) {
                    try {
                        p.addTimedTextSource(f[0], MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);
                        externalSubs.add(f[1]);
                        externalLabels.add(f[2]);
                    } catch (Exception e) {
                        Log.w(LuchService.TAG, "subtitle add: " + e);
                    }
                }
                if (subAfterSwitch != null) {
                    selectSub(subAfterSwitch);
                    subAfterSwitch = null;
                }
                report(null);
            });
        }, "luch-subs").start();
    }

    private static String fetchText(String url, Map<String, String> headers) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_SUB_BYTES) throw new java.io.IOException("subtitles too big");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---- commands, from the sender or from the remote --------------------------------------------------------

    void command(JSONObject c, String by) {
        if ("state".equals(c.optString("do"))) { // a question, not a change: "by" stays who changed it last
            report(null);
            return;
        }
        lastBy = by;
        String what = c.optString("do");
        switch (what) {
            case "play":
                play(true);
                break;
            case "pause":
                play(false);
                break;
            case "toggle":
                play(!wantPlaying);
                break;
            case "seek":
                seek((long) (c.optDouble("pos", 0) * 1000));
                break;
            case "skip":
                seek(position() + (long) (c.optDouble("by", 0) * 1000));
                break;
            case "rate":
                rate = clampRate((float) c.optDouble("rate", 1));
                applyRate();
                report(null);
                break;
            case "audio":
                selectAudio(c.optString("id"));
                break;
            case "sub":
                selectSub(c.isNull("id") ? null : c.optString("id"));
                break;

            default:
                Log.i(LuchService.TAG, "media_cmd ignored: " + what);
        }
        showOsd();
    }

    private static float clampRate(float r) {
        return Float.isNaN(r) || r <= 0 ? 1f : Math.max(0.25f, Math.min(4f, r));
    }

    private void play(boolean on) {
        wantPlaying = on;
        MediaPlayer p = player;
        if (p == null || !prepared || ended) return;
        if (on) {
            p.start();
            applyRate();
        } else {
            p.pause();
        }
        setState(on ? "playing" : "paused");
    }

    private void seek(long ms) {
        MediaPlayer p = player;
        if (p == null || !prepared) {
            resumeAt = Math.max(0, ms);
            return;
        }
        long dur = p.getDuration();
        long to = Math.max(0, dur > 0 ? Math.min(ms, dur - 500) : ms);
        if (Build.VERSION.SDK_INT >= 26) p.seekTo(to, MediaPlayer.SEEK_CLOSEST);
        else p.seekTo((int) to);
        if (ended && to < dur - 1000) ended = false;
    }

    /** MediaPlayer starts playing when given a speed, so a paused video keeps its speed until it plays. */
    private void applyRate() {
        MediaPlayer p = player;
        if (p == null || !prepared || Build.VERSION.SDK_INT < 23 || !wantPlaying) return;
        try {
            PlaybackParams params = p.getPlaybackParams();
            p.setPlaybackParams(params.setSpeed(rate));
        } catch (RuntimeException e) {
            Log.w(LuchService.TAG, "speed " + rate + ": " + e);
            rate = 1f;
        }
    }

    private int trackCount() {
        try {
            return player.getTrackInfo().length;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void selectAudio(String id) {
        MediaPlayer p = player;
        if (p == null || !prepared || id == null || !id.startsWith("a")) return;
        try {
            p.selectTrack(Integer.parseInt(id.substring(1)));
        } catch (RuntimeException e) {
            Log.w(LuchService.TAG, "audio track " + id + ": " + e);
        }
        report(null);
    }

    private void selectSub(String id) {
        MediaPlayer p = player;
        if (p == null || !prepared) return;
        try {
            if (subTrack >= 0) p.deselectTrack(subTrack);
        } catch (RuntimeException ignored) {
        }
        subTrack = -1;
        subtitle.setText("");
        int index = subIndex(id);
        if (index >= 0) {
            try {
                p.selectTrack(index);
                subTrack = index;
            } catch (RuntimeException e) {
                Log.w(LuchService.TAG, "subtitle track " + id + ": " + e);
            }
        }
        report(null);
    }

    /** The player's track for one of our subtitle ids: the sender's own ids, or "t<n>" for one in the file. */
    private int subIndex(String id) {
        if (id == null) return -1;
        int ext = externalSubs.indexOf(id);
        if (ext >= 0) return firstExternal + ext;
        if (id.startsWith("t")) {
            try {
                return Integer.parseInt(id.substring(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    // ---- reporting -------------------------------------------------------------------------------------------

    private void setState(String s) {
        state = s;
        report(null);
        showOsd();
    }

    private long position() {
        MediaPlayer p = player;
        if (p == null || !prepared) return resumeAt;
        try {
            return p.getCurrentPosition();
        } catch (RuntimeException e) {
            return resumeAt;
        }
    }

    private long duration() {
        MediaPlayer p = player;
        if (p == null || !prepared) return -1;
        try {
            return p.getDuration();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** One {@code media_state} line (PROTOCOL.md section 4b) to the sender that started it. */
    JSONObject stateJson(String detail) throws JSONException {
        long dur = duration();
        boolean live = prepared && dur <= 0;
        JSONObject s = new JSONObject()
                .put("t", "media_state")
                .put("state", state)
                .put("pos", position() / 1000.0)
                .put("dur", live || dur <= 0 ? JSONObject.NULL : dur / 1000.0)
                .put("rate", (double) rate)
                .put("live", live)
                .put("by", lastBy);
        if (videoW > 0) s.put("w", videoW).put("h", videoH);
        JSONArray audio = new JSONArray(), subs = new JSONArray();
        String selAudio = null, selSub = null;
        MediaPlayer p = player;
        if (p != null && prepared) {
            try {
                MediaPlayer.TrackInfo[] tracks = p.getTrackInfo();
                int a = -1;
                try {
                    a = p.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                } catch (RuntimeException ignored) {
                }
                for (int i = 0; i < tracks.length; i++) {
                    MediaPlayer.TrackInfo t = tracks[i];
                    String lang = t.getLanguage();
                    if ("und".equals(lang)) lang = "";
                    if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        audio.put(new JSONObject().put("id", "a" + i).put("lang", lang).put("label", trackLabel(t, i)));
                        if (i == a) selAudio = "a" + i;
                    } else if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                        int ext = firstExternal >= 0 ? i - firstExternal : -1;
                        boolean ours = ext >= 0 && ext < externalSubs.size();
                        String id = ours ? externalSubs.get(ext) : "t" + i;
                        String label = ours ? externalLabels.get(ext) : trackLabel(t, i);
                        subs.put(new JSONObject().put("id", id).put("lang", lang).put("label", label));
                        if (i == subTrack) selSub = id;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(LuchService.TAG, "tracks: " + e);
            }
        }
        s.put("tracks", new JSONObject().put("audio", audio).put("sub", subs));
        s.put("sel", new JSONObject().put("audio", selAudio == null ? JSONObject.NULL : selAudio)
                .put("sub", selSub == null ? JSONObject.NULL : selSub));
        if (detail != null) s.put("detail", detail);
        return s;
    }

    private static String trackLabel(MediaPlayer.TrackInfo t, int i) {
        String lang = t.getLanguage();
        if (lang == null || lang.isEmpty() || "und".equals(lang)) return "#" + i;
        Locale l = new Locale(lang);
        String name = l.getDisplayLanguage();
        return name.isEmpty() ? lang : name;
    }

    private void report(String detail) {
        MediaCast m = cast;
        if (m == null) return;
        try {
            m.state(stateJson(detail));
        } catch (JSONException ignored) {
        }
    }

    // ---- the screen ----------------------------------------------------------------------------------------

    private void showOsd() {
        updateOsd();
        osd.animate().cancel();
        osd.setAlpha(1f);
        main.removeCallbacks(hideOsd);
        main.postDelayed(hideOsd, OSD_MS);
    }

    private void updateOsd() {
        long pos = seekTarget >= 0 ? seekTarget : position();
        long dur = duration();
        bar.set(dur > 0 ? (float) pos / dur : 0f);
        time.setText(dur > 0 ? clock(pos) + " / " + clock(dur) : clock(pos));
        String b = "";
        if ("paused".equals(state)) b = getString(R.string.media_paused);
        if (rate != 1f) b = (b.isEmpty() ? "" : b + "  ·  ") + speed(rate);
        badge.setText(b);
    }

    static String speed(float r) {
        return (r == (int) r ? String.valueOf((int) r) : String.valueOf(r)) + "×";
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The surface the size of the video, centred: black bars, never stretched. */
    private void fit() {
        if (videoW <= 0 || videoH <= 0) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) {
            root.post(this::fit);
            return;
        }
        float scale = Math.min((float) w / videoW, (float) h / videoH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.round(videoW * scale);
        lp.height = Math.round(videoH * scale);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    /** The buttons under the video, the focus on speed; they close by themselves when left alone. */
    private void openBar() {
        if (menu != null && menu.isShowing()) return;
        JSONObject st;
        try {
            st = stateJson(null);
        } catch (JSONException e) {
            return;
        }
        JSONArray audio = st.optJSONObject("tracks").optJSONArray("audio");
        JSONArray subs = st.optJSONObject("tracks").optJSONArray("sub");
        JSONObject sel = st.optJSONObject("sel");
        String selAudio = sel.isNull("audio") ? null : sel.optString("audio");
        String selSub = sel.isNull("sub") ? null : sel.optString("sub");
        List<PlayerBar.Choice> list = new ArrayList<>();
        if (audio != null && audio.length() > 1) {
            list.add(new PlayerBar.Choice(getString(R.string.media_audio_short), label(audio, selAudio, "—"),
                    () -> trackMenu(audio, "audio", selAudio, false)));
        }
        if (subs != null && subs.length() > 0) {
            list.add(new PlayerBar.Choice(getString(R.string.media_subs_short),
                    label(subs, selSub, getString(R.string.media_subs_off)), () -> trackMenu(subs, "sub", selSub, true)));
        }
        MediaCast m = cast;
        if (m != null && m.qualities.size() > 1) {
            list.add(new PlayerBar.Choice(getString(R.string.media_quality), MediaLink.qualityLabel(currentQuality(m)),
                    this::qualityMenu));
        }
        list.add(new PlayerBar.Choice(getString(R.string.media_speed_title), speed(rate), this::speedMenu));
        choices.show(list);
        showOsd();
        main.removeCallbacks(hideOsd);
        keepBar();
    }

    /** The focused button's label while the buttons are open, else null (for tests). */
    String focusedButton() {
        return choices.isOpen() ? choices.focusedLabel() : null;
    }

    private void keepBar() {
        main.removeCallbacks(closeBar);
        main.postDelayed(closeBar, BAR_MS);
    }

    private void closeBar() {
        main.removeCallbacks(closeBar);
        if (!choices.isOpen()) return;
        choices.hide();
        showOsd();
    }

    private static String label(JSONArray tracks, String id, String none) {
        for (int i = 0; tracks != null && id != null && i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (id.equals(t.optString("id"))) return t.optString("label");
        }
        return none;
    }

    private JSONObject currentQuality(MediaCast m) {
        String url = quality != null ? quality : m.url;
        for (JSONObject q : m.qualities) if (url.equals(q.optString("url"))) return q;
        return m.qualities.get(0);
    }

    private void qualityMenu() {
        MediaCast m = cast;
        if (m == null) return;
        String[] labels = new String[m.qualities.size()];
        JSONObject now = currentQuality(m);
        int chosen = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = MediaLink.qualityLabel(m.qualities.get(i));
            if (m.qualities.get(i) == now) chosen = i;
        }
        choose(getString(R.string.media_quality), labels, chosen, which -> switchQuality(m.qualities.get(which)));
    }

    /** Another source of the same video, from where it is now, playing or paused as it was, its subtitle kept. */
    private void switchQuality(JSONObject q) {
        MediaCast m = cast;
        String url = q.optString("url");
        if (m == null || url.equals(quality != null ? quality : m.url)) return;
        try {
            JSONObject sel = stateJson(null).optJSONObject("sel");
            subAfterSwitch = sel.isNull("sub") ? null : sel.optString("sub");
        } catch (JSONException e) {
            subAfterSwitch = null;
        }
        if (prepared) resumeAt = position();
        quality = url;
        releasePlayer();
        externalSubs.clear();
        externalLabels.clear();
        firstExternal = -1;
        subTrack = -1;
        subtitle.setText("");
        state = "loading";
        message.setText(getString(R.string.media_loading));
        main.removeCallbacks(loadTimeout);
        main.postDelayed(loadTimeout, LOAD_MS);
        report(null);
        if (surfaceReady) open();
    }

    /** What a list does with the row picked (java.util.function is Android 7+). */
    private interface Pick {
        void at(int index);
    }

    /** One list to pick from, the current one marked; the bar closes once something is picked. */
    private void choose(String title, String[] labels, int chosen, Pick pick) {
        main.removeCallbacks(closeBar);
        menu = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setSingleChoiceItems(labels, chosen, (d, which) -> {
                    pick.at(which);
                    d.dismiss();
                    closeBar();
                })
                .setOnCancelListener(d -> keepBar())
                .create();
        menu.show();
    }

    private void speedMenu() {
        String[] labels = new String[RATES.length];
        int chosen = 2;
        for (int i = 0; i < RATES.length; i++) {
            labels[i] = speed(RATES[i]);
            if (RATES[i] == rate) chosen = i;
        }
        choose(getString(R.string.media_speed_title), labels, chosen, which -> {
            try {
                command(new JSONObject().put("do", "rate").put("rate", (double) RATES[which]), "receiver");
            } catch (JSONException ignored) {
            }
        });
    }

    private void trackMenu(JSONArray tracks, String kind, String selected, boolean offItem) {
        List<String> labels = new ArrayList<>(), ids = new ArrayList<>();
        if (offItem) {
            labels.add(getString(R.string.media_subs_off));
            ids.add(null);
        }
        int chosen = 0;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            labels.add(t.optString("label"));
            ids.add(t.optString("id"));
            if (t.optString("id").equals(selected)) chosen = labels.size() - 1;
        }
        choose(getString(kind.equals("audio") ? R.string.media_audio : R.string.media_subs),
                labels.toArray(new String[0]), chosen, which -> {
                    try {
                        JSONObject c = new JSONObject().put("do", kind);
                        c.put("id", ids.get(which) == null ? JSONObject.NULL : ids.get(which));
                        command(c, "receiver");
                    } catch (JSONException ignored) {
                    }
                });
    }

    // ---- the remote ----------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (choices.isOpen()) {
            keepBar();
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return false; // the focus moves between the buttons
                case KeyEvent.KEYCODE_DPAD_UP:
                    closeBar();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                default:
                    break;
            }
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                e.startTracking();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                local("toggle", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                local("play", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                local("pause", 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                holdSeek(-1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                holdSeek(1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showOsd();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                openBar();
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    @Override
    public boolean onKeyLongPress(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            openBar();
            return true;
        }
        return super.onKeyLongPress(key, e);
    }

    @Override
    public boolean onKeyUp(int key, KeyEvent e) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!e.isCanceled() && (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) == 0) local("toggle", 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (seekTarget >= 0) {
                    long to = seekTarget;
                    seekTarget = -1;
                    try {
                        command(new JSONObject().put("do", "seek").put("pos", to / 1000.0), "receiver");
                    } catch (JSONException ignored) {
                    }
                }
                return true;
            default:
                return super.onKeyUp(key, e);
        }
    }

    /**
     * Left or right: 10 s a press; held, the target moves on (10 s steps, 30 s after two seconds) and the seek
     * happens once, when the key is let go.
     */
    private void holdSeek(int dir, int repeat) {
        if (seekTarget < 0) seekTarget = position();
        if (repeat == 0 || repeat % 3 == 0) {
            long step = repeat > 40 ? 30_000 : 10_000;
            long dur = duration();
            seekTarget = Math.max(0, seekTarget + dir * step);
            if (dur > 0) seekTarget = Math.min(seekTarget, dur - 1000);
        }
        showOsd();
    }

    private void local(String what, double by) {
        try {
            command(new JSONObject().put("do", what).put("by", by), "receiver");
        } catch (JSONException ignored) {
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (osd.getAlpha() < 0.5f) showOsd();
            else local("toggle", 0);
            return true;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        back();
    }

    /** Back on the remote: the sender is told, and the video stops. */
    private void back() {
        if (menu != null && menu.isShowing()) {
            menu.dismiss();
            return;
        }
        if (choices.isOpen()) {
            closeBar();
            return;
        }
        if (!backGuard.confirm(this)) return;
        MediaCast m = cast;
        if (m != null) m.endedHere("back");
        cast = null;
        finish();
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (player == null) open();
        else player.setDisplay(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    /** The screen went (Home, the TV asleep): where it was is kept, and it goes on, paused, when it comes back. */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (player != null && !isFinishing()) {
            resumeAt = position();
            if (!"loading".equals(state)) {
                wantPlaying = false;
                lastBy = "receiver";
                state = "paused";
                report(null);
            }
        }
        releasePlayer();
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        prepared = false;
        if (p != null) {
            try {
                p.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        main.removeCallbacksAndMessages(null);
        if (menu != null) menu.dismiss();
        releasePlayer();
        MediaCast m = cast;
        if (m != null) {
            m.detach(this);
            if (MediaCast.current == m && isFinishing()) m.endedHere("screen_closed");
        }
        super.onDestroy();
    }

    /** The progress line: played part in amber over a dim track. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), done = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float part;

        Bar(Context c) {
            super(c);
            track.setColor(0x66FFFFFF);
            done.setColor(Ui.AMBER);
        }

        void set(float p) {
            float v = Math.max(0, Math.min(1, p));
            if (v != part) {
                part = v;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight() / 3f, y = (getHeight() - h) / 2f, r = h / 2f;
            c.drawRoundRect(0, y, getWidth(), y + h, r, r, track);
            c.drawRoundRect(0, y, getWidth() * part, y + h, r, r, done);
            // The knob whole at either end: its centre no nearer an edge than its radius.
            float knob = getHeight() / 2f;
            c.drawCircle(Math.max(knob, Math.min(getWidth() - knob, getWidth() * part)), knob, knob, done);
        }
    }
}
