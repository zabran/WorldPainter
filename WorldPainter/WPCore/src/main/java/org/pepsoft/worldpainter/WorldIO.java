package org.pepsoft.worldpainter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.pepsoft.minecraft.Direction;
import org.pepsoft.minecraft.SeededGenerator;
import org.pepsoft.minecraft.SuperflatGenerator;
import org.pepsoft.minecraft.SuperflatPreset;
import org.pepsoft.util.WPCustomObjectInputStream;
import org.pepsoft.util.plugins.PluginManager;
import org.pepsoft.worldpainter.history.HistoryEntry;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.layers.exporters.ExporterSettings;
import org.pepsoft.worldpainter.layers.exporters.ResourcesExporter;
import org.pepsoft.worldpainter.objects.AbstractObject;
import org.pepsoft.worldpainter.plugins.WPPluginManager;
import org.pepsoft.worldpainter.vo.EventVO;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.pepsoft.minecraft.Material.*;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_MCREGION;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.Generator.DEFAULT;

// TODO why this design again? Just make these utility methods, surely?

/**
 * A utility class for saving and loading WorldPainter {@link World2 worlds} in
 * the WorldPainter binary file format, with support for metadata and for
 * loading and migrating legacy formats.
 *
 * <p>Created by Pepijn Schmitz on 02-07-15.
 */
@SuppressWarnings("deprecation") // Support for deprecated classes is a stated goal of this class
public class WorldIO {
    public WorldIO() {
        // Do nothing
    }

    public WorldIO(World2 world) {
        this.world = world;
    }

    public World2 getWorld() {
        return world;
    }

    public void setWorld(World2 world) {
        this.world = world;
    }

    /**
     * Save the world to a binary stream, such that it can later be loaded using
     * {@link #load(InputStream)}. The stream is closed before returning.
     *
     * @param out The stream to which to save the world.
     * @throws IOException If an I/O error occurred saving the world.
     */
    public void save(OutputStream out) throws IOException {
        try (ObjectOutputStream wrappedOut = new ObjectOutputStream(new GZIPOutputStream(out))) {
            wrappedOut.writeObject(getMetadata());
            wrappedOut.writeObject(world);
        }
    }

    /**
     * Save the world to a binary stream, such that it can later be loaded using {@link #load(InputStream)}. The stream
     * is closed before returning.
     *
     * <p>This version uses a format that compresses every region separately, for faster access to individual regions
     * without having to load the entire world.
     *
     * @param out The stream to which to save the world.
     * @throws IOException If an I/O error occurred saving the world.
     */
    /**
     * Save the world in the compartmentalised ZIP format.
     *
     * <p>The {@code layers.bin} entry that's written here at the end of the archive is what makes
     * the format correct under cross-entry layer references: every {@code Layer} referenced by
     * {@code world-data.bin}, {@code dim-data.bin}, or any {@code region-data-X,Y.bin} entry is
     * replaced inline with a {@link org.pepsoft.worldpainter.io.LayerRef}{@code (index)}, and the
     * {@code layers.bin} entry holds the (index → Layer) registry. The substitution happens
     * silently inside {@link CompartmentalisedSerialisation#newObjectOutputStream}, which is what
     * the embedded {@link World2#save} / {@link Dimension#save} calls use to open their per-entry
     * {@code ObjectOutputStream}s.
     *
     * <p>The registry has to be written <em>after</em> the data entries — we don't know which
     * layers will be referenced until they've all been walked. Load works fine with the registry
     * at the end because {@link ZipFile} indexes the central directory, not the entry order.
     */
    public void saveCompartmentalised(OutputStream out) throws IOException {
        final org.pepsoft.worldpainter.io.LayerRegistry registry = new org.pepsoft.worldpainter.io.LayerRegistry();
        try (ZipOutputStream wrappedOut = new ZipOutputStream(out)) {
            final ObjectMapper objectMapper = new ObjectMapper()
                    .disable(AUTO_CLOSE_TARGET)
                    .disable(WRITE_DATES_AS_TIMESTAMPS);
            wrappedOut.putNextEntry(new ZipEntry("metadata.json"));
            try {
                objectMapper.writeValue(wrappedOut, getMetadata());
            } finally {
                wrappedOut.closeEntry();
            }
            org.pepsoft.worldpainter.io.CompartmentalisedSerialisation.withSaveBinding(registry, () -> {
                world.save(wrappedOut);
            });

            // Now write the registry, using a plain stream — its contents *are* the Layer
            // instances, so we don't want them to round-trip through LayerRef.
            wrappedOut.putNextEntry(new ZipEntry("layers.bin"));
            try {
                final ObjectOutputStream layersOut = new ObjectOutputStream(wrappedOut);
                layersOut.writeObject(registry);
                layersOut.flush();
            } finally {
                wrappedOut.closeEntry();
            }
        }
    }

