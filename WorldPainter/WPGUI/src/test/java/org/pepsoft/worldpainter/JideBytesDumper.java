package org.pepsoft.worldpainter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forensic tool for analysing JIDE Docking Framework layout bytes pulled from a release-WP
 * config file. Runs in two modes:
 *
 * <ol>
 *   <li><b>From {@link Configuration}</b> — no args: loads the user's current config (i.e. the
 *       one we share with release WP now that the SNAPSHOT classifier is gone), dumps any
 *       present JIDE bytes to {@code ~/Downloads/jide-bytes/}, then analyses each dump.</li>
 *   <li><b>From a file</b> — one arg: analyses the file at that path directly (handy when
 *       another release-WP user ships us their {@code config} or an extracted {@code .bin}).</li>
 * </ol>
 *
 * <h2>Why this tool exists</h2>
 * The JIDE Docking Framework was always closed-source commercial software. When JIDE Software
 * went under, no jars or source were open-sourced — only the JIDE Common Layer ({@code jide-oss})
 * was. So we can't link the JIDE library to parse a layout file, and we can't even FIND a
 * Maven-published {@code jide-dock} jar anywhere to decompile. The only viable path to importing
 * release-WP users' layouts is to reverse-engineer the byte format from real samples.
 *
 * <p>This tool produces the forensics that drive that reverse-engineering:
 * <ul>
 *   <li>First-4-byte magic detection. If it's {@code AC ED 00 05}, the bytes are vanilla Java
 *       serialisation and we can parse them with a custom walker that records class names +
 *       field names + values without instantiating anything (Java's stream format is documented;
 *       see {@code java.io.ObjectStreamConstants}). If it's something else, JIDE is using its own
 *       custom binary format and we'd have to figure out the framing from scratch.</li>
 *   <li>Hex dump of the first ~512 bytes. Patterns like length-prefixed UTF-8 strings show up as
 *       "two-byte length followed by ASCII run" — easy to spot by eye.</li>
 *   <li>Scan for printable ASCII runs of length 4+ with byte offsets. JIDE panel keys
 *       (e.g. {@code "brushSettings"}, {@code "infoPanel"}) should appear verbatim in the
 *       stream, which gives us anchor points.</li>
 *   <li>Heuristic length-prefix detection: for each ASCII run, check whether the two bytes just
 *       before it look like a valid 16-bit big-endian length matching the run's length. That
 *       suggests Java {@code DataOutputStream.writeUTF} encoding.</li>
 * </ul>
 *
 * <h2>What's needed to actually write the parser</h2>
 * Bytes from a release-WP user who actually customised their layout. {@link Configuration} on
 * the developer's machine often has {@code defaultJideLayoutData == null} simply because
 * release WP only writes the layout out on explicit "Save Layout", dimension switch, or world
 * save — and many users never trigger any of those before opening their first save with our
 * fork. Ask Arag (or anyone else who has customised their release-WP layout) for their
 * {@code config} file. This tool will then characterise its contents.
 */
