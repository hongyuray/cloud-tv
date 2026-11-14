package com.dsh.lightlive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * 极简 QR 编码器：字节模式 + 纠错等级 M + 版本 1..15，输出模块矩阵。
 * 只依赖 java.*，桌面端也能跑（用于离线自测）。
 */
public final class QrCode {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int[] TOTAL_CODEWORDS = {
            26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655
    };

    /** 每版本各数据块的数据码字数（纠错等级 M） */
    private static final int[][] BLOCK_DATA = {
            {16},
            {28},
            {44},
            {32, 32},
            {43, 43},
            {27, 27, 27, 27},
            {31, 31, 31, 31},
            {38, 38, 39, 39},
            {36, 36, 36, 37, 37},
            {43, 43, 43, 43, 44},
            {50, 51, 51, 51, 51},
            {36, 36, 36, 36, 36, 36, 37, 37},
            {37, 37, 37, 37, 37, 37, 37, 37, 38},
            {40, 40, 40, 40, 41, 41, 41, 41, 41},
            {41, 41, 41, 41, 41, 42, 42, 42, 42, 42}
    };

    private static final int[][] ALIGN = {
            {},
            {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}, {6, 30, 54},
            {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70}
    };

    private static final int ECC_BITS = 0; // M = 00

    public static boolean[][] encode(String text) {
        return encode(text, -1);
    }

    /** forceMask = -1 表示按惩罚分自动选best；0..7 强制某个掩码（自测用） */
    public static boolean[][] encode(String text, int forceMask) {
        byte[] payload = text.getBytes(UTF8);
        int version = pickVersion(payload.length);
        int size = version * 4 + 17;

        byte[] codewords = buildCodewords(payload, version);
        boolean[][] reserved = new boolean[size][size];
        boolean[][] modules = new boolean[size][size];

        placeFunctionPatterns(modules, reserved, version, size);

        placeData(modules, reserved, codewords, size);

        int mask = forceMask >= 0 ? forceMask : chooseMask(modules, reserved, size);
        applyMask(modules, reserved, mask, size);
        placeFormat(modules, size, mask);
        if (version >= 7) placeVersion(modules, size, version);
        return modules;
    }

    // ---------------- 版本与码字 ----------------

    private static int pickVersion(int byteLen) {
        for (int v = 1; v <= 15; v++) {
            int dataCodewords = 0;
            for (int d : BLOCK_DATA[v - 1]) dataCodewords += d;
            int need = 4 + (v < 10 ? 8 : 16) + byteLen * 8;
            if (need <= dataCodewords * 8) return v;
        }
        throw new IllegalArgumentException("内容过长，无法生成二维码");
    }

