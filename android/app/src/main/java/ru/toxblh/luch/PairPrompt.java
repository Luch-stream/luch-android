// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A sender the TV does not know yet, asking in a small card in the corner, over whatever plays: nothing pauses, and
 * the card takes the remote's keys only while it is up. It asks "allow?" first while something plays (so a stranger
 * cannot flash a code over a film), or shows the code straight away when the TV is idle; Back closes it, which is not
 * a refusal. Without the "display over other apps" permission the same question comes as a notification.
 */
final class PairPrompt {
    interface Answer {
        void allow();

        void deny();

        /** Back, or the card was closed another way: not counted as a refusal. */
        void close();
    }

    /** The prompt on screen, for tests to answer. */
    static volatile PairPrompt current;

    private static final String CHANNEL = "luch-pair";
    private static final String ACTION = "ru.toxblh.luch.PAIR_ANSWER";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final String name;
    /** An older sender: no TLS, so no code; "allow" lets this one connection in. */
    private final boolean unencrypted;
    private final Answer answer;
    private volatile String code;
    private volatile boolean answered;
    /** A question of its own instead of the pairing text (another computer wants the screen); allow or deny only. */
    private String question;

    private WindowManager wm;
    private View root;
    private TextView body, digits;
    private Button allowButton, denyButton;
    private BroadcastReceiver actions;
    /** In the notification's intents only: before Android 13 any app could send the action, but not this. */
    private final String secret = Long.toHexString(new java.security.SecureRandom().nextLong());

    private PairPrompt(Context context, String name, boolean unencrypted, String code, Answer answer) {
        this.context = context.getApplicationContext();
        this.name = name;
        this.unencrypted = unencrypted;
        this.code = code;
        this.answer = answer;
    }

    /** Shows the card; {@code code} null asks "allow?" first. Returns once it is up (any thread). */
    static PairPrompt show(Context context, String name, boolean unencrypted, String code, Answer answer) {
        PairPrompt p = new PairPrompt(context, name, unencrypted, code, answer);
        onMain(p::attach);
        current = p;
        return p;
    }

    /** Asks {@code question} about {@code name} with Allow and Deny, the focus on Deny; no code follows. */
    static PairPrompt ask(Context context, String name, String question, Answer answer) {
        PairPrompt p = new PairPrompt(context, name, true, null, answer);
        p.question = question;
        onMain(p::attach);
        current = p;
        return p;
    }

    /** Allowed: the code to type on the sender, in the same card. */
    void showCode(String c) {
        code = c;
        answered = false;
        onMain(this::render);
    }

    void dismiss() {
        if (current == this) current = null;
        onMain(this::detach);
    }

    String code() {
        return code;
    }

    /** Whether it is waiting for allow / deny rather than showing a code. */
    boolean asking() {
        return code == null;
    }

    String name() {
        return name;
    }

    // ---- what the buttons do; tests press them too ----

    void pressAllow() {
        if (answered || code != null) return;
        answered = true;
        answer.allow();
    }

    void pressDeny() {
        if (answered) return;
        answered = true;
        dismiss();
        answer.deny();
    }

    void pressBack() {
        if (answered) return;
        answered = true;
        dismiss();
        answer.close();
    }

    // ---- the card ----

