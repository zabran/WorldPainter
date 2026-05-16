package org.pepsoft.worldpainter.io;

import org.pepsoft.util.WPCustomObjectInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link WPCustomObjectInputStream} subclass that mirrors
 * {@link CompartmentalisedObjectOutputStream}: every {@link LayerRef} read from the stream is
 * resolved to its canonical {@code Layer} via a shared {@link LayerRegistry}, so callers receive
 * the already-deserialised, identity-deduplicated instance rather than a fresh decode of the same
 * data.
 *
 * <p>The registry must already be populated before this stream is used to read anything that
 * references it — i.e. {@code layers.bin} must be loaded first.
 */
public final class CompartmentalisedObjectInputStream extends WPCustomObjectInputStream {
    private final LayerRegistry registry;

    public CompartmentalisedObjectInputStream(InputStream in, ClassLoader classLoader, LayerRegistry registry, Class<?>... patchClasses) throws IOException {
        super(in, classLoader, patchClasses);
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        this.registry = registry;
        enableResolveObject(true);
    }

    @Override
    protected Object resolveObject(Object obj) throws IOException {
        if (obj instanceof LayerRef) {
            final int index = ((LayerRef) obj).getIndex();
            try {
                return registry.get(index);
            } catch (IndexOutOfBoundsException e) {
                throw new IOException("LayerRef index " + index + " is out of range for the loaded registry of " + registry.size() + " entries", e);
            }
        }
        return obj;
    }
}
