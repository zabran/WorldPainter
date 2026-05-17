package org.pepsoft.worldpainter.paging;

import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;

import java.awt.Point;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * B1 — tile-index.bin binary format unit tests. Validates that
 * {@link Dimension#writeTileIndexPayload} and {@link Dimension#readTileIndexPayload} are
 * inverses for representative coord sets, and that the reader rejects malformed input with
 * a useful error rather than silently misinterpreting it.
 *
 * <p>These tests don't need a real {@code Dimension}, {@code World2}, or any other expensive
 * scaffolding — the format methods are static (read) and act on a single {@code Set<Point>}
 * (write goes through a temporary Dimension purely to access the instance method, but writes
 * only that set; no other Dimension state participates).
 */
public class TileIndexFormatTest {

    @Test
    public void roundTripEmpty() throws IOException {
        roundTrip(new HashSet<>());
    }

    @Test
    public void roundTripSingleCoord() throws IOException {
        final Set<Point> coords = new HashSet<>();
        coords.add(new Point(0, 0));
        roundTrip(coords);
    }

    @Test
    public void roundTripSmallSparse() throws IOException {
        final Set<Point> coords = new HashSet<>(Arrays.asList(
                new Point(0, 0),
                new Point(1, 0),
                new Point(5, 7),
                new Point(-3, 12)));
        roundTrip(coords);
    }

    @Test
    public void roundTripNegativeCoords() throws IOException {
        final Set<Point> coords = new HashSet<>(Arrays.asList(
                new Point(-100, -100),
                new Point(-1, 0),
                new Point(0, -1),
                new Point(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        roundTrip(coords);
    }

    @Test
    public void roundTripLargeContiguous() throws IOException {
        // 50×50 dense block, 2500 coords — similar shape to a real dim's tile-index.
        final Set<Point> coords = new LinkedHashSet<>();
        for (int x = 0; x < 50; x++) {
            for (int y = 0; y < 50; y++) {
                coords.add(new Point(x, y));
            }
        }
        final byte[] bytes = roundTrip(coords);
        // Expected size: 12 header bytes + 8 bytes per coord = 12 + 8*2500 = 20012.
        assertEquals("on-disk size", 20012, bytes.length);
    }

    @Test
    public void readerRejectsBadMagic() {
        // Build a "tile-index.bin" with the magic flipped one byte.
        final byte[] bad = baseValidIndex();
        bad[0] = (byte) 0xFF; // corrupt magic
        try {
            Dimension.readTileIndexPayload(new ByteArrayInputStream(bad));
            fail("Expected IOException for bad magic");
        } catch (IOException e) {
            assertTrue("error mentions magic", e.getMessage().toLowerCase().contains("magic"));
        }
    }

    @Test
    public void readerRejectsBadVersion() throws IOException {
        // Build a "tile-index.bin" with a future version.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(Dimension.TILE_INDEX_MAGIC);
        out.writeInt(99); // unsupported version
        out.writeInt(0);
        try {
            Dimension.readTileIndexPayload(new ByteArrayInputStream(baos.toByteArray()));
            fail("Expected IOException for unsupported version");
        } catch (IOException e) {
            assertTrue("error mentions version", e.getMessage().toLowerCase().contains("version"));
        }
    }

    @Test
    public void readerRejectsNegativeCount() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(Dimension.TILE_INDEX_MAGIC);
        out.writeInt(Dimension.TILE_INDEX_VERSION);
        out.writeInt(-1);
        try {
            Dimension.readTileIndexPayload(new ByteArrayInputStream(baos.toByteArray()));
            fail("Expected IOException for negative count");
        } catch (IOException e) {
            assertTrue("error mentions count or negative",
                    e.getMessage().toLowerCase().contains("count")
                            || e.getMessage().toLowerCase().contains("negative"));
        }
    }

    // --- helpers ---

    /**
     * Write the given coord set via the static-write helper (bypasses Dimension instance
     * construction — we don't need any of its other state for the format), then read it back
     * and assert equality. Returns the serialised bytes for size assertions.
     */
    private static byte[] roundTrip(Set<Point> coords) throws IOException {
        final byte[] bytes = writeCoords(coords);
        final Set<Point> read = Dimension.readTileIndexPayload(new ByteArrayInputStream(bytes));
        assertNotNull(read);
        assertEquals("count round-trips", coords.size(), read.size());
        assertEquals("set round-trips", coords, read);
        return bytes;
    }

    /**
     * Use the production static writer so the format under test is the format actually shipped.
     */
    private static byte[] writeCoords(Set<Point> coords) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Dimension.writeTileIndexPayload(coords, baos);
        return baos.toByteArray();
    }

    /** Valid two-coord tile-index payload, for tests that need a starting point to corrupt. */
    private static byte[] baseValidIndex() {
        final Set<Point> coords = new HashSet<>(Arrays.asList(
                new Point(1, 2), new Point(3, 4)));
        try {
            return writeCoords(coords);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
