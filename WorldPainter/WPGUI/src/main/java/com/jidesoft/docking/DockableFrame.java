package com.jidesoft.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;

/**
 * Adapter for the JIDE {@code DockableFrame}, backed by DockingFrames Common's
 * {@link bibliothek.gui.dock.common.DefaultMultipleCDockable}.
 *
 * <p>This class is a {@link JPanel} so that existing WP code which adds children via
 * {@link #add(Component, Object)} keeps working unchanged. The collected JIDE-style metadata
 * (title, icon, init side/index, etc.) is read back by {@link DefaultDockingManager} when it
 * builds the actual DockingFrames dockable.</p>
 *
 * <p>Implements {@link Scrollable}: when our adapter wraps a non-scrollable frame in a
 * {@code JScrollPane} (because WP didn't mark it {@code .scrollable()}), this Scrollable impl
 * makes the frame track viewport width so we get vertical-only scrolling without horizontal
 * clipping.</p>
 */
public class DockableFrame extends JPanel implements Scrollable {

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
        // A hard floor for layouts so that aggressive drag-resizes can't collapse a panel down
        // to a 1-pixel sliver. DockingFrames respects component minimum sizes during user resize.
        setMinimumSize(new Dimension(80, 60));

        // Scrollbar visibility in nested JScrollPanes is decided at layout time. When this panel
        // first laid out — while hidden behind another tab — its viewport had width 0 because
        // VerticalScrollingJPanel honours getScrollableTracksViewportWidth(). The view's
        // preferred-height was computed against width=0, came out tiny, and JScrollPane
        // concluded "no scrollbar needed". That decision sticks even after the panel becomes
        // visible at real width because Swing reuses cached preferred sizes.
        //
        // We listen for the showing transition (Swing fires SHOWING_CHANGED on every descendant
        // when a tab swap brings this panel into the visible hierarchy) and force a top-down
        // re-layout: clear cached preferred sizes on every JScrollPane's view, then validate
        // synchronously so the scrollbar policy is recomputed against the now-real viewport.
        addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                    refreshScrollPanes(DockableFrame.this);
                    validate();
                    repaint();
                }
            }
        });
    }

    private static void refreshScrollPanes(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JScrollPane) {
                JScrollPane sp = (JScrollPane) child;
                Component view = sp.getViewport() != null ? sp.getViewport().getView() : null;
                if (view instanceof JComponent) {
                    JComponent jview = (JComponent) view;
                    // Drop any cached preferred-size memo so getPreferredSize() is re-computed
                    // against the real viewport width.
                    jview.setPreferredSize(null);
                    jview.invalidate();
                    jview.revalidate();
                }
                sp.invalidate();
                sp.doLayout();
            }
            if (child instanceof Container) {
                refreshScrollPanes((Container) child);
            }
        }
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

    // --- Scrollable impl (used when our adapter wraps us in a JScrollPane) -------------------

    @Override public Dimension getPreferredScrollableViewportSize()        { return getPreferredSize(); }
    @Override public boolean   getScrollableTracksViewportWidth()          { return true; }
    @Override public boolean   getScrollableTracksViewportHeight()         { return false; }
    @Override public int       getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)  { return 16; }
    @Override public int       getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return (orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width) - 16;
    }
}
