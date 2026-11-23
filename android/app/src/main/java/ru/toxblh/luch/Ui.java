// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The look of the status screen and of the panel over the picture: Material 3 Expressive, dark, with an amber accent.
 * One palette and one set of builders for both.
 */
final class Ui {
    // Taken from the reference design (luch-material-expressive): Material 3 dark with an amber primary.
    static final int BG = 0xFF121318, CARD = 0xFF1E1F24, CARD_HIGH = 0xFF292A2F, BUTTON_TONAL = 0xFF292A2F, TILE = 0xFF3E4758,
            TEXT = 0xFFE3E2E8, MUTED = 0xFFC4C6D0, OUTLINE = 0xFF47484D, DIVIDER = 0xFF47484D, OUTLINE_BUTTON = 0xFF939499,
            AMBER = 0xFFFFBA5F, ON_AMBER = 0xFF472A00, AMBER_CONTAINER = 0xFF674100, HALO_OUTER = 0xFF302C29,
            HALO_INNER = 0xFF534333;

    private Ui() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        return m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** 0 phone, 1 tablet, 2 TV (a projector with Google TV reports itself as a TV too). */
    static int kind(Context c) {
        if (isTv(c)) return 2;
        return c.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 1 : 0;
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static String number(String format, double v) {
        return String.format(Locale.getDefault(), format, v);
    }

    static GradientDrawable round(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.getDefault()), 12, MUTED, false);
        t.setLetterSpacing(0.08f);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, CARD, 28, 0));
        int p = dp(c, 20);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams below(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    /** A pill button: filled amber, or an outline. {@code icon} 0 for none. */
    static Button button(Context c, String label, boolean primary, int icon, View.OnClickListener click) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int ink = primary ? ON_AMBER : TEXT;
        b.setTextColor(ink);
        b.setStateListAnimator(null);
        b.setBackground(round(c, primary ? AMBER : Color.TRANSPARENT, 28, primary ? 0 : OUTLINE_BUTTON));
        if (icon != 0) {
            android.graphics.drawable.Drawable d = c.getResources().getDrawable(icon).mutate();
            d.setColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN);
            int s = dp(c, 22);
            d.setBounds(0, 0, s, s);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 10));
            b.setPadding(dp(c, 24), 0, dp(c, 24), 0);
            b.setGravity(Gravity.CENTER);
        }
        b.setOnClickListener(click);
        return b;
    }

    static Button button(Context c, String label, boolean primary, View.OnClickListener click) {
        return button(c, label, primary, 0, click);
    }

    /** A row of choices, the chosen one filled; {@code pick} gets the index. */
    static LinearLayout chips(Context c, String[] labels, int chosen, java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == chosen;
            TextView t = text(c, labels[i], 16, on ? TEXT : MUTED, on);
            t.setGravity(Gravity.CENTER);
            t.setBackground(on ? round(c, TILE, 12, 0) : round(c, Color.TRANSPARENT, 12, OUTLINE));
            t.setOnClickListener(v -> pick.accept(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 44), 1);
            lp.setMargins(i == 0 ? 0 : dp(c, 4), 0, i == labels.length - 1 ? 0 : dp(c, 4), 0);
            row.addView(t, lp);
        }
        return row;
    }

    /** A rounded square with an icon in it. */
    static View tile(Context c, int icon, int fill, int ink, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(icon);
        v.setColorFilter(ink);
        v.setBackground(round(c, fill, 16, 0));
        int p = dp(c, sizeDp / 4);
        v.setPadding(p, p, p, p);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A device's icon by its name: a game console for a Steam Deck, a TV for a TV or a streamer, a laptop for the rest. */
    static int senderIcon(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("deck")) return R.drawable.ic_gamepad;
        if (n.contains("tv") || n.contains("streamer") || n.contains("bravia") || n.contains("shield")) return R.drawable.ic_tv;
        return R.drawable.ic_laptop;
    }

    static View device(Context c, String title, String sub, int icon, int tile, int ink) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 8), 0, 0);
        row.addView(tile(c, icon, tile, ink, 48));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 16), 0, 0, 0);
        col.addView(text(c, title, 18, TEXT, false));
        if (!sub.isEmpty()) col.addView(text(c, sub, 15, MUTED, false));
        row.addView(col);
        return row;
    }

    /** From whom, over what, to this device. */
    static View flowCard(Context c, Luch.Peer from, MirrorSession.Live live, boolean tv, String name) {
        LinearLayout card = card(c);
        card.addView(label(c, c.getString(R.string.flow_from)));
        String fromName = from == null ? c.getString(R.string.offer_someone) : from.name;
        card.addView(device(c, fromName, from == null ? "" : from.address, senderIcon(fromName), TILE, TEXT));
        LinearLayout mid = new LinearLayout(c);
        mid.setPadding(dp(c, 12), dp(c, 8), 0, dp(c, 8));
        ImageView arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_arrow_down);
        arrow.setColorFilter(AMBER);
        mid.addView(arrow, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        LinearLayout caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(dp(c, 32), dp(c, 8), 0, 0);
        caption.addView(text(c, String.format(Locale.getDefault(), "%s %s, %d %s", live.codec, live.res, live.shownFps,
                c.getString(R.string.unit_fps)), 18, AMBER, true));
        caption.addView(text(c, number("%.1f", live.recvMbps) + " " + c.getString(R.string.unit_mbit) + ", "
                + (live.lost == 0 ? c.getString(R.string.no_loss) : c.getString(R.string.lost_n, live.lost)), 15, MUTED, false));
        mid.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(mid);
        card.addView(label(c, c.getString(R.string.flow_to)));
        card.addView(device(c, c.getString(tv ? R.string.this_tv : R.string.this_phone),
                name + ", " + c.getString(R.string.on_screen), tv ? R.drawable.ic_tv : R.drawable.ic_phone, AMBER, ON_AMBER));
        return card;
    }

    /** Delay, frames shown, packets lost, decoder. */
    static View stats(Context c, MirrorSession.Live live) {
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout a = new LinearLayout(c);
        a.addView(stat(c, c.getString(R.string.stat_latency), number("%.0f", live.latencyMs), c.getString(R.string.unit_ms)),
                statParams(c, true));
        a.addView(stat(c, c.getString(R.string.stat_shown), String.valueOf(live.shownFps), c.getString(R.string.unit_fps)),
                statParams(c, false));
        LinearLayout b = new LinearLayout(c);
        b.addView(stat(c, c.getString(R.string.stat_lost), String.valueOf(live.lost), ""), statParams(c, true));
        b.addView(stat(c, c.getString(R.string.stat_decoder), live.decoder.replaceFirst("\\.decoder$", ""), ""),
                statParams(c, false));
        grid.addView(a);
        grid.addView(b);
        return grid;
    }

    static View stat(Context c, String name, String value, String unit) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, CARD, 24, 0));
        l.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
        l.addView(text(c, name, 15, MUTED, false));
        LinearLayout v = new LinearLayout(c);
        v.setGravity(Gravity.BOTTOM);
        v.setPadding(0, dp(c, 4), 0, 0);
        boolean big = value.length() <= 4;
        v.addView(text(c, value, big ? 36 : 20, TEXT, false));
        if (!unit.isEmpty()) {
            TextView u = text(c, unit, 16, MUTED, false);
            u.setPadding(dp(c, 6), 0, 0, dp(c, 5));
            v.addView(u);
        }
        l.addView(v);
        return l;
    }

    static LinearLayout.LayoutParams statParams(Context c, boolean left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(left ? 0 : dp(c, 6), dp(c, 6), left ? dp(c, 6) : 0, dp(c, 6));
        return p;
    }
}