    /**
     * Load a world from a file. Detects the on-disk format from the first two bytes:
     * <ul>
     *   <li>{@code 1F 8B} (GZIP magic) → dispatches to {@link #load(InputStream)} (legacy single-stream format)</li>
     *   <li>{@code 50 4B} (ZIP local-file-header magic, "PK") → dispatches to {@link #loadCompartmentalised(File)} (new region-wise format)</li>
     *   <li>anything else → {@link UnloadableWorldException}</li>
     * </ul>
     *
     * <p>This is the preferred load entry point for callers that have a file on disk —
     * unlike {@link #load(InputStream)}, it never has to spool the new format to a temp
     * file because {@link ZipFile} can read it in place.
     *
     * @param file The {@code .world} or {@code .world.zip} file to load.
     */
    public void load(File file) throws IOException, UnloadableWorldException {
        final byte[] magic = new byte[2];
        try (InputStream in = new FileInputStream(file)) {
            final int read = in.read(magic);
            if (read < 2) {
                throw new UnloadableWorldException("File is too short to be a WorldPainter world (" + read + " bytes)", null);
            }
        }
        if (isGzipMagic(magic)) {
            try (FileInputStream in = new FileInputStream(file)) {
                load(in);
            }
        } else if (isZipMagic(magic)) {
            loadCompartmentalised(file);
        } else {
            throw new UnloadableWorldException(
                    String.format("File does not start with a recognised magic (got 0x%02X 0x%02X — expected GZIP 0x1F 0x8B or ZIP 0x50 0x4B)", magic[0] & 0xFF, magic[1] & 0xFF),
                    null);
        }
    }

