package org.pepsoft.worldpainter.paging;

import org.pepsoft.worldpainter.Tile;

import java.awt.Point;
import java.util.Map;
import java.util.Set;

/**
 * Lazy backing store for {@link PagingTileMap}. Knows the full set of tile coordinates in a
 * dimension (cheap in-memory index) and how to load the {@code region-data-X,Y.bin} ZIP entry
 * containing any given tile coord (slow, hits disk). May hold an LRU cache of resident regions
 * to bound memory.
 *
 * <p>A {@code PagingTileMap} with no attached pager behaves as a plain {@code HashMap<Point, Tile>}.
 * Attaching a pager via {@link PagingTileMap#setPager(TilePager)} switches the map into lazy
 * mode where reads consult the pager on miss.
 *
 * <p>Threading: implementations are called concurrently from any thread (Dimension's accessors
 * are called under Dimension's read/write lock, but pager methods may also be invoked off the
 * EDT by renderers and exporters). Implementations must synchronise internally. They must NOT
 * acquire Dimension's read/write lock — that would deadlock.
 *
 * <p>This interface is introduced in phase B0 (the seam). No implementation ships in B0; the
 * first real impl lands in B2.
 */
public interface TilePager {
    /** Total number of tile coordinates known to exist in the dimension. */
    int totalCoordCount();

    /** Whether a tile with this coordinate is known to exist (resident or evicted). */
    boolean hasCoord(Point tileCoord);

    /** Snapshot of every known tile coordinate. */
    Set<Point> allCoords();

    /**
     * Load the region containing {@code tileCoord}, populate {@code resident} with all tiles
     * in that region, and return the tile at {@code tileCoord} (or {@code null} if the region
     * has no tile at that coord). May evict another region first if the resident budget is full.
     */
    Tile loadTileFor(Point tileCoord, Map<Point, Tile> resident);

    /** Load every coord-known region not currently in {@code resident}. */
    void eagerLoadAll(Map<Point, Tile> resident);

    /** Caller has just added a tile to {@code resident}; update the coord index accordingly. */
    void noteCoordPresent(Point tileCoord);

    /** Caller has just removed a tile from {@code resident}; update the coord index accordingly. */
    void noteCoordRemoved(Point tileCoord);

    /** Forget the entire index. Called only by {@link PagingTileMap#clear()}. */
    void clearIndex();

    /** Release any held file handles. */
    void close();
}
