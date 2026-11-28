// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What "Show on TV" does with what another app shared: photos, one video or sound, one PDF, a link the TV fetches
 * itself, a YouTube link for the TV's own app, or an office file the phone opens while its screen is shown (nothing
 * here turns one into PDF, which is all the receiver draws). Then the message that shows it (PROTOCOL.md 4a, 4b, 4f,
 * 4g). Plain Java, for JVM tests; the content:// side and the network are CastService's.
 */
final class SharePlan {
    enum Kind {
        /** Pictures, one after another: {@code things} are all of them. */
        PHOTOS,
        /** One video or sound, served from the phone or (a link) fetched by the TV. */
        MEDIA,
        /** One PDF. */
        DOC,
        /** A YouTube link, played in the TV's YouTube app. */
        YOUTUBE,
        /** A document the TV cannot draw: the phone shows its screen and opens it. */
        MIRROR,
        /** Nothing to show: {@code problem} says why. */
        NOTHING,
    }

    /** Why there is nothing to show; CastService puts it in words. */
    enum Problem { NONE, UNSUPPORTED, NO_LINK, WEB_PAGE, NO_MODE, NO_YOUTUBE }

    /** One thing to show: a shared file ({@code index} into what was shared) or a link ({@code index} -1). */
    static final class Thing {
        final int index;
        final String url, mime, name;

        Thing(int index, String url, String mime, String name) {
            this.index = index;
            this.url = url;
            this.mime = mime;
            this.name = name;
        }

        boolean isLink() {
            return index < 0;
        }
    }

    /** A shared file as the share sheet describes it: its type (may be null or vague) and display name. */
    static final class Shared {
        final String mime, name;

        Shared(String mime, String name) {
            this.mime = mime;
            this.name = name;
        }
    }

    static final int MAX_PHOTOS = PhotoSession.MAX_ITEMS;
    /** Office files and text: the receiver draws only PDF (4g), so these are shown from the phone's own viewer. */
    private static final List<String> VIEWED = Arrays.asList(
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.oasis.opendocument.presentation",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/rtf");

    final Kind kind;
    final List<Thing> things;
    final Problem problem;
    /** Shared files left out (a video among photos, a second video). */
    final int skipped;
    final String youtubeId;
    final double start;

    private SharePlan(Kind kind, List<Thing> things, Problem problem, int skipped, String youtubeId, double start) {
        this.kind = kind;
        this.things = Collections.unmodifiableList(things);
        this.problem = problem;
        this.skipped = skipped;
        this.youtubeId = youtubeId;
        this.start = start;
    }

    private static SharePlan of(Kind kind, List<Thing> things, int skipped) {
        return new SharePlan(kind, things, Problem.NONE, skipped, null, 0);
    }

    static SharePlan nothing(Problem why) {
        return new SharePlan(Kind.NOTHING, new ArrayList<>(), why, 0, null, 0);
    }

