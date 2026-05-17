package org.pepsoft.worldpainter.paging;

import org.junit.Test;
import org.pepsoft.worldpainter.Tile;

import java.awt.Point;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * B0 — {@link PagingTileMap} unit tests. Validates that:
 * <ul>
 *   <li>Eager mode (no pager) behaves bit-for-bit like a plain {@link HashMap}.</li>
 *   <li>Lazy mode (pager attached) delegates the right calls to the pager.</li>
 *   <li>{@link PagingTileMap#setPager} guards against double-attach and non-empty maps.</li>
 *   <li>{@link PagingTileMap#writeReplace} produces a plain {@link HashMap} so legacy
 *       GZIP-ObjectStream saves stay byte-compatible with release WP.</li>
 * </ul>
 */
public class PagingTileMapTest {

    /** Smallest real {@link Tile} we can build cheaply for in-map storage. */
    private static Tile newTile(int x, int y) {
        return new Tile(x, y, 0, 256);
    }

    @Test
    public void eagerModeBehavesLikeHashMap() {
        final PagingTileMap map = new PagingTileMap();
        assertTrue("empty by default", map.isEmpty());
        assertEquals(0, map.size());
        assertFalse(map.isLazy());
        assertNull(map.getPager());

        final Point p1 = new Point(0, 0);
        final Point p2 = new Point(1, 0);
        final Point p3 = new Point(0, 1);
        final Tile t1 = newTile(0, 0);
        final Tile t2 = newTile(1, 0);

        map.put(p1, t1);
        map.put(p2, t2);
        assertEquals(2, map.size());
        assertSame(t1, map.get(p1));
        assertSame(t2, map.get(p2));
        assertNull(map.get(p3));
        assertTrue(map.containsKey(p1));
        assertFalse(map.containsKey(p3));

        final Set<Point> keys = map.keySet();
        final Set<Point> expectedKeys = new HashSet<>();
        expectedKeys.add(p1);
        expectedKeys.add(p2);
        assertEquals(expectedKeys, new HashSet<>(keys));

        map.remove(p1);
        assertEquals(1, map.size());
        assertFalse(map.containsKey(p1));

        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    public void seedCtorCopiesEntries() {
        final Map<Point, Tile> seed = new HashMap<>();
        seed.put(new Point(0, 0), newTile(0, 0));
        seed.put(new Point(1, 0), newTile(1, 0));

        final PagingTileMap map = new PagingTileMap(seed);

        assertEquals(2, map.size());
        assertTrue(map.containsKey(new Point(0, 0)));
        assertTrue(map.containsKey(new Point(1, 0)));
    }

    @Test
    public void setPagerThrowsOnNonEmptyMap() {
        final PagingTileMap map = new PagingTileMap();
        map.put(new Point(0, 0), newTile(0, 0));
        try {
            map.setPager(new RecordingPager(new HashSet<>()));
            fail("setPager on non-empty map should throw");
        } catch (IllegalStateException expected) {
            assertTrue("error mentions non-empty",
                    expected.getMessage().toLowerCase().contains("non-empty"));
        }
    }

    @Test
    public void setPagerThrowsOnDoubleAttach() {
        final PagingTileMap map = new PagingTileMap();
        map.setPager(new RecordingPager(new HashSet<>()));
        try {
            map.setPager(new RecordingPager(new HashSet<>()));
            fail("setPager twice should throw");
        } catch (IllegalStateException expected) {
            assertTrue("error mentions already attached",
                    expected.getMessage().toLowerCase().contains("already"));
        }
    }

    @Test
    public void lazyModeAnswersSizeContainsKeyKeysFromPagerIndex() {
        final Set<Point> indexed = new HashSet<>();
        indexed.add(new Point(0, 0));
        indexed.add(new Point(1, 0));
        indexed.add(new Point(99, -42));
        final RecordingPager pager = new RecordingPager(indexed);

        final PagingTileMap map = new PagingTileMap();
        map.setPager(pager);

        assertTrue(map.isLazy());
        assertSame(pager, map.getPager());

        // size/containsKey/keySet/isEmpty come from the pager (no load).
        assertEquals(3, map.size());
        assertFalse(map.isEmpty());
        assertTrue(map.containsKey(new Point(0, 0)));
        assertTrue(map.containsKey(new Point(99, -42)));
        assertFalse(map.containsKey(new Point(5, 5)));
        assertEquals(indexed, new HashSet<>(map.keySet()));

        // No load triggered yet — only the index was consulted.
        assertEquals(0, pager.loadCount.get());
    }

    @Test
    public void lazyModeGetFallsThroughToPagerOnMiss() {
        final Set<Point> indexed = new HashSet<>();
        indexed.add(new Point(0, 0));
        final Tile loadedTile = newTile(0, 0);
        final RecordingPager pager = new RecordingPager(indexed);
        pager.populateOnLoad.put(new Point(0, 0), loadedTile);

        final PagingTileMap map = new PagingTileMap();
        map.setPager(pager);

        final Tile resident = map.get(new Point(0, 0));
        assertSame("get() returns the lazily-loaded tile", loadedTile, resident);
        assertEquals("one load invoked", 1, pager.loadCount.get());

        // Now resident; second get must NOT invoke the pager.
        final Tile resident2 = map.get(new Point(0, 0));
        assertSame(loadedTile, resident2);
        assertEquals("still just one load", 1, pager.loadCount.get());
    }

    @Test
    public void lazyModeValuesForcesEagerLoadAll() {
        final Set<Point> indexed = new HashSet<>();
        indexed.add(new Point(0, 0));
        indexed.add(new Point(1, 0));
        final RecordingPager pager = new RecordingPager(indexed);
        pager.populateOnEagerLoad.put(new Point(0, 0), newTile(0, 0));
        pager.populateOnEagerLoad.put(new Point(1, 0), newTile(1, 0));

        final PagingTileMap map = new PagingTileMap();
        map.setPager(pager);

        assertEquals("no eager-loads yet", 0, pager.eagerLoadCount.get());
        final int valuesSeen = map.values().size();
        assertEquals(2, valuesSeen);
        assertEquals("eagerLoadAll invoked once", 1, pager.eagerLoadCount.get());
    }

    @Test
    public void lazyModePutNotifiesPagerCoordIndex() {
        final Set<Point> indexed = new HashSet<>();
        indexed.add(new Point(0, 0));
        final RecordingPager pager = new RecordingPager(indexed);

        final PagingTileMap map = new PagingTileMap();
        map.setPager(pager);

        final Tile newTile = newTile(5, 5);
        final Point newCoord = new Point(5, 5);
        map.put(newCoord, newTile);

        assertEquals("pager.noteCoordPresent called", 1, pager.noteCoordPresentCount.get());
        assertEquals(newCoord, pager.lastNotedPresent);
        assertTrue("subsequent containsKey true", map.containsKey(newCoord));

        map.remove(newCoord);
        assertEquals("pager.noteCoordRemoved called", 1, pager.noteCoordRemovedCount.get());
        assertEquals(newCoord, pager.lastNotedRemoved);
    }

    @Test
    public void serialisationStripsToPlainHashMap() throws Exception {
        // writeReplace must produce a plain HashMap so legacy GZIP-ObjectStream saves stay
        // byte-compatible with release WP (which has no idea what a PagingTileMap is).
        final PagingTileMap map = new PagingTileMap();
        map.put(new Point(0, 0), newTile(0, 0));
        map.put(new Point(1, 0), newTile(1, 0));

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(map);
        }
        final Object reloaded;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            reloaded = ois.readObject();
        }
        assertNotNull(reloaded);
        assertEquals("must be plain HashMap on disk, not PagingTileMap",
                HashMap.class, reloaded.getClass());
        @SuppressWarnings("unchecked")
        final HashMap<Point, Tile> reloadedMap = (HashMap<Point, Tile>) reloaded;
        assertEquals(2, reloadedMap.size());
        assertNotNull(reloadedMap.get(new Point(0, 0)));
    }

    // --- Test pager that records what was called without doing real I/O ---

    /**
     * Stand-in for {@link LazyTilePager}: records invocations, can be primed with
     * "loadable" tiles. Used to verify {@link PagingTileMap}'s call patterns into its
     * attached pager without standing up a real ZipFile.
     */
    private static class RecordingPager implements TilePager {
        final Set<Point> knownCoords;
        final Map<Point, Tile> populateOnLoad = new HashMap<>();
        final Map<Point, Tile> populateOnEagerLoad = new HashMap<>();
        final AtomicInteger loadCount = new AtomicInteger();
        final AtomicInteger eagerLoadCount = new AtomicInteger();
        final AtomicInteger noteCoordPresentCount = new AtomicInteger();
        final AtomicInteger noteCoordRemovedCount = new AtomicInteger();
        Point lastNotedPresent;
        Point lastNotedRemoved;

        RecordingPager(Set<Point> knownCoords) {
            this.knownCoords = new HashSet<>(knownCoords);
        }

        @Override
        public int totalCoordCount() {
            return knownCoords.size();
        }

        @Override
        public boolean hasCoord(Point tileCoord) {
            return knownCoords.contains(tileCoord);
        }

        @Override
        public Set<Point> allCoords() {
            return new HashSet<>(knownCoords);
        }

        @Override
        public Tile loadTileFor(Point tileCoord, Map<Point, Tile> resident) {
            loadCount.incrementAndGet();
            final Tile loaded = populateOnLoad.get(tileCoord);
            if (loaded != null) {
                resident.put(tileCoord, loaded);
            }
            return resident.get(tileCoord);
        }

        @Override
        public void eagerLoadAll(Map<Point, Tile> resident) {
            eagerLoadCount.incrementAndGet();
            resident.putAll(populateOnEagerLoad);
        }

        @Override
        public void noteCoordPresent(Point tileCoord) {
            noteCoordPresentCount.incrementAndGet();
            lastNotedPresent = tileCoord;
            knownCoords.add(tileCoord);
        }

        @Override
        public void noteCoordRemoved(Point tileCoord) {
            noteCoordRemovedCount.incrementAndGet();
            lastNotedRemoved = tileCoord;
            knownCoords.remove(tileCoord);
        }

        @Override
        public void clearIndex() {
            knownCoords.clear();
        }

        @Override
        public void close() {}
    }
}
