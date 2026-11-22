// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

/** What the receiver is doing: its name and address, the senders connected to it and those announced nearby. */
public class StatusActivity extends Activity {
    private TextView body;
    private Button update;
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
        // Only in the GitHub build, when a newer release is out (Updater).
        update = new Button(this);
        update.setVisibility(android.view.View.GONE);
        update.setOnClickListener(v -> installUpdate());
        root.addView(title);
        root.addView(body);
        root.addView(update, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        // A sender's offer to open the screen comes as a notification (Android 13+ asks once).
        if (android.os.Build.VERSION.SDK_INT >= 33
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
        String name = demo ? DEMO_TV : Luch.name(this);
        java.util.List<Luch.Peer> connected = demo ? java.util.Collections.singletonList(DEMO_SENDER) : Luch.connected();
        java.util.List<Luch.Peer> nearby = demo ? java.util.Collections.singletonList(DEMO_SENDER) : Luch.nearby();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.status_name, name)).append('\n');
        sb.append(getString(R.string.status_address, demo ? "192.168.1.50" : address(), Luch.PORT)).append('\n');
        sb.append(demo ? getString(R.string.status_announced, DEMO_TV + " · Luch") : Luch.status).append("\n\n");
        sb.append(getString(R.string.status_connected)).append('\n');
        if (connected.isEmpty()) sb.append("  —\n");
        for (Luch.Peer p : connected) sb.append("  • ").append(p.name).append("  ").append(p.address).append('\n');
        sb.append('\n').append(getString(R.string.status_nearby)).append('\n');
        if (nearby.isEmpty()) sb.append("  —\n");
        for (Luch.Peer p : nearby) sb.append("  • ").append(p.name).append("  ").append(p.address).append('\n');
        body.setText(sb);
        Updater.Release r = demo ? null : Updater.available;
        update.setVisibility(r == null ? android.view.View.GONE : android.view.View.VISIBLE);
        if (r != null) {
            update.setText(Updater.installing ? getString(R.string.update_installing)
                    : getString(R.string.update_to, r.version));
            update.setEnabled(!Updater.installing);
        }
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
                body.append("\n\n" + getString(R.string.update_allow));
            }
            return;
        }
        Updater.start(this, r, true);
    }

    private static final String DEMO_TV = "Living room TV";
    private static final Luch.Peer DEMO_SENDER = new Luch.Peer("demo", "Linux Laptop", "192.168.1.20");

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
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
