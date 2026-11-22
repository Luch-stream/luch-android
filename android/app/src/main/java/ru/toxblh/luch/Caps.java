// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;
import android.view.Surface;

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
            out.put("device", DeviceState.snapshot(context));
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

    /**
     * What works worse on this Android version than on 8.0+, for the sender to tell its user and pick a mode:
     * {"audio_latency_ms":N, "limits":[...]}. docs/older-android.md says what each limit means.
     */
    static JSONObject limits() {
        JSONObject out = new JSONObject();
        JSONArray l = new JSONArray();
        try {
            // Android 7 lets the receiver shorten the audio buffer to about 20 ms; before it, the system's minimum
            // stays, and the sound comes that much after the picture (or the picture waits, with "sync").
            int min = android.media.AudioTrack.getMinBufferSize(48000, android.media.AudioFormat.CHANNEL_OUT_STEREO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            int ms = Build.VERSION.SDK_INT >= 24 ? 20 : Math.max(20, min / 4 * 1000 / 48000);
            out.put("audio_latency_ms", ms);
            if (Build.VERSION.SDK_INT < 24) l.put("audio_lag");
            if (Build.VERSION.SDK_INT < 30) l.put("decoder_low_latency");     // KEY_LOW_LATENCY is Android 11
            if (Build.VERSION.SDK_INT < 30) l.put("game_mode");               // minimal post-processing, Android 11
            if (Build.VERSION.SDK_INT < 29) l.put("wifi_low_latency");        // WIFI_MODE_FULL_LOW_LATENCY, Android 10
            if (Build.VERSION.SDK_INT < 24) l.put("hdr_info");
            if (Build.VERSION.SDK_INT < 23) l.put("display_modes");
            out.put("limits", l);
        } catch (JSONException ignored) {
        }
        return out;
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

    /** Tell a TV from a phone or a tablet: the sender adapts its stream to handhelds, not to TVs. */
    private static String deviceType(Context context) {
        UiModeManager ui = context.getSystemService(UiModeManager.class);
        if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return "tv";
        int smallestWidthDp = context.getResources().getConfiguration().smallestScreenWidthDp;
        return smallestWidthDp >= 600 ? "tablet" : "phone";
    }

    private static JSONObject display(Context context) throws JSONException {
        JSONObject out = new JSONObject();
        Display d = Compat.service(context, DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return out;
        JSONArray modes = new JSONArray();
        if (Build.VERSION.SDK_INT >= 23) {
            for (Display.Mode m : d.getSupportedModes()) {
                modes.put(m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + Math.round(m.getRefreshRate()));
            }
        } else {
            // Android 5: only the current mode is known.
            android.graphics.Point size = new android.graphics.Point();
            d.getRealSize(size);
            modes.put(Math.max(size.x, size.y) + "x" + Math.min(size.x, size.y) + "@" + Math.round(d.getRefreshRate()));
        }
        out.put("modes", modes);
        // What the client would render into right now: mode with the rotation applied, and the rate.
        Display.Mode current = d.getMode();
        int cw = current.getPhysicalWidth(), ch = current.getPhysicalHeight();
        int rotation = d.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            int swap = cw; cw = ch; ch = swap;
        }
        out.put("current", cw + "x" + ch + "@" + Math.round(current.getRefreshRate()));
        out.put("rotation", rotation * 90);
        out.put("refresh", Math.round(d.getRefreshRate()));
        out.put("type", deviceType(context));
        JSONArray hdr = new JSONArray();
        Display.HdrCapabilities h = Build.VERSION.SDK_INT >= 24 ? d.getHdrCapabilities() : null;
        if (h != null && Build.VERSION.SDK_INT >= 24) {
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
