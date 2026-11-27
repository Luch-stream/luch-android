// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The row of buttons under a video (sound track, subtitles, quality, speed), each with what it is set to now. Left and
 * right move between them, OK opens one's list. Only what there is to choose is shown; speed always is, last.
 */
final class PlayerBar extends LinearLayout {
    static final class Choice {
        final String name, value;
        final Runnable open;

        Choice(String name, String value, Runnable open) {
            this.name = name;
            this.value = value;
            this.open = open;
        }
    }

    PlayerBar(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.END);
    }

    /** Shows {@code choices} and puts the focus on the last one (speed). */
    void show(List<Choice> choices) {
        removeAllViews();
        Context c = getContext();
        for (int i = 0; i < choices.size(); i++) {
            Choice ch = choices.get(i);
            TextView t = Ui.text(c, ch.name + "  ·  " + ch.value, 17, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            int padH = Ui.dp(c, 20), padV = Ui.dp(c, 10);
            t.setPadding(padH, padV, padH, padV);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, Ui.round(c, Ui.AMBER, 24, 0));
            bg.addState(new int[]{}, Ui.round(c, 0x99292A2F, 24, 0));
            t.setBackground(bg);
            t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{Ui.ON_AMBER, Ui.TEXT}));
            t.setFocusable(true);
            t.setOnClickListener(v -> ch.open.run());
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Ui.dp(c, 12);
            addView(t, lp);
        }
        setVisibility(VISIBLE);
        View last = getChildAt(getChildCount() - 1);
        if (last != null) last.requestFocus();
    }

    void hide() {
        View f = findFocus();
        if (f != null) f.clearFocus();
        setVisibility(GONE);
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** The label of the focused button, for tests. */
    String focusedLabel() {
        View f = findFocus();
        return f instanceof TextView ? ((TextView) f).getText().toString() : null;
    }
}
