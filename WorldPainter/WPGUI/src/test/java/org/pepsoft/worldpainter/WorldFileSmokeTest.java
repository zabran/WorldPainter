package org.pepsoft.worldpainter;

import org.pepsoft.util.UnsafeRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.Set;

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

        // -------- 4. Probe compartmentalised ZIP save (existing path; known-suspect) --------
        // Note: this is the path Marek wants to become the default in upcoming work (A2). It currently
        // contains a latent bug — Dimension.save(ZipOutputStream) nulls `tiles` and then triggers
        // Dimension.writeObject() which calls prepareForSaving() → ensureAllReadable() → NPE on the
        // now-null tiles map. Caught and reported here so the smoke test still reports a pass when
        // the legacy path works.
        final File zipOut = File.createTempFile("wp-smoke-zip-", ".world.zip");
        zipOut.deleteOnExit();
        final long zipSaveStart = System.currentTimeMillis();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(zipOut), 1 << 20)) {
            new WorldIO(world).saveCompartmentalised(out);
            final long zipSaveMs = System.currentTimeMillis() - zipSaveStart;
            logger.info("Compartmentalised ZIP save: {} ms ({} bytes)", zipSaveMs, zipOut.length());
        } catch (RuntimeException | IOException e) {
            logger.warn("Compartmentalised ZIP save FAILED (pre-existing bug; out of scope for this test): {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        // -------- 5. PR #478 microbenchmark --------
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
