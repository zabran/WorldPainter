package com.jidesoft.docking;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * The subset of the JIDE {@code DockingManager} interface that WorldPainter actually uses.
 * Implemented by {@link DefaultDockingManager}, which delegates to DockingFrames Common.
 */
public interface DockingManager {

    /** Constant retained from JIDE; the underlying renderer ignores it. */
    int MIX_OUTLINE_MODE = 1;

    void setOutlineMode(int mode);
    void setGroupAllowedOnSidePane(boolean allowed);
    void setTabbedPaneCustomizer(Consumer<JTabbedPane> customizer);

    JComponent getMainContainer();
    Workspace  getWorkspace();

    void          addFrame(DockableFrame frame);
    void          removeFrame(String key);
    void          showFrame(String key);
    void          activateFrame(String key);
    DockableFrame getFrame(String key);

    /** Move an already-added frame to a side at the given index. */
    void dockFrame(String key, int side, int index);

    void   loadLayoutFrom(InputStream in);
    byte[] getLayoutRawData();
    void   resetToDefault();
}
