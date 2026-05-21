package com.jidesoft.docking;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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

    /**
     * Task 8: attach a "Change icon..." action to the named dockable. The action appears in
     * the panel header's native right-click context menu (alongside Minimize / Externalize)
     * and, depending on theme settings, may also appear as a title-bar button. When triggered,
     * it invokes the supplied {@code onChooseIcon} Runnable on the EDT.
     */
    void attachIconChooserAction(String panelId, Runnable onChooseIcon);

    /** Task 8: update a registered dockable's title icon in place. */
    void setFrameIcon(String panelId, Icon icon);

    /**
     * Task 8: attach a "Change icon..." action to <strong>every</strong> registered panel,
     * including all future panels added after this call. The launcher receives {@code (panelId,
     * panelTitle)} and should open the icon-picker dialog (or whatever the application wants).
     *
     * <p>Effectively wires the right-click "Change icon..." menu item across the whole UI in
     * one call. Future panels (e.g. custom layer palettes registered after world load) get the
     * action auto-attached inside {@link #addFrame}.
     */
    void attachIconChooserActionToAll(BiConsumer<String, String> pickerLauncher);

    /**
     * Task 8: apply stored icon overrides to <strong>every</strong> registered panel, AND
     * remember the resolver so future panels (added post-deploy via {@link #addFrame}, e.g.
     * custom layer palettes registered after a world is loaded) also get their override
     * applied at registration time. The resolver receives {@code (panelId, panelTitle)} and
     * must return either the override Icon or {@code null} to leave the panel's existing
     * icon untouched.
     */
    void applyIconOverridesToAll(BiFunction<String, String, Icon> iconResolver);
}