    /**
     * Load a world from a binary stream to which it was previously saved by
     * {@link #save(OutputStream)} or {@link #saveCompartmentalised(OutputStream)}, or by a
     * previous version of WorldPainter. The stream is closed before returning.
     *
     * <p>Sniffs the first two bytes to dispatch between the legacy GZIP path and the new
     * compartmentalised ZIP path. The ZIP path requires random access (
     * {@link ZipFile}), so when this method receives a ZIP stream it spools the remaining
     * bytes to a temporary file before dispatching to {@link #loadCompartmentalised(File)}.
     * Callers that already have a {@link File} should prefer {@link #load(File)} to avoid
     * the spool.
     *
     * @param in The stream from which to load the world.
     * @throws IOException If an I/O error occurred loading the world.
     * @throws UnloadableWorldException If some other error occurred loading the
     *     world. If metadata was present and could be loaded it will be stored
     *     in the exception.
     */
    @SuppressWarnings("unchecked") // Guaranteed by WorldPainter
    public void load(InputStream in) throws IOException, UnloadableWorldException {
        // PushbackInputStream lets us peek two bytes without losing them.
        final PushbackInputStream pin = new PushbackInputStream(in, 2);
        final byte[] magic = new byte[2];
        final int read = pin.read(magic);
        if (read < 2) {
            try { pin.close(); } catch (IOException ignored) {}
            throw new UnloadableWorldException("Stream is too short to be a WorldPainter world (" + Math.max(0, read) + " bytes)", null);
        }
        pin.unread(magic);

        if (isZipMagic(magic)) {
            // Spool the whole stream to a temp file so we can hand it to ZipFile.
            final File tmp = File.createTempFile("wp-load-", ".world.zip");
            try {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20)) {
                    pin.transferTo(out);
                } finally {
                    pin.close();
                }
                loadCompartmentalised(tmp);
            } finally {
                if (! tmp.delete()) {
                    tmp.deleteOnExit();
                }
            }
            return;
        }
        if (! isGzipMagic(magic)) {
            try { pin.close(); } catch (IOException ignored) {}
            throw new UnloadableWorldException(
                    String.format("Stream does not start with a recognised magic (got 0x%02X 0x%02X — expected GZIP 0x1F 0x8B or ZIP 0x50 0x4B)", magic[0] & 0xFF, magic[1] & 0xFF),
                    null);
        }

        // Legacy GZIP path.
        Map<String, Object> metadata = null;
        world = null;
        try {
            try (WPCustomObjectInputStream wrappedIn = new WPCustomObjectInputStream(new GZIPInputStream(pin), PluginManager.getPluginClassLoader(), AbstractObject.class)) {
                Object object = wrappedIn.readObject();
                if (object instanceof Map) {
                    metadata = (Map<String, Object>) object;
                    object = wrappedIn.readObject();
                }
                if (object instanceof World2) {
                    world = (World2) object;
                } else if (object instanceof World) {
                    world =  migrate(object);
                } else {
                    throw new UnloadableWorldException("Object of unexpected type " + object.getClass() + " encountered", metadata);
                }
            }
        } catch (ZipException | StreamCorruptedException | IllegalArgumentException | ClassNotFoundException | InvalidClassException | EOFException e) {
            throw new UnloadableWorldException(e.getClass().getSimpleName() + " while loading world", e, metadata);
        } catch (IOException e) {
            if ("Not in GZIP format".equals(e.getMessage())) {
                throw new UnloadableWorldException("Not in GZIP format", e, metadata);
            } else {
                throw e;
            }
        }
        if (metadata != null) {
            world.setMetadata(metadata);
        }
    }

    private static boolean isGzipMagic(byte[] m) {
        return (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B;
    }

    private static boolean isZipMagic(byte[] m) {
        return (m[0] & 0xFF) == 0x50 && (m[1] & 0xFF) == 0x4B; // "PK"
    }

    /**
     * Load a world from a {@code .world.zip} file previously written by
     * {@link #saveCompartmentalised(OutputStream)}.
     *
     * <p>Layout (see {@link World2#save(ZipOutputStream)} and {@link Dimension#save(ZipOutputStream)}):
     * <pre>
     *   metadata.json                      — top-level metadata map (JSON)
     *   world-data.bin                     — serialised World2 with dimensionsByAnchor=null
     *   {anchor}/dim-data.bin              — serialised Dimension with tiles=null, world=null
     *   {anchor}/region-data-{x},{y}.bin   — List&lt;Tile&gt; for one 4×4 region
     * </pre>
     * where {@code {anchor}} is the {@link Dimension.Anchor#toString()} form, e.g. {@code "0 DETAIL"}.
     *
     * <p>Uses {@link ZipFile} rather than {@code ZipInputStream} for two reasons: (1) random
     * access lets us read {@code world-data.bin} first regardless of entry order, then walk per-dim
     * entries; (2) avoids buffering the whole archive in memory — only one ZIP entry is open at
     * a time. Caller is responsible for invoking via a {@code File}; {@link #load(InputStream)}
     * will eventually dispatch here for inputs whose magic bytes identify them as ZIPs (see A1
     * in the worldpainter.md plan).
     *
     * @param file The {@code .world.zip} file to load.
     * @throws IOException If an I/O error occurred reading the archive.
     * @throws UnloadableWorldException If the archive is structurally valid but logically wrong
     *     (missing expected entries, wrong object types) or class versions don't match.
     */
    @SuppressWarnings("unchecked")
    public void loadCompartmentalised(File file) throws IOException, UnloadableWorldException {
        final Map<String, Object>[] metadataHolder = new Map[]{null};
        world = null;
        try (ZipFile zip = new ZipFile(file)) {
            // 1. metadata.json (optional — old experimental saves might not have it)
            final ZipEntry metaEntry = zip.getEntry("metadata.json");
            if (metaEntry != null) {
                try (InputStream in = zip.getInputStream(metaEntry)) {
                    metadataHolder[0] = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(in, Map.class);
                }
            }

            // 2. layers.bin (optional — present in A3+ archives; older archives have embedded
            //    Layer instances in each entry, which we read transparently with no binding).
            final org.pepsoft.worldpainter.io.LayerRegistry registry;
            final ZipEntry layersEntry = zip.getEntry("layers.bin");
            if (layersEntry != null) {
                try (ObjectInputStream layersIn = new WPCustomObjectInputStream(
                        zip.getInputStream(layersEntry),
                        PluginManager.getPluginClassLoader(),
                        AbstractObject.class)) {
                    final Object obj = layersIn.readObject();
                    if (! (obj instanceof org.pepsoft.worldpainter.io.LayerRegistry)) {
                        throw new UnloadableWorldException(
                                "layers.bin contains object of unexpected type " + obj.getClass(),
                                metadataHolder[0]);
                    }
                    registry = (org.pepsoft.worldpainter.io.LayerRegistry) obj;
                }
            } else {
                registry = null;
            }

            // 3. Read everything else inside the registry binding (if any). Within the binding,
            //    every Layer ref in the data entries is resolved to its canonical instance from
            //    the registry. Without the binding (pre-A3 archives), Layer instances were
            //    serialised embedded — those still work, just without cross-entry identity.
            final Map<String, Object> finalMetadata = metadataHolder[0];
            if (registry != null) {
                org.pepsoft.worldpainter.io.CompartmentalisedSerialisation.withLoadBinding(
                        registry,
                        () -> {
                            try {
                                readWorldAndRegions(zip, finalMetadata);
                            } catch (UnloadableWorldException e) {
                                // Sneaky-throw out of the IOAction lambda — withLoadBinding only
                                // declares IOException, but the body legitimately needs to throw
                                // UnloadableWorldException too. Wrap-and-unwrap pattern.
                                throw new WrappedUnloadable(e);
                            }
                        });
            } else {
                readWorldAndRegions(zip, finalMetadata);
            }
        } catch (WrappedUnloadable e) {
            throw e.original;
        } catch (ZipException | StreamCorruptedException | IllegalArgumentException | ClassNotFoundException | InvalidClassException | EOFException e) {
            throw new UnloadableWorldException(e.getClass().getSimpleName() + " while loading world", e, metadataHolder[0]);
        }
        if (metadataHolder[0] != null) {
            world.setMetadata(metadataHolder[0]);
        }
    }

    /**
     * The body of {@link #loadCompartmentalised} after the registry has been resolved. Pulled
     * into a method so it can run both directly (no registry / pre-A3 archive) and inside the
     * registry binding (A3+ archives), without duplicating logic.
     */
    @SuppressWarnings("unchecked")
    private void readWorldAndRegions(ZipFile zip, Map<String, Object> metadata)
            throws IOException, UnloadableWorldException, ClassNotFoundException {
        // world-data.bin → World2
        final ZipEntry worldEntry = zip.getEntry("world-data.bin");
        if (worldEntry == null) {
            throw new UnloadableWorldException("Compartmentalised archive missing world-data.bin entry", metadata);
        }
        try (WPCustomObjectInputStream wrappedIn = org.pepsoft.worldpainter.io.CompartmentalisedSerialisation.newObjectInputStream(
                zip.getInputStream(worldEntry),
                PluginManager.getPluginClassLoader(),
                AbstractObject.class)) {
            final Object obj = wrappedIn.readObject();
            if (obj instanceof World2) {
                world = (World2) obj;
            } else {
                throw new UnloadableWorldException(
                        "world-data.bin contains object of unexpected type " + obj.getClass(),
                        metadata);
            }
        }

        // Group remaining entries by dimension prefix.
        final Map<String, List<ZipEntry>> regionEntriesByPrefix = new HashMap<>();
        final Map<String, ZipEntry> dimEntryByPrefix = new HashMap<>();
        final Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            final ZipEntry e = entries.nextElement();
            final String name = e.getName();
            final int slash = name.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            final String prefix = name.substring(0, slash);
            final String rest = name.substring(slash + 1);
            if (rest.equals("dim-data.bin")) {
                dimEntryByPrefix.put(prefix, e);
            } else if (rest.startsWith("region-data-")) {
                regionEntriesByPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(e);
            }
        }

        // Materialise each dimension and merge its tiles back in.
        for (Map.Entry<String, ZipEntry> dimEnt : dimEntryByPrefix.entrySet()) {
            final String prefix = dimEnt.getKey();
            final Dimension dim;
            try (WPCustomObjectInputStream wrappedIn = org.pepsoft.worldpainter.io.CompartmentalisedSerialisation.newObjectInputStream(
                    zip.getInputStream(dimEnt.getValue()),
                    PluginManager.getPluginClassLoader(),
                    AbstractObject.class)) {
                final Object obj = wrappedIn.readObject();
                if (obj instanceof Dimension) {
                    dim = (Dimension) obj;
                } else {
                    throw new UnloadableWorldException(
                            prefix + "/dim-data.bin contains object of unexpected type " + obj.getClass(),
                            metadata);
                }
            }

            // Defensive anchor cross-check.
            final Dimension.Anchor expectedAnchor;
            try {
                expectedAnchor = Dimension.Anchor.fromString(prefix);
            } catch (RuntimeException e) {
                throw new UnloadableWorldException(
                        "Cannot parse dimension anchor from entry path '" + prefix + "'",
                        e, metadata);
            }
            if (! expectedAnchor.equals(dim.getAnchor())) {
                throw new UnloadableWorldException(
                        "Anchor mismatch in '" + prefix + "': dim-data.bin has " + dim.getAnchor()
                                + " but path prefix implies " + expectedAnchor,
                        metadata);
            }

            dim.attachWorldFromLoad(world);
            world.attachDimensionFromLoad(dim);

            final List<ZipEntry> regionEntries = regionEntriesByPrefix.getOrDefault(prefix, List.of());
            for (ZipEntry regionEntry : regionEntries) {
                try (WPCustomObjectInputStream wrappedIn = org.pepsoft.worldpainter.io.CompartmentalisedSerialisation.newObjectInputStream(
                        zip.getInputStream(regionEntry),
                        PluginManager.getPluginClassLoader(),
                        AbstractObject.class)) {
                    final Object obj = wrappedIn.readObject();
                    if (! (obj instanceof List)) {
                        throw new UnloadableWorldException(
                                regionEntry.getName() + " contains object of unexpected type " + obj.getClass(),
                                metadata);
                    }
                    final List<Tile> tileList = (List<Tile>) obj;
                    for (Tile tile : tileList) {
                        dim.attachTileFromLoad(tile);
                    }
                }
            }
        }
    }

    /**
     * Carrier exception used to escape from the {@code withLoadBinding} {@code IOAction} lambda
     * when an UnloadableWorldException is thrown from inside it. {@code IOAction} only declares
     * {@code IOException}, and we don't want to weaken its signature for one call site.
     */
    private static final class WrappedUnloadable extends RuntimeException {
        final UnloadableWorldException original;
        WrappedUnloadable(UnloadableWorldException e) {
            super(e);
            this.original = e;
        }
    }

    @NotNull
    private Map<String, Object> getMetadata() {
        final Map<String, Object> metadata = new HashMap<>();
        metadata.put(World2.METADATA_KEY_NAME, world.getName());
        metadata.put(World2.METADATA_KEY_WP_VERSION, Version.VERSION);
        metadata.put(World2.METADATA_KEY_WP_BUILD, Version.BUILD);
        metadata.put(World2.METADATA_KEY_TIMESTAMP, new Date());
        if (WPPluginManager.getInstance() != null) {
            final List<String[]> pluginArray = new ArrayList<>();
            WPPluginManager.getInstance().getAllPlugins().stream()
                    .filter(plugin -> ! plugin.getClass().getName().startsWith("org.pepsoft.worldpainter"))
                    .forEach(plugin -> pluginArray.add(new String[]{plugin.getName(), plugin.getVersion()}));
            if (! pluginArray.isEmpty()) {
                metadata.put(World2.METADATA_KEY_PLUGINS, pluginArray.toArray(new String[pluginArray.size()][]));
            }
        }
        return metadata;
    }

    private World2 migrate(Object object) {
        if (object instanceof World) {
            World oldWorld = (World) object;
            World2 newWorld = new World2(JAVA_MCREGION, oldWorld.getMinecraftSeed(), oldWorld.getTileFactory());
            newWorld.setCreateGoodiesChest(oldWorld.isCreateGoodiesChest());
            newWorld.setImportedFrom(oldWorld.getImportedFrom());
            newWorld.setName(oldWorld.getName());
            newWorld.setSpawnPoint(oldWorld.getSpawnPoint());
            Dimension dim0 = newWorld.getDimension(NORMAL_DETAIL);
            newWorld.setAskToConvertToAnvil(true);
            newWorld.setUpIs(Direction.WEST);
            newWorld.setAskToRotate(true);
            newWorld.setAllowMerging(false);
            dim0.setEventsInhibited(true);
            try {
                dim0.setWallType(oldWorld.isBedrockWall() ? Dimension.WallType.BEDROCK : null);
                dim0.setBorder((oldWorld.getBorder() != null) ? Dimension.Border.valueOf(oldWorld.getBorder().name()) : null);
                dim0.setRoofType(oldWorld.isDarkLevel() ? Dimension.WallType.BEDROCK : null);
                for (Map.Entry<Layer, ExporterSettings> entry: oldWorld.getAllLayerSettings().entrySet()) {
                    dim0.setLayerSettings(entry.getKey(), entry.getValue());
                }
                dim0.setMinecraftSeed(oldWorld.getMinecraftSeed());
                dim0.setPopulate(oldWorld.isPopulate());
                dim0.setContoursEnabled(false);
                Terrain subsurfaceMaterial = oldWorld.getSubsurfaceMaterial();
                ResourcesExporter.ResourcesExporterSettings resourcesSettings = (ResourcesExporter.ResourcesExporterSettings) dim0.getLayerSettings(Resources.INSTANCE);
                if (subsurfaceMaterial == Terrain.RESOURCES) {
                    dim0.setSubsurfaceMaterial(Terrain.STONE);
                } else {
                    dim0.setSubsurfaceMaterial(subsurfaceMaterial);
                    resourcesSettings.setMinimumLevel(0);
                }

                TileFactory tileFactory = dim0.getTileFactory();
                if ((tileFactory instanceof HeightMapTileFactory)
                        && (((HeightMapTileFactory) tileFactory).getWaterHeight() < 32)
                        && (((HeightMapTileFactory) tileFactory).getBaseHeight() < 32)) {
                    // Low level
                    dim0.setGenerator(new SuperflatGenerator(SuperflatPreset.defaultPreset(JAVA_MCREGION)));
                } else {
                    dim0.setGenerator(new SeededGenerator(DEFAULT, oldWorld.getMinecraftSeed()));
                }

                // Load legacy settings
                resourcesSettings.setChance(GOLD_ORE,         1);
                resourcesSettings.setChance(IRON_ORE,         5);
                resourcesSettings.setChance(COAL,             9);
                resourcesSettings.setChance(LAPIS_LAZULI_ORE, 1);
                resourcesSettings.setChance(DIAMOND_ORE,      1);
                resourcesSettings.setChance(REDSTONE_ORE,     6);
                resourcesSettings.setChance(STATIONARY_WATER, 1);
                resourcesSettings.setChance(STATIONARY_LAVA,  1);
                resourcesSettings.setChance(DIRT,             9);
                resourcesSettings.setChance(GRAVEL,           9);
                resourcesSettings.setChance(EMERALD_ORE,      0);
                resourcesSettings.setMaxLevel(GOLD_ORE,         Terrain.GOLD_LEVEL);
                resourcesSettings.setMaxLevel(IRON_ORE,         Terrain.IRON_LEVEL);
                resourcesSettings.setMaxLevel(COAL,             Terrain.COAL_LEVEL);
                resourcesSettings.setMaxLevel(LAPIS_LAZULI_ORE, Terrain.LAPIS_LAZULI_LEVEL);
                resourcesSettings.setMaxLevel(DIAMOND_ORE,      Terrain.DIAMOND_LEVEL);
                resourcesSettings.setMaxLevel(REDSTONE_ORE,     Terrain.REDSTONE_LEVEL);
                resourcesSettings.setMaxLevel(STATIONARY_WATER, Terrain.WATER_LEVEL);
                resourcesSettings.setMaxLevel(STATIONARY_LAVA,  Terrain.LAVA_LEVEL);
                resourcesSettings.setMaxLevel(DIRT,             Terrain.DIRT_LEVEL);
                resourcesSettings.setMaxLevel(GRAVEL,           Terrain.GRAVEL_LEVEL);
                resourcesSettings.setMaxLevel(EMERALD_ORE,      Terrain.GOLD_LEVEL);

                oldWorld.getTiles().forEach(dim0::addTile);
            } finally {
                dim0.setEventsInhibited(false);
            }

            // Log event
            Configuration config = Configuration.getInstance();
            if (config != null) {
                config.logEvent(new EventVO(Constants.EVENT_KEY_ACTION_MIGRATE_WORLD).addTimestamp());
            }

            // Record origin of world in history
            newWorld.addHistoryEntry(HistoryEntry.WORLD_LEGACY_PRE_0_2);

            return newWorld;
        } else {
            throw new IllegalArgumentException("Don't know how to migrate object of type " + object.getClass());
        }
    }

    private World2 world;
}