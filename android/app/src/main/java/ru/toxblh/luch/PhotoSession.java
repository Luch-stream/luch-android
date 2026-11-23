// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Photos from a sender (PROTOCOL.md, section 4a): the sender lists links to its files, this device fetches them from the
 * sender's own address only and shows them full screen. One at a time, like a mirroring session.
 */
final class PhotoSession {
    static final int MAX_ITEMS = 200;

    static volatile PhotoSession current;

    static final class Item {
        final String url, mime, name;

        Item(String url, String mime, String name) {
            this.url = url;
            this.mime = mime;
            this.name = name;
        }
    }

    final MirrorSession.Control control;
    final String address;
    final List<Item> items = new ArrayList<>();
    volatile int index;

    private PhotoSession(MirrorSession.Control control, String address) {
        this.control = control;
        this.address = address;
    }

    /** Takes the list, checks that every link points at the sender, shows the first photo. */
    static void open(Context context, MirrorSession.Control control, String senderAddress, JSONObject msg) throws IOException {
        JSONArray list = msg.optJSONArray("items");
        if (list == null || list.length() == 0) throw new IOException("no items");
        PhotoSession s = new PhotoSession(control, senderAddress);
        for (int i = 0; i < Math.min(list.length(), MAX_ITEMS); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url");
            // Only plain http to the address the control connection came from: a link must not make this device fetch
            // from anywhere else on the network.
            java.net.URI u;
            try {
                u = new java.net.URI(url);
            } catch (java.net.URISyntaxException e) {
                throw new IOException("bad url");
            }
            if (!"http".equals(u.getScheme()) || u.getHost() == null || !u.getHost().equals(senderAddress)) {
                throw new IOException("link not on the sender");
            }
            s.items.add(new Item(url, o.optString("mime", "image/jpeg"), o.optString("name")));
        }
        if (s.items.isEmpty()) throw new IOException("no items");
        s.index = Math.max(0, Math.min(msg.optInt("index", 0), s.items.size() - 1));
        PhotoSession old = current;
        current = s;
        if (old != null) old.control.send(stop("replaced"));
        context.startActivity(new Intent(context, PhotoActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    static JSONObject stop(String reason) {
        try {
            return new JSONObject().put("t", "stop").put("by", "receiver").put("reason", reason);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** The sender said stop, or its connection went. */
    static void closeFrom(MirrorSession.Control control) {
        PhotoSession s = current;
        if (s == null || s.control != control) return;
        current = null;
        PhotoActivity.finishFromOutside();
    }
}
