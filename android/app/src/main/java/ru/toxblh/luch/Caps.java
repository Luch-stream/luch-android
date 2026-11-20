// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;
import java.util.TreeSet;

/**
 * What the TV's hardware says it can do, read from MediaCodecList and the display: no decoding, so it is instant
 * and safe to send in every "welcome". {@link Bench} adds what it really does, measured.
 */
final class Caps {
    private Caps() {
    }

    static JSONObject collect(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("video", video());
            out.put("audio", audio());
            out.put("display", display(context));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Hardware video decoders: largest frame, what rates the big sizes run at, 10-bit and HDR profiles. */
    private static JSONObject video() throws JSONException {
        JSONObject out = new JSONObject();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= 29 && (!info.isHardwareAccelerated() || info.isSoftwareOnly())) continue;
            for (String mime : info.getSupportedTypes()) {
                if (!mime.startsWith("video/") || out.has(mime)) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                if (v == null) continue;
                JSONObject c = new JSONObject();
                c.put("decoder", info.getName());
                c.put("max", v.getSupportedWidths().getUpper() + "x" + v.getSupportedHeights().getUpper());
                c.put("1080p60", v.areSizeAndRateSupported(1920, 1080, 60));
                c.put("1080p120", v.areSizeAndRateSupported(1920, 1080, 120));
                c.put("2160p30", v.areSizeAndRateSupported(3840, 2160, 30));
                c.put("2160p60", v.areSizeAndRateSupported(3840, 2160, 60));
                c.put("10bit", tenBit(mime, caps));
                c.put("low_latency", Build.VERSION.SDK_INT >= 30
                        && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
                out.put(mime, c);
            }
        }
        return out;
    }

    private static boolean tenBit(String mime, MediaCodecInfo.CodecCapabilities caps) {
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            switch (mime) {
                case "video/hevc":
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            || pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) return true;
                    break;
                case "video/x-vnd.on2.vp9":
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2
                            || pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR) return true;
                    break;
                case "video/av01":
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                            || pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) return true;
                    break;
                case "video/avc":
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /** Audio formats there is any decoder for (opus, aac, ac3, eac3, ...). */
    private static JSONArray audio() {
        Set<String> types = new TreeSet<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) if (t.startsWith("audio/")) types.add(t);
        }
        return new JSONArray(types);
    }

    private static JSONObject display(Context context) throws JSONException {
        JSONObject out = new JSONObject();
        Display d = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return out;
        JSONArray modes = new JSONArray();
        for (Display.Mode m : d.getSupportedModes()) {
            modes.put(m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + Math.round(m.getRefreshRate()));
        }
        out.put("modes", modes);
        JSONArray hdr = new JSONArray();
        Display.HdrCapabilities h = d.getHdrCapabilities();
        if (h != null) {
            for (int t : h.getSupportedHdrTypes()) {
                switch (t) {
                    case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: hdr.put("dolby_vision"); break;
                    case Display.HdrCapabilities.HDR_TYPE_HDR10: hdr.put("hdr10"); break;
                    case Display.HdrCapabilities.HDR_TYPE_HLG: hdr.put("hlg"); break;
                    case 4: hdr.put("hdr10plus"); break;
                    default: hdr.put(String.valueOf(t)); break;
                }
            }
        }
        out.put("hdr", hdr);
        if (Build.VERSION.SDK_INT >= 30) out.put("game_mode", d.isMinimalPostProcessingSupported());
        return out;
    }
}
