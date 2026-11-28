// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The six digits a TV shows when this phone pairs with it (PROTOCOL.md 3a), typed here. The connection's thread asks
 * and waits; a small window over whatever is on the phone takes the digits, and a notification opens it again when
 * Android does not let it come up by itself (a service starting a window from the background). Once paired, the TV
 * lets this phone in without asking.
 */
public class PairCode extends Activity {
    private static final String CHANNEL = "pair";
    private static final int NOTIFICATION = 7;
    private static final long WAIT_S = 150;   // the TV keeps its code for 2 minutes
    private static volatile LinkedBlockingQueue<String> waiting;
    private static volatile String tv;

    /** The code as typed for {@code tvName}, null when the person gave up or let it be. */
    static String ask(Context context, String tvName) throws InterruptedException {
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
        waiting = q;
        tv = tvName;
        Intent open = new Intent(context, PairCode.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notify(context, tvName, open);
        try {
            context.startActivity(open);
        } catch (RuntimeException blocked) {
            // the notification is the way then
        }
        try {
            String code = q.poll(WAIT_S, TimeUnit.SECONDS);
            return code == null || code.isEmpty() ? null : code;
        } finally {
            if (waiting == q) waiting = null;
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION);
        }
    }

    private static void answer(String code) {
        LinkedBlockingQueue<String> q = waiting;
        if (q != null) q.offer(code == null ? "" : code);
    }

    private static void notify(Context context, String tvName, Intent open) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.pair_code_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(context, CHANNEL);
        } else {
            b = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }
        PendingIntent tap = PendingIntent.getActivity(context, 7, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIFICATION, b.setSmallIcon(R.drawable.ic_luch)
                .setContentTitle(context.getString(R.string.pair_code_title, tvName))
                .setContentText(context.getString(R.string.pair_code_text)).setContentIntent(tap).setAutoCancel(true)
                .build());
    }

    private boolean answered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (waiting == null) {
            finish();
            return;
        }
        EditText digits = new EditText(this);
        digits.setInputType(InputType.TYPE_CLASS_NUMBER);
        digits.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Pairing.DIGITS)});
        digits.setGravity(Gravity.CENTER);
        digits.setTextSize(28);
        digits.setLetterSpacing(0.3f);
        digits.setHint("000000");
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(digits);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.pair_code_title, tv))
                .setMessage(R.string.pair_code_text)
                .setView(box)
                .setPositiveButton(R.string.pair_code_ok, (x, w) -> done(digits.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (x, w) -> done(null))
                .setOnCancelListener(x -> done(null))
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            digits.requestFocus();
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        digits.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (d.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(s.length() == Pairing.DIGITS);
                }
            }
        });
        d.show();
    }

    private void done(String code) {
        if (answered) return;
        answered = true;
        answer(code != null && code.length() == Pairing.DIGITS ? code : null);
        finish();
    }

    @Override
    protected void onDestroy() {
        // a window closed some other way is a "no", not a wait of two minutes
        if (!answered && isFinishing() && waiting != null) answer(null);
        super.onDestroy();
    }
}
