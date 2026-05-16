package org.pepsoft.worldpainter.io;

import java.io.Serial;
import java.io.Serializable;

/**
 * Opaque placeholder substituted in for {@link org.pepsoft.worldpainter.layers.Layer} instances
 * during a compartmentalised save. The wrapped {@code index} refers to a slot in the
 * {@link LayerRegistry} that accompanies the save (serialised as the top-level {@code layers.bin}
 * ZIP entry).
 *
 * <p>Used purely as a serialisation-time go-between: callers never see a {@code LayerRef} in a
 * fully-loaded world because {@link CompartmentalisedObjectInputStream#resolveObject} swaps each
 * one back to its registered {@code Layer} during load.
 *
 * <p>The class is deliberately tiny and stable — its on-disk form is part of the
 * compartmentalised file format and must not change incompatibly. Adding new fields here is a
 * format-breaking change.
 */
public final class LayerRef implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int index;

    public LayerRef(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return "LayerRef#" + index;
    }
}
