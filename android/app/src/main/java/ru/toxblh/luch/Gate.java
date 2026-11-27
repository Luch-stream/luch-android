// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Who may use this receiver (PROTOCOL.md, section 3a): the paired senders, known by their certificate, and what an
 * unknown one gets instead: a prompt on the TV, or nothing while it is banned or prompts are coming too often. A
 * sender chooses its own id and key, so refusals count against its key and against its address both. Plain logic with
 * an injectable clock and store: the JVM tests drive it through days in milliseconds.
 */
final class Gate {
    interface Clock {
        long now();
    }

    /** Where the paired senders and the mode are kept (SharedPreferences on the TV, a field in tests). */
    interface Store {
        String load();

        void save(String json);
    }

    static final int MAX_PAIRED = 20;
    /** Refusals (a denial on the TV, a wrong code) after which a sender is not asked about again for BAN_MS. */
    static final int STRIKES = 3;
    static final long BAN_MS = 60 * 60_000L;
    /** In guest mode a pairing lasts until the TV goes to standby, and never longer than this. */
    static final long GUEST_MS = 24 * 60 * 60_000L;
    /** At most PROMPTS prompts in PROMPT_WINDOW_MS, whoever asks: a flood of new keys and addresses stays quiet. */
    static final int PROMPTS = 5;
    static final long PROMPT_WINDOW_MS = 10 * 60_000L;
    /** What a sender is told to wait while another prompt is on screen. */
    static final long BUSY_RETRY_MS = 30_000L;

    enum Verdict {
        /** Paired: go on without asking. */
        PAIRED,
        /** Unknown: ask the person; {@link #promptEnded} must follow. */
        PROMPT,
        /** Refused too often lately: not asked about. */
        BANNED,
        /** Another prompt is up, or too many came lately. */
        BUSY
    }

    /** A paired sender. */
    static final class Sender {
        final String fp, id, name;
        final long pairedAt;
        long usedAt;

        Sender(String fp, String id, String name, long pairedAt, long usedAt) {
            this.fp = fp;
            this.id = id;
            this.name = name;
            this.pairedAt = pairedAt;
            this.usedAt = usedAt;
        }
    }

    /** Refusals counted for one key or address. */
    private static final class Strikes {
        int count;
        long since, bannedUntil;
    }

    private final Clock clock;
    private final Store store;
    private final Map<String, Sender> paired = new HashMap<>();
    private final Map<String, Strikes> strikes = new HashMap<>();
    private final ArrayDeque<Long> prompts = new ArrayDeque<>();
    private boolean prompting;
    private boolean guest;

    Gate(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
        load();
    }

    /** What to do with a sender whose certificate has fingerprint {@code fp} (null: an older sender without TLS). */
    synchronized Verdict check(String fp, String address) {
        long now = clock.now();
        expire(now);
        Sender s = fp == null ? null : paired.get(fp);
        if (s != null) {
            s.usedAt = now;
            save();
            return Verdict.PAIRED;
        }
        if (banLeft(fp, address, now) > 0) return Verdict.BANNED;
        if (prompting) return Verdict.BUSY;
        while (!prompts.isEmpty() && now - prompts.peekFirst() >= PROMPT_WINDOW_MS) prompts.removeFirst();
        if (prompts.size() >= PROMPTS) return Verdict.BUSY;
        prompting = true;
        prompts.addLast(now);
        return Verdict.PROMPT;
    }

    /** The prompt that {@link #check} allowed is gone (answered, closed or timed out). */
    synchronized void promptEnded() {
        prompting = false;
    }

    /** How long a refused sender should wait before asking again, in ms (0: it may ask now). */
    synchronized long retryMs(String fp, String address) {
        long now = clock.now();
        long ban = banLeft(fp, address, now);
        if (ban > 0) return ban;
        if (prompting) return BUSY_RETRY_MS;
        if (prompts.size() >= PROMPTS) return Math.max(0, prompts.peekFirst() + PROMPT_WINDOW_MS - now);
        return 0;
    }

