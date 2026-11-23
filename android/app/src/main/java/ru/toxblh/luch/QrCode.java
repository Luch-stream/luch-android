package ru.toxblh.luch;

import java.nio.charset.StandardCharsets;

/**
 * A QR code, byte mode, error correction H (a third of the picture can be lost, so it survives being drawn as dots),
 * versions 1-12 (up to 154 bytes). Standard layout, mask 0.
 */
final class QrCode {
    private static final int[] ECC_PER_BLOCK = {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28};
    private static final int[] BLOCKS = {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11};

    final int size;
    private final boolean[][] modules;
    private final boolean[][] function;

    private QrCode(int version, byte[] data) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns(version);
        drawCodewords(interleave(data, version));
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
        drawFormatBits();
    }

    boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && modules[y][x];
    }

    /** True for the three corner squares and the alignment squares, which readers look for first. */
    boolean isFunction(int x, int y) {
        return function[y][x];
    }

    static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= 12; version++) {
            int capacity = rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
            int countBits = version < 10 ? 8 : 16;
            int needBits = 4 + countBits + bytes.length * 8;
            if (needBits > capacity * 8) continue;
            java.util.BitSet bits = new java.util.BitSet();
            int[] len = {0};
            append(bits, len, 0x4, 4);
            append(bits, len, bytes.length, countBits);
            for (byte b : bytes) append(bits, len, b & 0xFF, 8);
            len[0] += Math.min(4, capacity * 8 - len[0]);
            len[0] = (len[0] + 7) / 8 * 8;
            byte[] data = new byte[capacity];
            for (int i = 0; i < len[0]; i++) if (bits.get(i)) data[i >> 3] |= 1 << (7 - (i & 7));
            for (int i = len[0] / 8, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) data[i] = (byte) pad;
            return new QrCode(version, data);
        }
        throw new IllegalArgumentException("too long for a QR code");
    }

    private static void append(java.util.BitSet bits, int[] len, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits.set(len[0]++, ((value >>> i) & 1) != 0);
    }

    private static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int align = ver == 1 ? 0 : ver / 7 + 2;
        int[] pos = new int[align];
        if (align > 0) {
            int step = (ver * 4 + align * 2 + 1) / (align * 2 - 2) * 2;
            pos[0] = 6;
            for (int i = align - 1, p = size - 7; i >= 1; i--, p -= step) pos[i] = p;
        }
        for (int i = 0; i < align; i++) {
            for (int j = 0; j < align; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == align - 1) || (i == align - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        drawFormatBits();
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist != 2 && dist != 4);
            }
        }
    }

    /** Error correction H is 2 in the format bits; the mask is 0. */
    private void drawFormatBits() {
        int data = 2 << 3;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static byte[] interleave(byte[] data, int ver) {
        int blocks = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int shortLen = raw / blocks, shortCount = blocks - raw % blocks;
        byte[] divisor = divisor(ecc);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortCount ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] e = remainder(dat, divisor);
            System.arraycopy(e, 0, block, block.length - ecc, ecc);
            all[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < all[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortCount) out[k++] = all[j][i];
            }
        }
        return out;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }
}
