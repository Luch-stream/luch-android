// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SharePlanTest {
    private static SharePlan.Shared f(String mime, String name) {
        return new SharePlan.Shared(mime, name);
    }

    private static SharePlan files(SharePlan.Shared... s) {
        return SharePlan.of(Arrays.asList(s), null);
    }

    private static SharePlan text(String t) {
        return SharePlan.of(Collections.emptyList(), t);
    }

    private static List<String> links(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("http://192.168.1.20:40000/tok/" + i + "/x");
        return l;
    }

    @Test
    public void photosBecomeOnePhotoSession() throws Exception {
        SharePlan p = files(f("image/jpeg", "IMG_1.jpg"), f("image/png", "b.png"), f("image/heif", "c.heic"));
        assertEquals(SharePlan.Kind.PHOTOS, p.kind);
        assertEquals(3, p.things.size());
        assertEquals("photo", p.mode());
        assertEquals("photo_ready", p.readyType());
        JSONObject m = p.message(links(3));
        assertEquals("photo", m.getString("t"));
        assertEquals(0, m.getInt("index"));
        JSONArray items = m.getJSONArray("items");
        assertEquals(3, items.length());
        assertEquals("http://192.168.1.20:40000/tok/1/x", items.getJSONObject(1).getString("url"));
        assertEquals("image/png", items.getJSONObject(1).getString("mime"));
        assertEquals("IMG_1.jpg", items.getJSONObject(0).getString("name"));
    }

    /** A gallery shares photos and a video together: the photos are shown, the video is said to be left out. */
    @Test
    public void aVideoAmongPhotosIsLeftOut() {
        SharePlan p = files(f("image/jpeg", "a.jpg"), f("video/mp4", "b.mp4"), f("image/jpeg", "c.jpg"));
        assertEquals(SharePlan.Kind.PHOTOS, p.kind);
        assertEquals(2, p.things.size());
        assertEquals(2, p.things.get(1).index);
        assertEquals(1, p.skipped);
    }

    @Test
    public void atMostTwoHundredPhotos() {
        SharePlan.Shared[] many = new SharePlan.Shared[250];
        Arrays.fill(many, f("image/jpeg", "a.jpg"));
        SharePlan p = files(many);
        assertEquals(200, p.things.size());
        assertEquals(50, p.skipped);
    }

    @Test
    public void aVideoIsServedFromThePhone() throws Exception {
        SharePlan p = files(f("video/mp4", "Holiday.mp4"));
        assertEquals(SharePlan.Kind.MEDIA, p.kind);
        assertFalse(p.needsType());
        JSONObject m = p.message(links(1));
        assertEquals("media", m.getString("t"));
        assertEquals("sender", m.getString("via"));
        assertEquals("Holiday", m.getString("title"));
        JSONObject src = m.getJSONArray("src").getJSONObject(0);
        assertEquals("http://192.168.1.20:40000/tok/0/x", src.getString("url"));
        assertEquals("video/mp4", src.getString("mime"));
        assertEquals("media_ready", p.readyType());
    }

    @Test
    public void soundIsMediaToo() {
        SharePlan p = files(f("audio/mpeg", "song.mp3"), f("audio/flac", "other.flac"));
        assertEquals(SharePlan.Kind.MEDIA, p.kind);
        assertEquals(0, p.things.get(0).index);
        assertEquals(1, p.skipped);
    }

    @Test
    public void aPdfIsADocument() throws Exception {
        SharePlan p = files(f("application/pdf", "Talk.pdf"));
        assertEquals(SharePlan.Kind.DOC, p.kind);
        JSONObject m = p.message(links(1));
        assertEquals("doc", m.getString("t"));
        assertEquals("application/pdf", m.getString("mime"));
        assertEquals("Talk.pdf", m.getString("name"));
        assertEquals(0, m.getInt("page"));
        assertEquals("doc_ready", p.readyType());
    }

    /** The receiver draws only PDF: a presentation is shown from the phone's own viewer while its screen is shown. */
    @Test
    public void officeFilesAreMirrored() {
        assertEquals(SharePlan.Kind.MIRROR,
                files(f("application/vnd.openxmlformats-officedocument.presentationml.presentation", "deck.pptx")).kind);
        assertEquals(SharePlan.Kind.MIRROR, files(f("application/vnd.oasis.opendocument.presentation", "d.odp")).kind);
        assertEquals(SharePlan.Kind.MIRROR, files(f("application/octet-stream", "deck.ppt")).kind);
        assertEquals(SharePlan.Kind.MIRROR, files(f("text/plain", "notes.txt")).kind);
        assertEquals("mirror", files(f("application/msword", "a.doc")).mode());
    }

    /** A vague or missing type: the name decides. */
    @Test
    public void vagueTypesGoByTheName() {
        assertEquals(SharePlan.Kind.MEDIA, files(f(null, "clip.mkv")).kind);
        assertEquals(SharePlan.Kind.DOC, files(f("application/octet-stream", "a.pdf")).kind);
        assertEquals(SharePlan.Kind.PHOTOS, files(f("image/*", "a")).kind);
        assertEquals(SharePlan.Problem.UNSUPPORTED, files(f("application/zip", "a.zip")).problem);
    }

    @Test
    public void aYouTubeLinkOpensTheTvsApp() throws Exception {
        SharePlan p = text("Watch this! https://youtu.be/dQw4w9WgXcQ?t=42 via the YouTube app");
        assertEquals(SharePlan.Kind.YOUTUBE, p.kind);
        JSONObject m = p.message(Collections.emptyList());
        assertEquals("open_app", m.getString("t"));
        assertEquals("youtube", m.getString("app"));
        assertEquals("dQw4w9WgXcQ", m.getString("id"));
        assertEquals(42.0, m.getDouble("start"), 0);
        assertEquals("app_opened", p.readyType());
        assertEquals(SharePlan.Problem.NONE, p.check(new JSONObject().put("apps", new JSONArray().put("youtube"))));
        assertEquals(SharePlan.Problem.NO_YOUTUBE, p.check(new JSONObject().put("apps", new JSONArray())));
    }

    /** The same links and answers as luchd's cast::tests::youtube_links. */
    @Test
    public void youtubeLinksAsLuchd() {
        String id = "dQw4w9WgXcQ";
        for (String u : new String[]{"https://www.youtube.com/watch?v=" + id, "https://m.youtube.com/watch?feature=share&v=" + id,
                "https://youtu.be/" + id, "https://www.youtube.com/shorts/" + id, "https://www.youtube-nocookie.com/embed/" + id + "?rel=0",
                "https://music.youtube.com/watch?v=" + id + "&list=x", "https://www.youtube.com/live/" + id}) {
            assertEquals(u, id, SharePlan.youtube(u)[0]);
        }
        assertEquals(90.0, SharePlan.time(SharePlan.youtube("https://youtu.be/" + id + "?t=90")[1]), 0);
        assertEquals(90.0, SharePlan.time(SharePlan.youtube("https://www.youtube.com/watch?v=" + id + "&t=1m30s")[1]), 0);
        assertEquals(3723.0, SharePlan.time(SharePlan.youtube("https://www.youtube.com/watch?v=" + id + "&t=1h2m3s")[1]), 0);
        assertNull(SharePlan.youtube("https://www.youtube.com/feed/subscriptions"));
        assertNull(SharePlan.youtube("https://www.youtube.com/watch?v=../../etc"));
        assertNull(SharePlan.youtube("https://notyoutube.com/watch?v=" + id));
    }

    @Test
    public void aMediaLinkIsFetchedByTheTv() throws Exception {
        SharePlan p = text("https://example.com/films/trailer.mp4");
        assertEquals(SharePlan.Kind.MEDIA, p.kind);
        assertTrue(p.things.get(0).isLink());
        assertFalse(p.needsType());
        JSONObject m = p.message(Collections.singletonList(null));
        assertEquals("direct", m.getString("via"));
        assertEquals("https://example.com/films/trailer.mp4", m.getJSONArray("src").getJSONObject(0).getString("url"));
        assertEquals("trailer", m.getString("title"));
    }

    /** Pictures and PDFs behind a link are relayed by the phone: the receiver fetches those from the sender only. */
    @Test
    public void picturesAndPdfsBehindLinksAreRelayed() {
        SharePlan photo = text("https://example.com/a/cat.jpg");
        assertEquals(SharePlan.Kind.PHOTOS, photo.kind);
        assertTrue(photo.things.get(0).isLink());
        assertEquals(SharePlan.Kind.DOC, text("https://example.com/paper.pdf?dl=1").kind);
    }

    /** A link that says nothing of its type is asked about first; a web page is not something the TV can show. */
    @Test
    public void aLinkOfUnknownTypeIsLookedAtFirst() throws Exception {
        SharePlan p = text("https://example.com/watch/123");
        assertEquals(SharePlan.Kind.MEDIA, p.kind);
        assertTrue(p.needsType());
        assertEquals(SharePlan.Problem.WEB_PAGE, p.refine("text/html; charset=utf-8").problem);
        assertEquals(SharePlan.Kind.PHOTOS, p.refine("image/webp").kind);
        assertEquals(SharePlan.Kind.DOC, p.refine("application/pdf").kind);
        SharePlan hls = p.refine("application/vnd.apple.mpegurl");
        assertEquals(SharePlan.Kind.MEDIA, hls.kind);
        assertFalse(hls.needsType());
        assertEquals("application/vnd.apple.mpegurl",
                hls.message(Collections.singletonList(null)).getJSONArray("src").getJSONObject(0).getString("mime"));
        assertTrue(p.refine("application/octet-stream").needsType());
        assertTrue(p.refine(null).needsType());
        // what is left unknown still goes as media; the receiver says whether it plays
        assertFalse(p.message(Collections.singletonList(null)).getJSONArray("src").getJSONObject(0).has("mime"));
    }

    @Test
    public void linksAreFoundInSharedText() {
        assertEquals("https://example.com/a?b=1", SharePlan.firstLink("see https://example.com/a?b=1."));
        assertEquals("https://en.wikipedia.org/wiki/Film_(genre)",
                SharePlan.firstLink("(https://en.wikipedia.org/wiki/Film_(genre))"));
        assertEquals("http://example.com/x", SharePlan.firstLink("«http://example.com/x»"));
        assertNull(SharePlan.firstLink("no link here"));
        assertNull(SharePlan.firstLink(null));
        assertEquals(SharePlan.Problem.NO_LINK, text("just words").problem);
    }

    @Test
    public void theReceiverMustSayItCan() throws Exception {
        SharePlan p = files(f("application/pdf", "a.pdf"));
        assertEquals(SharePlan.Problem.NONE, p.check(new JSONObject().put("modes", new JSONArray().put("photo").put("doc"))));
        assertEquals(SharePlan.Problem.NO_MODE, p.check(new JSONObject().put("modes", new JSONArray().put("photo"))));
        assertEquals(SharePlan.Problem.NO_MODE, p.check(new JSONObject()));
    }
}
