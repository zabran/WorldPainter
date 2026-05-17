package org.pepsoft.worldpainter.paging;

import org.pepsoft.worldpainter.io.LayerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Resource handle for a world loaded with {@code Configuration.regionPagingEnabled = true}.
 * Owns the open {@link ZipFile} and the {@link LayerRegistry} shared by every dimension's
 * {@link LazyTilePager}. Lives on {@link org.pepsoft.worldpainter.World2#lazyArchive} until
 * the world is discarded, at which point {@code World2.closeLazyResources} releases it.
 *
 * <p>Each {@link LazyTilePager} holds a borrowed reference to the {@link ZipFile} — they do
 * not close it themselves. The single owner ({@code LazyArchive}) coordinates close.
 */
public class LazyArchive implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LazyArchive.class);

    private final File sourceFile;
    private final ZipFile zipFile;
    private final LayerRegistry layerRegistry;
    private final List<LazyTilePager> pagers = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean closed;

    public LazyArchive(File sourceFile, ZipFile zipFile, LayerRegistry layerRegistry) {
        this.sourceFile = sourceFile;
        this.zipFile = zipFile;
        this.layerRegistry = layerRegistry;
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public ZipFile getZipFile() {
        if (closed) {
            throw new IllegalStateException("LazyArchive already closed: " + sourceFile);
        }
        return zipFile;
    }

    public LayerRegistry getLayerRegistry() {
        return layerRegistry;
    }

    public void registerPager(LazyTilePager pager) {
        pagers.add(pager);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // Close every pager first (no-op today; future LRU caches may release per-region buffers).
        for (LazyTilePager pager : pagers) {
            try {
                pager.close();
            } catch (RuntimeException e) {
                logger.warn("Pager close failed for {} (continuing)", sourceFile, e);
            }
        }
        zipFile.close();
        logger.info("LazyArchive closed: {}", sourceFile);
    }
}
