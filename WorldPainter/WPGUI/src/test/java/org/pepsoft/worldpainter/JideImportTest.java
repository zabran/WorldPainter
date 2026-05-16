package org.pepsoft.worldpainter;

import com.jidesoft.docking.DockContext;
import com.jidesoft.docking.JideLayoutImporter;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * End-to-end test of {@link JideLayoutImporter} against a real JIDE layout file extracted by
 * {@link JideBytesDumper}. Validates that:
 * <ul>
 *   <li>The parser recognises the bytes as JIDE format.</li>
 *   <li>Every panel key embedded in the file is decoded.</li>
 *   <li>Each panel's reported (side, index) matches the canonical WP defaults from {@code App.java}
 *       (since the test file is from a freshly-installed release-WP that hasn't been customised).</li>
 * </ul>
 *
 * <p>When we have a sample with customised panel placements (Arag's), the same harness will catch
 * any regression in the parser's read of non-default placements.
 *
 * <h2>Running</h2>
 * <pre>
 *   java org.pepsoft.worldpainter.JideImportTest [&lt;path to .bin&gt;]
 * </pre>
 * Defaults to {@code ~/Downloads/jide-bytes/dim-1e8409f7-8200-4424-bbc6-bc321264b37b.bin}.
 */
public class JideImportTest {
    public static void main(String[] args) throws Exception {
        final File f = (args.length >= 1)
                ? new File(args[0])
                : new File(System.getProperty("user.home"),
                        "Downloads/jide-bytes/dim-1e8409f7-8200-4424-bbc6-bc321264b37b.bin");
        if (!f.isFile()) {
            System.err.println("Not a file: " + f);
            System.exit(2);
        }
        System.out.println("Parsing: " + f.getAbsolutePath());
        final byte[] bytes = Files.readAllBytes(f.toPath());

        final Map<String, JideLayoutImporter.FramePlacement> placements = JideLayoutImporter.tryParse(bytes);
        if (placements == null) {
            System.err.println("FAILED: parser returned null (bytes not recognised as JIDE)");
            System.exit(3);
        }

        System.out.println("Extracted " + placements.size() + " panel placement(s):");
        placements.forEach((key, p) -> System.out.println(String.format(
                "  %-22s  jideSide=%-2d  index=%d  →  DockContext.DOCK_SIDE_%s",
                key, p.jideSide, p.index, sideName(p.toDockContextSide()))));

        // Expected mapping for a vanilla 2.26.1 install — every value here is hard-coded in
        // App.java's addFrame block.  When we have customised samples we should add cases for
        // those too.
        final Map<String, int[]> expected = new LinkedHashMap<>();
        expected.put("biomes",        new int[]{DockContext.DOCK_SIDE_WEST, 3});
        expected.put("toolSettings",  new int[]{DockContext.DOCK_SIDE_WEST, 2});
        expected.put("layers",        new int[]{DockContext.DOCK_SIDE_WEST, 3});
        expected.put("annotations",   new int[]{DockContext.DOCK_SIDE_WEST, 3});
        expected.put("brushes",       new int[]{DockContext.DOCK_SIDE_EAST, 1});
        expected.put("infoPanel",     new int[]{DockContext.DOCK_SIDE_EAST, 2});
        expected.put("brushSettings", new int[]{DockContext.DOCK_SIDE_EAST, 2});
        expected.put("tools",         new int[]{DockContext.DOCK_SIDE_WEST, 1});
        expected.put("terrain",       new int[]{DockContext.DOCK_SIDE_WEST, 3});

        int failures = 0;
        for (Map.Entry<String, int[]> e : expected.entrySet()) {
            final JideLayoutImporter.FramePlacement p = placements.get(e.getKey());
            if (p == null) {
                System.err.println("  MISSING: " + e.getKey());
                failures++;
                continue;
            }
            final int actualSide = p.toDockContextSide();
            final int actualIndex = p.index;
            if (actualSide != e.getValue()[0] || actualIndex != e.getValue()[1]) {
                System.err.println(String.format("  MISMATCH on %s: expected (DOCK_SIDE_%s, %d), got (DOCK_SIDE_%s, %d)",
                        e.getKey(), sideName(e.getValue()[0]), e.getValue()[1],
                        sideName(actualSide), actualIndex));
                failures++;
            }
        }
        if (failures > 0) {
            System.err.println("FAILED: " + failures + " mismatch(es)");
            System.exit(4);
        }
        System.out.println("PASSED: " + expected.size() + " panel(s) match the canonical 2.26.1 defaults");
    }

    private static String sideName(int s) {
        switch (s) {
            case DockContext.DOCK_SIDE_NORTH: return "NORTH";
            case DockContext.DOCK_SIDE_SOUTH: return "SOUTH";
            case DockContext.DOCK_SIDE_EAST:  return "EAST";
            case DockContext.DOCK_SIDE_WEST:  return "WEST";
            default: return "(unknown:" + s + ")";
        }
    }
}
