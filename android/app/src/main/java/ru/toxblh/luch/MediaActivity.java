// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays a {@link MediaCast} full screen with Media3's ExoPlayer: progressive files, HLS (MPEG-TS or fMP4, separate
 * sound tracks), DASH and Smooth Streaming, the player's own pick of quality; FFmpeg's sound decoders where the build
 * has them (not F-Droid's). The remote: OK or Play/Pause toggles, left/right skip, up shows the
 * controls, down (or Menu, or a long OK) the buttons for sound track, subtitles, quality and speed, Back stops. Every change goes back as {@code media_state}.
 */
@OptIn(markerClass = UnstableApi.class)
public class MediaActivity extends Activity implements SurfaceHolder.Callback, Player.Listener {
    private final BackGuard backGuard = new BackGuard();
    static final float[] RATES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final long REPORT_MS = 5000, OSD_MS = 3000, LOAD_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaCast cast;
    private ExoPlayer player;
    private boolean prepared, surfaceReady, ended;
    private String state = "loading";
    /** The player waits for data while it should play: told as "loading", so a sender stops moving its clock. */
    private boolean buffering;
    private float rate = 1f;
    private boolean wantPlaying = true;
    /** Where to start or come back to, in ms (a new video's {@code start}, or where it was when the screen went). */
    private long resumeAt;
    private int videoW, videoH;
    private String lastBy = "receiver";
    /** The labels of the subtitles the sender listed, by their ids. */
    private final Map<String, String> senderSubs = new HashMap<>();

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
        buffering = false;
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
        prepared = false;
        try {
            Map<String, String> headers = new HashMap<>(m.headers);
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true);
            String agent = headers.remove("User-Agent");
            if (agent != null) http.setUserAgent(agent);
            http.setDefaultRequestProperties(headers);
            // FFmpeg's sound decoders first where the build has them (DTS, TrueHD, AC-3 on a TV without the licence);
            // the picture always from the TV's own decoders: the library's software video decoder would be found as
            // well, and a TV's CPU cannot decode a film.
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
                @Override
                protected void buildVideoRenderers(Context context, int mode, MediaCodecSelector selector,
                        boolean fallback, Handler handler, VideoRendererEventListener listener, long joining,
                        ArrayList<Renderer> out) {
                    super.buildVideoRenderers(context, EXTENSION_RENDERER_MODE_OFF, selector, fallback, handler,
                            listener, joining, out);
                }
            }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true);
            ExoPlayer p = new ExoPlayer.Builder(this, renderers)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                    .build();
            player = p;
            p.addListener(this);
            p.setVideoSurfaceHolder(surface.getHolder());
            // The sender's subtitles start switched off, as a file's own do.
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            p.setPlaybackParameters(new PlaybackParameters(rate));
            p.setMediaItem(item(m));
            p.setPlayWhenReady(wantPlaying);
            p.prepare();
        } catch (RuntimeException e) {
            fail(String.valueOf(e));
        }
    }

    /** What to play: the chosen source with its kind (links often have no extension), and the sender's subtitles. */
    private MediaItem item(MediaCast m) {
        JSONObject q = currentQuality(m);
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(quality != null ? quality : m.url));
        String kind = MediaLink.streamMime(q.optString("mime"));
        if (kind != null) b.setMimeType(kind);
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        senderSubs.clear();
        for (int i = 0; i < m.subs.size(); i++) {
            JSONObject s = m.subs.get(i);
            String id = s.optString("id", "s" + i);
            String label = s.optString("label", s.optString("lang", "#" + (i + 1)));
            senderSubs.put(id, label);
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.optString("url")))
                    .setMimeType(MediaLink.subtitleMime(s.optString("mime"), s.optString("url")))
                    .setLanguage(s.optString("lang", null))
                    .setLabel(label)
                    .setId(id)
                    .build());
        }
        return b.setSubtitleConfigurations(subs).build();
    }

    // ---- what the player says -----------------------------------------------------------------------------

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        ExoPlayer p = player;
        if (p == null) return;
        switch (playbackState) {
            case Player.STATE_READY:
                buffering = false;
                message.setText("");
                if (!prepared) {
                    prepared = true;
                    // A live stream ignores where the page was in its own window of it.
                    if (resumeAt > 0 && !p.isCurrentMediaItemLive()) seek(resumeAt);
                    setState(wantPlaying ? "playing" : "paused");
                } else {
                    report(null);
                }
                break;
            case Player.STATE_BUFFERING:
                if (prepared) {
                    buffering = true;
                    message.setText(getString(R.string.media_loading));
                    report(null);
                }
                break;
            case Player.STATE_ENDED:
                if (ended) return;
                ended = true;
                lastBy = "receiver";
                setState("ended");
                main.postDelayed(() -> {
                    if (cast != null) cast.endedHere("ended");
                    finish();
                }, 1500);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPlayerError(PlaybackException e) {
        // A refused piece says which host and what status (the whole link may carry a token, so not the link).
        Throwable c = e.getCause();
        if (c instanceof HttpDataSource.InvalidResponseCodeException) {
            HttpDataSource.InvalidResponseCodeException h = (HttpDataSource.InvalidResponseCodeException) c;
            fail("HTTP " + h.responseCode + " from " + h.dataSpec.uri.getHost());
            return;
        }
        fail("player error " + e.getErrorCodeName() + (c == null || c.getMessage() == null ? "" : ": " + c.getMessage()));
    }

    @Override
    public void onVideoSizeChanged(VideoSize size) {
        if (size.width <= 0 || size.height <= 0) return;
        videoW = Math.round(size.width * size.pixelWidthHeightRatio);
        videoH = size.height;
        fit();
    }

    @Override
    public void onCues(CueGroup cues) {
        StringBuilder text = new StringBuilder();
        for (Cue c : cues.cues) {
            if (c.text == null) continue;
            if (text.length() > 0) text.append('\n');
            text.append(c.text);
        }
        subtitle.setText(text);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (subAfterSwitch != null && prepared) {
            String id = subAfterSwitch;
            subAfterSwitch = null;
            selectSub(id);
        } else {
            report(null);
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
        ExoPlayer p = player;
        if (p == null || !prepared || ended) return;
        p.setPlayWhenReady(on);
        setState(on ? "playing" : "paused");
    }

    private void seek(long ms) {
        ExoPlayer p = player;
        if (p == null || !prepared) {
            resumeAt = Math.max(0, ms);
            return;
        }
        // A live stream is not sought in: a page's player sends where it was in its own window of it.
        long dur = p.getDuration();
        if (p.isCurrentMediaItemLive() || dur == C.TIME_UNSET) return;
        long to = Math.max(0, Math.min(ms, dur - 500));
        p.seekTo(to);
        if (ended && to < dur - 1000) ended = false;
    }

    private void applyRate() {
        ExoPlayer p = player;
        if (p != null) p.setPlaybackParameters(new PlaybackParameters(rate));
    }

    /** The groups of one kind of track (audio or text), in the player's order: their index is our track id. */
    private List<Tracks.Group> groups(int type) {
        List<Tracks.Group> out = new ArrayList<>();
        ExoPlayer p = player;
        if (p == null) return out;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) if (g.getType() == type) out.add(g);
        return out;
    }

    /** A text group's id: the sender's own for a subtitle it listed, "t<n>" for one in the stream. */
    private String subId(Tracks.Group g, int n) {
        String id = g.getTrackFormat(0).id;
        if (id != null) {
            // Side-loaded tracks' ids come with the player's own prefix ("1:s0").
            String bare = id.substring(id.lastIndexOf(':') + 1);
            if (senderSubs.containsKey(bare)) return bare;
            if (senderSubs.containsKey(id)) return id;
        }
        return "t" + n;
    }

    private void selectAudio(String id) {
        ExoPlayer p = player;
        if (p == null || !prepared || id == null || !id.startsWith("a")) return;
        try {
            Tracks.Group g = groups(C.TRACK_TYPE_AUDIO).get(Integer.parseInt(id.substring(1)));
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
        } catch (RuntimeException e) {
            Log.w(LuchService.TAG, "audio track " + id + ": " + e);
        }
        report(null);
    }

    private void selectSub(String id) {
        ExoPlayer p = player;
        if (p == null || !prepared) return;
        subtitle.setText("");
        TrackSelectionParameters.Builder b = p.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        Tracks.Group chosen = null;
        List<Tracks.Group> text = groups(C.TRACK_TYPE_TEXT);
        for (int i = 0; id != null && i < text.size(); i++) if (id.equals(subId(text.get(i), i))) chosen = text.get(i);
        if (chosen == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(chosen.getMediaTrackGroup(), 0));
        }
        p.setTrackSelectionParameters(b.build());
        report(null);
    }

    // ---- reporting -------------------------------------------------------------------------------------------

    private void setState(String s) {
        state = s;
        report(null);
        showOsd();
    }

    private long position() {
        ExoPlayer p = player;
        return p == null || !prepared ? resumeAt : p.getCurrentPosition();
    }

    /** The length in ms; -1 when unknown, and for a live stream (its window is no length to show). */
    private long duration() {
        ExoPlayer p = player;
        if (p == null || !prepared || p.isCurrentMediaItemLive()) return -1;
        long d = p.getDuration();
        return d == C.TIME_UNSET ? -1 : d;
    }

    /** One {@code media_state} line (PROTOCOL.md section 4b) to the sender that started it. */
    JSONObject stateJson(String detail) throws JSONException {
        long dur = duration();
        boolean live = prepared && player != null && player.isCurrentMediaItemLive();
        JSONObject s = new JSONObject()
                .put("t", "media_state")
                .put("state", buffering && "playing".equals(state) ? "loading" : state)
                .put("pos", position() / 1000.0)
                .put("dur", live || dur <= 0 ? JSONObject.NULL : dur / 1000.0)
                .put("rate", (double) rate)
                .put("live", live)
                .put("by", lastBy);
        if (videoW > 0) s.put("w", videoW).put("h", videoH);
        JSONArray audio = new JSONArray(), subs = new JSONArray();
        String selAudio = null, selSub = null;
        if (player != null && prepared) {
            List<Tracks.Group> a = groups(C.TRACK_TYPE_AUDIO);
            for (int i = 0; i < a.size(); i++) {
                Format f = a.get(i).getTrackFormat(0);
                audio.put(new JSONObject().put("id", "a" + i).put("lang", lang(f)).put("label", trackLabel(f, i)));
                if (a.get(i).isSelected()) selAudio = "a" + i;
            }
            List<Tracks.Group> t = groups(C.TRACK_TYPE_TEXT);
            for (int i = 0; i < t.size(); i++) {
                Format f = t.get(i).getTrackFormat(0);
                String id = subId(t.get(i), i);
                String label = senderSubs.containsKey(id) ? senderSubs.get(id) : trackLabel(f, i);
                subs.put(new JSONObject().put("id", id).put("lang", lang(f)).put("label", label));
                if (t.get(i).isSelected()) selSub = id;
            }
        }
        s.put("tracks", new JSONObject().put("audio", audio).put("sub", subs));
        s.put("sel", new JSONObject().put("audio", selAudio == null ? JSONObject.NULL : selAudio)
                .put("sub", selSub == null ? JSONObject.NULL : selSub));
        if (detail != null) s.put("detail", detail);
        return s;
    }

    private static String lang(Format f) {
        String l = f.language;
        return l == null || "und".equals(l) ? "" : l;
    }

    private static String trackLabel(Format f, int i) {
        if (f.label != null && !f.label.isEmpty()) return f.label;
        String lang = lang(f);
        if (lang.isEmpty()) return "#" + i;
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
        subtitle.setText("");
        state = "loading";
        buffering = false;
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
        else player.setVideoSurfaceHolder(holder);
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
        ExoPlayer p = player;
        player = null;
        prepared = false;
        if (p != null) {
            p.removeListener(this);
            p.release();
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
