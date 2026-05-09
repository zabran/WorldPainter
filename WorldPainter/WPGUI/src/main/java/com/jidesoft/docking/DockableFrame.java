package com.jidesoft.docking;

import javax.swing.*;
import java.awt.*;

/**
 * Adapter for the JIDE {@code DockableFrame}, backed by DockingFrames Common's
 * {@link bibliothek.gui.dock.common.DefaultMultipleCDockable}.
 *
 * <p>This class is a {@link JPanel} so that existing WP code which adds children via
 * {@link #add(Component, Object)} keeps working unchanged. The collected JIDE-style metadata
 * (title, icon, init side/index, etc.) is read back by {@link DefaultDockingManager} when it
 * builds the actual DockingFrames dockable.</p>
 */
public class DockableFrame extends JPanel {

    public static final int BUTTON_FLOATING      = 1 << 0;
    public static final int BUTTON_AUTOHIDE      = 1 << 1;
    public static final int BUTTON_HIDE_AUTOHIDE = 1 << 2;

    private String key;
    private String title = "", sideTitle = "", tabTitle = "";
    private Icon frameIcon;
    private int autohideWidth, dockedWidth, dockedHeight;
    private Rectangle undockedBounds;
    private boolean hidable = true;
    private boolean showContextMenu = true;
    private boolean autohideWhenActive;
    private boolean maximizable = true;
    private int availableButtons;
    private int initMode  = DockContext.STATE_FRAMEDOCKED;
    private int initSide  = DockContext.DOCK_SIDE_WEST;
    private int initIndex = 0;

    public DockableFrame(String key) {
        super(new BorderLayout());
        this.key = key;
    }

    public String getKey()           { return key; }
    public void   setKey(String k)   { this.key = k; }

    public String getTitle()                                { return title; }
    public void   setTitle(String t)                        { this.title = t != null ? t : ""; }
    public String getSideTitle()                            { return sideTitle; }
    public void   setSideTitle(String t)                    { this.sideTitle = t != null ? t : ""; }
    public String getTabTitle()                             { return tabTitle; }
    public void   setTabTitle(String t)                     { this.tabTitle = t != null ? t : ""; }
    public Icon   getFrameIcon()                            { return frameIcon; }
    public void   setFrameIcon(Icon i)                      { this.frameIcon = i; }
    public int    getAutohideWidth()                        { return autohideWidth; }
    public void   setAutohideWidth(int w)                   { this.autohideWidth = w; }
    public int    getDockedWidth()                          { return dockedWidth; }
    public void   setDockedWidth(int w)                     { this.dockedWidth = w; }
    public int    getDockedHeight()                         { return dockedHeight; }
    public void   setDockedHeight(int h)                    { this.dockedHeight = h; }
    public Rectangle getUndockedBounds()                    { return undockedBounds; }
    public void   setUndockedBounds(Rectangle b)            { this.undockedBounds = b; }
    public boolean isHidable()                              { return hidable; }
    public void   setHidable(boolean h)                     { this.hidable = h; }
    public boolean isShowContextMenu()                      { return showContextMenu; }
    public void   setShowContextMenu(boolean s)             { this.showContextMenu = s; }
    public boolean isAutohideWhenActive()                   { return autohideWhenActive; }
    public void   setAutohideWhenActive(boolean a)          { this.autohideWhenActive = a; }
    public boolean isMaximizable()                          { return maximizable; }
    public void   setMaximizable(boolean m)                 { this.maximizable = m; }
    public int    getAvailableButtons()                     { return availableButtons; }
    public void   setAvailableButtons(int b)                { this.availableButtons = b; }
    public int    getInitMode()                             { return initMode; }
    public void   setInitMode(int m)                        { this.initMode = m; }
    public int    getInitSide()                             { return initSide; }
    public void   setInitSide(int s)                        { this.initSide = s; }
    public int    getInitIndex()                            { return initIndex; }
    public void   setInitIndex(int i)                       { this.initIndex = i; }
}
