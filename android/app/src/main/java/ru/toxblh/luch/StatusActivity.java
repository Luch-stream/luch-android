// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.util.Collections;
import java.util.List;

/**
 * What the receiver is doing, in Material 3 Expressive. Idle: it is ready, under which name and address, which senders are
 * nearby, two switches. While a picture is received: from whom, to this device, and the figures of the last second.
 */
public class StatusActivity extends Activity {
    private static final String DEMO_TV = "Living room TV";
    private static final Luch.Peer DEMO_SENDER = new Luch.Peer("demo", "Linux Laptop", "192.168.1.20");

    private LinearLayout content, root, right;
    private boolean wide, tvMode, focused;
    private LinearLayout nav;
    private QrView qr;
    private TextView whoView;
    private final java.util.List<android.animation.Animator> pulses = new java.util.ArrayList<>();
    private android.window.OnBackInvokedCallback backCb;
    private boolean backOn;
    /** 0 receive, 1 send. */
    private int tab;
    private boolean receivingNow, sendingNow;
    private GestureDetector swipe;
    private Luch.Receiver pending;
    private static final int REQUEST_CAPTURE = 77;
    private final Luch.Listener listener = () -> runOnUiThread(this::render);

    /** On a TV the text is a fifth larger: it is read from the sofa. */
    @Override
    protected void attachBaseContext(Context base) {
        // not Ui.isTv(base): the context is not attached yet and its system services fail on some Android versions
        int type = base.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        if (type == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
            c.fontScale = 1.1f;
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        tvMode = Ui.isTv(this);
        if (Luch.enabled(this)) LuchService.start(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false); // otherwise the scroll view itself takes the first focus and the first D-pad press is lost
        FrameLayout outer = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        if (tvMode) content.setPadding(dp(48), dp(28), dp(48), dp(32));
        else content.setPadding(pad, dp(20), pad, dp(20));
        root = content;
        outer.addView(content, new FrameLayout.LayoutParams(tvMode ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.min(dp(wide() ? 1040 : 560), getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(outer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);
        scroll.post(() -> openLink(getIntent()));
        // A sender's offer to open the screen comes as a notification (Android 13+ asks once).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openLink(intent);
    }

    /** A scanned code: ask, then show this screen on the device the code names. */
    private void openLink(Intent intent) {
        android.net.Uri u = intent == null ? null : intent.getData();
        if (u == null || !"luch".equals(u.getScheme()) || !"connect".equals(u.getHost())) return;
        setIntent(new Intent(this, StatusActivity.class));
        String host = u.getQueryParameter("host");
        String name = u.getQueryParameter("name");
        int port;
        try {
            port = Integer.parseInt(u.getQueryParameter("port"));
        } catch (RuntimeException e) {
            return;
        }
        if (host == null || host.isEmpty() || Sender.active()) return;
        if (name == null) name = host;
        Luch.Receiver known = null;
        for (Luch.Receiver r : Luch.receivers()) if (host.equals(r.address)) known = r;
        final Luch.Receiver target = known != null ? known : new Luch.Receiver(host, name, "", host, port, "");
        tab = 1;
        render();
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.link_title, target.name))
                .setMessage(getString(R.string.link_message, host))
                .setPositiveButton(R.string.show_btn, (d, w) -> startSend(target))
                .setNegativeButton(R.string.rename_cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Luch.addListener(listener);
        render();
        if (!SleepService.returnedTooSoon(this)) askSleepOnce();
    }

    /**
     * On a TV, once: offer to turn on the accessibility service that lets a computer put the TV to sleep, saying
     * what it is for before Android's own warning does.
     */
    private void askSleepOnce() {
        if (!tvMode || !SleepService.supported() || SleepService.enabled(this)) return;
        android.content.SharedPreferences prefs = getSharedPreferences("luch", MODE_PRIVATE);
        if (prefs.getBoolean("sleep_asked", false) || getIntent().getBooleanExtra("demo", false)) return;
        prefs.edit().putBoolean("sleep_asked", true).apply();
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.sleep_ask_title)
                .setMessage(R.string.sleep_ask_message)
                .setPositiveButton(R.string.sleep_ask_open, (d, w) -> SleepService.openSettings(this))
                .setNegativeButton(R.string.sleep_ask_later, null)
                .show();
    }

    @Override
    protected void onPause() {
        Luch.removeListener(listener);
        for (android.animation.Animator a : pulses) a.pause();
        super.onPause();
    }

    private void render() {
        // Demo values for store screenshots, so no real name or address is ever in one:
        //   adb shell am start -n ru.toxblh.luch/.StatusActivity --ez demo true
        boolean demo = getIntent().getBooleanExtra("demo", false);
        boolean tv = Ui.isTv(this);
        String name = demo ? DEMO_TV : Luch.name(this);
        List<Luch.Peer> connected = demo ? Collections.singletonList(DEMO_SENDER) : Luch.connected();
        List<Luch.Peer> nearby = demo ? Collections.singletonList(DEMO_SENDER) : Luch.nearby();
        MirrorSession session = MirrorSession.current;
        MirrorSession.Live live = demo || session == null ? null : session.live();
        boolean enabled = demo || Luch.enabled(this);

        for (android.animation.Animator a : pulses) a.cancel();
        pulses.clear();
        root.removeAllViews();
        content = root;
        if (!tvMode) header();
        boolean sending = Sender.active();
        if (live != null) tab = 0;
        else if (sending) tab = 1;
        receivingNow = live != null;
        sendingNow = sending;
        if (tvMode) tvTop(name); else tabs(live != null, sending);
        wide = wide();
        if (wide) {
            LinearLayout body = new LinearLayout(this);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setPadding(dp(tvMode ? 14 : 20), 0, 0, 0);
            body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (tvMode) bp.topMargin = dp(24);
            root.addView(body, bp);
            content = left;
        }
        if (tab == 1) {
            sendContent(sending, live != null);
        } else if (live != null) {
            statusLine(getString(R.string.state_live), Ui.clock(live.sinceMs));
            Luch.Peer from = connected.isEmpty() ? null : connected.get(0);
            content.addView(Ui.flowCard(this, from, live, tv, name), Ui.below(this, 0));
            split();
            content.addView(Ui.stats(this, live), Ui.below(this, wide ? 0 : 6));
            spacer();
            Button stop = Ui.button(this, getString(R.string.action_stop), true, R.drawable.ic_stop, v -> {
                MirrorSession s = MirrorSession.current;
                if (s != null) s.stopByReceiver(MirrorSession.BY_BACK);
            });
            content.addView(stop, pill(0));
            content.addView(Ui.button(this, getString(R.string.action_open), false, R.drawable.ic_fullscreen,
                    v -> startActivity(new Intent(this, MirrorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))), pill(12));
        } else {
            readyCard(name, demo ? "192.168.1.50" : address(), enabled, tv);
            if (tvMode) {
                View hero = content.getChildAt(content.getChildCount() - 1);
                hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ((LinearLayout) hero).setGravity(Gravity.CENTER);
                split();
                LinearLayout panel = Ui.card(this);
                right.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                content = panel;
                nearbyList(nearby, tv, name);
                spacer();
                settingsCard(enabled);
            } else {
                split();
                nearbyList(nearby, tv, name);
                spacer();
                settingsCard(enabled);
            }
            if (enabled) findMe();
        }
        updateButton(demo);
        rings(root);
        if (tvMode) {
            // After layout (before it the request is lost) and only if the redraw left nothing focused.
            nav.post(() -> {
                if (getCurrentFocus() == null) nav.getChildAt(tab).requestFocus();
            });
        }
        syncBack();
    }

    // ---- the two modes: receive and send ---------------------------------------------------------------------

    /** A segmented control under the header; the mode that cannot be entered now says why. */
    private void tabs(boolean receiving, boolean sending) {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(this, Color.TRANSPARENT, 24, Ui.OUTLINE));
        bar.setPadding(dp(1), dp(1), dp(1), dp(1));
        String[] names = {getString(R.string.tab_receive), getString(R.string.tab_send)};
        for (int i = 0; i < 2; i++) {
            final int mode = i;
            TextView t = Ui.text(this, names[i], 16, tab == i ? Ui.TEXT : Ui.MUTED, tab == i);
            t.setGravity(Gravity.CENTER);
            if (tab == i) t.setBackground(Ui.round(this, Ui.TILE, 23, 0));
            t.setOnClickListener(v -> switchTab(mode));
            bar.addView(t, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        content.addView(bar, Ui.below(this, 16));
    }

    /** Opens a mode, or says why it cannot be entered now; the tabs and the swipe both come here. */
    private void switchTab(int mode) {
        if (mode == tab) return;
        if (mode == 1 && receivingNow) {
            Toast.makeText(this, R.string.busy_receiving, Toast.LENGTH_SHORT).show();
        } else if (mode == 0 && sendingNow) {
            Toast.makeText(this, R.string.busy_sending, Toast.LENGTH_SHORT).show();
        } else {
            tab = mode;
            render();
        }
    }

    /** A swipe to the left opens the send tab, to the right the receive tab. Mostly horizontal and long enough, so scrolling is left alone. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (swipe == null) {
            final int far = dp(80);
            swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (a == null || b == null) return false;
                    float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                    if (Math.abs(dx) < far || Math.abs(dx) < 2 * Math.abs(dy) || Math.abs(vx) < 600) return false;
                    switchTab(dx < 0 ? 1 : 0);
                    return true;
                }
            });
        }
        swipe.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    private void sendContent(boolean sending, boolean receiving) {
        Sender.Session session = Sender.current;
        Sender.Live live = sending && session != null ? session.live : null;
        if (sending) {
            statusLine(getString(R.string.state_sending), live == null ? "" : Ui.clock(live.sinceMs));
            if (live == null) {
                content.addView(Ui.text(this, getString(R.string.connecting), 16, Ui.MUTED, false), Ui.below(this, 8));
            } else {
                LinearLayout card = Ui.card(this);
                card.addView(Ui.label(this, getString(R.string.flow_from)));
                card.addView(Ui.device(this, getString(Ui.kind(this) == 2 ? R.string.this_tv : Ui.kind(this) == 1 ? R.string.this_tablet : R.string.this_phone),
                        getString(R.string.from_screen, Luch.name(this)), Ui.isTv(this) ? R.drawable.ic_tv : R.drawable.ic_phone,
                        Ui.AMBER, Ui.ON_AMBER));
                LinearLayout mid = new LinearLayout(this);
                mid.setPadding(dp(12), dp(8), 0, dp(8));
                ImageView arrow = new ImageView(this);
                arrow.setImageResource(R.drawable.ic_arrow_down);
                arrow.setColorFilter(Ui.AMBER);
                mid.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
                LinearLayout cap = new LinearLayout(this);
                cap.setOrientation(LinearLayout.VERTICAL);
                cap.setPadding(dp(32), dp(8), 0, 0);
                cap.addView(Ui.text(this, live.codec + " " + live.res.replace('x', '\u00d7') + ", " + live.fps + " "
                        + getString(R.string.unit_fps), 18, Ui.AMBER, true));
                cap.addView(Ui.text(this, Ui.number("%.1f", live.mbps) + " " + getString(R.string.unit_mbit) + ", "
                        + (live.lost == 0 ? getString(R.string.no_loss) : getString(R.string.lost_n, live.lost)), 15, Ui.MUTED,
                        false));
                mid.addView(cap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                card.addView(mid);
                card.addView(Ui.label(this, getString(R.string.flow_to)));
                card.addView(Ui.device(this, live.peerName, live.peerAddress, Ui.senderIcon(live.peerName), Ui.TILE, Ui.TEXT));
                content.addView(card, Ui.below(this, 0));
                split();
                LinearLayout grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout a = new LinearLayout(this);
                a.addView(Ui.stat(this, getString(R.string.stat_latency), Ui.number("%.0f", live.delayMs), getString(R.string.unit_ms)),
                        Ui.statParams(this, true));
                a.addView(Ui.stat(this, getString(R.string.stat_sent), String.valueOf(live.fps), getString(R.string.unit_fps)),
                        Ui.statParams(this, false));
                LinearLayout b = new LinearLayout(this);
                b.addView(Ui.stat(this, getString(R.string.stat_network), Ui.number("%.0f", live.rttMs), getString(R.string.unit_rtt)),
                        Ui.statParams(this, true));
                b.addView(Ui.stat(this, getString(R.string.stat_encoder), live.encoder, ""), Ui.statParams(this, false));
                grid.addView(a);
                grid.addView(b);
                content.addView(grid, Ui.below(this, 6));
            }
            spacer();
            content.addView(Ui.button(this, getString(R.string.action_stop_send), true, R.drawable.ic_stop,
                    v -> Sender.stop()), pill(0));
            return;
        }
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.text(this, getString(Ui.kind(this) == 2 ? R.string.send_title_tv : Ui.kind(this) == 1 ? R.string.send_title_tablet : R.string.send_title), tvMode ? 32 : 22, Ui.TEXT, true));
        TextView sub = Ui.text(this, getString(receiving ? R.string.busy_receiving : R.string.send_sub), 15, Ui.MUTED, false);
        sub.setPadding(0, dp(8), 0, 0);
        if (tvMode) sub.setGravity(Gravity.CENTER);
        intro.addView(sub);
        if (tvMode) {
            intro.setGravity(Gravity.CENTER);
            intro.setPadding(dp(40), dp(24), dp(40), dp(24));
            content.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            split();
            LinearLayout panel = Ui.card(this);
            right.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content = panel;
        } else {
            content.addView(intro, Ui.below(this, 16));
        }
        TextView head = Ui.label(this, getString(R.string.where_to));
        head.setPadding(dp(8), dp(22), 0, dp(10));
        content.addView(head);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, tvMode ? Ui.CARD_HIGH : Ui.CARD, 28, 0));
        List<Luch.Receiver> list = Luch.receivers();
        if (list.isEmpty()) {
            TextView none = Ui.text(this, getString(R.string.no_receivers), 16, Ui.MUTED, false);
            none.setPadding(dp(24), dp(20), dp(24), dp(20));
            card.addView(none);
        }
        for (int i = 0; i < list.size(); i++) {
            Luch.Receiver r = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            row.addView(Ui.tile(this, Ui.senderIcon(r.name), Ui.TILE, Ui.TEXT, 48));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), 0, 0, 0);
            col.addView(Ui.text(this, r.name, 18, Ui.TEXT, false));
            col.addView(Ui.text(this, r.address, 15, Ui.MUTED, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView go = Ui.text(this, getString(R.string.show_btn), 15, receiving ? Ui.MUTED : Ui.ON_AMBER, true);
            go.setBackground(Ui.round(this, receiving ? Ui.CARD_HIGH : Ui.AMBER, 20, 0));
            go.setPadding(dp(16), dp(8), dp(16), dp(8));
            go.setOnClickListener(v -> {
                if (receiving) Toast.makeText(this, R.string.busy_receiving, Toast.LENGTH_SHORT).show();
                else startSend(r);
            });
            row.addView(go);
            card.addView(row);
            if (i < list.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                lp.leftMargin = dp(84);
                card.addView(line, lp);
            }
        }
        content.addView(card, Ui.below(this, 0));
        if (!tvMode) split();
        TextView vh = Ui.label(this, getString(R.string.video_label));
        vh.setPadding(dp(8), dp(22), 0, dp(10));
        content.addView(vh);
        LinearLayout chips = new LinearLayout(this);
        String[] keys = {"auto", "hevc", "avc"};
        String[] names = {getString(R.string.codec_auto), "HEVC", "H.264"};
        String chosen = codecPref();
        for (int i = 0; i < 3; i++) {
            final String key = keys[i];
            boolean on = key.equals(chosen);
            TextView c = Ui.text(this, names[i], 16, on ? Ui.TEXT : Ui.MUTED, on);
            c.setGravity(Gravity.CENTER);
            c.setBackground(on ? Ui.round(this, Ui.TILE, 12, 0) : Ui.round(this, Color.TRANSPARENT, 12, Ui.OUTLINE));
            c.setOnClickListener(v -> {
                getSharedPreferences("luch", MODE_PRIVATE).edit().putString("sender_codec", key).apply();
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            chips.addView(c, lp);
        }
        content.addView(chips, Ui.below(this, 0));
        spacer();
        LinearLayout sound = new LinearLayout(this);
        sound.setGravity(Gravity.CENTER_VERTICAL);
        sound.setBackground(Ui.round(this, tvMode ? Ui.CARD_HIGH : Ui.CARD, 28, 0));
        sound.setPadding(dp(20), dp(16), dp(20), dp(16));
        sound.addView(Ui.text(this, getString(R.string.sound_label), 17, Ui.TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (Build.VERSION.SDK_INT >= 29) {
            Switch sw = new Switch(this);
            sw.setTrackDrawable(getResources().getDrawable(R.drawable.switch_track));
            sw.setThumbDrawable(getResources().getDrawable(R.drawable.switch_thumb));
            sw.setChecked(getSharedPreferences("luch", MODE_PRIVATE).getBoolean("send_audio", true));
            sw.setOnCheckedChangeListener((b, on) -> getSharedPreferences("luch", MODE_PRIVATE).edit().putBoolean("send_audio", on).apply());
            sound.addView(sw);
        } else {
            sound.addView(Ui.text(this, getString(R.string.sound_android10), 15, Ui.MUTED, false));
        }
        content.addView(sound, Ui.below(this, 20));
    }

    private String codecPref() {
        return getSharedPreferences("luch", MODE_PRIVATE).getString("sender_codec", "auto");
    }

    /** Android asks once per session whether this app may record the screen; the answer starts the capture. */
    private void startSend(Luch.Receiver r) {
        pending = r;
        // the sound of the phone is captured under the microphone permission (Android 10+): asked once, here
        if (Build.VERSION.SDK_INT >= 29 && getSharedPreferences("luch", MODE_PRIVATE).getBoolean("send_audio", true)
                && checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.RECORD_AUDIO"}, REQUEST_AUDIO);
            return;
        }
        askCapture();
    }

    private static final int REQUEST_AUDIO = 79;

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_AUDIO && pending != null) askCapture(); // without it the picture goes alone
        if (request == REQUEST_NEARBY) Nearby.start(this);
    }

    private void askCapture() {
        android.media.projection.MediaProjectionManager m =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAPTURE || pending == null) return;
        Luch.Receiver r = pending;
        pending = null;
        if (result == RESULT_OK && data != null) {
            SenderService.start(this, result, data, r.id, codecPref());
        }
    }

