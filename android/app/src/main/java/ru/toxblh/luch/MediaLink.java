// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The rules of PROTOCOL.md section 4b that need no Android: which link may be fetched, which source is played, which
 * headers go along, and what kind of stream or subtitle a link is for the player. Kept apart so it runs in JVM tests.
 */
final class MediaLink {
    private MediaLink() {
    }

    /**
     * What this receiver's player plays, sent in the welcome as {@code media_types}: a sender casts a DASH manifest
     * only to a receiver that lists {@code application/dash+xml} (PROTOCOL.md 4b).
     */
    static final String[] TYPES = {
            "video/mp4", "video/webm", "video/x-matroska", "video/mp2t", "video/3gpp", "video/quicktime",
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml", "application/vnd.ms-sstr+xml",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "audio/flac", "audio/wav", "audio/webm",
    };

    static final String[] HEADERS = {"Referer", "User-Agent", "Origin", "Cookie", "Authorization"};

    static boolean playable(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        for (String t : TYPES) if (t.equals(m)) return true;
        return false;
    }

    /** Thrown for a link this receiver must not fetch; its message is the protocol's {@code detail}. */
    static final class BadLink extends Exception {
        BadLink(String why) {
            super(why);
        }
    }

    /**
     * Checks a link before anything is fetched. {@code via:"sender"}: plain http to the sender's own address only.
     * {@code via:"direct"}: http or https; the host is resolved and may not be loopback, link-local or a private
     * network unless it is the sender's address.
     */
    static void check(String url, String sender, boolean direct, Resolver dns) throws BadLink {
        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            throw new BadLink("bad url");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new BadLink("no host");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (!direct) {
            if (!"http".equals(scheme) || !host.equals(sender)) throw new BadLink("link not on the sender");
            return;
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new BadLink("only http and https");
        if (host.equals(sender)) return;
        InetAddress[] all;
        try {
            all = dns.resolve(host);
        } catch (java.net.UnknownHostException e) {
            throw new BadLink("unknown host");
        }
        if (all == null || all.length == 0) throw new BadLink("unknown host");
        for (InetAddress a : all) {
            if (a.getHostAddress().equals(sender)) continue;
            if (local(a)) throw new BadLink("link into the local network");
        }
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws java.net.UnknownHostException;
    }

    static final Resolver SYSTEM_DNS = InetAddress::getAllByName;

    /** Loopback, link-local, private (10/8, 172.16/12, 192.168/16, fc00::/7), CGNAT (100.64/10) or unspecified. */
    static boolean local(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /**
     * The source to play from {@code src}: a type this receiver plays, a codec it decodes ({@code decoders}, MIME types
     * like video/hevc), no bigger than {@code maxW}x{@code maxH}; of those the biggest. Sizes and codecs left out count
     * as fitting. Null when none fits.
     */
    static JSONObject pick(JSONArray src, Collection<String> decoders, int maxW, int maxH) {
        List<JSONObject> all = choices(src, decoders, maxW, maxH);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every source {@link #pick} could take, biggest first and one per height (the first of a size is kept): what the
     * quality menu offers. The first is the pick.
     */
    static List<JSONObject> choices(JSONArray src, Collection<String> decoders, int maxW, int maxH) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; src != null && i < src.length(); i++) {
            JSONObject s = src.optJSONObject(i);
            if (s == null || s.optString("url").isEmpty()) continue;
            String mime = s.optString("mime");
            if (!mime.isEmpty() && !playable(mime)) continue;
            String codec = s.optString("codec");
            if (!codec.isEmpty() && !decodes(codec, decoders)) continue;
            int w = s.optInt("w"), h = s.optInt("h");
            // A portrait video fits when its long side fits the screen's long side.
            if (maxW > 0 && maxH > 0 && w > 0 && h > 0
                    && (Math.max(w, h) > Math.max(maxW, maxH) || Math.min(w, h) > Math.min(maxW, maxH))) {
                continue;
            }
            out.add(s);
        }
        // Stable: of the same size the first listed stays first.
        Collections.sort(out, (x, y) -> Long.compare(area(y), area(x)));
        java.util.Set<Integer> sizes = new java.util.HashSet<>();
        List<JSONObject> one = new ArrayList<>();
        for (JSONObject s : out) {
            int h = Math.min(s.optInt("w"), s.optInt("h"));
            if (h > 0 && !sizes.add(h)) continue;
            one.add(s);
        }
        return one;
    }

    private static long area(JSONObject s) {
        return (long) s.optInt("w") * s.optInt("h");
    }

    /** What the quality menu calls a source: the page's own label, else its short side ("720p"), else its type. */
    static String qualityLabel(JSONObject s) {
        String label = s.optString("label");
        if (!label.isEmpty()) return label;
        int h = Math.min(s.optInt("w"), s.optInt("h"));
        if (h > 0) return h + "p";
        String mime = s.optString("mime");
        return mime.contains("mpegurl") ? "Auto" : "—";
    }

    private static final Map<String, String> CODECS = new HashMap<>();

    static {
        CODECS.put("avc", "video/avc");
        CODECS.put("h264", "video/avc");
        CODECS.put("avc1", "video/avc");
        CODECS.put("hevc", "video/hevc");
        CODECS.put("h265", "video/hevc");
        CODECS.put("hvc1", "video/hevc");
        CODECS.put("hev1", "video/hevc");
        CODECS.put("vp8", "video/x-vnd.on2.vp8");
        CODECS.put("vp9", "video/x-vnd.on2.vp9");
        CODECS.put("vp09", "video/x-vnd.on2.vp9");
        CODECS.put("av1", "video/av01");
        CODECS.put("av01", "video/av01");
        CODECS.put("dvhe", "video/dolby-vision");
        CODECS.put("dvh1", "video/dolby-vision");
    }

    /** Whether a codec name from a page ("hevc", "avc1.640028", "video/hevc") has a decoder here. */
    static boolean decodes(String codec, Collection<String> decoders) {
        String c = codec.toLowerCase(Locale.ROOT).trim();
        if (c.contains("/")) return decoders.contains(c);
        int dot = c.indexOf('.');
        String key = dot > 0 ? c.substring(0, dot) : c;
        String mime = CODECS.get(key);
        return mime == null || decoders.contains(mime); // an unknown name is left for the player to try
    }

    /** The allowed headers of a {@code media} message, in the case Android passes on. */
    static Map<String, String> headers(JSONObject h) {
        Map<String, String> out = new HashMap<>();
        if (h == null) return out;
        java.util.Iterator<String> keys = h.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            for (String allowed : HEADERS) {
                if (allowed.equalsIgnoreCase(k)) {
                    String v = h.optString(k);
                    if (!v.isEmpty() && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) out.put(allowed, v);
                }
            }
        }
        return out;
    }

    /** The adaptive kinds, named for the player; null for a file, whose kind the player finds from its bytes. */
    static String streamMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpegurl")) return androidx.media3.common.MimeTypes.APPLICATION_M3U8;
        if (m.startsWith("application/dash+xml")) return androidx.media3.common.MimeTypes.APPLICATION_MPD;
        if (m.startsWith("application/vnd.ms-sstr+xml")) return androidx.media3.common.MimeTypes.APPLICATION_SS;
        return null;
    }

    /** A subtitle file's kind, from the sender's type or the link's name; WebVTT, the web's own, when neither says. */
    static String subtitleMime(String mime, String url) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String path = url == null ? "" : url.toLowerCase(Locale.ROOT).replaceAll("[?#].*$", "");
        if (m.contains("subrip") || path.endsWith(".srt")) return androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
        if (m.contains("ssa") || m.contains("x-ass") || path.endsWith(".ass") || path.endsWith(".ssa")) {
            return androidx.media3.common.MimeTypes.TEXT_SSA;
        }
        if (m.contains("ttml") || path.endsWith(".ttml") || path.endsWith(".dfxp")) {
            return androidx.media3.common.MimeTypes.APPLICATION_TTML;
        }
        return androidx.media3.common.MimeTypes.TEXT_VTT;
    }
}
