// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The sound this device plays (a video in the gallery, music) goes to the receiver with the picture: captured with the same
 * MediaProjection (Android 10+), Opus-encoded in 20 ms packets, sent as PROTOCOL.md section 4 says. While it runs the
 * phone's own media volume is turned to zero, so the sound comes out of the TV only; it is put back when the session ends.
 */
final class AudioFeed {
    private static final String TAG = LuchService.TAG;
    private static final int RATE = 48000, CHANNELS = 2, CHUNK_MS = 20;
    private static final int CHUNK_BYTES = RATE * CHANNELS * 2 * CHUNK_MS / 1000;
    private static final String SAVED_VOLUME = "saved_music_volume";

    private final Context context;
    private final MediaProjection projection;
    private final DatagramSocket udp;
    private final InetAddress peer;
    private final boolean muteLocal;
    private volatile int port;
    private volatile boolean running;
    private AudioRecord record;
    private MediaCodec encoder;
    private Thread thread;
    private int seq;

    AudioFeed(Context context, MediaProjection projection, DatagramSocket udp, InetAddress peer, int port, boolean muteLocal) {
        this.context = context;
        this.projection = projection;
        this.udp = udp;
        this.peer = peer;
        this.port = port;
        this.muteLocal = muteLocal;
    }

    /** Android 10+ and the microphone permission, which playback capture is tied to. */
    static boolean possible(Context context) {
        return Build.VERSION.SDK_INT >= 29
                && context.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    void setPort(int port) {
        this.port = port;
    }

    boolean start() {
        if (!possible(context)) return false;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, CHUNK_BYTES * 4)).build();
            MediaFormat f = MediaFormat.createAudioFormat("audio/opus", RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            encoder = MediaCodec.createEncoderByType("audio/opus");
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (Exception e) {
            Log.w(TAG, "audio capture not available", e);
            release();
            return false;
        }
        if (muteLocal) muteLocalVolume();
        running = true;
        thread = new Thread(this::loop, "luch-sender-audio");
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        release();
        restoreVolume(context);
    }

    private void release() {
        try {
            if (record != null) record.release();
        } catch (RuntimeException ignored) {
        }
        record = null;
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (RuntimeException ignored) {
        }
        encoder = null;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] packet = new byte[24 + 1500];
        long lastConfig = 0;
        try {
            record.startRecording();
            while (running) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastConfig >= 500) {
                    lastConfig = now;
                    send(packet, opusHead(), true, System.nanoTime() / 1000);
                }
                int in = encoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer ib = encoder.getInputBuffer(in);
                    ib.clear();
                    int n = record.read(ib, CHUNK_BYTES, AudioRecord.READ_BLOCKING);
                    long pts = System.nanoTime() / 1000 - CHUNK_MS * 1000L; // when the first sample of the chunk was played
                    if (n > 0) encoder.queueInputBuffer(in, 0, n, pts, 0);
                    else encoder.queueInputBuffer(in, 0, 0, pts, 0);
                }
                int out;
                while ((out = encoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer ob = encoder.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        byte[] data = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(data, 0, info.size);
                        send(packet, data, false, info.presentationTimeUs);
                    }
                    encoder.releaseOutputBuffer(out, false);
                }
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "audio feed stopped", e);
        }
    }

    private void send(byte[] packet, byte[] data, boolean config, long pts) throws java.io.IOException {
        if (data.length > 1500 || port == 0) return;
        ByteBuffer h = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        h.put((byte) 2).put((byte) (config ? 2 : 0)).putShort((short) 0).putShort((short) 1).putShort((short) 0)
                .putInt(config ? 0 : seq++).putInt((int) (pts & 0xFFFFFFFFL)).putInt(data.length).putShort((short) 0)
                .putShort((short) 0);
        System.arraycopy(data, 0, packet, 24, data.length);
        udp.send(new DatagramPacket(packet, 24 + data.length, peer, port));
    }

    /** The 19-byte OpusHead the receiver's decoder starts from: stereo, 48 kHz, the encoder's 6.5 ms of lead-in. */
    static byte[] opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'}).put((byte) 1).put((byte) CHANNELS).putShort((short) 312)
                .putInt(RATE).putShort((short) 0).put((byte) 0);
        return b.array();
    }

    private void muteLocalVolume() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        SharedPrefs.put(context, SAVED_VOLUME, now); // survives a crash: the next start puts it back
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
    }

    /** Puts the phone's media volume back after a session (also at the next start, if the app was killed in between). */
    static void restoreVolume(Context context) {
        int saved = SharedPrefs.get(context, SAVED_VOLUME);
        if (saved < 0) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
        SharedPrefs.put(context, SAVED_VOLUME, -1);
    }

    /** The one small preference this class needs. */
    private static final class SharedPrefs {
        static int get(Context c, String k) {
            return c.getSharedPreferences("luch", Context.MODE_PRIVATE).getInt(k, -1);
        }

        static void put(Context c, String k, int v) {
            c.getSharedPreferences("luch", Context.MODE_PRIVATE).edit().putInt(k, v).apply();
        }
    }
}
