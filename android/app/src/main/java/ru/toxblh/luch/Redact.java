// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Hide addresses and names" of a report: every IP address becomes 192.0.2.N, every MAC 02:00:00:00:00:NN and each
 * name given a placeholder that placeholder, the same one wherever it is in the report, so lines still tell devices
 * apart. Loopback and the unspecified address stay: they say nothing about anyone. Plain Java, for the JVM tests.
 */
final class Redact {
    private static final Pattern MAC = Pattern.compile(
            "(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])");
    private static final String V4 = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}";
    private static final String H = "[0-9A-Fa-f]{1,4}";
    /**
     * IPv6 first, so an IPv4 address inside one ("::ffff:10.0.0.5") goes as a whole. IPv6 needs "::" or all eight
     * groups: a time ("14:03:12") or a file and line ("File.java:12") is not one.
     */
    private static final Pattern ADDRESS = Pattern.compile(
            "(?<![\\w:.])(?:(?:" + H + ":){7}" + H
                    + "|(?:" + H + "(?::" + H + ")*)?::(?:" + H + "(?::" + H + ")*(?::" + V4 + ")?|" + V4 + ")?)"
                    + "(?:%[\\w.]+)?(?![\\w:]|\\.\\d)"
                    + "|(?<![\\d.])" + V4 + "(?![\\d.]*\\d)");
    private static final String[] NETS = {"192.0.2.", "198.51.100.", "203.0.113."};

    private final Map<String, String> addresses = new HashMap<>();
    private final Map<String, String> macs = new HashMap<>();
    /** Real name → placeholder, the longest first when applied (a name may contain another). */
    private final Map<String, String> names = new LinkedHashMap<>();

    /** {@code real} becomes {@code placeholder}; false for a blank name or one already given (it is left alone). */
    boolean name(String real, String placeholder) {
        if (real == null || real.trim().length() < 2 || names.containsKey(real.trim())) return false;
        names.put(real.trim(), placeholder);
        return true;
    }

    String apply(String text) {
        if (text == null) return null;
        String s = replace(MAC, text, m -> stable(macs, m.toLowerCase(Locale.ROOT).replace('-', ':'),
                String.format(Locale.ROOT, "02:00:00:00:00:%02x", macs.size() + 1)));
        s = replace(ADDRESS, s, this::address);
        List<String> keys = new ArrayList<>(names.keySet());
        java.util.Collections.sort(keys, (a, b) -> b.length() - a.length());
        for (String k : keys) {
            s = Pattern.compile(Pattern.quote(k), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s)
                    .replaceAll(Matcher.quoteReplacement(names.get(k)));
        }
        return s;
    }

    private String address(String a) {
        String key = a.toLowerCase(Locale.ROOT);
        if (key.startsWith("127.") || key.equals("0.0.0.0") || key.equals("::1") || key.equals("::")) return a;
        int n = addresses.size();
        return stable(addresses, key, NETS[Math.min(n / 254, NETS.length - 1)] + (n % 254 + 1));
    }

    /** The placeholder {@code key} already has, else {@code next} from now on (no computeIfAbsent before Android 7). */
    private static String stable(Map<String, String> m, String key, String next) {
        String had = m.get(key);
        if (had != null) return had;
        m.put(key, next);
        return next;
    }

    private interface Swap {
        String to(String found);
    }

    private static String replace(Pattern p, String s, Swap swap) {
        Matcher m = p.matcher(s);
        StringBuffer b = new StringBuffer(s.length());
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(swap.to(m.group())));
        m.appendTail(b);
        return b.toString();
    }
}
