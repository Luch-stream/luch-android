// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * A hardware video decoder set up for the lowest latency it accepts, after moonlight-android's MediaCodecHelper and
 * MediaCodecDecoderRenderer: every known low-latency key first, and on a failed configure() the riskiest key goes and
 * the next attempt starts, down to a plain configuration.
 */
final class LowLatencyDecoder {
    private LowLatencyDecoder() {
    }

    /** Keys tried, least risky first; attempts drop them from the end. Value 1 unless noted. */
    private static final String[] VENDOR_KEYS = {
            "vendor.low-latency.enable",                      // Amlogic
            "vendor.qti-ext-dec-low-latency.enable",          // Qualcomm
            "vendor.qti-ext-dec-picture-order.enable",        // Qualcomm
            "vendor.rtc-ext-dec-low-latency.enable",          // Realtek
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",  // HiSilicon
    };

    static MediaCodec create(String mime, int width, int height, int fps, Surface surface, boolean operatingRate) {
        String name = hardwareDecoder(mime);
        if (name == null) return null;
        MediaCodec codec;
        try {
            codec = MediaCodec.createByCodecName(name);
        } catch (Exception e) {
            Log.w(LuchService.TAG, "create " + name, e);
            return null;
        }
        boolean qualcomm = name.toLowerCase().contains("qcom") || name.toLowerCase().contains("qti");

        // The full list, in the order they are given up: vendor keys first, then the generic ones.
        List<String> keys = new ArrayList<>();
        keys.add("priority");
        if (Build.VERSION.SDK_INT >= 30) keys.add(MediaFormat.KEY_LOW_LATENCY);
        keys.add("vdec-lowlatency");   // MediaTek and Amlogic, outside the vendor namespace
        List<String> supportedVendor = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                supportedVendor = codec.getSupportedVendorParameters();
            } catch (RuntimeException ignored) {
            }
        }
        for (String k : VENDOR_KEYS) {
            if (supportedVendor == null || supportedVendor.contains(k)) keys.add(k);
        }
        Log.i(LuchService.TAG, "decoder " + name + " vendor params: " + supportedVendor);

        for (int keep = keys.size(); keep >= 0; keep--) {
            MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 1 << 20));
            List<String> used = keys.subList(0, keep);
            for (String k : used) {
                if (k.equals("priority")) {
                    if (qualcomm || operatingRate) f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
                    if (!qualcomm) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                } else {
                    f.setInteger(k, 1);
                }
            }
            try {
                codec.configure(f, surface, null, 0);
                codec.start();
                Log.i(LuchService.TAG, "decoder " + name + " " + width + "x" + height + "@" + fps + " with " + used);
                return codec;
            } catch (RuntimeException e) {
                Log.i(LuchService.TAG, "configure with " + used + " failed: " + e);
                try {
                    codec.reset();
                } catch (RuntimeException ignored) {
                }
            }
        }
        codec.release();
        return null;
    }

    /** The first hardware decoder for the type, or any decoder if none says it is hardware (API < 29). */
    static String hardwareDecoder(String mime) {
        String fallback = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            boolean types = false;
            for (String t : info.getSupportedTypes()) types |= t.equalsIgnoreCase(mime);
            if (!types) continue;
            if (Build.VERSION.SDK_INT >= 29) {
                if (info.isHardwareAccelerated() && !info.isSoftwareOnly()) return info.getName();
            } else if (!info.getName().startsWith("OMX.google.") && !info.getName().startsWith("c2.android.")) {
                return info.getName();
            }
            if (fallback == null) fallback = info.getName();
        }
        return fallback;
    }
}
