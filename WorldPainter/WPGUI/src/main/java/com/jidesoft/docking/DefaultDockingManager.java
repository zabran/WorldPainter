package com.jidesoft.docking;

import bibliothek.gui.dock.common.CContentArea;
import bibliothek.gui.dock.common.CControl;
import bibliothek.gui.dock.common.CGrid;
import bibliothek.gui.dock.common.CLocation;
import bibliothek.gui.dock.common.DefaultMultipleCDockable;
import bibliothek.gui.dock.common.DefaultSingleCDockable;
import bibliothek.gui.dock.common.MultipleCDockable;
import bibliothek.gui.dock.common.MultipleCDockableFactory;
import bibliothek.gui.dock.common.MultipleCDockableLayout;
import bibliothek.util.xml.XElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bridges the JIDE-shaped DockingManager API onto DockingFrames Common.
 *
 * <p>JIDE's API is imperative: callers add frames one by one with {@code (initSide, initIndex)}
 * hints, then either restore a saved layout or {@code resetToDefault()}. DockingFrames is
 * declarative: build a {@link CGrid}, then {@code deploy(grid)}. The bridge accumulates frames
 * until the first {@code resetToDefault()} or {@code loadLayoutFrom()} call, then deploys.
 * Frames added after that point are inserted live with {@link CControl#addDockable}.</p>
 */
public class DefaultDockingManager implements DockingManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDockingManager.class);
    private static final String FACTORY_ID  = "wp.panel";
    private static final String WORKSPACE_ID = "wp.workspace";

    private final CControl   control;
    private final Workspace  workspace;
    private final SimpleFactory factory = new SimpleFactory();

    private final Map<String, DockableFrame>             frames    = new LinkedHashMap<>();
    private final Map<String, DefaultMultipleCDockable>  dockables = new HashMap<>();
    private boolean layoutDeployed = false;

    public DefaultDockingManager(Frame owner, Container hostContainer) {
        if (!(owner instanceof JFrame)) {
            throw new IllegalArgumentException("DockingFrames CControl requires a JFrame; got " + owner.getClass());
        }
        this.control = new CControl((JFrame) owner);
        this.control.addMultipleDockableFactory(FACTORY_ID, factory);
        this.workspace = new Workspace();

        // Mount DockingFrames' content area inside the host container the caller passed in.
        CContentArea contentArea = control.getContentArea();
        hostContainer.add(contentArea, BorderLayout.CENTER);
    }

    @Override public JComponent getMainContainer() { return control.getContentArea(); }
    @Override public Workspace  getWorkspace()      { return workspace; }

    @Override public void setOutlineMode(int mode)                              { /* DockingFrames manages its own */ }
    @Override public void setGroupAllowedOnSidePane(boolean allowed)            { /* no analogue needed */ }
    @Override public void setTabbedPaneCustomizer(Consumer<JTabbedPane> c)      { /* no-op for now */ }

    @Override
    public void addFrame(DockableFrame frame) {
        frames.put(frame.getKey(), frame);
        if (layoutDeployed) {
            installFrameLive(frame);
        }
    }

    @Override
    public void removeFrame(String key) {
        DefaultMultipleCDockable d = dockables.remove(key);
        if (d != null) {
            control.removeDockable(d);
        }
        frames.remove(key);
    }

    @Override
    public void showFrame(String key) {
        DefaultMultipleCDockable d = dockables.get(key);
        if (d != null) {
            d.setVisible(true);
        }
    }

    @Override
    public void activateFrame(String key) {
        DefaultMultipleCDockable d = dockables.get(key);
        if (d != null) {
            d.setVisible(true);
            d.toFront();
        }
    }

    @Override
    public DockableFrame getFrame(String key) {
        return frames.get(key);
    }

    @Override
    public void dockFrame(String key, int side, int index) {
        DockableFrame frame = frames.get(key);
        if (frame == null) {
            return;
        }
        frame.setInitSide(side);
        frame.setInitIndex(index);
        DefaultMultipleCDockable d = dockables.get(key);
        if (d != null) {
            CLocation base = (side == DockContext.DOCK_SIDE_EAST)
                    ? CLocation.base().normalEast(0.25)
                    : CLocation.base().normalWest(0.25);
            d.setLocation(base);
            d.setVisible(true);
        }
    }

    @Override
    public void resetToDefault() {
        if (!layoutDeployed) {
            deployInitialLayout();
        } else {
            // Subsequent reset: tear down and redeploy. Simplest: rebuild from frames map.
            for (DefaultMultipleCDockable d : new ArrayList<>(dockables.values())) {
                control.removeDockable(d);
            }
            dockables.clear();
            layoutDeployed = false;
            deployInitialLayout();
        }
    }

    @Override
    public void loadLayoutFrom(InputStream in) {
        if (!layoutDeployed) {
            deployInitialLayout();
        }
        try (DataInputStream dis = new DataInputStream(in)) {
            control.read(dis);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not restore docking layout (likely from a previous JIDE-based version); using defaults.", e);
        }
    }

    @Override
    public byte[] getLayoutRawData() {
        if (!layoutDeployed) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                control.write(dos);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            logger.warn("Failed to serialise docking layout", e);
            return new byte[0];
        }
    }

    // --- Layout build ---------------------------------------------------------------------------

    private void deployInitialLayout() {
        // Bucket frames by side, sort by index. Same-index frames stack into the same dock cell.
        List<DockableFrame> west = new ArrayList<>();
        List<DockableFrame> east = new ArrayList<>();
        for (DockableFrame f : frames.values()) {
            (f.getInitSide() == DockContext.DOCK_SIDE_EAST ? east : west).add(f);
        }
        Comparator<DockableFrame> byIndex = Comparator.comparingInt(DockableFrame::getInitIndex);
        west.sort(byIndex);
        east.sort(byIndex);

        CGrid grid = new CGrid(control);

        // Coordinate scheme: the centre is wide; each side gets one column.
        //   x = 0   : west column
        //   x = 1-4 : centre (workspace)
        //   x = 5   : east column
        // Within a column we give each unique index its own row; multiple frames sharing the same
        // index will stack as tabs in the same cell.
        layoutColumn(grid, west, 0);
        DefaultSingleCDockable workspaceDockable = new DefaultSingleCDockable(WORKSPACE_ID, "Map", workspace);
        workspaceDockable.setCloseable(false);
        workspaceDockable.setMaximizable(true);
        int rows = Math.max(Math.max(distinctIndices(west), distinctIndices(east)), 1);
        grid.add(1, 0, 4, rows, workspaceDockable);
        layoutColumn(grid, east, 5);

        control.getContentArea().deploy(grid);
        layoutDeployed = true;
    }

    private void layoutColumn(CGrid grid, List<DockableFrame> column, int x) {
        int row = 0;
        Integer prevIndex = null;
        List<DefaultMultipleCDockable> currentCell = new ArrayList<>();
        for (DockableFrame f : column) {
            DefaultMultipleCDockable d = wrap(f);
            dockables.put(f.getKey(), d);
            if (prevIndex != null && f.getInitIndex() != prevIndex) {
                grid.add(x, row++, 1, 1, currentCell.toArray(new DefaultMultipleCDockable[0]));
                currentCell.clear();
            }
            currentCell.add(d);
            prevIndex = f.getInitIndex();
        }
        if (!currentCell.isEmpty()) {
            grid.add(x, row, 1, 1, currentCell.toArray(new DefaultMultipleCDockable[0]));
        }
    }

    private static int distinctIndices(List<DockableFrame> frames) {
        return (int) frames.stream().mapToInt(DockableFrame::getInitIndex).distinct().count();
    }

    private DefaultMultipleCDockable wrap(DockableFrame f) {
        DefaultMultipleCDockable d = new DefaultMultipleCDockable(factory, f.getTitle(), f);
        if (f.getFrameIcon() != null) {
            d.setTitleIcon(f.getFrameIcon());
        }
        d.setMaximizable(f.isMaximizable());
        // JIDE distinguishes "hidable" (can be hidden by the user) from "closeable" (permanent
        // close). DockingFrames only has "closeable", which means a close-X is shown. WP wants the
        // hide-via-autohide-button behaviour, not permanent close, so leave closeable=false.
        d.setCloseable(false);
        return d;
    }

    private void installFrameLive(DockableFrame f) {
        DefaultMultipleCDockable d = wrap(f);
        control.addDockable(d);
        CLocation base = (f.getInitSide() == DockContext.DOCK_SIDE_EAST)
                ? CLocation.base().normalEast(0.25)
                : CLocation.base().normalWest(0.25);
        d.setLocation(base);
        d.setVisible(true);
        dockables.put(f.getKey(), d);
    }

    // --- Factory glue ---------------------------------------------------------------------------

    /**
     * Layouts only restore positions, not contents — WorldPainter re-creates dockables from
     * scratch on every startup, so we don't need a real factory. Returning null from
     * {@link #read(MultipleCDockableLayout)} tells DockingFrames to skip restoring the dockable.
     */
    private static class SimpleFactory implements MultipleCDockableFactory<MultipleCDockable, MultipleCDockableLayout> {
        @Override public MultipleCDockableLayout create() { return new EmptyLayout(); }
        @Override public MultipleCDockable read(MultipleCDockableLayout layout) { return null; }
        @Override public MultipleCDockableLayout write(MultipleCDockable dockable) { return new EmptyLayout(); }
        @Override public boolean match(MultipleCDockable d, MultipleCDockableLayout l) { return true; }
    }

    private static class EmptyLayout implements MultipleCDockableLayout {
        @Override public void writeStream(DataOutputStream out) {}
        @Override public void readStream(DataInputStream in)   {}
        @Override public void writeXML(XElement e)             {}
        @Override public void readXML(XElement e)              {}
    }
}
