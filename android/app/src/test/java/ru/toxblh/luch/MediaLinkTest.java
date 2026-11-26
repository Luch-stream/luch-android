// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MediaLinkTest {
    private static final List<String> AVC_HEVC = Arrays.asList("video/avc", "video/hevc");

    /** A resolver that answers every name with one fixed address. */
    private static MediaLink.Resolver to(String ip) {
        return host -> new InetAddress[]{InetAddress.getByName(ip)};
    }

    private static void refused(String url, String sender, boolean direct, MediaLink.Resolver dns) {
        try {
            MediaLink.check(url, sender, direct, dns);
            fail("should refuse " + url);
        } catch (MediaLink.BadLink expected) {
            // refused
        }
    }

    @Test
    public void senderLinksOnlyToTheSenderOverHttp() throws Exception {
        MediaLink.check("http://192.168.1.20:47810/t/0", "192.168.1.20", false, to("8.8.8.8"));
        refused("http://192.168.1.21:47810/t/0", "192.168.1.20", false, to("8.8.8.8"));
        refused("https://192.168.1.20/t/0", "192.168.1.20", false, to("8.8.8.8"));
        refused("http://example.com/v.mp4", "192.168.1.20", false, to("8.8.8.8"));
    }

    @Test
    public void directLinksMayNotReachIntoTheNetwork() throws Exception {
        MediaLink.check("https://cdn.example.com/v.m3u8", "192.168.1.20", true, to("93.184.216.34"));
        MediaLink.check("http://cdn.example.com/v.mp4", "192.168.1.20", true, to("93.184.216.34"));
        // The sender itself is fine, it may relay.
        MediaLink.check("http://192.168.1.20:8080/v.mp4", "192.168.1.20", true, to("192.168.1.20"));
        refused("http://router.lan/admin", "192.168.1.20", true, to("192.168.1.1"));
        refused("http://127.0.0.1:47800/", "192.168.1.20", true, to("127.0.0.1"));
        refused("http://evil.example/", "192.168.1.20", true, to("10.0.0.5"));
        refused("http://evil.example/", "192.168.1.20", true, to("172.20.0.1"));
        refused("http://evil.example/", "192.168.1.20", true, to("169.254.1.1"));
        refused("http://evil.example/", "192.168.1.20", true, to("100.64.0.1"));
        refused("http://evil.example/", "192.168.1.20", true, to("::1"));
        refused("http://evil.example/", "192.168.1.20", true, to("fd00::1"));
        refused("file:///sdcard/x.mp4", "192.168.1.20", true, to("93.184.216.34"));
        refused("rtsp://cam.example/", "192.168.1.20", true, to("93.184.216.34"));
        refused("http://nowhere.invalid/", "192.168.1.20", true, host -> {
            throw new UnknownHostException(host);
        });
    }

    @Test
    public void cgnatBoundaries() throws Exception {
        assertTrue(MediaLink.local(InetAddress.getByName("100.127.255.255")));
        assertFalse(MediaLink.local(InetAddress.getByName("100.128.0.1")));
        assertFalse(MediaLink.local(InetAddress.getByName("100.63.255.255")));
    }

    private static JSONObject src(String url, String mime, int w, int h, String codec) throws Exception {
        JSONObject o = new JSONObject().put("url", url).put("mime", mime);
        if (w > 0) o.put("w", w).put("h", h);
        if (codec != null) o.put("codec", codec);
        return o;
    }

    @Test
    public void picksTheBiggestThatFitsTheScreen() throws Exception {
        JSONArray s = new JSONArray()
                .put(src("8k", "video/mp4", 7680, 4320, "hevc"))
                .put(src("4k", "video/mp4", 3840, 2160, "hevc"))
                .put(src("1080", "video/mp4", 1920, 1080, "avc1.640028"));
        assertEquals("4k", MediaLink.pick(s, AVC_HEVC, 3840, 2160).optString("url"));
        assertEquals("1080", MediaLink.pick(s, AVC_HEVC, 1920, 1080).optString("url"));
        assertEquals("8k", MediaLink.pick(s, AVC_HEVC, 7680, 4320).optString("url"));
    }

    @Test
    public void skipsCodecsAndTypesItCannotPlay() throws Exception {
        JSONArray s = new JSONArray()
                .put(src("av1", "video/mp4", 3840, 2160, "av01.0.12M.10"))
                .put(src("dash", "application/dash+xml", 3840, 2160, null))
                .put(src("vp9", "video/webm", 2560, 1440, "vp9"))
                .put(src("avc", "video/mp4", 1920, 1080, "avc"));
        assertEquals("avc", MediaLink.pick(s, AVC_HEVC, 3840, 2160).optString("url"));
        List<String> withVp9 = Arrays.asList("video/avc", "video/x-vnd.on2.vp9");
        assertEquals("vp9", MediaLink.pick(s, withVp9, 3840, 2160).optString("url"));
        assertNull(MediaLink.pick(new JSONArray().put(src("d", "application/dash+xml", 0, 0, null)), AVC_HEVC, 3840, 2160));
    }

    @Test
    public void portraitFitsByItsLongSide() throws Exception {
        JSONArray s = new JSONArray()
                .put(src("tall4k", "video/mp4", 2160, 3840, "hevc"))
                .put(src("tall", "video/mp4", 1080, 1920, "hevc"));
        assertEquals("tall4k", MediaLink.pick(s, AVC_HEVC, 3840, 2160).optString("url"));
        assertEquals("tall", MediaLink.pick(s, AVC_HEVC, 1920, 1080).optString("url"));
    }

    @Test
    public void aPlaylistOrAnUnknownSizeIsTaken() throws Exception {
        JSONArray s = new JSONArray().put(src("hls", "application/vnd.apple.mpegurl", 0, 0, null));
        assertEquals("hls", MediaLink.pick(s, AVC_HEVC, 3840, 2160).optString("url"));
        JSONArray bare = new JSONArray().put(new JSONObject().put("url", "x"));
        assertEquals("x", MediaLink.pick(bare, AVC_HEVC, 1920, 1080).optString("url"));
        assertTrue(MediaLink.playable("video/mp4; codecs=\"avc1\""));
        assertFalse(MediaLink.playable("application/dash+xml"));
    }

    @Test
    public void onlyAllowedHeadersGoAlong() throws Exception {
        Map<String, String> h = MediaLink.headers(new JSONObject()
                .put("referer", "https://site.example/")
                .put("Cookie", "a=1")
                .put("Host", "other.example")
                .put("X-Forwarded-For", "1.2.3.4")
                .put("User-Agent", "bad\r\nInjected: 1"));
        assertEquals("https://site.example/", h.get("Referer"));
        assertEquals("a=1", h.get("Cookie"));
        assertEquals(2, h.size());
    }

    @Test
    public void webVttBecomesSubRip() {
        String vtt = "WEBVTT\n\nNOTE a comment\n\n1\n00:01.000 --> 00:02.500 align:start\n<v Bob>Hello</v>\n\n"
                + "01:00:00.250 --> 01:00:01.000\nSecond\nline\n";
        String srt = MediaLink.vttToSrt(vtt);
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nHello\n\n"
                + "2\n01:00:00,250 --> 01:00:01,000\nSecond\nline\n\n", srt);
    }

    @Test
    public void times() {
        assertEquals("00:00:05,000", MediaLink.time("00:05"));
        assertEquals("00:01:02,500", MediaLink.time("01:02.5"));
        assertEquals("02:03:04,050", MediaLink.time("2:03:04.050"));
        assertNull(MediaLink.time("soon"));
    }
}
