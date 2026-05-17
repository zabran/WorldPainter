package org.pepsoft.worldpainter.paging;

import org.pepsoft.util.plugins.PluginManager;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.io.CompartmentalisedSerialisation;
import org.pepsoft.worldpainter.io.LayerRegistry;
import org.pepsoft.worldpainter.objects.AbstractObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Backing store for a {@link PagingTileMap} that defers tile-body deserialisation to first
 * access. Reads the dim's full tile coord set up front (cheap, from {@code tile-index.bin}),
 * but holds tile bodies in compressed form inside the source {@link ZipFile} and decompresses
 * them on demand, a whole 4×4 region at a time.
 *
 * <p><b>Eviction (B3a)</b>: each load past {@code residentRegionBudget} evicts the
 * least-recently-loaded resident region whose tiles are not in
 * {@code dimension.getDirtyRegions()} (dirty regions are pinned — evicting them would lose
 * unsaved edits). Eviction is skipped during {@link #eagerLoadAll} so callers iterating all
 * tiles see the complete set; peak RAM may exceed the budget during such iterations.
 *
 * <p><b>Remaining limitations:</b>
 * <ul>
 *   <li>Eviction is approximate-LRU: order tracks load events, not subsequent accesses.</li>
 *   <li>The source {@link ZipFile} stays open for the lifetime of the pager. Closing the
 *       world while WP is running will leak the file handle until GC; saving over the source
 *       file may fail on Windows. Addressed in B4 (save-path integration).</li>
 * </ul>
 *
 * <p>Threading: {@link #loadTileFor} synchronises on {@code this} so concurrent first-access
 * misses for the same region don't deserialise twice. The {@code resident} map (the
 * {@link PagingTileMap}'s internal store) must be thread-safe — typically a
 * {@link java.util.concurrent.ConcurrentHashMap} when a pager is attached — because reads
 * (under Dimension.readLock) can mutate it via this pager's load path.
 */
public class LazyTilePager implements TilePager {
    private static final Logger logger = LoggerFactory.getLogger(LazyTilePager.class);

    private final ZipFile zipFile;
    private final String dimPrefix;
    private final Set<Point> knownTileCoords;
    private final Map<Point, ZipEntry> regionEntriesByRegionCoord;
    private final LayerRegistry layerRegistry;
    private Consumer<Tile> tileLoadedCallback;

    /**
     * Regions already deserialised into resident, in approximate-LRU order (oldest load first).
     * Guarded by {@code this}; iteration must not happen outside the synchronized block.
     */
    private final LinkedHashSet<Point> loadedRegions = new LinkedHashSet<>();

    /** Max resident regions; 0 or negative = unlimited. */
    private final int residentRegionBudget;

    /** Supplies the set of dirty regions to pin from eviction. Null = nothing pinned. */
    private Supplier<Set<Point>> dirtyRegionsSupplier;

    /** Reentry guard: skip eviction during eagerLoadAll. */
    private boolean eagerLoadInProgress;

    private final AtomicLong totalLoadNanos = new AtomicLong();
    private final AtomicLong totalLoadCount = new AtomicLong();
    private final AtomicLong totalEvictionCount = new AtomicLong();

    public LazyTilePager(ZipFile zipFile,
                          String dimPrefix,
                          Set<Point> knownTileCoords,
                          Map<Point, ZipEntry> regionEntriesByRegionCoord,
                          LayerRegistry layerRegistry,
                          int residentRegionBudget) {
        this.zipFile = Objects.requireNonNull(zipFile, "zipFile");
        this.dimPrefix = Objects.requireNonNull(dimPrefix, "dimPrefix");
        this.knownTileCoords = Objects.requireNonNull(knownTileCoords, "knownTileCoords");
        this.regionEntriesByRegionCoord = Objects.requireNonNull(regionEntriesByRegionCoord, "regionEntriesByRegionCoord");
        this.layerRegistry = Objects.requireNonNull(layerRegistry, "layerRegistry");
        this.residentRegionBudget = residentRegionBudget;
    }

    /**
     * Supply the set of "dirty" (modified-since-load) regions to pin from eviction. Called by
     * {@link org.pepsoft.worldpainter.Dimension#attachTilePagerForLoad} so the pager can call
     * {@code dimension.getDirtyRegions()} on each eviction sweep.
     */
    public void setDirtyRegionsSupplier(Supplier<Set<Point>> supplier) {
        this.dirtyRegionsSupplier = supplier;
    }

    /**
     * Set the per-tile-loaded callback. Invoked once per tile when a region is first
     * deserialised; receivers wire up listeners (Dimension.this) and bind seeds to the dim's
     * garden — the same wiring that {@code Dimension.attachTileFromLoad} does for the eager
     * path.
     */
    public void setTileLoadedCallback(Consumer<Tile> callback) {
        this.tileLoadedCallback = callback;
    }

    @Override
    public int totalCoordCount() {
        return knownTileCoords.size();
    }

    @Override
    public boolean hasCoord(Point tileCoord) {
        return knownTileCoords.contains(tileCoord);
    }

    @Override
    public Set<Point> allCoords() {
        return Collections.unmodifiableSet(knownTileCoords);
    }

    @Override
    public Tile loadTileFor(Point tileCoord, Map<Point, Tile> resident) {
        final Point regionCoord = new Point(tileCoord.x >> 2, tileCoord.y >> 2);
        synchronized (this) {
            // Re-check: another reader may have loaded the containing region while we waited.
            final Tile cached = resident.get(tileCoord);
            if (cached != null) {
                return cached;
            }
            if (loadedRegions.contains(regionCoord)) {
                // Region is loaded but no tile lives at this coord.
                return null;
            }
            final ZipEntry entry = regionEntriesByRegionCoord.get(regionCoord);
            if (entry == null) {
                // No such region — coord must be absent.
                return null;
            }
            final long t0 = System.nanoTime();
            try {
                loadRegionEntry(entry, resident);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Lazy-load failed for region " + regionCoord + " in " + dimPrefix, e);
            }
            loadedRegions.add(regionCoord);
            totalLoadNanos.addAndGet(System.nanoTime() - t0);
            totalLoadCount.incrementAndGet();
            // Evict unless we're in the middle of eagerLoadAll (which wants every region).
            if (!eagerLoadInProgress) {
                evictUntilAtBudget(resident);
            }
            return resident.get(tileCoord);
        }
    }

    @Override
    public void eagerLoadAll(Map<Point, Tile> resident) {
        synchronized (this) {
            eagerLoadInProgress = true;
            try {
                for (Map.Entry<Point, ZipEntry> e : regionEntriesByRegionCoord.entrySet()) {
                    if (loadedRegions.contains(e.getKey())) {
                        continue;
                    }
                    final long t0 = System.nanoTime();
                    try {
                        loadRegionEntry(e.getValue(), resident);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(
                                "Eager-load failed for region " + e.getKey() + " in " + dimPrefix, ex);
                    }
                    loadedRegions.add(e.getKey());
                    totalLoadNanos.addAndGet(System.nanoTime() - t0);
                    totalLoadCount.incrementAndGet();
                }
            } finally {
                eagerLoadInProgress = false;
            }
        }
    }

    /**
     * Drop resident regions from the LRU tail until {@code loadedRegions.size() <= budget}.
     * Skips regions in the dimension's dirty set (saving them via the lazy path would lose
     * unsaved edits). Must be called under {@code synchronized(this)}.
     */
    private void evictUntilAtBudget(Map<Point, Tile> resident) {
        if (residentRegionBudget <= 0 || loadedRegions.size() <= residentRegionBudget) {
            return;
        }
        final Set<Point> pinned = (dirtyRegionsSupplier != null)
                ? dirtyRegionsSupplier.get()
                : Collections.emptySet();
        final Iterator<Point> it = loadedRegions.iterator();
        while (it.hasNext() && loadedRegions.size() > residentRegionBudget) {
            final Point region = it.next();
            if (pinned.contains(region)) {
                continue;
            }
            // Drop every tile in this 4×4 region from resident. The Dimension's listener
            // relationship is FROM tile TO listener, so simply releasing the strong reference
            // here lets the tile's resident-side strong refs drop. GC handles the rest.
            for (int dx = 0; dx < 4; dx++) {
                for (int dy = 0; dy < 4; dy++) {
                    resident.remove(new Point((region.x << 2) | dx, (region.y << 2) | dy));
                }
            }
            it.remove();
            totalEvictionCount.incrementAndGet();
        }
    }

    @Override
    public void noteCoordPresent(Point tileCoord) {
        knownTileCoords.add(tileCoord);
    }

    @Override
    public void noteCoordRemoved(Point tileCoord) {
        knownTileCoords.remove(tileCoord);
    }

    @Override
    public void clearIndex() {
        knownTileCoords.clear();
        synchronized (this) {
            loadedRegions.clear();
        }
    }

    @Override
    public void close() {
        // ZipFile is owned by the World2-level resource handle (LazyArchive), not by individual
        // pagers — multiple dims may share the same archive. Close coordination lives in
        // World2.closeLazyResources (added in B2).
    }

    // --- Diagnostics for the smoke test / benchmark ---

    public long getTotalLoadNanos() {
        return totalLoadNanos.get();
    }

    public long getTotalLoadCount() {
        return totalLoadCount.get();
    }

    public int getLoadedRegionCount() {
        synchronized (this) {
            return loadedRegions.size();
        }
    }

    public long getTotalEvictionCount() {
        return totalEvictionCount.get();
    }

    public int getResidentRegionBudget() {
        return residentRegionBudget;
    }

    /** Holder array trick to ferry the deserialised list out of the {@code IOAction} lambda. */
    @SuppressWarnings("unchecked")
    private void loadRegionEntry(ZipEntry entry, Map<Point, Tile> resident) throws IOException {
        @SuppressWarnings("rawtypes")
        final List[] holder = new List[]{null};
        CompartmentalisedSerialisation.withLoadBinding(layerRegistry, () -> {
            try (InputStream in = zipFile.getInputStream(entry);
                 ObjectInputStream oin = CompartmentalisedSerialisation.newObjectInputStream(
                         in, PluginManager.getPluginClassLoader(), AbstractObject.class)) {
                final Object obj = oin.readObject();
                if (!(obj instanceof List)) {
                    throw new IOException("Region entry " + entry.getName()
                            + " contains unexpected type " + obj.getClass());
                }
                holder[0] = (List<?>) obj;
            } catch (ClassNotFoundException e) {
                throw new IOException("ClassNotFoundException reading region entry "
                        + entry.getName() + ": " + e.getMessage(), e);
            }
        });
        final List<Tile> loaded = holder[0];
        for (Tile tile : loaded) {
            final Point key = new Point(tile.getX(), tile.getY());
            if (!resident.containsKey(key)) {
                resident.put(key, tile);
                if (tileLoadedCallback != null) {
                    tileLoadedCallback.accept(tile);
                }
            }
        }
    }
}
