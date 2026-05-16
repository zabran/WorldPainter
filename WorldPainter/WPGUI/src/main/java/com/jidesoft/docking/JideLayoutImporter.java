package com.jidesoft.docking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reverse-engineered importer for JIDE Docking Framework layout bytes saved by release-WP
 * (versions ≤ 2.26.x). Used by {@link DefaultDockingManager#loadLayoutFrom} as a fall-back path
 * when {@link bibliothek.gui.dock.common.CControl#read} rejects the bytes — recovers the
 * panel-→-dock-side mapping so a release-WP user transitioning to our fork doesn't have to
 * re-arrange their custom panel groups by hand.
 *
 * <h2>Format (reverse-engineered from real bytes saved by WorldPainter 2.26.1)</h2>
 *
 * <pre>
 *   bytes [0..2):   uint16 BE  version (observed: 20 / 0x0014; we accept any small value)
 *   bytes [2..18):  4× int32 BE — window x, y, width, height
 *   bytes [18..1A): int32 BE   — flag (observed: 0)
 *   bytes [1A..1E): int32 BE   — frame count (observed: 9)
 *   then for each frame, a variable-length record beginning with:
 *       int32 BE     — string length (number of UTF-16 chars in the panel key)
 *       UTF-16 BE    — the panel key (e.g. "biomes", "brushSettings", ...)
 *       int32 BE     — some per-frame id/hash (we skip — varies, not the dock side)
 *       int32 BE     — dock side as a JIDE bitmask: 1=N, 2=S, 4=E, 8=W, 16=Center
 *       int32 BE     — dock index within that side
 *       int32 BE     — visibility flag (we skip)
 *       int32 BE     — button mask (observed: 15 / 0x0F)
 *       … further per-DockContext records that capture sub-state for each visibility mode
 *         (DOCKED, FLOATING, AUTOHIDE, HIDDEN). We skip these and look for the next panel
 *         key by scanning forward for a plausible (length, UTF-16 chars) record.
 * </pre>
 *
 * <p>Cross-checked end-to-end against bytes from a clean 2.26.1 install: all 9 panels round-trip
 * to the exact (side, index) pairs hard-coded in App.java. See JideBytesStructuredDump for the
 * raw integer-record dump that drove the analysis.
 *
 * <h2>What we DO and DON'T preserve</h2>
 *
 * <p><b>Preserved:</b> for each panel, the dock side (W/E/N/S/Center) and the per-side index
 * (which determines which group/tab-stack it joins).
 *
 * <p><b>Not preserved:</b> precise sizes, undocked/floating positions, autohide-vs-docked state
 * per panel, custom button visibility. These were never important for the migration case (a
 * release-WP user moving to our fork mainly cares that "my four custom panels are still on the
 * west side as a group", not "their pixel coordinates match exactly").
 */
public final class JideLayoutImporter {
    private static final Logger logger = LoggerFactory.getLogger(JideLayoutImporter.class);

    // JIDE DockContext side bitmask values (confirmed against the byte stream and App.java).
    private static final int JIDE_SIDE_NORTH  = 1;
    private static final int JIDE_SIDE_SOUTH  = 2;
    private static final int JIDE_SIDE_EAST   = 4;
    private static final int JIDE_SIDE_WEST   = 8;
    private static final int JIDE_SIDE_CENTER = 16;

    /** Carrier for what we extract per panel: dock side (JIDE bitmask) and index in that side. */
    public static final class FramePlacement {
        public final int jideSide;
        public final int index;
        FramePlacement(int jideSide, int index) {
            this.jideSide = jideSide;
            this.index = index;
        }
        /** Translate the JIDE bitmask to our adapter's {@link DockContext} constant. */
        public int toDockContextSide() {
            switch (jideSide) {
                case JIDE_SIDE_EAST:  return DockContext.DOCK_SIDE_EAST;
                case JIDE_SIDE_WEST:  return DockContext.DOCK_SIDE_WEST;
                case JIDE_SIDE_NORTH: return DockContext.DOCK_SIDE_NORTH;
                case JIDE_SIDE_SOUTH: return DockContext.DOCK_SIDE_SOUTH;
                // JIDE has a CENTER side that DockingFrames doesn't expose as a "side" (the
                // centre is the workspace canvas, not a docked-panel area). Treat any panel
                // imported as CENTER as WEST — it has to live somewhere, and a WP user who put
                // a panel in CENTER is misusing the layout anyway. Same for any unknown value.
                case JIDE_SIDE_CENTER:
                default:              return DockContext.DOCK_SIDE_WEST;
            }
        }
        @Override public String toString() {
            return "FramePlacement{side=" + jideSide + ", index=" + index + "}";
        }
    }

    private JideLayoutImporter() {}

    /**
     * Parse the given bytes as a JIDE Docking Framework layout. Returns a map from panel key
     * to its extracted placement, or {@code null} if the bytes don't look like JIDE format.
     *
     * <p>Heuristic-driven: we don't have a spec, so we read fields in the order the format
     * appears to present them, falling back to "next string" scanning between frames to skip
     * the per-DockContext metadata blocks we don't fully decode. Robust against the variations
     * we've seen — the parser accepts any version byte and any leading number of header ints,
     * and recovers gracefully if the format ever changes shape in places we don't read.
     */
    public static Map<String, FramePlacement> tryParse(byte[] b) {
        if (b == null || b.length < 0x20) {
            return null;
        }
        // Cheap shape check: must NOT start with Java serialisation magic (those bytes go through
        // a different path), and must look like the JIDE format we know — first int32 read from
        // offset 2 should be a plausible small positive number (window x or 0).
        if ((b[0] & 0xFF) == 0xAC && (b[1] & 0xFF) == 0xED) {
            return null;
        }
        final int firstInt = readIntBE(b, 2);
        if (firstInt < 0 || firstInt > 100_000) {
            return null;
        }

        // Walk the stream as a sequence of int32 BE values starting at offset 2. When we see an
        // int that looks like a UTF-16 BE string length (small, fits in remaining bytes, all
        // subsequent chars are printable ASCII in the BMP), consume it as a panel key — and then
        // read the next three ints as (id, dockSide, dockIndex). The remaining per-frame metadata
        // is skipped by scanning forward for the next plausible string.
        final Map<String, FramePlacement> placements = new LinkedHashMap<>();
        int i = 2;
        try {
            while (i + 4 <= b.length) {
                final int val = readIntBE(b, i);
                if (looksLikeStringLength(b, i + 4, val)) {
                    final String key = readUtf16BE(b, i + 4, val);
                    i += 4 + 2 * val;
                    // Need at least 3 more int32s for (id, side, index).
                    if (i + 12 > b.length) {
                        break;
                    }
                    final int id = readIntBE(b, i);
                    final int side = readIntBE(b, i + 4);
                    final int index = readIntBE(b, i + 8);
                    if (isValidSide(side)) {
                        placements.put(key, new FramePlacement(side, index));
                        logger.debug("JIDE import: panel '{}' → side=0x{} index={} (id={})",
                                key, Integer.toHexString(side), index, id);
                    } else {
                        // Mistaken string detection (or a panel-less header section we shouldn't
                        // have entered). Skip the three trailing ints and continue.
                        logger.debug("JIDE import: '{}' had unexpected side value {}, skipping",
                                key, side);
                    }
                    i += 12; // past (id, side, index)
                } else {
                    i += 4;
                }
            }
        } catch (RuntimeException e) {
            logger.warn("JIDE import: parser threw at offset 0x{} — keeping the {} placement(s) we did extract",
                    Integer.toHexString(i), placements.size(), e);
        }

        if (placements.isEmpty()) {
            return null;
        }
        return placements;
    }

    /**
     * Top-level entry point. Parses the bytes, then writes the discovered placement back onto
     * each registered frame's {@code initSide}/{@code initIndex}. Caller (loadLayoutFrom)
     * is responsible for triggering layout deployment after this returns true.
     *
     * @return {@code true} if anything was extracted and applied.
     */
    public static boolean tryImport(byte[] bytes, Map<String, DockableFrame> frames) {
        final Map<String, FramePlacement> placements = tryParse(bytes);
        if (placements == null) {
            return false;
        }
        int applied = 0;
        for (Map.Entry<String, FramePlacement> e : placements.entrySet()) {
            final DockableFrame f = frames.get(e.getKey());
            if (f != null) {
                f.setInitSide(e.getValue().toDockContextSide());
                f.setInitIndex(e.getValue().index);
                applied++;
            } else {
                logger.debug("JIDE import: no current frame matches imported key '{}' — skipping", e.getKey());
            }
        }
        logger.info("JIDE import: applied {} placement(s) (out of {} parsed)", applied, placements.size());
        return applied > 0;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static boolean looksLikeStringLength(byte[] b, int start, int len) {
        if (len < 2 || len > 64) return false;
        if ((long) start + 2 * len > b.length) return false;
        for (int j = 0; j < len; j++) {
            final int hi = b[start + 2 * j] & 0xFF;
            final int lo = b[start + 2 * j + 1] & 0xFF;
            if (hi != 0) return false;
            // ASCII letters/digits/underscore/period/colon/slash — anything that could plausibly
            // be in a panel key.
            if (! ((lo >= 'a' && lo <= 'z') || (lo >= 'A' && lo <= 'Z')
                    || (lo >= '0' && lo <= '9') || lo == '_' || lo == '.' || lo == ':' || lo == '/')) {
                return false;
            }
        }
        return true;
    }

    private static String readUtf16BE(byte[] b, int start, int chars) {
        final byte[] bytes = new byte[chars * 2];
        System.arraycopy(b, start, bytes, 0, chars * 2);
        return new String(bytes, StandardCharsets.UTF_16BE);
    }

    private static boolean isValidSide(int side) {
        return side == JIDE_SIDE_NORTH || side == JIDE_SIDE_SOUTH
                || side == JIDE_SIDE_EAST  || side == JIDE_SIDE_WEST
                || side == JIDE_SIDE_CENTER;
    }
}