    /** TV: logo, the two modes as big pills in the middle, the device name at the edge. */
    private void tvTop(String name) {
        // Three fixed slots: the pills stay in the middle of the screen whatever the name length is.
        FrameLayout bar = new FrameLayout(this);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(Ui.tile(this, R.drawable.ic_luch, Ui.TILE, Ui.AMBER, 48));
        TextView title = Ui.text(this, getString(R.string.app_title), 26, Ui.AMBER, true);
        title.setPadding(dp(14), 0, 0, 0);
        brand.addView(title);
        bar.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        nav = new LinearLayout(this);
        nav.setBackground(Ui.round(this, Color.TRANSPARENT, 28, Ui.OUTLINE));
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {getString(R.string.tab_receive), getString(R.string.tab_send)};
        for (int i = 0; i < 2; i++) {
            final int mode = i;
            TextView t = Ui.text(this, names[i], 20, tab == i ? Ui.TEXT : Ui.MUTED, tab == i);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.round(this, tab == i ? Ui.TILE : Color.TRANSPARENT, 24, 0));
            t.setOnClickListener(v -> switchTab(mode));
            t.setFocusableInTouchMode(true); // a TV can still be in touch mode after a tap; focus must land anyway
            // Left/right on the pills opens the neighbouring mode at once; focus stays on the pills.
            t.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN
                        || (key != android.view.KeyEvent.KEYCODE_DPAD_LEFT && key != android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    return false;
                }
                int to = key == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                if (to == tab) {
                    // Past the last pill: on to the name, which opens its editor.
                    if (to == 1 && whoView != null) whoView.requestFocus();
                    return true;
                }
                focused = false;
                switchTab(to);
                return true;
            });
            nav.addView(t, new LinearLayout.LayoutParams(dp(170), dp(48)));
        }
        bar.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        // A long name shrinks a little, then ends in dots.
        TextView who = Ui.text(this, name + " Luch", 17, Ui.MUTED, false);
        who.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        who.setSingleLine(true);
        who.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 26) who.setAutoSizeTextTypeUniformWithConfiguration(13, 17, 1, TypedValue.COMPLEX_UNIT_SP);
        whoView = who;
        who.setOnClickListener(v -> renameDialog());
        who.setBackground(Ui.round(this, Color.TRANSPARENT, 16, 0));
        who.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.addView(who, new FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(bar, Ui.below(this, 0));
    }

    /** The name other devices see. Typed with the on-screen keyboard (on a TV also by voice, through the keyboard's microphone). */
    private void renameDialog() {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(30)});
        field.setText(Luch.customName(this));
        field.setHint(Luch.systemName(this));
        field.setSelectAllOnFocus(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.rename_title)
                .setMessage(R.string.rename_message)
                .setView(box)
                .setPositiveButton(R.string.rename_save, (d, w) -> {
                    Luch.setCustomName(this, field.getText().toString());
                    render();
                })
                .setNegativeButton(R.string.rename_cancel, null);
        if (!Luch.customName(this).isEmpty()) {
            b.setNeutralButton(R.string.rename_reset, (d, w) -> {
                Luch.setCustomName(this, "");
                render();
            });
        }
        b.show();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && tvMode && nav != null && getCurrentFocus() == null) nav.getChildAt(tab).requestFocus();
    }

    /** Back goes one step: from "Send" to "Receive" first, only then out of the app. */
    private boolean backStep() {
        if (tab == 1 && !sendingNow) {
            switchTab(0);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (!backStep()) super.onBackPressed();
    }

    /** Android 13+: the callback exists only while there is a step to go back to, so the system keeps its own exit animation otherwise. */
    private void syncBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean need = tab == 1 && !sendingNow;
        if (need == backOn) return;
        backOn = need;
        if (backCb == null) backCb = () -> backStep();
        if (need) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCb);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCb);
    }

    /** A soft pulse around the icon while the receiver is on. */
    private void pulse(View v) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.16f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.16f),
                android.animation.PropertyValuesHolder.ofFloat("alpha", 1f, 0.45f));
        a.setDuration(1500);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.start();
        pulses.add(a);
    }

    private void header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.tile(this, R.drawable.ic_luch, Ui.TILE, Ui.AMBER, 48));
        TextView title = Ui.text(this, getString(R.string.app_title), 26, Ui.AMBER, true);
        title.setPadding(dp(16), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(Ui.TEXT);
        gear.setBackground(Ui.round(this, Ui.BUTTON_TONAL, 24, 0));
        gear.setContentDescription(getString(R.string.settings));
        gear.setOnClickListener(v -> renameDialog());
        row.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(row, Ui.below(this, 0));
    }

    private void statusLine(String label, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(22), dp(4), dp(14));
        View d = new View(this);
        d.setBackground(Ui.round(this, Ui.AMBER, 6, 0));
        row.addView(d, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView t = Ui.text(this, label, 22, Ui.TEXT, true);
        t.setPadding(dp(14), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clock = Ui.text(this, right, 16, Ui.MUTED, false);
        clock.setBackground(Ui.round(this, Ui.CARD_HIGH, 20, 0));
        clock.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.addView(clock);
        content.addView(row, Ui.below(this, 0));
    }

    private void readyCard(String name, String address, boolean enabled, boolean tv) {
        LinearLayout card = Ui.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        // a phone on its side is only about 400 dp high: the card gets tighter, and the address chips (the code says it all) go
        boolean compact = wide && !tvMode && getResources().getConfiguration().screenHeightDp < 480;
        if (compact) card.setPadding(dp(20), dp(12), dp(20), dp(12));
        else card.setPadding(dp(20), dp(32), dp(20), dp(28));
        FrameLayout halo = new FrameLayout(this);
        int ring = enabled ? Ui.AMBER : Ui.MUTED;
        halo.setBackground(Ui.round(this, enabled ? Ui.HALO_OUTER : Ui.CARD_HIGH, 48, 0));
        FrameLayout inner = new FrameLayout(this);
        inner.setBackground(Ui.round(this, enabled ? Ui.HALO_INNER : Ui.TILE, 32, 0));
        ImageView wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.ic_wifi);
        wifi.setColorFilter(ring);
        inner.addView(wifi, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        halo.addView(inner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        if (enabled) pulse(halo);
        if (wide && enabled && !address.equals("?")) {
            // the icon on the left, the code coming out of it to the right
            if (qr == null) qr = new QrView(this);
            if (qr.getParent() != null) ((ViewGroup) qr.getParent()).removeView(qr);
            qr.setPlain(false);
            qr.setText(link(name, address));
            // the beam starts at the icon's centre
            FrameLayout row = new FrameLayout(this);
            FrameLayout slot = new FrameLayout(this); // room for the pulse, which grows past the icon
            slot.addView(halo, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
            int slotSize = compact ? 108 : 124;
            FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(compact ? 92 : 112),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            qp.leftMargin = dp(slotSize / 2); // the beam starts at the icon's centre
            row.addView(qr, qp);
            row.addView(slot, new FrameLayout.LayoutParams(dp(slotSize), dp(slotSize), Gravity.START | Gravity.CENTER_VERTICAL)); // the icon over the beam
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            card.addView(halo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        }
        TextView big = Ui.text(this, enabled ? getString(R.string.state_ready_big) : getString(R.string.state_off), 30, Ui.TEXT, true);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, dp(compact ? 2 : 18), 0, dp(compact ? 2 : 8));
        if (compact) big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        card.addView(big);
        TextView sub = Ui.text(this, getString(Ui.kind(this) == 2 ? R.string.visible_as_tv : Ui.kind(this) == 1 ? R.string.visible_as_tablet : R.string.visible_as_phone), 16, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);
        TextView nm = Ui.text(this, name + " Luch", 19, Ui.TEXT, true);
        nm.setGravity(Gravity.CENTER);
        nm.setPadding(0, dp(4), 0, dp(compact ? 0 : 14));
        card.addView(nm);
        if (compact) {
            content.addView(card, Ui.below(this, 12));
            return;
        }
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        chips.addView(chip(address));
        chips.addView(chip(networkKind() + getString(R.string.port_n, Luch.PORT)));
        card.addView(chips);
        if (wide || !enabled || address.equals("?")) {
            content.addView(card, Ui.below(this, 20));
            return;
        }
        // a phone held upright: a tap turns the card over, the code is on its back
        TextView tap = Ui.text(this, getString(R.string.qr_tap), 13, Ui.MUTED, false);
        tap.setGravity(Gravity.CENTER);
        tap.setPadding(0, dp(14), 0, 0);
        card.addView(tap);
        LinearLayout back = qrBack(name, address);
        FrameLayout holder = new FrameLayout(this);
        float dist = getResources().getDisplayMetrics().density * 8000;
        card.setCameraDistance(dist);
        back.setCameraDistance(dist);
        holder.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        (flipped ? card : back).setVisibility(View.INVISIBLE);
        card.setOnClickListener(v -> flip(card, back));
        back.setOnClickListener(v -> flip(card, back));
        content.addView(holder, Ui.below(this, 20));
    }

    private boolean flipped;

    private LinearLayout qrBack(String name, String address) {
        LinearLayout b = Ui.card(this);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(20), dp(20), dp(20));
        if (qr == null) qr = new QrView(this);
        if (qr.getParent() != null) ((ViewGroup) qr.getParent()).removeView(qr);
        qr.setPlain(true);
        qr.setText(link(name, address));
        b.addView(qr, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView caption = Ui.text(this, getString(R.string.qr_scan), 16, Ui.MUTED, false);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(14), 0, dp(4));
        b.addView(caption);
        TextView nm = Ui.text(this, name + " Luch", 18, Ui.TEXT, true);
        nm.setGravity(Gravity.CENTER);
        b.addView(nm);
        TextView hint = Ui.text(this, getString(R.string.qr_back), 13, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        b.addView(hint);
        return b;
    }

    /** Turns the card over: the face turns edge-on, the other face comes round. */
    private void flip(View front, View back) {
        final View from = flipped ? back : front, to = flipped ? front : back;
        from.animate().rotationY(90f).setDuration(190).withEndAction(() -> {
            from.setVisibility(View.INVISIBLE);
            from.setRotationY(0f);
            to.setRotationY(-90f);
            to.setVisibility(View.VISIBLE);
            flipped = !flipped;
            if (flipped && qr != null) qr.replay();
            to.animate().rotationY(0f).setDuration(190).start();
        }).start();
    }

    /**
     * What the code says: a phone with Luch opens it, picks this device and starts showing its screen here; a
     * computer's camera reads it too (PROTOCOL.md 1e), and its one-time token pairs it.
     */
    private String link(String name, String address) { // name is not used any more, see below
        // short on purpose: fewer, bigger dots scan better; the name comes from the sender's own list when it knows the device
        return new Card(Card.RECEIVER, Luch.id(this), address, Luch.PORT, Card.Pairing.current()).link();
    }

    private TextView chip(String s) {
        TextView t = Ui.text(this, s, 16, Ui.TEXT, false);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setBackground(Ui.round(this, Color.TRANSPARENT, 20, Ui.OUTLINE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void nearbyList(List<Luch.Peer> peers, boolean tv, String name) {
        TextView head = Ui.label(this, getString(R.string.senders_nearby));
        head.setPadding(dp(8), dp(22), 0, dp(10));
        content.addView(head);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 28, 0));
        if (peers.isEmpty()) {
            TextView none = Ui.text(this, getString(R.string.nearby_none), 16, Ui.MUTED, false);
            none.setPadding(dp(24), dp(20), dp(24), dp(20));
            card.addView(none);
        }
        for (int i = 0; i < peers.size(); i++) {
            Luch.Peer p = peers.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            row.addView(Ui.tile(this, Ui.senderIcon(p.name), Ui.TILE, Ui.TEXT, 48));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), 0, 0, 0);
            col.addView(Ui.text(this, p.name, 18, Ui.TEXT, false));
            col.addView(Ui.text(this, p.address, 15, Ui.MUTED, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View dot = new View(this);
            dot.setBackground(Ui.round(this, Ui.MUTED, 4, 0));
            LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(dp(8), dp(8));
            dp8.rightMargin = dp(8);
            row.addView(dot, dp8);
            row.addView(Ui.text(this, getString(R.string.waiting), 16, Ui.MUTED, false));
            card.addView(row);
            if (i < peers.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                lp.leftMargin = dp(84);
                card.addView(line, lp);
            }
        }
        content.addView(card, Ui.below(this, 0));
        TextView hint = Ui.text(this, getString(tv ? R.string.hint_choose_tv : R.string.hint_choose, name), 15, Ui.MUTED, false);
        hint.setPadding(dp(8), dp(14), dp(8), 0);
        content.addView(hint);
    }

    /**
     * For a computer that cannot find this TV on the network (PROTOCOL.md 1): a line when one nearby is looking
     * (heard over Bluetooth), and the address said by sound for its microphone.
     */
    private void findMe() {
        if (System.currentTimeMillis() - Nearby.lookingAt < 120_000) {
            TextView t = Ui.text(this, getString(R.string.sender_looking), 15, Ui.AMBER, false);
            t.setPadding(dp(4), dp(16), dp(4), 0);
            content.addView(t);
        }
        content.addView(Ui.button(this, getString(R.string.action_say_address), false, v -> {
            if (!Nearby.say(this)) Toast.makeText(this, R.string.status_no_address, Toast.LENGTH_SHORT).show();
        }), pill(12));
        askBluetoothOnce();
    }

    /**
     * Android 12+ asks for "Nearby devices" before an app may advertise, and "direct" (PROTOCOL.md 4m) needs Wi-Fi
     * Direct's permission and Bluetooth's connect; each asked once, in one dialog, and the TV works without them.
     */
    private void askBluetoothOnce() {
        if (Build.VERSION.SDK_INT < Direct.MIN_SDK || getIntent().getBooleanExtra("demo", false)) return;
        android.content.SharedPreferences prefs = getSharedPreferences("luch", MODE_PRIVATE);
        java.util.List<String> ask = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && Nearby.NO_PERMISSION.equals(Nearby.unavailable(this))
                && !prefs.getBoolean("nearby_asked", false)) {
            prefs.edit().putBoolean("nearby_asked", true).apply();
            ask.add("android.permission.BLUETOOTH_ADVERTISE");
            ask.add("android.permission.BLUETOOTH_SCAN");
        }
        if (DirectGroup.worthAsking(this) && !prefs.getBoolean("direct_asked", false)) {
            prefs.edit().putBoolean("direct_asked", true).apply();
            for (String p : Direct.permissions(Build.VERSION.SDK_INT)) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) ask.add(p);
            }
        }
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQUEST_NEARBY);
    }

    private static final int REQUEST_NEARBY = 80;

    private void settingsCard(boolean enabled) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 28, 0));
        card.addView(switchRow(getString(R.string.setting_background), enabled, (b, on) -> {
            Luch.setEnabled(this, on);
            render();
        }));
        // On a TV the notification is only a fallback; nobody turns it off there.
        if (!tvMode) {
            View line = new View(this);
            line.setBackgroundColor(Ui.DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(dp(20), 0, dp(20), 0);
            card.addView(line, lp);
            card.addView(switchRow(getString(R.string.setting_offer), Luch.offerNotification(this),
                    (b, on) -> Luch.setOfferNotification(this, on)));
        }
        // Who may connect (PROTOCOL.md 3a): at home pairings stay; a guest's computer is forgotten at standby.
        Gate gate = ControlServer.gate(this);
        card.addView(divider());
        card.addView(switchRow(getString(R.string.setting_guest), gate.guest(), (b, on) -> gate.setGuest(on)));
        int paired = gate.paired().size();
        if (paired > 0) {
            card.addView(divider());
            View forget = Ui.text(this, getString(R.string.setting_paired, paired), 17, Ui.TEXT, false);
            forget.setPadding(dp(20), dp(14), dp(20), dp(14));
            forget.setFocusable(true);
            forget.setClickable(true);
            forget.setOnClickListener(v -> {
                gate.forgetAll();
                render();
            });
            card.addView(forget);
        }
        if (SleepService.supported()) {
            View line = new View(this);
            line.setBackgroundColor(Ui.DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(dp(20), 0, dp(20), 0);
            card.addView(line, lp);
            card.addView(sleepRow());
        }
        // Sync's acoustic calibration plays only when a sender asks; here its detector runs on a recording made up in
        // memory, silently.
        View line = new View(this);
        line.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(20), 0, dp(20), 0);
        card.addView(line, lp);
        card.addView(dryRunRow());
        card.addView(divider());
        card.addView(reportRow());
        content.addView(card, Ui.below(this, 20));
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(20), 0, dp(20), 0);
        line.setLayoutParams(lp);
        return line;
    }

    /** Runs the calibration's detector on a recording made up in memory, and says what it found. */
    private View dryRunRow() {
        TextView t = Ui.text(this, getString(R.string.calibration_run), 15, Ui.AMBER, true);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> new Thread(() -> {
            ru.toxblh.luch.sync.DryRun.Outcome o = ru.toxblh.luch.sync.DryRun.run(System.nanoTime());
            String text = o.accepted ? getString(R.string.calibration_measured, o.measuredMs, o.trueMs, o.psrDb)
                    : getString(R.string.calibration_refused, o.why);
            runOnUiThread(() -> t.setText(text));
        }, "luch-calibration-dry-run").start());
        return t;
    }

    /** One file with this device's log and what it runs on, for the person to send where they like (ProblemReport). */
    private View reportRow() {
        TextView t = Ui.text(this, getString(R.string.report_problem), 17, Ui.TEXT, false);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> ProblemReport.ask(this));
        return t;
    }

    /** Turning the TV off from a computer: on, or a way to the accessibility switch that turns it on. */
    private View sleepRow() {
        boolean on = SleepService.enabled(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.addView(Ui.text(this, getString(R.string.setting_sleep), 17, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(this, getString(on ? R.string.setting_sleep_on : R.string.setting_sleep_set_up), 15,
                on ? Ui.MUTED : Ui.AMBER, !on));
        // Also when on: the same page turns it off again.
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(v -> SleepService.openSettings(this));
        return row;
    }

    private View switchRow(String label, boolean on, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.addView(Ui.text(this, label, 17, Ui.TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setTrackDrawable(getResources().getDrawable(R.drawable.switch_track));
        sw.setThumbDrawable(getResources().getDrawable(R.drawable.switch_thumb));
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    /** Remote control: everything tappable can take focus and shows an amber ring while it has it. */
    private void rings(View v) {
        if (Build.VERSION.SDK_INT >= 23 && (v.hasOnClickListeners() || v instanceof CompoundButton)) {
            v.setFocusable(true);
            // The ring takes the corner radius of the view's own fill, so the two always match.
            float radius = dp(24);
            if (Build.VERSION.SDK_INT >= 24 && v.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                radius = ((android.graphics.drawable.GradientDrawable) v.getBackground()).getCornerRadius();
            }
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setCornerRadius(radius);
            ring.setStroke(dp(3), Ui.AMBER);
            v.setOnFocusChangeListener((x, on) -> x.setForeground(on ? ring : null));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) rings(g.getChildAt(i));
        }
    }

    /** Tablet and TV: the second half of the screen goes into the right column. */
    private void split() {
        if (wide) content = right;
    }

    private boolean wide() {
        return Ui.isTv(this) || getResources().getConfiguration().screenWidthDp >= 840;
    }

    private void spacer() {
        content.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private LinearLayout.LayoutParams pill(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        p.topMargin = dp(topDp);
        return p;
    }

    private void updateButton(boolean demo) {
        // Only in the GitHub build, when a newer release is out (Updater).
        Updater.Release r = demo ? null : Updater.available;
        if (r == null) return;
        Button b = Ui.button(this, Updater.installing ? getString(R.string.update_installing) : getString(R.string.update_to, r.version),
                false, v -> installUpdate());
        b.setEnabled(!Updater.installing);
        content.addView(b, pill(12));
    }

    private void installUpdate() {
        Updater.Release r = Updater.available;
        if (r == null) return;
        // Android 8+ asks per app whether it may install apps; before it, the system-wide "unknown sources" switch
        // applies and the installer itself says so.
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Android asks once whether Luch may install apps; some TVs have no page for it.
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                content.addView(Ui.text(this, getString(R.string.update_allow), 15, Ui.MUTED, false));
            }
            return;
        }
        Updater.start(this, r, true);
    }

    /** "Wi‑Fi, " or "Ethernet, " before the port, nothing when the kind of network is not known. */
    private String networkKind() {
        if (Build.VERSION.SDK_INT < 23) return "";
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(lan(cm));
        if (caps == null) return "";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi, ";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet, ";
        return "";
    }

    /**
     * The Wi‑Fi or Ethernet network senders reach us on. Not the active one: with a VPN on, that is the tunnel, and its
     * address is not where anyone on the LAN can find us.
     */
    static android.net.Network lan(ConnectivityManager cm) {
        for (android.net.Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return n;
            }
        }
        return cm.getActiveNetwork();
    }

    private String address() {
        if (Build.VERSION.SDK_INT < 23) return Compat.ipv4();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(lan(cm));
        if (lp != null) {
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        }
        return "?";
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
