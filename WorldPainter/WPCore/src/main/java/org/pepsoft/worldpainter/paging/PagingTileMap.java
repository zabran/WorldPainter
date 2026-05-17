package org.pepsoft.worldpainter.paging;

import org.pepsoft.worldpainter.Tile;

import java.awt.Point;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The map that backs {@code Dimension.tiles}. Acts as a transparent {@link HashMap}{@code <Point,
 * Tile>} when constructed without a pager (eager mode, default). When a {@link TilePager} is
 * attached via {@link #setPager(TilePager)} it becomes the seam for lazy region paging:
 *
 * <ul>
 *   <li>{@link #containsKey} / {@link #keySet} / {@link #size} are answered from the pager's
 *       coord index, not the resident tile set.</li>
 *   <li>{@link #get} consults the resident set first, falls through to the pager to load the
 *       containing region on a miss.</li>
 *   <li>{@link #values} / {@link #entrySet} force-load every unloaded region first
 *       (semantically correct for callers that iterate "every tile in the dimension" — most
 *       in-{@code Dimension} loops; slow but correct).</li>
 *   <li>{@link #put} / {@link #remove} keep the pager's coord index in sync with the resident
 *       set.</li>
 * </ul>
 *
 * <p><b>Serialisation</b>: implements {@link Serializable#writeReplace()} returning a plain
 * {@link HashMap}, so this class is invisible in legacy GZIP-ObjectStream {@code .world} files
 * (compatible with release WP, which expects {@code tiles} to be a HashMap). Compartmentalised
 * ZIP saves null out {@code Dimension.tiles} before serialisation (see
 * {@code Dimension.writeDimDataPayload}), so the {@code writeReplace} hook doesn't fire on that
 * path. Read-back code in {@code Dimension.readObject} rewraps any incoming HashMap into a
 * fresh {@code PagingTileMap}.
 *
 * <p>Phase B0 ships this class with the pager-less branch only: behaviour is bit-for-bit
 * equivalent to {@code new HashMap<Point, Tile>()} except for one extra null-check per access.
 * Lazy mode is exercised starting in phase B2.
 */
public class PagingTileMap implements Map<Point, Tile>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Tiles currently resident in memory. In eager mode this is the complete tile set, backed
     * by {@link HashMap}. In lazy mode (after {@link #setPager}) we swap to a
     * {@link ConcurrentHashMap} so that the pager can mutate it under Dimension.readLock
     * (lazy load is a read that has a write side-effect — caching).
     */
    private Map<Point, Tile> resident;

    /** Lazy backing store, or {@code null} for eager mode. */
    private transient TilePager pager;

    /** Construct an empty map in eager mode. */
    public PagingTileMap() {
        this.resident = new HashMap<>();
    }

    /**
     * Construct a map in eager mode pre-populated from {@code initial}. Used by
     * {@code Dimension.readObject} to wrap a legacy on-disk HashMap into a PagingTileMap so
     * future code paths see a consistent type.
     */
    public PagingTileMap(Map<Point, Tile> initial) {
        this.resident = new HashMap<>(initial);
    }

    /**
     * Attach a pager, switching this map into lazy mode. May only be called once and only on
     * an empty map (we don't yet support mixing a pre-populated resident set with a lazy
     * coord index; that's something to consider for future phases).
     *
     * <p>Side effect: swaps the resident map to {@link ConcurrentHashMap} so reads can mutate
     * it concurrently with other reads — lazy loads are reads-with-caching-write semantics.
     */
    public void setPager(TilePager pager) {
        if (this.pager != null) {
            throw new IllegalStateException("Pager already attached");
        }
        if (!resident.isEmpty()) {
            throw new IllegalStateException("Cannot switch a non-empty PagingTileMap into lazy mode");
        }
        this.resident = new ConcurrentHashMap<>();
        this.pager = pager;
    }

    /**
     * Live view of the resident map, for the pager to mutate. Intentionally returns the
     * internal field so {@link TilePager#loadTileFor} can populate it as a side-effect of
     * loading.
     */
    Map<Point, Tile> residentRef() {
        return resident;
    }

    public TilePager getPager() {
        return pager;
    }

    public boolean isLazy() {
        return pager != null;
    }

    /** Resident-only view: tiles currently in memory. Does NOT trigger lazy load. */
    public Collection<Tile> residentValues() {
        return Collections.unmodifiableCollection(resident.values());
    }

    /** Resident-only count: how many tiles are in memory right now. Does NOT trigger lazy load. */
    public int residentSize() {
        return resident.size();
    }

    /**
     * Ask the pager to enforce its region budget against the current resident set. Used by
     * callers (notably the 3D view's close path) that bulk-loaded the dimension and want the
     * budget re-applied once they release their strong references. No-op in eager mode.
     */
    public void trimResidentToBudget() {
        if (pager != null) {
            pager.trimResidentToBudget(resident);
        }
    }

    // --- Map<Point, Tile> ---

    @Override
    public int size() {
        return (pager != null) ? pager.totalCoordCount() : resident.size();
    }

    @Override
    public boolean isEmpty() {
        return (pager != null) ? (pager.totalCoordCount() == 0) : resident.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        if (resident.containsKey(key)) {
            return true;
        }
        return pager != null && (key instanceof Point) && pager.hasCoord((Point) key);
    }

    @Override
    public boolean containsValue(Object value) {
        // Rare on Dimension.tiles. Don't force a full lazy load for it.
        return resident.containsValue(value);
    }

    @Override
    public Tile get(Object key) {
        final Tile resident0 = resident.get(key);
        if (resident0 != null) {
            return resident0;
        }
        if (pager != null && (key instanceof Point)) {
            return pager.loadTileFor((Point) key, resident);
        }
        return null;
    }

    @Override
    public Tile put(Point key, Tile value) {
        final Tile prev = resident.put(key, value);
        if (pager != null) {
            pager.noteCoordPresent(key);
        }
        return prev;
    }

    @Override
    public Tile remove(Object key) {
        final Tile prev = resident.remove(key);
        if (pager != null && (key instanceof Point)) {
            pager.noteCoordRemoved((Point) key);
        }
        return prev;
    }

    @Override
    public void putAll(Map<? extends Point, ? extends Tile> m) {
        for (Map.Entry<? extends Point, ? extends Tile> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        resident.clear();
        if (pager != null) {
            pager.clearIndex();
        }
    }

    @Override
    public Set<Point> keySet() {
        if (pager != null) {
            return pager.allCoords();
        }
        return resident.keySet();
    }

    @Override
    public Collection<Tile> values() {
        if (pager != null) {
            pager.eagerLoadAll(resident);
        }
        return resident.values();
    }

    @Override
    public Set<Entry<Point, Tile>> entrySet() {
        if (pager != null) {
            pager.eagerLoadAll(resident);
        }
        return resident.entrySet();
    }

    /**
     * Serialise as a plain {@link HashMap}. Forces a full lazy load first so the on-disk
     * representation is complete. Only fires on the legacy GZIP-ObjectStream save path;
     * compartmentalised saves null out {@code Dimension.tiles} before serialisation.
     */
    @Serial
    private Object writeReplace() {
        if (pager != null) {
            pager.eagerLoadAll(resident);
        }
        return new HashMap<>(resident);
    }
}
