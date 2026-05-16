package org.pepsoft.worldpainter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Second-stage forensic tool, run on a known-real JIDE Docking Framework layout file dumped
 * by {@link JideBytesDumper}. Hypothesis under test (from the initial hex dump):
 *
 * <pre>
 *   byte 0..1:     uint16 BE version (observed value 0x0014 = 20)
 *   byte 2..end:   stream of int32 BE values, with strings stored inline as
 *                  (int32 length){UTF-16 BE chars} — no separate type tags
 * </pre>
 *
 * The detection heuristic walks the stream as int32 BE values. For each value, if it looks
 * plausibly like a string length (small enough that the next {@code 2*length} bytes are still
 * in range and consist entirely of printable UTF-16 BE chars), treat it as a length and consume
 * the string; otherwise treat it as a plain int and continue.
 *
 * <p>Output is a numbered list of records:
 * <pre>
 *   [###] offset=NNNN  type=int     value
 *   [###] offset=NNNN  type=string  length value
 * </pre>
 * with a "frequency table" at the end showing the most-common int values (the format's type
 * tags should bubble up here).
 *
 * <p>Usage:
 * <pre>
 *   java org.pepsoft.worldpainter.JideBytesStructuredDump /path/to/dim-...bin
 * </pre>
 */
public class JideBytesStructuredDump {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: JideBytesStructuredDump <file>");
            System.exit(2);
        }
        final File f = new File(args[0]);
        final byte[] b = Files.readAllBytes(f.toPath());
        System.out.println("File: " + f.getAbsolutePath() + "  (" + b.length + " bytes)");
        if (b.length < 2) {
            System.err.println("Too short");
            return;
        }

        // 2-byte version header
        final int version = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        System.out.println("Version (uint16 BE at offset 0): " + version + " (0x" + Integer.toHexString(version) + ")");
        System.out.println();

        // Walk the rest as int32 BE values, detecting inline UTF-16 BE strings.
        int i = 2;
        int recordIdx = 0;
        final Map<Integer, Integer> intFrequency = new LinkedHashMap<>();

        while (i + 4 <= b.length) {
            final int val = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                    | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            // Look-ahead: is this a string length?
            final boolean isString = looksLikeStringLength(b, i + 4, val);
            if (isString) {
                final String s = readUtf16BE(b, i + 4, val);
                System.out.println(String.format("[%4d] offset=0x%04X  string len=%-3d  \"%s\"",
                        recordIdx, i, val, s));
                i += 4 + 2 * val;
            } else {
                System.out.println(String.format("[%4d] offset=0x%04X  int          %-8d  (0x%X)",
                        recordIdx, i, val, val));
                intFrequency.merge(val, 1, Integer::sum);
                i += 4;
            }
            recordIdx++;
        }

        if (i < b.length) {
            System.out.println();
            System.out.println("Trailing bytes (" + (b.length - i) + "): " + hex(b, i, b.length - i));
        }

        // Frequency table of int values — type tags should show up as repeating values
        System.out.println();
        System.out.println("Most-frequent int values (likely type tags):");
        intFrequency.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted((a, c) -> Integer.compare(c.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> System.out.println(String.format("  0x%08X (= %d)  appears %d times",
                        e.getKey(), e.getKey(), e.getValue())));
    }

    /**
     * Plausibility check for "the next int32 we just read is the length of a UTF-16 BE string
     * whose chars follow immediately." Must be:
     *   * a small non-negative count that fits in the remaining bytes,
     *   * paired with 2*length bytes that are all printable BMP chars (high byte zero, low byte
     *     in 0x20–0x7E) — JIDE panel keys and similar are ASCII subsets of UTF-16.
     *
     * We accept length 1+ (single-character keys aren't expected but don't want to reject 'O' /
     * 'X' / etc.). Length 0 is rejected because there's no way to confirm — too many false
     * positives.
     */
    private static boolean looksLikeStringLength(byte[] b, int start, int len) {
        if (len < 1 || len > 256) return false;
        if ((long) start + 2 * len > b.length) return false;
        for (int j = 0; j < len; j++) {
            final int hi = b[start + 2 * j] & 0xFF;
            final int lo = b[start + 2 * j + 1] & 0xFF;
            if (hi != 0) return false;
            if (lo < 0x20 || lo > 0x7E) return false;
        }
        return true;
    }

    private static String readUtf16BE(byte[] b, int start, int chars) {
        final byte[] bytes = new byte[chars * 2];
        System.arraycopy(b, start, bytes, 0, chars * 2);
        return new String(bytes, StandardCharsets.UTF_16BE);
    }

    private static String hex(byte[] b, int off, int len) {
        final StringBuilder sb = new StringBuilder();
        for (int j = 0; j < len; j++) {
            if (j > 0) sb.append(' ');
            sb.append(String.format("%02X", b[off + j] & 0xFF));
        }
        return sb.toString();
    }
}
