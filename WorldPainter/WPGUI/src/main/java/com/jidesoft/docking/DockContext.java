package com.jidesoft.docking;

/**
 * Constants from the JIDE Docking Framework that WorldPainter relies on. The values themselves are
 * arbitrary — only this adapter package and {@link DockableFrame} ever read them.
 */
public final class DockContext {
    private DockContext() {}

    public static final int DOCK_SIDE_NORTH = 0;
    public static final int DOCK_SIDE_SOUTH = 1;
    public static final int DOCK_SIDE_EAST  = 2;
    public static final int DOCK_SIDE_WEST  = 3;

    public static final int STATE_HIDDEN      = 0;
    public static final int STATE_AUTOHIDE    = 1;
    public static final int STATE_FRAMEDOCKED = 2;
    public static final int STATE_FLOATING    = 3;
}