public class JideBytesDumper extends AbstractTool {
    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            final File f = new File(args[0]);
            if (! f.isFile()) {
                System.err.println("Not a file: " + f);
                System.exit(1);
            }
            // If the file looks like a WP config (starts with the Java-ser magic 0xACED), extract
            // JIDE bytes from it; otherwise treat it as a raw JIDE blob.
            final byte[] raw = Files.readAllBytes(f.toPath());
            if (raw.length >= 4 && (raw[0] & 0xFF) == 0xAC && (raw[1] & 0xFF) == 0xED) {
                System.out.println("Input looks like a WP config file — extracting JIDE bytes via Configuration.load…");
                initialisePlatform();
                final Configuration config = Configuration.load(f);
                if (config == null) {
                    System.err.println("Configuration.load returned null");
                    System.exit(1);
                }
                analyseFromConfig(config);
            } else {
                System.out.println("Treating input as raw JIDE layout blob: " + f.getAbsolutePath());
                analyseBlob("input", raw);
            }
            return;
        }

        // No args: load the user's current config in place
        initialisePlatform();
        final Configuration config = Configuration.getInstance();
        if (config == null) {
            System.err.println("No configuration loaded.");
            System.exit(1);
        }
        analyseFromConfig(config);
    }

    private static void analyseFromConfig(Configuration config) throws Exception {
        final File outDir = new File(System.getProperty("user.home"), "Downloads/jide-bytes");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        System.out.println("Output: " + outDir.getAbsolutePath());
        System.out.println();

        final byte[] defaultBytes = config.getDefaultJideLayoutData();
        if (defaultBytes != null && defaultBytes.length > 0) {
            writeAndAnalyse(outDir, "jide-default", defaultBytes);
        } else {
            System.out.println("jide-default.bin: ABSENT or empty (defaultJideLayoutData is null/zero-length).");
            System.out.println("  → Release WP only writes defaultJideLayoutData on explicit File → Save Layout.");
            System.out.println("    Per-dim layouts in the map below are written on every world save / dim switch.");
        }
        System.out.println();

        // Our fork's parallel fields (added in commit d7766eeb). Dump these too so we can
        // confirm whether the populated bytes are JIDE (from release WP) or DockingFrames
        // (from our fork via Save As → Legacy or just our normal save).
        final byte[] dfDefault = config.getDefaultDockingFramesLayoutData();
        if (dfDefault != null && dfDefault.length > 0) {
            writeAndAnalyse(outDir, "df-default", dfDefault);
        } else {
            System.out.println("df-default.bin: ABSENT (defaultDockingFramesLayoutData is null).");
        }
        System.out.println();

        final Map<String, byte[]> dfPerDim = config.getDockingFramesLayoutData();
        if (dfPerDim != null && !dfPerDim.isEmpty()) {
            System.out.println("DockingFrames per-dim layouts (our fork's writes):");
            for (Map.Entry<String, byte[]> e : dfPerDim.entrySet()) {
                final byte[] bytes = e.getValue();
                if (bytes == null || bytes.length == 0) continue;
                final String safe = e.getKey().replaceAll("[^A-Za-z0-9_.-]", "_");
                writeAndAnalyse(outDir, "df-dim-" + safe, bytes);
                System.out.println();
            }
        } else {
            System.out.println("(no per-dim DockingFrames layouts)");
        }
        System.out.println();

        System.out.println("JIDE per-dim layouts:");
        final Map<String, byte[]> perDim = config.getJideLayoutData();
        if (perDim != null && !perDim.isEmpty()) {
            for (Map.Entry<String, byte[]> e : perDim.entrySet()) {
                final byte[] bytes = e.getValue();
                if (bytes == null || bytes.length == 0) continue;
                final String safe = e.getKey().replaceAll("[^A-Za-z0-9_.-]", "_");
                writeAndAnalyse(outDir, "dim-" + safe, bytes);
                System.out.println();
            }
        } else {
            System.out.println("(no per-dimension JIDE layouts)");
        }
    }

    private static void writeAndAnalyse(File outDir, String name, byte[] bytes) throws Exception {
        final File f = new File(outDir, name + ".bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        System.out.println(name + ".bin: " + bytes.length + " bytes -> " + f.getAbsolutePath());
        analyseBlob(name, bytes);
    }

    /** All the forensic prints. Idempotent: pure read of the given byte array. */
    private static void analyseBlob(String label, byte[] b) {
        if (b.length == 0) {
            System.out.println("  (empty)");
            return;
        }

        // ---- Magic check ---------------------------------------------------------------------
        if (b.length >= 4 && (b[0] & 0xFF) == 0xAC && (b[1] & 0xFF) == 0xED) {
            final int version = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
            System.out.println("  Magic: Java-serialisation stream, version " + version);
            System.out.println("    → Parseable with java.io.ObjectInputStream IF we had JIDE classes on classpath.");
            System.out.println("    → Also parseable WITHOUT JIDE classes by walking the stream tags directly");
            System.out.println("      (TC_OBJECT, TC_CLASSDESC, TC_STRING, etc. — see ObjectStreamConstants).");
        } else {
            System.out.println(String.format("  Magic: %02X %02X %02X %02X — not Java serialisation",
                    b[0] & 0xFF, b.length > 1 ? b[1] & 0xFF : 0,
                    b.length > 2 ? b[2] & 0xFF : 0, b.length > 3 ? b[3] & 0xFF : 0));
            System.out.println("    → JIDE is using its own custom binary format. Reverse-engineering it");
            System.out.println("      requires bracketing observed patterns (string offsets, length prefixes,");
            System.out.println("      type tags) against a known panel layout.");
        }

        // ---- Hex dump of first 256 bytes -----------------------------------------------------
        System.out.println("  Hex dump (first 256 bytes):");
        for (int off = 0; off < Math.min(256, b.length); off += 16) {
            final StringBuilder hex = new StringBuilder();
            final StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16 && (off + i) < b.length; i++) {
                final int v = b[off + i] & 0xFF;
                hex.append(String.format("%02X ", v));
                ascii.append((v >= 0x20 && v < 0x7F) ? (char) v : '.');
            }
            while (hex.length() < 48) hex.append(' ');
            System.out.println(String.format("    %08X  %s |%s|", off, hex, ascii));
        }

        // ---- ASCII strings of length 4+ with byte offsets ------------------------------------
        System.out.println("  Printable ASCII runs (length 4+):");
        printAsciiRuns(b);

        // ---- UTF-16 BE strings of length 4+ with byte offsets --------------------------------
        // DockingFrames' CControl.write encodes panel IDs as UTF-16 BE with a 2-byte length
        // prefix. JIDE may do the same — depending on whether they used writeUTF (modified
        // UTF-8) or writeChars (UTF-16 BE). Scanning for both gives us coverage either way.
        System.out.println("  UTF-16 BE strings (length 3+ chars):");
        printUtf16BeRuns(b);
    }

    /** Find ASCII (single-byte 0x20–0x7E) runs of length 4+. */
    private static void printAsciiRuns(byte[] b) {
        final List<int[]> runs = new ArrayList<>(); // [offset, length]
        int runStart = -1;
        for (int i = 0; i < b.length; i++) {
            final int v = b[i] & 0xFF;
            final boolean printable = v >= 0x20 && v < 0x7F;
            if (printable && runStart < 0) {
                runStart = i;
            } else if (!printable && runStart >= 0) {
                final int len = i - runStart;
                if (len >= 4) runs.add(new int[]{runStart, len});
                runStart = -1;
            }
        }
        if (runStart >= 0 && b.length - runStart >= 4) {
            runs.add(new int[]{runStart, b.length - runStart});
        }
        int shown = 0;
        for (int[] run : runs) {
            String prefixMarker = "";
            if (run[0] >= 2 && run[1] <= 0xFFFF) {
                final int len = ((b[run[0] - 2] & 0xFF) << 8) | (b[run[0] - 1] & 0xFF);
                if (len == run[1]) {
                    prefixMarker = "  ← preceded by 0x" + String.format("%04X", len) + " (DataOutputStream.writeUTF?)";
                }
            }
            System.out.println(String.format("    %08X (%d)  \"%s\"%s",
                    run[0], run[1],
                    new String(b, run[0], run[1], java.nio.charset.StandardCharsets.US_ASCII),
                    prefixMarker));
            shown++;
            if (shown >= 30) {
                System.out.println("    … (and " + (runs.size() - shown) + " more)");
                break;
            }
        }
        if (runs.isEmpty()) {
            System.out.println("    (none — try UTF-16 below)");
        }
    }

    /** Find UTF-16 BE runs: pairs (0x00, ascii) of length 3+ chars (6+ bytes). */
    private static void printUtf16BeRuns(byte[] b) {
        final List<int[]> runs = new ArrayList<>(); // [offset, length in CHARS]
        int runStart = -1;
        int i = 0;
        while (i + 1 < b.length) {
            final int hi = b[i] & 0xFF;
            final int lo = b[i + 1] & 0xFF;
            final boolean printable = hi == 0x00 && lo >= 0x20 && lo < 0x7F;
            if (printable && runStart < 0) {
                runStart = i;
            } else if (!printable && runStart >= 0) {
                final int chars = (i - runStart) / 2;
                if (chars >= 3) runs.add(new int[]{runStart, chars});
                runStart = -1;
            }
            i += 2;
        }
        if (runStart >= 0 && (b.length - runStart) / 2 >= 3) {
            runs.add(new int[]{runStart, (b.length - runStart) / 2});
        }
        int shown = 0;
        for (int[] run : runs) {
            // The byte right before a UTF-16 BE string in DockingFrames' format is usually a
            // 2-byte length. Check if (b[off-2], b[off-1]) == big-endian char count.
            String prefixMarker = "";
            if (run[0] >= 2) {
                final int len = ((b[run[0] - 2] & 0xFF) << 8) | (b[run[0] - 1] & 0xFF);
                if (len == run[1]) {
                    prefixMarker = "  ← preceded by 0x" + String.format("%04X", len) + " (UTF-16 BE length prefix)";
                }
            }
            final StringBuilder sb = new StringBuilder(run[1]);
            for (int c = 0; c < run[1]; c++) {
                sb.append((char) (b[run[0] + c * 2 + 1] & 0xFF));
            }
            System.out.println(String.format("    %08X (%d chars)  \"%s\"%s",
                    run[0], run[1], sb, prefixMarker));
            shown++;
            if (shown >= 30) {
                System.out.println("    … (and " + (runs.size() - shown) + " more)");
                break;
            }
        }
        if (runs.isEmpty()) {
            System.out.println("    (none)");
        }
    }
}
