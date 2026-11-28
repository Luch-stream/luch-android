// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which receiver "Show on TV" goes to: the only one at once, else a list with the one used last on top. A TV seen
 * twice (on two interfaces, or IPv6 and IPv4) is one entry, under its IPv4 address (PROTOCOL.md 2). Plain Java.
 */
final class ReceiverChoice {
    private ReceiverChoice() {
    }

    /** One entry per id, the last used first, the rest as found. */
    static List<Luch.Receiver> order(List<Luch.Receiver> found, String lastId) {
        Map<String, Luch.Receiver> byId = new LinkedHashMap<>();
        for (Luch.Receiver r : found) {
            Luch.Receiver seen = byId.get(r.id);
            if (seen == null || (!ipv4(seen.address) && ipv4(r.address))) byId.put(r.id, r);
        }
        List<Luch.Receiver> out = new ArrayList<>();
        Luch.Receiver last = lastId == null ? null : byId.remove(lastId);
        if (last != null) out.add(last);
        out.addAll(byId.values());
        return out;
    }

    /** The receiver to go to without asking: the only one there is; null when the person picks. */
    static Luch.Receiver auto(List<Luch.Receiver> ordered) {
        return ordered.size() == 1 ? ordered.get(0) : null;
    }

    private static boolean ipv4(String a) {
        return a != null && a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
