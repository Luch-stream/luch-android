// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

/** What the receiver is doing: its name and address, the senders connected to it and those announced nearby. */
public class StatusActivity extends Activity {
    private TextView body;
    private final Luch.Listener listener = () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LuchService.start(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        int pad = dp(48);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body = new TextView(this);
        body.setTextColor(0xCCFFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        body.setLineSpacing(0, 1.3f);
        body.setGravity(Gravity.START);
        body.setPadding(0, dp(24), 0, 0);
        root.addView(title);
        root.addView(body);
        setContentView(root);
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
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.status_name, Luch.name(this))).append('\n');
        sb.append(getString(R.string.status_address, address(), Luch.PORT)).append('\n');
        sb.append(Luch.status).append("\n\n");
        sb.append(getString(R.string.status_connected)).append('\n');
        if (Luch.connected().isEmpty()) sb.append("  —\n");
        for (Luch.Peer p : Luch.connected()) sb.append("  • ").append(p.name).append("  ").append(p.address).append('\n');
        sb.append('\n').append(getString(R.string.status_nearby)).append('\n');
        if (Luch.nearby().isEmpty()) sb.append("  —\n");
        for (Luch.Peer p : Luch.nearby()) sb.append("  • ").append(p.name).append("  ").append(p.address).append('\n');
        body.setText(sb);
    }

    private String address() {
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
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
