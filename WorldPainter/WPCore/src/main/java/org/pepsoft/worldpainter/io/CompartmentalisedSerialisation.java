package org.pepsoft.worldpainter.io;

import org.pepsoft.util.WPCustomObjectInputStream;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * Thread-local glue between {@code WorldIO.saveCompartmentalised} / {@code loadCompartmentalised}
 * and the per-entry {@code ObjectOutputStream} / {@code ObjectInputStream} creations buried inside
 * {@link org.pepsoft.worldpainter.World2#save} and {@link org.pepsoft.worldpainter.Dimension#save}.
 *
 * <p>WorldIO is the only place that knows we're doing a compartmentalised round-trip; the
 * inner save methods just need to <em>open a stream</em>. Rather than change their signatures to
 * take a stream factory (six callers, all in production code paths we'd rather not destabilise),
 * we bind the active {@link LayerRegistry} into a thread-local here and have the inner methods
 * call {@link #newObjectOutputStream}, which checks the binding and chooses either the standard
 * {@link ObjectOutputStream} or the substituting {@link CompartmentalisedObjectOutputStream}.
 *
 * <p>Same idea on the load side: {@link #newObjectInputStream} picks {@link WPCustomObjectInputStream}
 * vs. {@link CompartmentalisedObjectInputStream} based on whether a registry is bound.
 *
 * <p>Threading: the binding lives in a {@link ThreadLocal}, so concurrent operations on
 * unrelated worlds don't interfere. {@link #withSaveBinding}/{@link #withLoadBinding} guarantee
 * the binding is cleared on exit even if the caller throws.
 */
public final class CompartmentalisedSerialisation {
    private CompartmentalisedSerialisation() {}

    private static final ThreadLocal<LayerRegistry> SAVE_REGISTRY = new ThreadLocal<>();
    private static final ThreadLocal<LayerRegistry> LOAD_REGISTRY = new ThreadLocal<>();

    /** Bind a registry for the duration of {@code action}'s execution, then clear it. */
    public static <T extends Throwable> void withSaveBinding(LayerRegistry registry, IOAction<T> action) throws IOException, T {
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        if (SAVE_REGISTRY.get() != null) {
            throw new IllegalStateException("Nested compartmentalised save is not supported");
        }
        SAVE_REGISTRY.set(registry);
        try {
            action.run();
        } finally {
            SAVE_REGISTRY.remove();
        }
    }

    /** Bind a registry for the duration of {@code action}'s execution, then clear it. */
    public static <T extends Throwable> void withLoadBinding(LayerRegistry registry, IOAction<T> action) throws IOException, T {
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        if (LOAD_REGISTRY.get() != null) {
            throw new IllegalStateException("Nested compartmentalised load is not supported");
        }
        LOAD_REGISTRY.set(registry);
        try {
            action.run();
        } finally {
            LOAD_REGISTRY.remove();
        }
    }

    /**
     * Open an {@link ObjectOutputStream} for serialising a piece of a compartmentalised save.
     * Returns a substituting stream if a registry is bound on this thread; otherwise a plain one.
     * Callers in World2.save / Dimension.save use this so they automatically participate in the
     * layer-deduplication scheme when invoked through {@code saveCompartmentalised}.
     */
    public static ObjectOutputStream newObjectOutputStream(OutputStream out) throws IOException {
        final LayerRegistry registry = SAVE_REGISTRY.get();
        if (registry != null) {
            return new CompartmentalisedObjectOutputStream(out, registry);
        }
        return new ObjectOutputStream(out);
    }

    /**
     * Open a {@link WPCustomObjectInputStream} (or substituting subclass) for deserialising a
     * piece of a compartmentalised load. Symmetric to {@link #newObjectOutputStream}.
     */
    public static WPCustomObjectInputStream newObjectInputStream(java.io.InputStream in, ClassLoader classLoader, Class<?>... patchClasses) throws IOException {
        final LayerRegistry registry = LOAD_REGISTRY.get();
        if (registry != null) {
            return new CompartmentalisedObjectInputStream(in, classLoader, registry, patchClasses);
        }
        return new WPCustomObjectInputStream(in, classLoader, patchClasses);
    }

    /** Functional interface that allows the lambda body to throw {@link IOException}. */
    @FunctionalInterface
    public interface IOAction<T extends Throwable> {
        void run() throws IOException, T;
    }
}
