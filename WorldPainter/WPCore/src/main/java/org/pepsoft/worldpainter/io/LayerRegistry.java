package org.pepsoft.worldpainter.io;

import org.pepsoft.worldpainter.layers.Layer;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The compartmentalised-save analogue of an interned-string table: a per-archive directory that
 * stores each distinct {@link Layer} once and addresses it from elsewhere via integer index. The
 * registry itself is serialised as the top-level {@code layers.bin} ZIP entry; everywhere else in
 * the archive, what would otherwise be a duplicate {@link Layer} reference is written as a tiny
 * {@link LayerRef}{@code (index)}.
 *
 * <p>Two reasons this exists, in order of importance:
 * <ol>
 *   <li><b>Object identity across the boundary.</b> Java's {@code ObjectOutputStream} maintains an
 *       identity-table that lasts only for the duration of one stream. Each ZIP entry in the
 *       compartmentalised format is a fresh stream, so without this registry every region entry
 *       that mentions a {@code CustomLayer} would deserialise into its own fresh {@code Layer}
 *       instance. {@code Layer.equals} is id-based so map lookups still work — but
 *       {@code ==}/identity-map paths and the post-deserialisation {@code init()} machinery
 *       (loads classes for renderer/exporter/icon) silently duplicate.</li>
 *   <li><b>Disk size.</b> Custom layers carry several hundred bytes of state (name, palette,
 *       colour, export index, dimension settings). On a world with N regions touching K custom
 *       layers, the legacy compartmentalised format pays N×K of that. The registry pays K total.
 *       The exact saving depends on the world; budget tens to hundreds of KB on typical maps.</li>
 * </ol>
 *
 * <p><b>Stability invariant (critical for A4):</b> once a Layer is registered at index <i>i</i>,
 * its index is never reassigned. When the incremental save in A4 copies clean region entries
 * verbatim from the previous archive, those entries already encode {@code LayerRef(i)} values;
 * a load against a re-numbered registry would resolve them to the wrong layers. The
 * {@code readResolve} rebuild and the {@link #registerKeepingPreviousIndices} helper preserve
 * existing indices and append new ones at the end.
 */
public final class LayerRegistry implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Indexed by registry id; this is the on-disk part. */
    private final ArrayList<Layer> layers;

    /** Indexed by {@link Layer#getId()}; rebuilt on deserialisation. Not part of the on-disk form. */
    private transient Map<String, Integer> idToIndex;

    public LayerRegistry() {
        this.layers = new ArrayList<>();
        this.idToIndex = new HashMap<>();
    }

    /**
     * Register a Layer for inclusion in {@code layers.bin}, returning the index that should be
     * written into the corresponding {@link LayerRef}. If a Layer with the same {@link Layer#getId()}
     * is already registered, its existing index is returned and no duplicate is stored.
     */
    public synchronized int register(Layer layer) {
        if (layer == null) {
            throw new NullPointerException("layer");
        }
        final String key = layer.getId();
        final Integer existing = idToIndex.get(key);
        if (existing != null) {
            return existing;
        }
        final int index = layers.size();
        layers.add(layer);
        idToIndex.put(key, index);
        return index;
    }

    /**
     * Pre-seed the registry from a list loaded off a prior archive, preserving each Layer's
     * original index. Called by the A4 incremental-save path before re-serialising dirty regions:
     * by registering the prior layers first, any clean entries copied verbatim still resolve to
     * the right indices.
     *
     * <p>The list must be in <em>original index order</em> — slot 0 is registry index 0.
     */
    public synchronized void registerKeepingPreviousIndices(List<Layer> previousLayers) {
        if (! layers.isEmpty()) {
            throw new IllegalStateException(
                    "registerKeepingPreviousIndices may only be called on an empty registry");
        }
        for (Layer layer : previousLayers) {
            if (idToIndex.containsKey(layer.getId())) {
                throw new IllegalStateException(
                        "Duplicate layer id in previous registry: " + layer.getId());
            }
            idToIndex.put(layer.getId(), layers.size());
            layers.add(layer);
        }
    }

    /** Look up a registered Layer by index. Used during load by {@link CompartmentalisedObjectInputStream}. */
    public Layer get(int index) {
        return layers.get(index);
    }

    /** @return an unmodifiable view of the registered layers in index order. */
    public List<Layer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    public int size() {
        return layers.size();
    }

    @Serial
    private Object readResolve() {
        // The map is transient — rebuild it so the loaded registry is immediately usable for
        // further registers (the A4 incremental-save flow appends new layers to an existing
        // registry, so we need the lookup map populated).
        if (idToIndex == null) {
            idToIndex = new HashMap<>(layers.size() * 2);
        }
        for (int i = 0; i < layers.size(); i++) {
            idToIndex.put(layers.get(i).getId(), i);
        }
        return this;
    }
}