    private static byte[] buildCodewords(byte[] payload, int version) {
        int dataCount = 0;
        for (int d : BLOCK_DATA[version - 1]) dataCount += d;

        BitBuf bb = new BitBuf(dataCount * 8);
        bb.append(0x4, 4);                       // 字节模式
        bb.append(payload.length, version < 10 ? 8 : 16);
        for (byte b : payload) bb.append(b & 0xFF, 8);
        int remain = dataCount * 8 - bb.size();
        if (remain > 0) bb.append(0, Math.min(4, remain));
        while (bb.size() % 8 != 0) bb.append(0, 1);
        int pad = 0;
        while (bb.size() < dataCount * 8) {
            bb.append(pad % 2 == 0 ? 0xEC : 0x11, 8);
            pad++;
        }
        byte[] dataCw = bb.toBytes();

        int total = TOTAL_CODEWORDS[version - 1];
        int blocks = BLOCK_DATA[version - 1].length;
        int ecPerBlock = total / blocks - BLOCK_DATA[version - 1][0];

        byte[][] dataBlocks = new byte[blocks][];
        byte[][] ecBlocks = new byte[blocks][];
        int off = 0;
        for (int i = 0; i < blocks; i++) {
            int n = BLOCK_DATA[version - 1][i];
            dataBlocks[i] = new byte[n];
            System.arraycopy(dataCw, off, dataBlocks[i], 0, n);
            off += n;
            ecBlocks[i] = rsEncode(dataBlocks[i], ecPerBlock);
        }

        byte[] out = new byte[total];
        int p = 0;
        int maxData = 0;
        for (int d : BLOCK_DATA[version - 1]) maxData = Math.max(maxData, d);
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dataBlocks[b].length) out[p++] = dataBlocks[b][i];
            }
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < blocks; b++) out[p++] = ecBlocks[b][i];
        }
        return out;
    }

    // ---------------- Reed-Solomon ----------------

    private static int[] EXP = new int[512];
    private static int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static byte[] rsEncode(byte[] data, int ecLen) {
        int[] gen = new int[ecLen];
        gen[ecLen - 1] = 1;
        int root = 1;
        for (int i = 0; i < ecLen; i++) {
            for (int j = 0; j < ecLen; j++) {
                gen[j] = gfMul(gen[j], root);
                if (j + 1 < ecLen) gen[j] ^= gen[j + 1];
            }
            root = gfMul(root, 2);
        }
        int[] res = new int[ecLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ res[0];
            System.arraycopy(res, 1, res, 0, ecLen - 1);
            res[ecLen - 1] = 0;
            if (factor != 0) {
                for (int i = 0; i < ecLen; i++) res[i] ^= gfMul(gen[i], factor);
            }
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) out[i] = (byte) res[i];
        return out;
    }

    // ---------------- 图形布置 ----------------

    private static void placeFunctionPatterns(boolean[][] m, boolean[][] res, int version, int size) {
        for (int i = 0; i < size; i++) {
            res[6][i] = true;
            res[i][6] = true;
        }
        // 定位图形
        finder(m, res, 0, 0);
        finder(m, res, 0, size - 7);
        finder(m, res, size - 7, 0);
        // 校正图形
        int[] pos = ALIGN[version - 1];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                int r = pos[i], c = pos[j];
                if ((r <= 8 && c <= 8) || (r <= 8 && c >= size - 9) || (r >= size - 9 && c <= 8)) continue;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        boolean dark = Math.max(Math.abs(dr), Math.abs(dc)) != 1;
                        m[r + dr][c + dc] = dark;
                        res[r + dr][c + dc] = true;
                    }
                }
            }
        }
        // 时序
        for (int i = 8; i < size - 8; i++) {
            boolean dark = (i % 2 == 0);
            m[6][i] = dark;
            m[i][6] = dark;
            res[6][i] = true;
            res[i][6] = true;
        }
        // 格式信息保留区
        for (int i = 0; i < 9; i++) {
            if (i != 6) {
                res[8][i] = true;
                res[i][8] = true;
            }
        }
        for (int i = 0; i < 8; i++) {
            res[8][size - 1 - i] = true;
            res[size - 1 - i][8] = true;
        }
        res[8][8] = true;
        // 固定黑点
        m[size - 8][8] = true;
        res[size - 8][8] = true;
        // 版本信息区
        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    res[size - 11 + j][i] = true;
                    res[i][size - 11 + j] = true;
                }
            }
        }
    }

    private static void finder(boolean[][] m, boolean[][] res, int row, int col) {
        for (int dr = -1; dr <= 7; dr++) {
            for (int dc = -1; dc <= 7; dc++) {
                int r = row + dr, c = col + dc;
                if (r < 0 || c < 0 || r >= m.length || c >= m.length) continue;
                boolean dark = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6
                        && (dr == 0 || dr == 6 || dc == 0 || dc == 6
                        || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
                m[r][c] = dark;
                res[r][c] = true;
            }
        }
    }

    private static void placeData(boolean[][] m, boolean[][] res, byte[] data, int size) {
        int bit = 0;
        int total = data.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!res[y][x] && bit < total) {
                        m[y][x] = ((data[bit >> 3] >> (7 - (bit & 7))) & 1) == 1;
                        bit++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] res, int mask, int size) {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (res[r][c]) continue;
                if (maskBit(mask, r, c)) m[r][c] = !m[r][c];
            }
        }
    }

    private static boolean maskBit(int mask, int r, int c) {
        switch (mask) {
            case 0: return (r + c) % 2 == 0;
            case 1: return r % 2 == 0;
            case 2: return c % 3 == 0;
            case 3: return (r + c) % 3 == 0;
            case 4: return (r / 2 + c / 3) % 2 == 0;
            case 5: return (r * c) % 2 + (r * c) % 3 == 0;
            case 6: return ((r * c) % 2 + (r * c) % 3) % 2 == 0;
            default: return ((r + c) % 2 + (r * c) % 3) % 2 == 0;
        }
    }

    private static int chooseMask(boolean[][] m, boolean[][] res, int size) {
        int best = 0, bestScore = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] t = new boolean[size][size];
            for (int r = 0; r < size; r++) t[r] = m[r].clone();
            applyMask(t, res, mask, size);
            placeFormat(t, size, mask);
            int s = penalty(t, size);
            if (s < bestScore) {
                bestScore = s;
                best = mask;
            }
        }
        return best;
    }

    // ---------------- 格式/版本信息 ----------------

    private static void placeFormat(boolean[][] m, int size, int mask) {
        int data = (ECC_BITS << 3) | mask;
        int d = data << 10;
        for (int i = 14; i >= 10; i--) {
            if (((d >> i) & 1) != 0) d ^= 0x537 << (i - 10);
        }
        int value = ((data << 10) | (d & 0x3FF)) ^ 0x5412;

        // 竖排副本（第 8 列）
        for (int i = 0; i <= 5; i++) m[i][8] = ((value >> i) & 1) == 1;
        m[7][8] = ((value >> 6) & 1) == 1;
        m[8][8] = ((value >> 7) & 1) == 1;
        for (int i = 8; i < 15; i++) m[size - 15 + i][8] = ((value >> i) & 1) == 1;

        // 横排副本（第 8 行）
        for (int i = 0; i < 8; i++) m[8][size - 1 - i] = ((value >> i) & 1) == 1;
        m[8][7] = ((value >> 8) & 1) == 1;
        for (int i = 9; i < 15; i++) m[8][14 - i] = ((value >> i) & 1) == 1;

        m[size - 8][8] = true;
    }

    private static void placeVersion(boolean[][] m, int size, int version) {
        int d = version << 12;
        for (int i = 17; i >= 12; i--) {
            if (((d >> i) & 1) != 0) d ^= 0x1F25 << (i - 12);
        }
        int value = (version << 12) | (d & 0xFFF);
        for (int i = 0; i < 18; i++) {
            boolean dark = ((value >> i) & 1) == 1;
            m[size - 11 + i % 3][i / 3] = dark;
            m[i / 3][size - 11 + i % 3] = dark;
        }
    }

    // ---------------- 掩码评分 ----------------

    private static int penalty(boolean[][] m, int size) {
        int score = 0;
        for (int r = 0; r < size; r++) {
            int run = 1;
            for (int c = 1; c < size; c++) {
                if (m[r][c] == m[r][c - 1]) {
                    run++;
                } else {
                    if (run >= 5) score += 3 + (run - 5);
                    run = 1;
                }
            }
            if (run >= 5) score += 3 + (run - 5);
        }
        for (int c = 0; c < size; c++) {
            int run = 1;
            for (int r = 1; r < size; r++) {
                if (m[r][c] == m[r - 1][c]) {
                    run++;
                } else {
                    if (run >= 5) score += 3 + (run - 5);
                    run = 1;
                }
            }
            if (run >= 5) score += 3 + (run - 5);
        }
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                boolean v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3;
            }
        }
        int dark = 0;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (m[r][c]) dark++;
            }
        }
        int total = size * size;
        int percent = dark * 100 / total;
        score += 10 * (Math.abs(percent - 50) / 5);
        return score;
    }

    // ---------------- 位缓冲 ----------------

    private static final class BitBuf {
        private byte[] buf;
        private int bitLen;

        BitBuf(int capacity) {
            buf = new byte[Math.max(16, capacity / 8 + 8)];
        }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                int idx = bitLen >> 3;
                if (idx >= buf.length) {
                    byte[] nb = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, nb, 0, buf.length);
                    buf = nb;
                }
                if (((value >> i) & 1) != 0) buf[idx] |= (byte) (1 << (7 - (bitLen & 7)));
                bitLen++;
            }
        }

        int size() {
            return bitLen;
        }

        byte[] toBytes() {
            byte[] out = new byte[(bitLen + 7) / 8];
            System.arraycopy(buf, 0, out, 0, out.length);
            return out;
        }
    }
}
