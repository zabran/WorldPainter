package com.jidesoft.utils;

/**
 * No-op stand-in for JIDE's commercial license manager. With the docking framework swapped out
 * for DockingFrames, no JIDE license is required, but {@code Main.java} still defensively calls
 * {@code Lm.verifyLicense(...)} when a {@code jide_licence.properties} file is present on the
 * classpath. Keeping the call site unchanged is cheaper than special-casing it.
 */
public final class Lm {
    private Lm() {}

    public static void verifyLicense(String companyName, String projectName, String licenceKey) {
        // intentionally empty
    }
}