    /** The plan for {@code files} (ACTION_SEND's stream or SEND_MULTIPLE's), else for the first link in {@code text}. */
    static SharePlan of(List<Shared> files, String text) {
        if (files.isEmpty()) return forLink(firstLink(text));
        List<Thing> photos = new ArrayList<>(), media = new ArrayList<>(), docs = new ArrayList<>(),
                viewed = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Shared f = files.get(i);
            String mime = mimeOf(f.mime, f.name);
            Thing t = new Thing(i, null, mime, f.name == null ? "" : f.name);
            if (mime.startsWith("image/")) photos.add(t);
            else if (isMedia(mime)) media.add(t);
            else if (mime.equals("application/pdf")) docs.add(t);
            else if (VIEWED.contains(mime) || mime.startsWith("text/")) viewed.add(t);
        }
        int all = files.size();
        if (!photos.isEmpty()) {
            List<Thing> shown = photos.subList(0, Math.min(photos.size(), MAX_PHOTOS));
            return of(Kind.PHOTOS, new ArrayList<>(shown), all - shown.size());
        }
        if (!media.isEmpty()) return of(Kind.MEDIA, one(media.get(0)), all - 1);
        if (!docs.isEmpty()) return of(Kind.DOC, one(docs.get(0)), all - 1);
        if (!viewed.isEmpty()) return of(Kind.MIRROR, one(viewed.get(0)), all - 1);
        return nothing(Problem.UNSUPPORTED);
    }

    /**
     * A link: YouTube to the TV's app, a picture or a PDF relayed by the phone (the receiver fetches those from the
     * sender only), anything else as media the TV fetches itself; a link of unknown type is looked at first
     * ({@link #refine}).
     */
    static SharePlan forLink(String url) {
        if (url == null) return nothing(Problem.NO_LINK);
        String[] yt = youtube(url);
        if (yt != null) return new SharePlan(Kind.YOUTUBE, new ArrayList<>(), Problem.NONE, 0, yt[0], time(yt[1]));
        return byType(url, ShareServer.mimeOf(url));
    }

    private static SharePlan byType(String url, String mime) {
        Thing t = new Thing(-1, url, mime, fileName(url));
        if (mime.startsWith("image/")) return of(Kind.PHOTOS, one(t), 0);
        if (mime.equals("application/pdf")) return of(Kind.DOC, one(t), 0);
        if (mime.equals("text/html") || mime.equals("application/xhtml+xml")) return nothing(Problem.WEB_PAGE);
        return of(Kind.MEDIA, one(t), 0);
    }

    /** Whether the link's type has to be asked of its server before anything is sent ({@link #refine}). */
    boolean needsType() {
        return kind == Kind.MEDIA && things.get(0).isLink() && things.get(0).mime.isEmpty();
    }

    /** The plan once the link's server said its {@code Content-Type}; as it was when it said nothing useful. */
    SharePlan refine(String contentType) {
        if (!needsType() || contentType == null) return this;
        String m = contentType.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi);
        m = m.trim();
        if (m.isEmpty() || m.equals("application/octet-stream") || m.equals("binary/octet-stream")) return this;
        return byType(things.get(0).url, m);
    }

    /** What the receiver must list in {@code modes} for this, and what the hello asks for. */
    String mode() {
        switch (kind) {
            case PHOTOS: return "photo";
            case MEDIA: return "media";
            case DOC: return "doc";
            case MIRROR: return "mirror";
            default: return null;
        }
    }

    /** Why this receiver cannot show it, from its welcome; NONE when it can. */
    Problem check(JSONObject welcome) {
        if (kind == Kind.YOUTUBE) return contains(welcome.optJSONArray("apps"), "youtube") ? Problem.NONE : Problem.NO_YOUTUBE;
        String mode = mode();
        return mode == null || contains(welcome.optJSONArray("modes"), mode) ? Problem.NONE : Problem.NO_MODE;
    }

    /**
     * The message that shows it. {@code links} has, for each of {@code things}, where the receiver fetches it from
     * this phone (null for a link the TV fetches itself).
     */
    JSONObject message(List<String> links) throws JSONException {
        switch (kind) {
            case YOUTUBE:
                return new JSONObject().put("t", "open_app").put("app", "youtube").put("id", youtubeId).put("start", start);
            case PHOTOS: {
                JSONArray items = new JSONArray();
                for (int i = 0; i < things.size(); i++) {
                    Thing t = things.get(i);
                    items.put(new JSONObject().put("url", links.get(i)).put("mime", t.mime).put("name", t.name));
                }
                return new JSONObject().put("t", "photo").put("index", 0).put("items", items);
            }
            case DOC: {
                Thing t = things.get(0);
                return new JSONObject().put("t", "doc").put("url", links.get(0)).put("mime", "application/pdf")
                        .put("name", t.name).put("page", 0);
            }
            case MEDIA: {
                Thing t = things.get(0);
                boolean direct = links.get(0) == null;
                JSONObject src = new JSONObject().put("url", direct ? t.url : links.get(0));
                if (!t.mime.isEmpty()) src.put("mime", t.mime);
                return new JSONObject().put("t", "media").put("via", direct ? "direct" : "sender")
                        .put("src", new JSONArray().put(src)).put("title", title(t.name));
            }
            default:
                throw new JSONException("nothing to send for " + kind);
        }
    }

    /** The answer that says the receiver shows it. */
    String readyType() {
        switch (kind) {
            case PHOTOS: return "photo_ready";
            case MEDIA: return "media_ready";
            case DOC: return "doc_ready";
            case YOUTUBE: return "app_opened";
            default: return null;
        }
    }

    // ---- helpers ----

    private static List<Thing> one(Thing t) {
        List<Thing> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    private static boolean isMedia(String mime) {
        return mime.startsWith("video/") || mime.startsWith("audio/") || mime.contains("mpegurl")
                || mime.equals("application/dash+xml");
    }

    /** The share sheet's type, unless it is missing or says nothing ("application/octet-stream"): then the name's. */
    static String mimeOf(String given, String name) {
        String m = given == null ? "" : given.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty() || m.endsWith("/*") || m.equals("application/octet-stream")) {
            String byName = ShareServer.mimeOf(name);
            if (!byName.isEmpty()) return byName;
            if (m.endsWith("/*")) return m.substring(0, m.length() - 1) + "unknown";
        }
        return m;
    }

    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);

    /** The first http(s) link in a shared text ("Look at this https://…"), without the punctuation after it. */
    static String firstLink(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        while (!u.isEmpty() && ".,;:!?)]}'»".indexOf(u.charAt(u.length() - 1)) >= 0) {
            char last = u.charAt(u.length() - 1);
            // a closing bracket that belongs to the link (a Wikipedia title) stays
            if (last == ')' && count(u, '(') >= count(u, ')')) break;
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String fileName(String url) {
        String p = url.split("[?#]", 2)[0];
        String n = p.substring(p.lastIndexOf('/') + 1);
        return n.isEmpty() ? url : n;
    }

    /** "Holiday.mp4" → "Holiday". */
    private static String title(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; a != null && i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    /**
     * The video id and the {@code t=} of a YouTube link (watch, youtu.be, shorts, embed, live), as luchd's
     * cast::youtube reads them; null for anything else.
     */
    static String[] youtube(String url) {
        String u = url.split("#", 2)[0];
        int scheme = u.indexOf("://");
        if (scheme < 0) return null;
        String rest = u.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
        String pathQuery = slash < 0 ? "" : rest.substring(slash + 1);
        for (String p : new String[]{"www.", "m.", "music."}) if (host.startsWith(p)) host = host.substring(p.length());
        int q = pathQuery.indexOf('?');
        String path = q < 0 ? pathQuery : pathQuery.substring(0, q);
        String query = q < 0 ? "" : pathQuery.substring(q + 1);
        String id = null;
        String[] parts = path.split("/");
        if (host.equals("youtu.be")) {
            id = parts.length > 0 ? parts[0] : null;
        } else if (host.equals("youtube.com") || host.equals("youtube-nocookie.com")) {
            String first = parts.length > 0 ? parts[0] : "";
            if (first.equals("watch")) id = param(query, "v");
            else if (Arrays.asList("shorts", "embed", "live", "v").contains(first) && parts.length > 1) id = parts[1];
        }
        if (id == null || id.length() < 6 || id.length() > 20 || !id.matches("[A-Za-z0-9_-]+")) return null;
        String t = param(query, "t");
        if (t == null) t = param(query, "start");
        return new String[]{id, t == null ? "0" : t};
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return null;
    }

    /** "90", "90s", "1m30s", "1h2m3s" in seconds. */
    static double time(String t) {
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            // with units
        }
        double total = 0;
        StringBuilder num = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
                continue;
            }
            double n = num.length() == 0 ? 0 : Double.parseDouble(num.toString());
            num.setLength(0);
            total += n * (c == 'h' ? 3600 : c == 'm' ? 60 : 1);
        }
        return total + (num.length() == 0 ? 0 : Double.parseDouble(num.toString()));
    }
}
