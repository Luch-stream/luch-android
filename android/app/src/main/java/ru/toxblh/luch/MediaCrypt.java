// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The media of a paired session (PROTOCOL.md, section 4): AES-128-GCM over each whole frame, not each datagram. A frame
 * is decoded only once its last shard is in anyway, so opening it then costs one call per frame (tens of microseconds
 * for a 1080p frame where the CPU has AES instructions) and no wait. The key comes with {@code ready} over TLS; the
 * nonce is the kind and the frame's sequence number, never repeated under one key; the header, but for the shard's
 * index and the parity bit, is authenticated with it.
 */
final class MediaCrypt {
    static final int KEY_BYTES = 16, TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final Cipher cipher;

    MediaCrypt(byte[] key) throws GeneralSecurityException {
        this.key = new SecretKeySpec(key, "AES");
        this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
    }

    static byte[] newKey() {
        byte[] k = new byte[KEY_BYTES];
        RANDOM.nextBytes(k);
        return k;
    }

    /** kind | 0 0 0 | seq (u32, big-endian) | 0 0 0 0. */
    static byte[] nonce(int kind, long seq) {
        return new byte[]{(byte) kind, 0, 0, 0, (byte) (seq >>> 24), (byte) (seq >>> 16), (byte) (seq >>> 8), (byte) seq,
                0, 0, 0, 0};
    }

    /** The 24-byte header as authenticated: the shard index zeroed and the parity bit cleared, the same for a frame. */
    static byte[] aad(byte[] header, int off) {
        byte[] a = new byte[MirrorSession.HEADER];
        System.arraycopy(header, off, a, 0, a.length);
        a[1] &= ~4;
        a[2] = 0;
        a[3] = 0;
        return a;
    }

    /** Opens {@code len} bytes of ciphertext and tag in place; the plain length, or -1 when it does not authenticate. */
    int open(int kind, long seq, byte[] aad, byte[] buf, int off, int len) {
        if (len < TAG_BYTES) return -1;
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(kind, seq)));
            cipher.updateAAD(aad);
            return cipher.doFinal(buf, off, len, buf, off);
        } catch (GeneralSecurityException | IllegalStateException bad) {
            return -1;
        }
    }

    /** The sender's side, for senders written in Java and for tests: ciphertext with the tag at its end. */
    byte[] seal(int kind, long seq, byte[] aad, byte[] data) throws GeneralSecurityException {
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(kind, seq)));
        cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }
}
