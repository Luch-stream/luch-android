// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.media.AudioDeviceInfo;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;

import ru.toxblh.luch.sync.AudioClock;

/**
 * The session's AudioTrack, counting what was written and knowing when it will be heard: the track's own timestamps
 * where the route has them, the playback head and a guess where it has not (sync.AudioClock).
 */
final class AudioOut {
    final AudioTrack track;
    final int channels;
    final AudioClock clock;
    /** Frames written so far (the W of docs: sync, 6.4). */
    private long written;
    private boolean playing;
    private final AudioTimestamp ts = new AudioTimestamp();
    private long headWraps, lastHead;
    private final int frameBytes;

    AudioOut(AudioTrack track, int rate, int channels) {
        this.track = track;
        this.channels = channels;
        this.clock = new AudioClock(rate);
        this.frameBytes = 2 * channels;
    }

    long written() {
        return written;
    }

    boolean playing() {
        return playing;
    }

    /** Writes without blocking (what does not fit is dropped), starts the track on the first sound; returns frames. */
    int write(byte[] pcm, int off, int bytes) {
        int n;
        if (Build.VERSION.SDK_INT >= 23) {
            n = track.write(pcm, off, bytes, AudioTrack.WRITE_NON_BLOCKING);
        } else {
            // Android 5: no non-blocking byte[] write; a direct ByteBuffer is.
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(pcm, off, bytes);
            n = track.write(b, bytes, AudioTrack.WRITE_NON_BLOCKING);
        }
        if (n <= 0) return 0;
        int frames = n / frameBytes;
        written += frames;
        if (!playing) {
            track.play();
            playing = true;
        }
        return frames;
    }

    /** Asks the track where it is, when the clock's schedule says so. */
    void poll(long nowNs) {
        if (!playing || nowNs < clock.nextPollNs()) return;
        boolean ok;
        try {
            ok = track.getTimestamp(ts);
        } catch (RuntimeException e) {
            ok = false;
        }
        clock.onTimestamp(ok, ts.framePosition, ts.nanoTime, nowNs);
        if (!"timestamp".equals(clock.source())) clock.onHead(head(), nowNs);
    }

    /** The playback head in frames, unwrapped (getPlaybackHeadPosition is an int that wraps after a day at 48 kHz). */
    private long head() {
        long h = track.getPlaybackHeadPosition() & 0xffffffffL;
        if (h < lastHead) headWraps += 1L << 32;
        lastHead = h;
        return headWraps + h;
    }

    /** When the next frame written will be heard (local ns); before the clock knows, a guess from the buffer. */
    long hearNs(long nowNs) {
        return clock.hearNs(written, buffered(), nowNs);
    }

    /** Frames written and not yet taken by the mixer. */
    int buffered() {
        return (int) Math.max(0, playing ? written - head() : written);
    }

    /** Where the sound goes, as the protocol names it ("builtin", "hdmi", "hdmi_arc", "bluetooth", "usb", "other"). */
    String route() {
        if (Build.VERSION.SDK_INT < 24) return "unknown";
        AudioDeviceInfo d = track.getRoutedDevice();
        return d == null ? "unknown" : route(d.getType());
    }

    static String route(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "builtin";
            case AudioDeviceInfo.TYPE_HDMI:
                return "hdmi";
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case 29:   // TYPE_HDMI_EARC, Android 12
                return "hdmi_arc";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case 26:   // TYPE_BLE_HEADSET, Android 12
            case 27:   // TYPE_BLE_SPEAKER
            case 30:   // TYPE_BLE_BROADCAST, Android 13
                return "bluetooth";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "usb";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "line";
            default:
                return "other";
        }
    }

    void release() {
        try {
            track.stop();
        } catch (RuntimeException ignored) {
        }
        track.release();
    }
}
