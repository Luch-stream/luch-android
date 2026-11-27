// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Data over sound (PROTOCOL.md 1d): this TV says its card (address, port, a pairing token) as a short run of tones
 * near the top of what people hear, for a sender's microphone. 16-tone FSK, one tone per 40 ms symbol (4 bits), a
 * fixed preamble, Reed-Solomon over GF(256) to mend lost tones; the same frames as luchd's find/modem.rs.
 * Pure code: bytes in, samples out (and back, for the tests).
 */
final class Chirp {
    static final int RATE = 48000;
    static final int SYMBOL = 1920;
    static final int TONES = 16;
    static final float SPACING = 100f;
    /** Tone 0 of each band: near-ultrasonic first, the audible band for speakers that cut the top. */
    static final float[] BANDS = {17000f, 1800f};
    /** All different: shifted by a symbol or more, the preamble matches nothing of itself. */
    static final int[] PREAMBLE = {0, 15, 3, 12, 6, 9, 1, 14};
    static final int PARITY = 8;
    static final int MAX_PAYLOAD = 32;
    private static final int RAMP = 96;
    private static final int WIN_START = SYMBOL / 8;
    private static final int WIN_LEN = SYMBOL * 3 / 4;

    private Chirp() {
    }

    // ---------- Reed-Solomon (polynomial 0x11d, first root α^0) ----------

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11d;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int mul(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static int div(int a, int b) {
        return a == 0 ? 0 : EXP[(LOG[a] + 255 - LOG[b]) % 255];
    }

    private static int alpha(int e) {
        return EXP[e % 255];
    }

    /** The message followed by {@code nsym} parity bytes. */
    static byte[] rsEncode(byte[] msg, int nsym) {
        int[] g = {1};
        for (int i = 0; i < nsym; i++) {
            int[] next = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) {
                next[j] ^= g[j];
                next[j + 1] ^= mul(g[j], alpha(i));
            }
            g = next;
        }
        int[] rem = new int[nsym];
        for (byte mb : msg) {
            int factor = (mb & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, nsym - 1);
            rem[nsym - 1] = 0;
            for (int j = 0; j < nsym; j++) rem[j] ^= mul(g[j + 1], factor);
        }
        byte[] out = Arrays.copyOf(msg, msg.length + nsym);
        for (int j = 0; j < nsym; j++) out[msg.length + j] = (byte) rem[j];
        return out;
    }

    private static int[] syndromes(int[] w, int nsym) {
        int[] s = new int[nsym];
        for (int j = 0; j < nsym; j++) {
            int a = alpha(j), acc = 0;
            for (int c : w) acc = mul(acc, a) ^ c;
            s[j] = acc;
        }
        return s;
    }

    private static boolean zero(int[] s) {
        for (int v : s) if (v != 0) return false;
        return true;
    }

    private static int eval(int[] p, int x) {
        int acc = 0;
        for (int i = p.length - 1; i >= 0; i--) acc = mul(acc, x) ^ p[i];
        return acc;
    }