    /** The person said no, or the code was wrong: counts against the key and the address. */
    synchronized void strike(String fp, String address) {
        long now = clock.now();
        for (String key : keys(fp, address)) {
            Strikes s = strikes.get(key);
            if (s == null || now - s.since >= BAN_MS) {
                s = new Strikes();
                s.since = now;
                strikes.put(key, s);
            }
            if (++s.count >= STRIKES) {
                s.bannedUntil = now + BAN_MS;
                s.count = 0;
                s.since = now;
            }
        }
    }

    /** Remembers a sender after its code was right; one key per sender id, the least recently used dropped past 20. */
    synchronized void pair(String fp, String id, String name) {
        long now = clock.now();
        for (Iterator<Sender> i = paired.values().iterator(); i.hasNext(); ) {
            if (i.next().id.equals(id)) i.remove();   // the same sender with a new key (reinstalled)
        }
        paired.put(fp, new Sender(fp, id, name, now, now));
        while (paired.size() > MAX_PAIRED) {
            Sender oldest = Collections.min(paired.values(), (a, b) -> Long.compare(a.usedAt, b.usedAt));
            paired.remove(oldest.fp);
        }
        save();
    }

    /** The TV goes to standby: in guest mode every pairing ends with this TV session. */
    synchronized void standby() {
        if (guest && !paired.isEmpty()) {
            paired.clear();
            save();
        }
    }

    synchronized boolean guest() {
        return guest;
    }

    synchronized void setGuest(boolean on) {
        guest = on;
        save();
    }

    /** The paired senders, the most recently used first. */
    synchronized List<Sender> paired() {
        expire(clock.now());
        List<Sender> out = new ArrayList<>(paired.values());
        Collections.sort(out, (a, b) -> Long.compare(b.usedAt, a.usedAt));
        return out;
    }

    synchronized boolean isPaired(String fp) {
        expire(clock.now());
        return fp != null && paired.containsKey(fp);
    }

    synchronized void forget(String fp) {
        if (paired.remove(fp) != null) save();
    }

    synchronized void forgetAll() {
        paired.clear();
        save();
    }

    private long banLeft(String fp, String address, long now) {
        long left = 0;
        for (String key : keys(fp, address)) {
            Strikes s = strikes.get(key);
            if (s != null) left = Math.max(left, s.bannedUntil - now);
        }
        return left;
    }

    private static List<String> keys(String fp, String address) {
        List<String> k = new ArrayList<>(2);
        if (fp != null) k.add("key:" + fp);
        if (address != null) k.add("ip:" + address);
        return k;
    }

    private void expire(long now) {
        if (!guest) return;
        boolean changed = false;
        for (Iterator<Sender> i = paired.values().iterator(); i.hasNext(); ) {
            if (now - i.next().pairedAt >= GUEST_MS) {
                i.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private void load() {
        try {
            String json = store.load();
            if (json == null || json.isEmpty()) return;
            JSONObject o = new JSONObject(json);
            guest = o.optBoolean("guest", false);
            JSONArray a = o.optJSONArray("paired");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                Sender s = new Sender(p.getString("fp"), p.optString("id"), p.optString("name"), p.optLong("paired"),
                        p.optLong("used"));
                paired.put(s.fp, s);
            }
        } catch (JSONException broken) {
            paired.clear();   // a damaged list pairs everyone again rather than trusting part of it
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Sender s : paired.values()) {
                a.put(new JSONObject().put("fp", s.fp).put("id", s.id).put("name", s.name).put("paired", s.pairedAt)
                        .put("used", s.usedAt));
            }
            store.save(new JSONObject().put("guest", guest).put("paired", a).toString());
        } catch (JSONException ignored) {
            // only strings and numbers go in
        }
    }
}
