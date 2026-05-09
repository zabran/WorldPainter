package com.jidesoft.docking;

/**
 * Marker interface JIDE used to indicate that a window owns a {@link DockingManager}.
 * WorldPainter's {@code App} implements this so that other components can reach the docking
 * manager via a uniform API.
 */
public interface DockableHolder {
    DockingManager getDockingManager();
}