    /** Corrects up to nsym/2 wrong bytes in place; false when there are more. */
    static boolean rsCorrect(int[] w, int nsym) {
        int[] s = syndromes(w, nsym);
        if (zero(s)) return true;
        int[] c = {1}, b = {1};
        int l = 0, m = 1, bd = 1;
        for (int n = 0; n < nsym; n++) {
            int d = s[n];
            for (int i = 1; i <= Math.min(l, c.length - 1); i++) d ^= mul(c[i], s[n - i]);
            if (d == 0) {
                m++;
                continue;
            }
            int coef = div(d, bd);
            int[] next = Arrays.copyOf(c, Math.max(c.length, b.length + m));
            for (int i = 0; i < b.length; i++) next[i + m] ^= mul(coef, b[i]);
            if (2 * l <= n) {
                b = c;
                l = n + 1 - l;
                bd = d;
                m = 1;
            } else {
                m++;
            }
            c = next;
        }
        if (l == 0 || 2 * l > nsym) return false;
        int len = w.length;
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            if (eval(c, alpha(255 - (len - 1 - i) % 255)) == 0) pos.add(i);
        }
        if (pos.size() != l) return false;
        int[] omega = new int[nsym];
        for (int i = 0; i < nsym; i++) {
            for (int j = 0; j < c.length && i + j < nsym; j++) omega[i + j] ^= mul(s[i], c[j]);
        }
        int[] deriv = new int[Math.max(1, c.length - 1)];
        for (int i = 1; i < c.length; i++) if (i % 2 == 1) deriv[i - 1] = c[i];
        for (int i : pos) {
            int power = (len - 1 - i) % 255;
            int xInv = alpha(255 - power);
            int denom = eval(deriv, xInv);
            if (denom == 0) return false;
            w[i] ^= mul(alpha(power), div(eval(omega, xInv), denom));
        }
        return zero(syndromes(w, nsym));
    }

    // ---------- the modem ----------

    /** Tone indices of a frame: preamble, then the codeword of [len, payload..], high nibble first. */
    static int[] symbols(byte[] payload) {
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too long");
        byte[] msg = new byte[payload.length + 1];
        msg[0] = (byte) payload.length;
        System.arraycopy(payload, 0, msg, 1, payload.length);
        byte[] word = rsEncode(msg, PARITY);
        int[] out = new int[PREAMBLE.length + word.length * 2];
        System.arraycopy(PREAMBLE, 0, out, 0, PREAMBLE.length);
        for (int i = 0; i < word.length; i++) {
            out[PREAMBLE.length + 2 * i] = (word[i] & 0xFF) >> 4;
            out[PREAMBLE.length + 2 * i + 1] = word[i] & 15;
        }
        return out;
    }

    /** The frame as samples at {@code rate} (the output's own: 48 or 44.1 kHz), peak {@code amp}. */
    static float[] synth(byte[] payload, float band, float amp, int rate) {
        int[] syms = symbols(payload);
        int per = SYMBOL * rate / RATE, ramp = RAMP * rate / RATE;
        float[] out = new float[syms.length * per];
        int k = 0;
        for (int s : syms) {
            double w = 2 * Math.PI * (band + s * SPACING) / rate;
            for (int i = 0; i < per; i++) {
                int edge = Math.min(i, per - 1 - i);
                double env = edge < ramp ? 0.5 - 0.5 * Math.cos(Math.PI * edge / ramp) : 1.0;
                out[k++] = (float) (amp * env * Math.sin(w * i));
            }
        }
        return out;
    }

    /** As 16-bit PCM, for AudioTrack. */
    static short[] pcm(float[] x) {
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (short) Math.round(Math.max(-1f, Math.min(1f, x[i])) * 32767);
        return out;
    }

    private static final float[] HANN = new float[WIN_LEN];

    static {
        for (int i = 0; i < WIN_LEN; i++) HANN[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN_LEN - 1)));
    }

    private static float[] energies(float[] x, int start, float band) {
        int from = start + WIN_START;
        if (from < 0 || from + WIN_LEN > x.length) return null;
        float[] e = new float[TONES];
        for (int k = 0; k < TONES; k++) {
            double w = 2 * Math.PI * (band + k * SPACING) / RATE;
            float coeff = (float) (2 * Math.cos(w));
            float s1 = 0, s2 = 0;
            for (int i = 0; i < WIN_LEN; i++) {
                float s0 = x[from + i] * HANN[i] + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            e[k] = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        }
        return e;
    }

    private static float share(float[] e, int tone) {
        float sum = 0;
        for (float v : e) sum += v;
        return sum <= 0 ? 0 : e[tone] / sum;
    }

    private static int best(float[] e) {
        int k = 0;
        for (int i = 1; i < TONES; i++) if (e[i] > e[k]) k = i;
        return k;
    }

    private static float preambleScore(float[] x, int t, float band) {
        float sum = 0;
        for (int i = 0; i < PREAMBLE.length; i++) {
            float[] e = energies(x, t + i * SYMBOL, band);
            if (e != null) sum += share(e, PREAMBLE[i]);
        }
        return sum / PREAMBLE.length;
    }

    private static byte[] frameAt(float[] x, int t0, float band) {
        int nudge = SYMBOL / 64, most = 2 * (1 + MAX_PAYLOAD + PARITY), offset = 0;
        int[] tones = new int[most];
        int n = 0;
        for (int k = 0; k < most; k++) {
            int at = t0 + (PREAMBLE.length + k) * SYMBOL + offset;
            int bestD = 0, bestTone = -1;
            float bestS = -1;
            for (int d : new int[] {0, -nudge, nudge}) {
                float[] e = energies(x, at + d, band);
                if (e == null) continue;
                int tone = best(e);
                float s = share(e, tone) * (d == 0 ? 1.05f : 1f);
                if (s > bestS) {
                    bestS = s;
                    bestD = d;
                    bestTone = tone;
                }
            }
            if (bestTone < 0) break;
            offset += bestD;
            tones[n++] = bestTone;
        }
        int[] bytes = new int[n / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = tones[2 * i] << 4 | tones[2 * i + 1];
        for (int len = 0; len <= MAX_PAYLOAD && len + 1 + PARITY <= bytes.length; len++) {
            int[] w = Arrays.copyOf(bytes, len + 1 + PARITY);
            if (rsCorrect(w, PARITY) && w[0] == len) {
                byte[] out = new byte[len];
                for (int i = 0; i < len; i++) out[i] = (byte) w[1 + i];
                return out;
            }
        }
        return null;
    }

    /** Every frame in {@code x} (48 kHz), each once. Slow and thorough: for tests and a phone listening. */
    static List<byte[]> demodulate(float[] x) {
        List<byte[]> out = new ArrayList<>();
        int step = SYMBOL / 8, perSymbol = SYMBOL / step;
        if (x.length < PREAMBLE.length * SYMBOL) return out;
        int grid = (x.length - PREAMBLE.length * SYMBOL) / step + 1;
        for (float band : BANDS) {
            float[] scores = new float[grid];
            for (int g = 0; g < grid; g++) scores[g] = preambleScore(x, g * step, band);
            for (int g = 0; g < grid; g++) {
                // A peak: the best score within a symbol either side.
                boolean peak = scores[g] > 0.5f;
                for (int n = Math.max(0, g - perSymbol); peak && n <= Math.min(grid - 1, g + perSymbol); n++) {
                    if (scores[n] > scores[g] || (scores[n] == scores[g] && n < g)) peak = false;
                }
                if (!peak) continue;
                int fine = g * step;
                float fs = -1;
                for (int d = -step; d <= step; d += 16) {
                    float v = preambleScore(x, g * step + d, band);
                    if (v > fs) {
                        fs = v;
                        fine = g * step + d;
                    }
                }
                byte[] p = frameAt(x, fine, band);
                if (p != null && !contains(out, p)) out.add(p);
            }
        }
        return out;
    }

    private static boolean contains(List<byte[]> list, byte[] p) {
        for (byte[] q : list) if (Arrays.equals(q, p)) return true;
        return false;
    }
}
