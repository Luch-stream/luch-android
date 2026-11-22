// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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
import android.view.Gravity;
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

    private LinearLayout content;
    /** 0 receive, 1 send. */
    private int tab;
    private Luch.Receiver pending;
    private static final int REQUEST_CAPTURE = 77;
    private final Luch.Listener listener = () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Luch.enabled(this)) LuchService.start(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        FrameLayout outer = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(20), pad, dp(20));
        outer.addView(content, new FrameLayout.LayoutParams(Math.min(dp(560), getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(outer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);
        // A sender's offer to open the screen comes as a notification (Android 13+ asks once).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Luch.addListener(listener);
        render();
    }

    @Override
    protected void onPause() {
        Luch.removeListener(listener);
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

        content.removeAllViews();
        header();
        boolean sending = Sender.active();
        if (live != null) tab = 0;
        else if (sending) tab = 1;
        tabs(live != null, sending);
        if (tab == 1) {
            sendContent(sending, live != null);
        } else if (live != null) {
            statusLine(getString(R.string.state_live), Ui.clock(live.sinceMs));
            Luch.Peer from = connected.isEmpty() ? null : connected.get(0);
            content.addView(Ui.flowCard(this, from, live, tv, name), Ui.below(this, 0));
            content.addView(Ui.stats(this, live), Ui.below(this, 6));
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
            nearbyList(nearby, tv, name);
            spacer();
            settingsCard(enabled);
        }
        updateButton(demo);
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
            t.setOnClickListener(v -> {
                if (mode == 1 && receiving) {
                    Toast.makeText(this, R.string.busy_receiving, Toast.LENGTH_SHORT).show();
                } else if (mode == 0 && sending) {
                    Toast.makeText(this, R.string.busy_sending, Toast.LENGTH_SHORT).show();
                } else {
                    tab = mode;
                    render();
                }
            });
            bar.addView(t, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        content.addView(bar, Ui.below(this, 16));
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
                card.addView(Ui.device(this, getString(Ui.isTv(this) ? R.string.this_tv : R.string.this_phone),
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
        intro.addView(Ui.text(this, getString(Ui.isTv(this) ? R.string.send_title_tablet : R.string.send_title), 22, Ui.TEXT, true));
        TextView sub = Ui.text(this, getString(receiving ? R.string.busy_receiving : R.string.send_sub), 15, Ui.MUTED, false);
        sub.setPadding(0, dp(4), 0, 0);
        intro.addView(sub);
        content.addView(intro, Ui.below(this, 16));
        TextView head = Ui.label(this, getString(R.string.where_to));
        head.setPadding(dp(8), dp(22), 0, dp(10));
        content.addView(head);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 28, 0));
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
        sound.setBackground(Ui.round(this, Ui.CARD, 28, 0));
        sound.setPadding(dp(20), dp(16), dp(20), dp(16));
        sound.addView(Ui.text(this, getString(R.string.sound_label), 17, Ui.TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sound.addView(Ui.text(this, getString(R.string.soon), 15, Ui.MUTED, false));
        content.addView(sound, Ui.below(this, 20));
    }

    private String codecPref() {
        return getSharedPreferences("luch", MODE_PRIVATE).getString("sender_codec", "auto");
    }

    /** Android asks once per session whether this app may record the screen; the answer starts the capture. */
    private void startSend(Luch.Receiver r) {
        pending = r;
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
        gear.setOnClickListener(v -> Toast.makeText(this, R.string.settings_soon, Toast.LENGTH_SHORT).show());
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
        card.setPadding(dp(20), dp(32), dp(20), dp(28));
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
        card.addView(halo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView big = Ui.text(this, enabled ? getString(R.string.state_ready_big) : getString(R.string.state_off), 30, Ui.TEXT, true);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, dp(18), 0, dp(8));
        card.addView(big);
        TextView sub = Ui.text(this, getString(tv ? R.string.visible_as_tv : R.string.visible_as_phone), 16, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);
        TextView nm = Ui.text(this, name + " Luch", 19, Ui.TEXT, true);
        nm.setGravity(Gravity.CENTER);
        nm.setPadding(0, dp(4), 0, dp(14));
        card.addView(nm);
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        chips.addView(chip(address));
        chips.addView(chip(networkKind() + getString(R.string.port_n, Luch.PORT)));
        card.addView(chips);
        content.addView(card, Ui.below(this, 20));
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

    private void settingsCard(boolean enabled) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 28, 0));
        card.addView(switchRow(getString(R.string.setting_background), enabled, (b, on) -> {
            Luch.setEnabled(this, on);
            render();
        }));
        View line = new View(this);
        line.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(20), 0, dp(20), 0);
        card.addView(line, lp);
        card.addView(switchRow(getString(R.string.setting_offer), Luch.offerNotification(this),
                (b, on) -> Luch.setOfferNotification(this, on)));
        content.addView(card, Ui.below(this, 20));
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
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return "";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi, ";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet, ";
        return "";
    }

    private String address() {
        if (Build.VERSION.SDK_INT < 23) return Compat.ipv4();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
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