    private static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    @SuppressWarnings("deprecation")   // TYPE_PHONE: the overlay type before Android 8
    private void attach() {
        if (!canOverlay(context)) {
            notifyInstead();
            return;
        }
        wm = Compat.service(context, WindowManager.class);
        FrameLayout frame = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) pressBack();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        LinearLayout card = Ui.card(context);
        card.setElevation(Ui.dp(context, 8));
        card.addView(Ui.text(context, context.getString(R.string.pair_title, name), 18, Ui.TEXT, true));
        body = Ui.text(context, "", 15, Ui.MUTED, false);
        card.addView(body, Ui.below(context, 6));
        digits = Ui.text(context, "", 40, Ui.AMBER, true);
        digits.setLetterSpacing(0.15f);
        card.addView(digits, Ui.below(context, 8));
        LinearLayout buttons = new LinearLayout(context);
        allowButton = Ui.button(context, context.getString(R.string.pair_allow), true, v -> pressAllow());
        denyButton = Ui.button(context, context.getString(R.string.pair_deny), false, v -> pressDeny());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1);
        bp.rightMargin = Ui.dp(context, 8);
        buttons.addView(allowButton, bp);
        buttons.addView(denyButton, new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1));
        card.addView(buttons, Ui.below(context, 14));
        frame.addView(card, new FrameLayout.LayoutParams(Ui.dp(context, 400), ViewGroup.LayoutParams.WRAP_CONTENT));
        root = frame;
        render();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // Focusable, so the remote reaches the buttons; touches outside go to what plays.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = lp.y = Ui.dp(context, 32);
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {   // the permission was taken back meanwhile
            Log.w(LuchService.TAG, "pair prompt", e);
            root = null;
            notifyInstead();
        }
    }

    private void render() {
        if (root == null) {
            if (actions != null) notifyInstead();   // the notification shows the new state
            return;
        }
        boolean asking = code == null;
        body.setText(question != null ? question : context.getString(unencrypted ? R.string.pair_unencrypted
                : asking ? R.string.pair_ask : R.string.pair_type_code, name));
        digits.setVisibility(asking ? View.GONE : View.VISIBLE);
        if (!asking) digits.setText(code.substring(0, 3) + " " + code.substring(3));
        allowButton.setVisibility(asking ? View.VISIBLE : View.GONE);
        // An older sender gets in on "allow" alone: there the safe button is the one under the cursor.
        (asking && !unencrypted ? allowButton : denyButton).requestFocus();
    }

    private void detach() {
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
            }
            root = null;
        }
        if (actions != null) {
            try {
                context.unregisterReceiver(actions);
            } catch (RuntimeException ignored) {
            }
            actions = null;
            NotificationManager nm = Compat.service(context, NotificationManager.class);
            if (nm != null) nm.cancel(CHANNEL, 0);
        }
    }

    /** The same question as a notification with buttons, for a TV where the app may not draw over others. */
    @SuppressWarnings("deprecation")   // Notification.Builder(Context) and PRIORITY_HIGH: the way before Android 8
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")   // no flag before 13; the secret guards it
    private void notifyInstead() {
        NotificationManager nm = Compat.service(context, NotificationManager.class);
        if (nm == null) return;
        if (actions == null) {
            actions = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (!secret.equals(i.getStringExtra("secret"))) return;
                    String what = i.getStringExtra("answer");
                    if ("allow".equals(what)) pressAllow();
                    else if ("deny".equals(what)) pressDeny();
                    else pressBack();
                }
            };
            IntentFilter f = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(actions, f, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(actions, f);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.pair_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(context, CHANNEL);
        } else {
            b = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }
        boolean asking = code == null;
        String text = question != null ? question
                : asking ? context.getString(unencrypted ? R.string.pair_unencrypted : R.string.pair_ask, name)
                : context.getString(R.string.pair_type_code, name) + " " + code;
        b.setSmallIcon(R.drawable.ic_luch)
                .setContentTitle(context.getString(R.string.pair_title, name))
                .setContentText(text)
                .setDeleteIntent(answerIntent("close", 3));
        if (asking) b.addAction(new Notification.Action.Builder(0, context.getString(R.string.pair_allow),
                answerIntent("allow", 1)).build());
        b.addAction(new Notification.Action.Builder(0, context.getString(R.string.pair_deny),
                answerIntent("deny", 2)).build());
        nm.notify(CHANNEL, 0, b.build());
    }

    private PendingIntent answerIntent(String what, int request) {
        Intent i = new Intent(ACTION).setPackage(context.getPackageName()).putExtra("answer", what)
                .putExtra("secret", secret);
        return PendingIntent.getBroadcast(context, request, i, PendingIntent.FLAG_IMMUTABLE
                | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Runs {@code r} on the main thread and waits for it (at most a second). */
    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
