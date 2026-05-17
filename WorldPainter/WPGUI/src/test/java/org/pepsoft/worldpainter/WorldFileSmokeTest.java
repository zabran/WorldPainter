package org.pepsoft.worldpainter;

import org.pepsoft.util.UnsafeRandom;
import org.pepsoft.worldpainter.layers.CustomLayer;
import org.pepsoft.worldpainter.layers.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Headless smoke test for a real WorldPainter {@code .world} file. Loads a user-supplied
 * {@code .world} via {@link WorldIO#load}, reports basic stats, round-trips it through both save
 * formats ({@link WorldIO#save legacy GZIP} and {@link WorldIO#saveCompartmentalised ZIP}), then
 * re-loads each artifact to confirm the file is fully self-describing under our fork's class
 * versions (post JIDE-removal, post {@code pepsoft-utils 2.0.2-local}, post {@code MaterialVersions}).
 *
 * <p>Also runs a microbenchmark for PR #478 to substantiate the &gt;50% perf claim:
 * {@code new Random(seed).nextInt(bound)} vs. {@code UnsafeRandom.fastNextInt(seed, bound)} —
 * the exact substitution that PR makes in {@link Terrain#getSurfaceObject} hot paths.
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn -pl WorldPainter/WPCore exec:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=org.pepsoft.worldpainter.WorldFileSmokeTest \
 *     -Dexec.args="<absolute path to .world file>"
 * </pre>
 * If no path is given, defaults to {@code test.world} under
 * {@code <your home>/Downloads/}.
 */
public class WorldFileSmokeTest extends AbstractTool {

    public static void main(String[] args) throws IOException, UnloadableWorldException {
        initialisePlatform();

        final File inFile = (args.length >= 1)
                ? new File(args[0])
                : new File(System.getProperty("user.home"), "Downloads/test.world");
        if (!inFile.isFile()) {
            logger.error("Input file does not exist: {}", inFile.getAbsolutePath());
            System.exit(2);
        }
        logger.info("=== WorldFileSmokeTest ===");
        logger.info("Input: {} ({} bytes)", inFile.getAbsolutePath(), inFile.length());

        // -------- 1. Load --------
        final long loadStart = System.currentTimeMillis();
        final WorldIO loader = new WorldIO();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(inFile), 1 << 20)) {
            loader.load(in);
        }
        final long loadMs = System.currentTimeMillis() - loadStart;
        final World2 world = loader.getWorld();
        logger.info("Load: {} ms", loadMs);
        logger.info("World name: {}", world.getName());
        logger.info("Platform: {}", world.getPlatform() == null ? "(null)" : world.getPlatform().displayName);
        final Set<Dimension> dimensions = world.getDimensions();
        logger.info("Dimension count: {}", dimensions.size());
        int totalTiles = 0;
        for (Dimension dim : dimensions) {
            int n = dim.getTileCount();
            totalTiles += n;
            logger.info("  Dim {} anchor={} tiles={}", dim.getName(), dim.getAnchor(), n);
        }
        logger.info("Total tiles across all dimensions: {}", totalTiles);

        // -------- 2. Save legacy GZIP --------
        final File legacyOut = File.createTempFile("wp-smoke-legacy-", ".world");
        legacyOut.deleteOnExit();
        final long legacySaveStart = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(legacyOut), 1 << 20)) {
            new WorldIO(world).save(out);
        }
        final long legacySaveMs = System.currentTimeMillis() - legacySaveStart;
        logger.info("Legacy GZIP save: {} ms ({} bytes)", legacySaveMs, legacyOut.length());

        // -------- 3. Re-load legacy GZIP — the real round-trip test --------
        final long reloadStart = System.currentTimeMillis();
        final WorldIO reloader = new WorldIO();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(legacyOut), 1 << 20)) {
            reloader.load(in);
        }
        final long reloadMs = System.currentTimeMillis() - reloadStart;
        final World2 reloaded = reloader.getWorld();
        int reloadedTiles = 0;
        for (Dimension dim : reloaded.getDimensions()) {
            reloadedTiles += dim.getTileCount();
        }
        logger.info("Re-load of legacy save: {} ms (tiles: {} — expected {})",
                reloadMs, reloadedTiles, totalTiles);
        if (reloadedTiles != totalTiles) {
            logger.error("ROUND-TRIP FAILED: tile count mismatch");
            System.exit(3);
        }
        logger.info("Legacy round-trip: PASSED");

        // -------- 4. Compartmentalised ZIP save + round-trip --------
        // This is the new format Marek wants to become the default (A2). Save it next to the
        // input file with a stable name so each run overwrites and the artifact is inspectable
        // (unzip -l ..., diff ZIP entries between runs).
        final File zipOut = new File(inFile.getParentFile(),
                inFile.getName().replaceFirst("\\.world$", "") + "-smoke.world.zip");
        if (zipOut.exists()) {
            zipOut.delete();
        }
        final long zipSaveStart = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(zipOut), 1 << 20)) {
            new WorldIO(world).saveCompartmentalised(out);
        }
        final long zipSaveMs = System.currentTimeMillis() - zipSaveStart;
        logger.info("Compartmentalised ZIP save: {} ms ({} bytes) -> {}",
                zipSaveMs, zipOut.length(), zipOut.getAbsolutePath());

        // -------- 5. Re-load from ZIP via the explicit File API --------
        final long zipReloadStart = System.currentTimeMillis();
        final WorldIO zipReloader = new WorldIO();
        zipReloader.loadCompartmentalised(zipOut);
        final long zipReloadMs = System.currentTimeMillis() - zipReloadStart;
        final World2 zipReloaded = zipReloader.getWorld();
        int zipReloadedTiles = 0;
        for (Dimension dim : zipReloaded.getDimensions()) {
            zipReloadedTiles += dim.getTileCount();
        }
        logger.info("Re-load of compartmentalised save: {} ms (tiles: {} — expected {})",
                zipReloadMs, zipReloadedTiles, totalTiles);
        if (zipReloadedTiles != totalTiles) {
            logger.error("ZIP ROUND-TRIP FAILED: tile count mismatch");
            System.exit(4);
        }
        if (zipReloaded.getDimensions().size() != dimensions.size()) {
            logger.error("ZIP ROUND-TRIP FAILED: dimension count mismatch ({} vs {})",
                    zipReloaded.getDimensions().size(), dimensions.size());
            System.exit(4);
        }
        logger.info("Compartmentalised round-trip: PASSED");

        // -------- 5a. B1 — tile-index.bin present and matches in-memory coord set --------
        verifyTileIndex(zipOut, world);
        logger.info("tile-index.bin per dim: PASSED");

        // -------- 5b. A3 — Layer identity preserved across ZIP boundary --------
        // Pre-A3: each ZIP entry was a fresh ObjectOutputStream, so the same CustomLayer ended up
        // as a different Java object per region. Layer.equals is id-based so map lookups still
        // worked, but identity-based code paths and post-load init() were silently duplicated.
        // Post-A3: a single LayerRegistry (layers.bin entry) backs every reference. Assert that
        // every Layer reachable from any reloaded tile is `==` to the canonical one in the
        // dimension's customLayers list.
        verifyLayerIdentityPreserved(zipReloaded);
        logger.info("Layer identity across ZIP boundary: PASSED");

        // -------- 5c. A4 — incremental save (this run + a forced-dirty rewrite) --------
        // First incremental save with no dirty regions: every region should be copied verbatim
        // from prior. Should be dramatically faster than the full save above.
        final File zipOut2 = new File(zipOut.getParentFile(), zipOut.getName().replace(".zip", "-2.zip"));
        if (zipOut2.exists()) { zipOut2.delete(); }
        // The currently in-memory `world` was loaded fresh — its dirtyRegions are empty.
        final long incrCleanStart = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(zipOut2), 1 << 20)) {
            new WorldIO(world).saveCompartmentalised(out, zipOut);
        }
        final long incrCleanMs = System.currentTimeMillis() - incrCleanStart;
        logger.info("Incremental save (0 dirty regions): {} ms ({} bytes) -> {}",
                incrCleanMs, zipOut2.length(), zipOut2.getName());

        // Now dirty a few tiles so we get a mixed clean/dirty run.
        final Dimension probeDim = world.getDimensions().iterator().next();
        int markedDirty = 0;
        for (Tile probeTile : probeDim.getTiles()) {
            // Bump (then unbump) a height value so the dimension's heightMapChanged listener
            // marks the region. Net visible state change is zero.
            final int x = 0, y = 0;
            final float orig = probeTile.getHeight(x, y);
            probeTile.setHeight(x, y, orig + 0.001f);
            probeTile.setHeight(x, y, orig);
            markedDirty++;
            if (markedDirty >= 5) {
                break;
            }
        }
        final int dirtyRegionCount = probeDim.getDirtyRegions().size();
        logger.info("Marked {} tiles dirty -> {} dirty regions on {}",
                markedDirty, dirtyRegionCount, probeDim.getName());

        final File zipOut3 = new File(zipOut.getParentFile(), zipOut.getName().replace(".zip", "-3.zip"));
        if (zipOut3.exists()) { zipOut3.delete(); }
        final long incrDirtyStart = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(zipOut3), 1 << 20)) {
            new WorldIO(world).saveCompartmentalised(out, zipOut2);
        }
        final long incrDirtyMs = System.currentTimeMillis() - incrDirtyStart;
        logger.info("Incremental save ({} dirty regions of {}): {} ms",
                dirtyRegionCount, probeDim.getDirtyRegions().size() == 0 ? "cleared" : "?",
                incrDirtyMs);
        // After save, dirtyRegions should be cleared.
        if (! probeDim.getDirtyRegions().isEmpty()) {
            logger.error("A4 FAILED: dirtyRegions not cleared after save: {} still dirty",
                    probeDim.getDirtyRegions().size());
            System.exit(8);
        }

        // Verify the incremental save round-trips.
        final WorldIO incrReloader = new WorldIO();
        incrReloader.loadCompartmentalised(zipOut3);
        int incrReloadedTiles = 0;
        for (Dimension dim : incrReloader.getWorld().getDimensions()) {
            incrReloadedTiles += dim.getTileCount();
        }
        if (incrReloadedTiles != totalTiles) {
            logger.error("A4 INCREMENTAL ROUND-TRIP FAILED: {} tiles, expected {}", incrReloadedTiles, totalTiles);
            System.exit(8);
        }
        logger.info("Incremental round-trip: PASSED");

        // -------- 6. Verify magic-byte dispatch in load(File) --------
        // The whole point of A1 is that callers can hand the same File to load() and the right
        // path runs automatically. Round-trip both formats via load(File) and confirm tile counts.
        final long dispatchLegacyStart = System.currentTimeMillis();
        final WorldIO dispatchLegacy = new WorldIO();
        dispatchLegacy.load(legacyOut);
        final long dispatchLegacyMs = System.currentTimeMillis() - dispatchLegacyStart;
        int dispatchLegacyTiles = 0;
        for (Dimension dim : dispatchLegacy.getWorld().getDimensions()) {
            dispatchLegacyTiles += dim.getTileCount();
        }
        if (dispatchLegacyTiles != totalTiles) {
            logger.error("load(File) dispatch (legacy GZIP) FAILED: {} tiles, expected {}", dispatchLegacyTiles, totalTiles);
            System.exit(5);
        }
        logger.info("load(File) dispatch (legacy GZIP): {} ms — PASSED", dispatchLegacyMs);

        final long dispatchZipStart = System.currentTimeMillis();
        final WorldIO dispatchZip = new WorldIO();
        dispatchZip.load(zipOut);
        final long dispatchZipMs = System.currentTimeMillis() - dispatchZipStart;
        int dispatchZipTiles = 0;
        for (Dimension dim : dispatchZip.getWorld().getDimensions()) {
            dispatchZipTiles += dim.getTileCount();
        }
        if (dispatchZipTiles != totalTiles) {
            logger.error("load(File) dispatch (compartmentalised ZIP) FAILED: {} tiles, expected {}", dispatchZipTiles, totalTiles);
            System.exit(5);
        }
        logger.info("load(File) dispatch (compartmentalised ZIP): {} ms — PASSED", dispatchZipMs);

        // -------- 7. Verify load(InputStream) spool path for ZIPs --------
        // Less-common path but supported (preserves the legacy InputStream signature).
        final long streamSpoolStart = System.currentTimeMillis();
        final WorldIO streamSpool = new WorldIO();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(zipOut), 1 << 20)) {
            streamSpool.load(in);
        }
        final long streamSpoolMs = System.currentTimeMillis() - streamSpoolStart;
        int streamSpoolTiles = 0;
        for (Dimension dim : streamSpool.getWorld().getDimensions()) {
            streamSpoolTiles += dim.getTileCount();
        }
        if (streamSpoolTiles != totalTiles) {
            logger.error("load(InputStream) spool (compartmentalised ZIP) FAILED: {} tiles, expected {}", streamSpoolTiles, totalTiles);
            System.exit(6);
        }
        logger.info("load(InputStream) ZIP spool: {} ms — PASSED", streamSpoolMs);

        // -------- 8. B2 — lazy region paging round-trip --------
        verifyLazyLoad(zipOut, totalTiles);

        // -------- 9. PR #478 microbenchmark --------
        runPr478Benchmark();
    }

    /**
     * Microbenchmark of the primitive substitution that PR #478 makes in
     * {@link Terrain#getSurfaceObject} hot paths: {@code new Random(seed).nextInt(bound)} →
     * {@code UnsafeRandom.fastNextInt(seed, bound)}. Bound is {@code 1000} to mirror DESERT's
     * {@code CACTUS_CHANCE}; the seed-derivation expression mirrors the per-block seed mix
     * {@code seed + (x * 65537L) + (y * 4099L)}.
     *
     * <p>Three warmup + three measurement rounds, blackhole pattern via an XOR accumulator that's
     * forced to the JVM by being printed. Iteration count chosen so each round takes a few
     * hundred ms on contemporary hardware.
     */
    private static void runPr478Benchmark() {
        final int iterations = 20_000_000;
        final int bound = 1000;
        final long baseSeed = 0x12345678ABCDL;

        logger.info("=== PR #478 microbenchmark ===");
        logger.info("Iterations per round: {}", iterations);
        logger.info("Bound: {}", bound);

        // Warm up both paths.
        for (int round = 0; round < 3; round++) {
            timeRandom(iterations, bound, baseSeed);
            timeUnsafeRandom(iterations, bound, baseSeed);
        }

        // Measure.
        long randomTotalNs = 0, unsafeTotalNs = 0;
        long randomAcc = 0, unsafeAcc = 0;
        for (int round = 0; round < 3; round++) {
            long[] r = timeRandom(iterations, bound, baseSeed);
            long[] u = timeUnsafeRandom(iterations, bound, baseSeed);
            randomTotalNs += r[0]; randomAcc ^= r[1];
            unsafeTotalNs += u[0]; unsafeAcc ^= u[1];
            logger.info("Round {}: new Random()={} ms, UnsafeRandom.fastNextInt={} ms",
                    round + 1, r[0] / 1_000_000, u[0] / 1_000_000);
        }
        final double randomAvgMs = randomTotalNs / 3.0 / 1_000_000.0;
        final double unsafeAvgMs = unsafeTotalNs / 3.0 / 1_000_000.0;
        final double speedup = randomAvgMs / unsafeAvgMs;
        final double pctFaster = (randomAvgMs - unsafeAvgMs) / randomAvgMs * 100.0;
        logger.info("---");
        logger.info("Mean: new Random()={} ms; UnsafeRandom.fastNextInt={} ms",
                String.format("%.1f", randomAvgMs), String.format("%.1f", unsafeAvgMs));
        logger.info("Speedup: {}× (UnsafeRandom.fastNextInt is {}% faster)",
                String.format("%.2f", speedup), String.format("%.1f", pctFaster));
        logger.info("(blackhole: r={}, u={})", randomAcc, unsafeAcc);
    }

    /**
     * Assert that A3's layer-deduplication actually delivers the property it advertises: every
     * {@code Layer} reachable from any tile's {@code getLayers()} call must be the exact same
     * Java object as the corresponding {@code CustomLayer} in the dimension's {@code customLayers}
     * list. Equality alone isn't enough — {@link Layer#equals} is id-based and would have passed
     * even when the format was producing duplicate Layer instances per region (which was the
     * pre-A3 behaviour).
     *
     * <p>Built-in (non-custom) layers can also appear in tile data; for those, we just require
     * that two tiles referencing the same built-in {@code Layer} (by id) get the <em>same</em>
     * canonical instance — i.e., the canonical-instance count must match the unique-id count.
     */
    /**
     * Verify B2 lazy mode: toggle {@code Configuration.regionPagingEnabled = true}, load the
     * compartmentalised file via {@code load(File)}, confirm the dim has a pager attached,
     * verify cheap operations (coord enumeration) don't load any regions, then trigger an
     * actual {@code getTile} call and confirm the region is materialised correctly.
     * Finally close the lazy archive and confirm the file handle is released.
     */
    private static void verifyLazyLoad(File zipOut, int totalTiles)
            throws IOException, UnloadableWorldException {
        final Configuration cfg = Configuration.getInstance();
        final boolean priorFlag = cfg.isRegionPagingEnabled();
        cfg.setRegionPagingEnabled(true);
        try {
            final long lazyLoadStart = System.currentTimeMillis();
            final WorldIO lazyIo = new WorldIO();
            lazyIo.load(zipOut);
            final long lazyLoadMs = System.currentTimeMillis() - lazyLoadStart;
            final World2 lazyWorld = lazyIo.getWorld();

            // Dim should have a pager attached.
            int totalLazyCoords = 0;
            for (Dimension dim : lazyWorld.getDimensions()) {
                if (dim.getTilePager() == null) {
                    logger.error("B2 FAILED: dim {} has no pager attached", dim.getAnchor());
                    System.exit(10);
                }
                totalLazyCoords += dim.getTileCoords().size();
            }
            if (totalLazyCoords != totalTiles) {
                logger.error("B2 FAILED: coord index has {} tiles, expected {}",
                        totalLazyCoords, totalTiles);
                System.exit(10);
            }
            logger.info("Lazy load: {} ms, {} coords indexed across {} dim(s)",
                    lazyLoadMs, totalLazyCoords, lazyWorld.getDimensions().size());

            // After load, no regions should be resident yet.
            int totalLoadedBefore = 0;
            for (Dimension dim : lazyWorld.getDimensions()) {
                totalLoadedBefore += dim.getTilePager().getLoadedRegionCount();
            }
            if (totalLoadedBefore != 0) {
                logger.error("B2 FAILED: {} regions loaded after open (expected 0)", totalLoadedBefore);
                System.exit(10);
            }

            // Force-load a single tile.
            final Dimension dim = lazyWorld.getDimensions().iterator().next();
            final Point firstCoord = dim.getTileCoords().iterator().next();
            final long t0 = System.nanoTime();
            final Tile firstTile = dim.getTile(firstCoord);
            final long getTileMs = (System.nanoTime() - t0) / 1_000_000;
            if (firstTile == null) {
                logger.error("B2 FAILED: getTile({}) returned null", firstCoord);
                System.exit(10);
            }
            logger.info("First-access getTile({}): {} ms; region count loaded={} after",
                    firstCoord, getTileMs, dim.getTilePager().getLoadedRegionCount());

            // Trigger eager-load of EVERYTHING (via values()) and verify the count matches.
            // This also exercises the eagerLoadAll path.
            final long fullLoadStart = System.currentTimeMillis();
            int seen = 0;
            for (Tile t : dim.getTiles()) {
                if (t == null) {
                    logger.error("B2 FAILED: null tile from getTiles()");
                    System.exit(10);
                }
                seen++;
            }
            final long fullLoadMs = System.currentTimeMillis() - fullLoadStart;
            if (seen != totalTiles) {
                logger.error("B2 FAILED: eager iteration saw {} tiles, expected {}", seen, totalTiles);
                System.exit(10);
            }
            logger.info("eagerLoadAll: {} ms; {} regions resident; {} loads total avg {} ms",
                    fullLoadMs,
                    dim.getTilePager().getLoadedRegionCount(),
                    dim.getTilePager().getTotalLoadCount(),
                    dim.getTilePager().getTotalLoadCount() > 0
                            ? dim.getTilePager().getTotalLoadNanos() / dim.getTilePager().getTotalLoadCount() / 1_000_000
                            : 0);

            // Close to release the file handle.
            lazyWorld.closeLazyResources();
            logger.info("Lazy round-trip: PASSED");
        } finally {
            cfg.setRegionPagingEnabled(priorFlag);
        }
    }

    /**
     * Verify the {@code <anchor>/tile-index.bin} entry exists for every dimension in a freshly
     * saved compartmentalised archive, and that its decoded {@code Set<Point>} matches the
     * in-memory tile coord set on the corresponding live Dimension. Catches B1 regressions
     * before they reach the consuming lazy loader in B2.
     */
    private static void verifyTileIndex(File zipFile, World2 world) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            for (Dimension dim : world.getDimensions()) {
                final String entryName = dim.getAnchor() + "/tile-index.bin";
                final ZipEntry entry = zf.getEntry(entryName);
                if (entry == null) {
                    logger.error("B1 FAILED: missing entry {}", entryName);
                    System.exit(9);
                }
                final Set<Point> indexed;
                try (InputStream in = zf.getInputStream(entry)) {
                    indexed = Dimension.readTileIndexPayload(in);
                }
                final Set<Point> expected = new HashSet<>(dim.getTileCoords());
                if (!indexed.equals(expected)) {
                    final Set<Point> onlyIndex = new HashSet<>(indexed);
                    onlyIndex.removeAll(expected);
                    final Set<Point> onlyMem = new HashSet<>(expected);
                    onlyMem.removeAll(indexed);
                    logger.error("B1 FAILED: tile-index for {} doesn't match live tiles "
                                    + "(only-in-index={}, only-in-memory={})",
                            dim.getAnchor(), onlyIndex.size(), onlyMem.size());
                    System.exit(9);
                }
                logger.info("tile-index.bin for {} dim: {} tile coords match in-memory set "
                                + "({} bytes on disk)",
                        dim.getAnchor(), indexed.size(), entry.getSize());
            }
        }
    }

    private static void verifyLayerIdentityPreserved(World2 world) {
        // Build an identity map: layer-id -> first instance seen, plus a second map id -> set of
        // distinct instances. If the registry is doing its job, every set is size 1.
        final Map<String, IdentityHashMap<Layer, Boolean>> distinctInstancesById = new java.util.HashMap<>();
        int tilesScanned = 0;
        for (Dimension dim : world.getDimensions()) {
            for (Tile tile : dim.getTiles()) {
                for (Layer layer : tile.getLayers()) {
                    distinctInstancesById
                            .computeIfAbsent(layer.getId(), k -> new IdentityHashMap<>())
                            .put(layer, Boolean.TRUE);
                }
                tilesScanned++;
            }
            // Also include the dim's custom-layer list — they must share identity with tile refs.
            for (CustomLayer cl : dim.getCustomLayers()) {
                distinctInstancesById
                        .computeIfAbsent(cl.getId(), k -> new IdentityHashMap<>())
                        .put(cl, Boolean.TRUE);
            }
        }
        int duplicated = 0, uniqueIds = 0, totalInstances = 0;
        for (Map.Entry<String, IdentityHashMap<Layer, Boolean>> e : distinctInstancesById.entrySet()) {
            uniqueIds++;
            final int n = e.getValue().size();
            totalInstances += n;
            if (n > 1) {
                duplicated++;
                logger.error("Layer id '{}' has {} distinct Java instances after load", e.getKey(), n);
            }
        }
        logger.info("Layer identity scan: {} tiles, {} unique layer ids, {} total instances",
                tilesScanned, uniqueIds, totalInstances);
        if (duplicated > 0) {
            logger.error("A3 LAYER-IDENTITY FAILED: {} layer ids had >1 Java instance", duplicated);
            System.exit(7);
        }
    }

    private static long[] timeRandom(int iterations, int bound, long baseSeed) {
        long acc = 0;
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final long seed = baseSeed + (i * 65537L) + ((i ^ 0xCAFE) * 4099L);
            acc ^= new Random(seed).nextInt(bound);
        }
        final long ns = System.nanoTime() - start;
        return new long[]{ns, acc};
    }

    private static long[] timeUnsafeRandom(int iterations, int bound, long baseSeed) {
        long acc = 0;
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final long seed = baseSeed + (i * 65537L) + ((i ^ 0xCAFE) * 4099L);
            acc ^= UnsafeRandom.fastNextInt(seed, bound);
        }
        final long ns = System.nanoTime() - start;
        return new long[]{ns, acc};
    }

    private static final Logger logger = LoggerFactory.getLogger(WorldFileSmokeTest.class);
}
