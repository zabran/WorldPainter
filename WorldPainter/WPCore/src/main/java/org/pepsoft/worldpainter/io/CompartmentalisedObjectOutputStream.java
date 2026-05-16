package org.pepsoft.worldpainter.io;

import org.pepsoft.worldpainter.layers.Layer;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * {@link ObjectOutputStream} subclass that intercepts every {@link Layer} instance flowing
 * through the stream and replaces it with a {@link LayerRef} pointing at a shared
 * {@link LayerRegistry}. Used as the writer for all the per-dimension and per-region entries in
 * a compartmentalised {@code .world.zip} save — the registry itself is written separately, using
 * a plain {@link ObjectOutputStream} (so the layers it contains aren't replaced into refs to
 * themselves and lost).
 *
 * <p>The replacement is keyed on Layer object identity but deduplicated by {@link Layer#getId()}
 * via {@link LayerRegistry#register} — see the class doc there for why.
 */
public final class CompartmentalisedObjectOutputStream extends ObjectOutputStream {
    private final LayerRegistry registry;

    public CompartmentalisedObjectOutputStream(OutputStream out, LayerRegistry registry) throws IOException {
        super(out);
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        this.registry = registry;
        // Required permission for the JVM to consult replaceObject(). Default policy
        // ("enableSubstitution") is granted out of the box absent a SecurityManager, which is
        // WP's default deployment.
        enableReplaceObject(true);
    }

    @Override
    protected Object replaceObject(Object obj) {
        if (obj instanceof Layer) {
            return new LayerRef(registry.register((Layer) obj));
        }
        return obj;
    }
}
