package com.airmusic.player.transfer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal QR Code generator (byte mode, automatic version), ported from the
 * MIT-licensed qrcodegen library by Project Nayuki
 * (https://www.nayuki.io/page/qr-code-generator-library).
 */
public final class QrEncoder {

    private QrEncoder() {
    }

    /** Encodes {@code text} as a QR matrix; true = dark module, border=4. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        QrCode qr = QrCode.encodeSegments(
                java.util.Collections.singletonList(QrSegment.makeBytes(data)),
                QrCode.Ecc.MEDIUM);
        int size = qr.size + 8;
        boolean[][] matrix = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = y - 4, j = x - 4;
                matrix[y][x] = i >= 0 && j >= 0 && i < qr.size && j < qr.size
                        && qr.getModule(j, i);
            }
        }
        return matrix;
    }

    private static final class QrCode {

        final int version;
        final int size;
        final Ecc errorCorrectionLevel;
        private boolean[][] modules;

        private QrCode(int ver, Ecc ecl) {
            version = ver;
            size = ver * 4 + 17;
            errorCorrectionLevel = ecl;
            modules = new boolean[size][size];
        }

        boolean getModule(int x, int y) {
            return 0 <= x && x < size && 0 <= y && y < size && modules[y][x];
        }

        static QrCode encodeSegments(List<QrSegment> segs, Ecc ecl) {
            int ver = 1;
            while (getNumDataCodewords(ver, ecl) * 8 < usedBits(segs, ver)) {
                ver++;
                if (ver > 40) {
                    throw new IllegalArgumentException("Data too long");
                }
            }
            QrCode qr = new QrCode(ver, ecl);
            BitBuffer bb = new BitBuffer();
            for (QrSegment seg : segs) {
                bb.appendBits(seg.mode.modeBits, 4);
                bb.appendBits(seg.numChars, seg.mode.numCharCountBits(ver));
                bb.appendData(seg.data);
            }
            int dataCapacityBits = getNumDataCodewords(ver, ecl) * 8;
            bb.appendBits(0, Math.min(4, dataCapacityBits - bb.bitLength));
            bb.appendBits(0, (8 - bb.bitLength % 8) % 8);
            for (int i = 0; i < 4 && bb.bitLength < dataCapacityBits; i++) {
                bb.appendBits(0xEC, 8);
                if (bb.bitLength < dataCapacityBits) {
                    bb.appendBits(0x11, 8);
                }
            }
            byte[] dataCodewords = new byte[bb.bitLength / 8];
            for (int i = 0; i < dataCodewords.length; i++) {
                dataCodewords[i] = (byte) bb.getBits(i * 8, 8);
            }
            byte[] allCodewords = qr.addEccAndInterleave(dataCodewords);
            if (System.getProperty("qr.debug") != null) {
                StringBuilder bitb = new StringBuilder();
                for (int i = 0; i < bb.bitLength; i++) {
                    bitb.append(bb.data.get(i));
                }
                System.out.println("QR bits: " + bitb);
                System.out.println("QR bits 80..110: " + bitb.substring(80, Math.min(110, bb.bitLength)));
                StringBuilder sb = new StringBuilder();
                for (byte b : dataCodewords) sb.append(String.format("%02x ", b));
                System.out.println("QR data codewords: " + sb);
                StringBuilder sb2 = new StringBuilder();
                for (byte b : allCodewords) sb2.append(String.format("%02x ", b));
                System.out.println("QR all codewords: " + sb2);
            }
            qr.drawFunctionPatterns();
            qr.drawCodewords(allCodewords);
            int mask = 0;
            int minPenalty = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                qr.applyMask(i);
                qr.drawFormatBits(i);
                int penalty = qr.getPenaltyScore();
                if (penalty < minPenalty) {
                    mask = i;
                    minPenalty = penalty;
                }
                qr.applyMask(i);
            }
            qr.applyMask(mask);
            qr.drawFormatBits(mask);
            if (System.getProperty("qr.debug") != null) {
                System.out.println("QR mask: " + mask + " version: " + qr.version);
            }
            return qr;
        }

        private static int usedBits(List<QrSegment> segs, int version) {
            int bits = 0;
            for (QrSegment seg : segs) {
                bits += seg.mode.numCharCountBits(version) + seg.numBits;
            }
            return bits;
        }

        private void drawFunctionPatterns() {
            for (int i = 0; i < size; i++) {
                setFunctionModule(6, i, i % 2 == 0);
                setFunctionModule(i, 6, i % 2 == 0);
            }
            drawFinderPattern(3, 3);
            drawFinderPattern(size - 4, 3);
            drawFinderPattern(3, size - 4);
            int[] alignPatPos = getAlignmentPatternPositions();
            int numAlign = alignPatPos.length;
            for (int i = 0; i < numAlign; i++) {
                for (int j = 0; j < numAlign; j++) {
                    if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1
                            || i == numAlign - 1 && j == 0)) {
                        drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
                    }
                }
            }
            drawFormatBits(0);
            drawVersion();
        }

        private void drawFormatBits(int mask) {
            int data = errorCorrectionLevel.formatBits << 3 | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) {
                rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            }
            int bits = (data << 10 | rem) ^ 0x5412;
            for (int i = 0; i <= 5; i++) {
                setFunctionModule(8, i, getBit(bits, i));
            }
            setFunctionModule(8, 7, getBit(bits, 6));
            setFunctionModule(8, 8, getBit(bits, 7));
            setFunctionModule(7, 8, getBit(bits, 8));
            for (int i = 9; i < 15; i++) {
                setFunctionModule(14 - i, 8, getBit(bits, i));
            }
            for (int i = 0; i < 8; i++) {
                setFunctionModule(size - 1 - i, 8, getBit(bits, i));
            }
            for (int i = 8; i < 15; i++) {
                setFunctionModule(8, size - 15 + i, getBit(bits, i));
            }
            setFunctionModule(8, size - 8, true);
        }

        private void drawVersion() {
            if (version < 7) return;
            int rem = version;
            for (int i = 0; i < 12; i++) {
                rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            }
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = getBit(bits, i);
                int a = size - 11 + i % 3;
                int b = i / 3;
                setFunctionModule(a, b, bit);
                setFunctionModule(b, a, bit);
            }
        }

        private void drawFinderPattern(int x, int y) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    int xx = x + dx, yy = y + dy;
                    if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                        setFunctionModule(xx, yy, dist != 2 && dist != 4);
                    }
                }
            }
        }

        private void drawAlignmentPattern(int x, int y) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }

        private void setFunctionModule(int x, int y, boolean isDark) {
            modules[y][x] = isDark;
        }

        private void applyMask(int mask) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean invert = mask == 0 ? (x + y) % 2 == 0
                            : mask == 1 ? y % 2 == 0
                            : mask == 2 ? x % 3 == 0
                            : mask == 3 ? (x + y) % 3 == 0
                            : mask == 4 ? (x / 3 + y / 2) % 2 == 0
                            : mask == 5 ? x * y % 2 + x * y % 3 == 0
                            : mask == 6 ? (x * y % 2 + x * y % 3) % 2 == 0
                            : ((x + y) % 2 + x * y % 3) % 2 == 0;
                    modules[y][x] ^= invert & !isFunctionModule(x, y);
                }
            }
        }

        private boolean isFunctionModule(int x, int y) {
            if (x < 0 || y < 0 || x >= size || y >= size) return false;
            if (x < 9 && y < 9 || x < 9 && y >= size - 8 || x >= size - 8 && y < 9) {
                return true;
            }
            if (x == 6 || y == 6) return true;
            int[] alignPatPos = getAlignmentPatternPositions();
            int numAlign = alignPatPos.length;
            for (int i = 0; i < numAlign; i++) {
                for (int j = 0; j < numAlign; j++) {
                    if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1
                            || i == numAlign - 1 && j == 0)) {
                        if (Math.abs(x - alignPatPos[i]) <= 2
                                && Math.abs(y - alignPatPos[j]) <= 2) {
                            return true;
                        }
                    }
                }
            }
            return false;
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
                        if (!isFunctionModule(x, y) && i < data.length * 8) {
                            modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
        }

        private byte[] addEccAndInterleave(byte[] data) {
            int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel.ordinal()][version];
            int blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal()][version];
            int rawCodewords = getNumRawDataModules(version) / 8;
            int numShortBlocks = numBlocks - rawCodewords % numBlocks;
            int shortBlockLen = rawCodewords / numBlocks;
            byte[][] blocks = new byte[numBlocks][];
            int k = 0;
            for (int i = 0; i < numBlocks; i++) {
                int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
                byte[] dat = Arrays.copyOfRange(data, k, k + datLen);
                k += datLen;
                byte[] block = new byte[shortBlockLen + 1];
                System.arraycopy(dat, 0, block, 0, datLen);
                byte[] ecc = computeRemainder(dat, blockEccLen);
                System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
                blocks[i] = block;
            }
            byte[] result = new byte[rawCodewords];
            int dest = 0;
            for (int i = 0; i < shortBlockLen + 1; i++) {
                for (int j = 0; j < numBlocks; j++) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[dest++] = blocks[j][i];
                    }
                }
            }
            return result;
        }

        private static byte[] computeRemainder(byte[] data, int divisorLen) {
            int[] divisor = new int[divisorLen];
            divisor[divisorLen - 1] = 1;
            int root = 1;
            for (int i = 0; i < divisorLen; i++) {
                for (int j = 0; j < divisor.length; j++) {
                    divisor[j] = mul(divisor[j], root);
                    if (j + 1 < divisor.length) divisor[j] ^= divisor[j + 1];
                }
                root = mul(root, 2);
            }
            byte[] result = new byte[divisorLen];
            for (byte b : data) {
                int factor = (b ^ result[0]) & 0xFF;
                System.arraycopy(result, 1, result, 0, result.length - 1);
                result[result.length - 1] = 0;
                for (int i = 0; i < result.length; i++) {
                    result[i] ^= mul(divisor[i], factor);
                }
            }
            return result;
        }

        private static final byte[] EXP_TABLE = new byte[512];
        private static final byte[] LOG_TABLE = new byte[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP_TABLE[i] = (byte) x;
                LOG_TABLE[x] = (byte) i;
                x *= 2;
                if (x >= 256) x ^= 0x11D;
            }
            for (int i = 255; i < 512; i++) {
                EXP_TABLE[i] = EXP_TABLE[i - 255];
            }
        }

        private static int exp(int e) {
            return EXP_TABLE[e] & 0xFF;
        }

        private static int mul(int x, int y) {
            if (x == 0 || y == 0) return 0;
            return EXP_TABLE[(LOG_TABLE[x] & 0xFF) + (LOG_TABLE[y] & 0xFF)] & 0xFF;
        }

        private int getPenaltyScore() {
            int result = 0;
            for (int y = 0; y < size; y++) {
                boolean runColor = false;
                int runX = 0;
                for (int x = 0; x < size; x++) {
                    if (modules[y][x] == runColor) {
                        runX++;
                        if (runX == 5) result += 3;
                        else if (runX > 5) result++;
                    } else {
                        runColor = modules[y][x];
                        runX = 1;
                    }
                }
            }
            for (int x = 0; x < size; x++) {
                boolean runColor = false;
                int runY = 0;
                for (int y = 0; y < size; y++) {
                    if (modules[y][x] == runColor) {
                        runY++;
                        if (runY == 5) result += 3;
                        else if (runY > 5) result++;
                    } else {
                        runColor = modules[y][x];
                        runY = 1;
                    }
                }
            }
            for (int y = 0; y < size - 1; y++) {
                for (int x = 0; x < size - 1; x++) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x]
                            && c == modules[y + 1][x + 1]) {
                        result += 3;
                    }
                }
            }
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (findFinderLike(x, y)) result += 40;
                }
            }
            int dark = 0;
            for (boolean[] row : modules) {
                for (boolean m : row) {
                    if (m) dark++;
                }
            }
            int total = size * size;
            int k = (int) (Math.ceil(Math.abs(dark * 20 - total * 10) / (double) total) - 1);
            result += k * 10;
            return result;
        }

        private boolean findFinderLike(int x, int y) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= size || yy >= size
                            || !modules[yy][xx]) {
                        return false;
                    }
                }
            }
            for (int i = 0; i < 7; i++) {
                boolean horiz = x - 3 + i >= 0 && x - 3 + i < size
                        && modules[y][x - 3 + i] == (i == 0 || i == 6);
                boolean vert = y - 3 + i >= 0 && y - 3 + i < size
                        && modules[y - 3 + i][x] == (i == 0 || i == 6);
                if (!horiz || !vert) return false;
            }
            return true;
        }

        private int[] getAlignmentPatternPositions() {
            if (version == 1) return new int[]{};
            int numAlign = version / 7 + 2;
            int step = version == 32 ? 26 : (version * 4 + numAlign * 2 + 1)
                    / (numAlign * 2 - 2) * 2;
            int[] result = new int[numAlign];
            result[0] = 6;
            for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) {
                result[i] = pos;
            }
            return result;
        }

        private static int getNumRawDataModules(int ver) {
            int result = (16 * ver + 128) * ver + 64;
            if (ver >= 2) {
                int numAlign = ver / 7 + 2;
                result -= (25 * numAlign - 10) * numAlign - 55;
                if (ver >= 7) result -= 36;
            }
            return result;
        }

        private static int getNumDataCodewords(int ver, Ecc ecl) {
            return getNumRawDataModules(ver) / 8
                    - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver]
                    * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
        }

        private static boolean getBit(int x, int i) {
            return ((x >>> i) & 1) != 0;
        }

        enum Ecc {
            LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
            final int formatBits;
            Ecc(int fb) {
                formatBits = fb;
            }
        }

        private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
                {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28,
                        28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                        30, 30, 30},
                {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
                        26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
                        28, 28, 28},
                {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26,
                        30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                        30, 30, 30},
                {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26,
                        28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                        30, 30, 30},
        };

        private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
                {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10,
                        12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
                {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17,
                        18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
                {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23,
                        23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65,
                        68},
                {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25,
                        34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77,
                        81},
        };
    }

    private static final class QrSegment {
        final Mode mode;
        final int numChars;
        final byte[] data;
        final int numBits;

        private QrSegment(Mode mode, int numChars, byte[] data, int numBits) {
            this.mode = mode;
            this.numChars = numChars;
            this.data = data;
            this.numBits = numBits;
        }

        static QrSegment makeBytes(byte[] data) {
            return new QrSegment(Mode.BYTE, data.length, data, data.length * 8);
        }

        enum Mode {
            BYTE(0x4, 8, 16, 16);
            final int modeBits;
            final int[] numBitsCharCount;
            Mode(int mode, int... counts) {
                modeBits = mode;
                numBitsCharCount = counts;
            }
            int numCharCountBits(int version) {
                return numBitsCharCount[(version + 7) / 17];
            }
        }
    }

    private static final class BitBuffer {
        private final List<Integer> data = new ArrayList<>();
        int bitLength;

        void appendBits(int val, int len) {
            for (int i = len - 1; i >= 0; i--) {
                data.add((val >>> i) & 1);
            }
            bitLength += len;
        }

        void appendData(byte[] bytes) {
            for (byte b : bytes) {
                appendBits(b, 8);
            }
        }

        int getBits(int index, int len) {
            int result = 0;
            for (int i = 0; i < len; i++) {
                result = (result << 1) | data.get(index + i);
            }
            return result;
        }
    }
}
