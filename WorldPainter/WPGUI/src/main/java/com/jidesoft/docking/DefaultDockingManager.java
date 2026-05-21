package com.jidesoft.docking;

import bibliothek.extension.gui.dock.theme.EclipseTheme;
import bibliothek.gui.dock.StackDockStation;
import glass.eclipse.theme.CGlassEclipseColorSchemeExtension;
import glass.eclipse.theme.CGlassEclipseTabPainter;
import bibliothek.gui.dock.common.CContentArea;
import bibliothek.gui.dock.common.CControl;
import bibliothek.gui.dock.common.CGrid;
import bibliothek.gui.dock.common.CLocation;
import bibliothek.gui.dock.common.DefaultSingleCDockable;
import bibliothek.gui.dock.common.event.CDockableStateListener;
import bibliothek.gui.dock.common.intern.CDockable;
import bibliothek.gui.dock.common.mode.ExtendedMode;
import bibliothek.gui.dock.common.theme.ThemeMap;
import bibliothek.gui.dock.station.stack.tab.DefaultTabContentFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
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
 *
 * <p>WP's panels are unique-by-id (one Tools, one Layers, etc.) so we use
 * {@link DefaultSingleCDockable} — the canonical pattern shown in DockingFrames' own
 * {@code EclipseLikeCloseButtonExample}. {@code MultipleCDockable} would be appropriate only if
 * we needed many instances of the same dockable type (e.g. multiple text editor tabs).</p>
 */
public class DefaultDockingManager implements DockingManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDockingManager.class);
    private static final String WORKSPACE_ID = "wp.workspace";

    /** U4: default autohide popup size, shared across all panels on the same flap station. */
    private static final int DEFAULT_FLAP_WIDTH_PX  = 280;
    private static final int DEFAULT_FLAP_HEIGHT_PX = 200;

    private final CControl   control;
    private final Workspace  workspace;

    private final Map<String, DockableFrame>           frames    = new LinkedHashMap<>();
    private final Map<String, DefaultSingleCDockable>  dockables = new HashMap<>();
    private boolean layoutDeployed = false;

    public DefaultDockingManager(Frame owner, Container hostContainer) {
        if (!(owner instanceof JFrame)) {
            throw new IllegalArgumentException("DockingFrames CControl requires a JFrame; got " + owner.getClass());
        }
        this.control = new CControl((JFrame) owner);
        configureTheme(this.control);
        this.workspace = new Workspace();

        // Mount DockingFrames' content area inside the host container the caller passed in.
        CContentArea contentArea = control.getContentArea();
        hostContainer.add(contentArea, BorderLayout.CENTER);
    }

    /**
     * Theme + tab-rendering knobs that, together, give us a JIDE-like polished look while keeping
     * every grouped tab visible at once even in WP's narrow side columns.
     */
    private static void configureTheme(CControl control) {
        // Eclipse theme is the closest match to JIDE's polished look.
        control.setTheme(ThemeMap.KEY_ECLIPSE_THEME);
        // By default Eclipse theme suppresses icons on inactive tabs (they appear blank). We need
        // them painted at all times so users can see and click any tab in a group.
        control.putProperty(EclipseTheme.PAINT_ICONS_WHEN_DESELECTED, Boolean.TRUE);
        // Glass-extension tab painter: glossy gradient tabs with hover/focus effects. Not in the
        // stock DockingFrames jars — supplied by docking-frames-ext-glass which we build locally
        // (the ext-glass module is not published to Maven Central).
        control.putProperty(EclipseTheme.TAB_PAINTER, CGlassEclipseTabPainter.FACTORY);
        // Glass theme's default focused/selected gradients have a near-black middle stop, which
        // hides black PNG icons. Override every focused- and selected-tab slot with a light
        // cool-blue palette via a subclass — applying overrides inside updateUI() makes them
        // stick across the theme's periodic colour refresh (calling setColor() on a vanilla
        // instance is wiped by the next updateUI cycle).
        control.putProperty(EclipseTheme.ECLIPSE_COLOR_SCHEME, new WPGlassColorScheme());
        // Combined with PAINT_ICONS_WHEN_DESELECTED, this turns Eclipse's icon+text tab strip into
        // an icon-only strip for grouped tabs (single-dockable cells are unaffected — the filter
        // only applies to actual stacked tabs). With 16-px icons four siblings fit comfortably in
        // WP's side column without the "» N" overflow button.
        control.putProperty(
                StackDockStation.TAB_CONTENT_FILTER,
                new DefaultTabContentFilter(DefaultTabContentFilter.Behavior.ICON_ONLY));

        // B3: WP owns its keymap; DockingFrames Common's panel-management keybindings shadow
        // WP's existing application shortcuts. Defaults: Ctrl+E = goto externalized, Ctrl+N =
        // goto normalized, Ctrl+M = goto minimized/maximized, Ctrl+F4 = close. WP needs Ctrl+E
        // for Export and Ctrl+N for New world. The original JIDE Docking framework didn't bind
        // any of these — release WP users expect their existing shortcuts. Unbinding globally
        // here; drag-drop, title-bar buttons, and right-click menus remain functional.
        //
        // Use an unreachable KeyStroke (F24 with all modifiers) rather than null, since some
        // DockingFrames versions treat null as "restore default" rather than "unbind". F24 is
        // a key only a few specialised programmer keyboards have, and the modifier combination
        // is one no user would press by accident.
        //
        // Reported by Arag 2026-05-18: Ctrl+E externalized the map area instead of opening
        // Export, leaving him with no way to get the map back into the workspace.
        final javax.swing.KeyStroke unbound = javax.swing.KeyStroke.getKeyStroke("ctrl alt shift F24");
        control.putProperty(CControl.KEY_GOTO_EXTERNALIZED, unbound);
        control.putProperty(CControl.KEY_GOTO_NORMALIZED, unbound);
        control.putProperty(CControl.KEY_GOTO_MINIMIZED, unbound);
        control.putProperty(CControl.KEY_GOTO_MAXIMIZED, unbound);
        control.putProperty(CControl.KEY_CLOSE, unbound);
    }

    @Override public JComponent getMainContainer() { return control.getContentArea(); }
    @Override public Workspace  getWorkspace()      { return workspace; }

    @Override public void setOutlineMode(int mode)                         { /* DockingFrames manages its own */ }
    @Override public void setGroupAllowedOnSidePane(boolean allowed)       { /* no analogue needed */ }
    @Override public void setTabbedPaneCustomizer(Consumer<JTabbedPane> c) { /* no-op for now */ }

    @Override
    public void addFrame(DockableFrame frame) {
        frames.put(frame.getKey(), frame);
        if (layoutDeployed) {
            installFrameLive(frame);
            // Task 8: if a global icon-chooser launcher is registered, auto-attach the
            // "Change icon..." action to this newly-added dockable too. This is the path
            // taken by post-deploy addFrame calls — most notably custom layer palettes
            // registered after a world is loaded (CustomLayerController.registerCustomLayer).
            // Pre-deploy addFrames are covered by the sweep inside attachIconChooserActionToAll
            // which runs after the deploy finishes.
            if (globalIconChooserLauncher != null) {
                final String panelId = frame.getKey();
                final String panelTitle = frame.getTitle();
                attachIconChooserAction(panelId, () -> globalIconChooserLauncher.accept(panelId, panelTitle));
            }
            // Task 8: apply any stored icon override to this newly-added dockable too. Same
            // mechanism applyIconOverridesToAll uses for the bulk pass at end of setup; this
            // is the per-frame variant for post-deploy registrations (custom layer palettes
            // added after world load).
            if (globalIconResolver != null) {
                final String panelId = frame.getKey();
                final String panelTitle = frame.getTitle();
                final javax.swing.Icon override = globalIconResolver.apply(panelId, panelTitle);
                if (override != null) {
                    setFrameIcon(panelId, override);
                }
            }
        }
    }

    @Override
    public void removeFrame(String key) {
        DefaultSingleCDockable d = dockables.remove(key);
        if (d != null) {
            control.removeDockable(d);
        }
        frames.remove(key);
    }

    @Override
    public void showFrame(String key) {
        DefaultSingleCDockable d = dockables.get(key);
        if (d != null) {
            d.setVisible(true);
        }
    }

    @Override
    public void activateFrame(String key) {
        DefaultSingleCDockable d = dockables.get(key);
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
        DefaultSingleCDockable d = dockables.get(key);
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
            tearDownDeployedLayout();
            deployInitialLayout();
        }
    }

    /**
     * Remove every dockable we added during {@link #deployInitialLayout} from the {@link CControl},
     * clear our internal {@code dockables} map, and reset {@code layoutDeployed} to {@code false}.
     * The next call to {@code deployInitialLayout} will rebuild from scratch using the current
     * (possibly imported-from-JIDE) {@code initSide}/{@code initIndex} values on each frame.
     *
     * <p>Crucial detail: the workspace dockable ({@code wp.workspace}) is added by deployInitialLayout
     * via {@code grid.add(workspaceDockable)} but isn't in our per-frame {@code dockables} map.
     * We have to look it up by id and remove it explicitly, otherwise the next deploy throws
     * {@code IllegalArgumentException: unique id 'wp.workspace' already in use}.
     */
    private void tearDownDeployedLayout() {
        for (DefaultSingleCDockable d : new ArrayList<>(dockables.values())) {
            control.removeDockable(d);
        }
        dockables.clear();
        final bibliothek.gui.dock.common.SingleCDockable workspace = control.getSingleDockable(WORKSPACE_ID);
        if (workspace instanceof DefaultSingleCDockable) {
            control.removeDockable((DefaultSingleCDockable) workspace);
        }
        layoutDeployed = false;
    }

    @Override
    public void loadLayoutFrom(InputStream in) {
        // Buffer the bytes once so we can try multiple decode paths against the same data.
        final byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            logger.warn("Could not read docking layout bytes; using defaults.", e);
            if (!layoutDeployed) deployInitialLayout();
            return;
        }

        // ----- JIDE-import fast path -----------------------------------------------------------
        // A release-WP user's config carries layout bytes in the proprietary JIDE Docking
        // Framework binary format. We extract:
        //   - panel → (side, index) — written into each DockableFrame's init fields before deploy
        //   - panel → mode (DOCKED/AUTOHIDE/FLOATING) — applied post-deploy via setExtendedMode
        //
        // Runs for both first-load (layout never deployed) AND subsequent per-dim loads
        // (layout already deployed by a previous resetToDefault / deployInitialLayout). The
        // second case is the common one: App.java loads the default layout via resetToDefault,
        // then later calls loadLayoutFrom with the per-dim JIDE bytes when the user opens a
        // world — by that point the deploy has already happened with built-in defaults. To
        // apply the imported placement we tear the dockables down and redeploy, exactly the
        // same dance resetToDefault uses for its own re-deploy branch.
        java.util.Map<String, JideLayoutImporter.FramePlacement> jidePlacements = null;
        try {
            jidePlacements = JideLayoutImporter.tryParse(bytes);
        } catch (RuntimeException e) {
            logger.warn("JIDE-import parse threw; falling back to DockingFrames decode.", e);
            jidePlacements = null;
        }
        if (jidePlacements != null) {
            // (a) Apply (side, index) to each frame's init fields. Has to happen before any
            // deploy so the deploy reads the imported values.
            //
            // Special handling for FLOATING groups: by default release WP declares its
            // floating-by-default panels at the same (side, index) as some DOCKED panels
            // (e.g. biomes/layers/annotations AND terrain all default to (WEST, 3)). If we
            // copy those defaults into our build verbatim, the deploy puts all four of them
            // in the same tab-stack. Then when we later setExtendedMode(EXTERNALIZED) one of
            // the floaters, DockingFrames pulls the whole tab-stack out — terrain comes along
            // for the ride. Fix: bump every FLOATING panel's initIndex to a fresh value
            // (shared within a group, so they still tab-stack with each other), leaving the
            // DOCKED panels at their original index. Terrain stays at (WEST, 3) alone;
            // biomes/layers/annotations move to (WEST, 99+) and externalize as a group.
            final int floatingIndexBase = 99;
            final java.util.Map<Integer, Integer> floatingGroupIndex = new java.util.HashMap<>();
            int nextFloatingIndex = floatingIndexBase;
            int applied = 0;
            for (java.util.Map.Entry<String, JideLayoutImporter.FramePlacement> e : jidePlacements.entrySet()) {
                final DockableFrame f = frames.get(e.getKey());
                if (f == null) continue;
                final JideLayoutImporter.FramePlacement p = e.getValue();
                f.setInitSide(p.toDockContextSide());
                if (p.mode == JideLayoutImporter.MODE_FLOATING) {
                    // Group leader id = smallest member id (stable across re-runs).
                    int leaderId = p.id;
                    for (int memberId : p.groupMemberIds) {
                        if (memberId < leaderId) leaderId = memberId;
                    }
                    final int assigned = floatingGroupIndex.computeIfAbsent(leaderId, k -> {
                        return floatingIndexBase + floatingGroupIndex.size();
                    });
                    f.setInitIndex(assigned);
                } else {
                    f.setInitIndex(p.index);
                }
                applied++;
            }
            logger.info("Restoring layout from JIDE bytes — applied {} of {} placement(s).",
                    applied, jidePlacements.size());

            // (b) Deploy (or redeploy if we'd already deployed something).
            if (layoutDeployed) {
                tearDownDeployedLayout();
            }
            deployInitialLayout();

            // (c) Apply modes (AUTOHIDE → MINIMIZED, FLOATING → EXTERNALIZED). DOCKED stays
            // NORMALIZED (the deploy default).
            applyJideModesPostDeploy(jidePlacements);
            return;
        }

        // Not JIDE. Deploy default first if not yet deployed, so reads have something to apply onto.
        if (!layoutDeployed) {
            deployInitialLayout();
        }

        // B2: prefer the XML format (current path) — it's per-resource version-aware and avoids
        // the binary format's "Unknown format for version" failures during round-trip. Detect XML
        // by the leading '<' or whitespace-then-'<' (BOM stripping happens inside XIO.readUTF).
        // Falls back to the binary format if XML parsing fails — handles existing user configs
        // from before this change that were saved in the binary format.
        boolean looksLikeXml = false;
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '<') {
                looksLikeXml = true;
                break;
            }
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n' && b != (byte) 0xEF && b != (byte) 0xBB && b != (byte) 0xBF) {
                // Non-whitespace, non-BOM character before '<' — not XML.
                break;
            }
        }
        if (looksLikeXml) {
            try {
                final bibliothek.util.xml.XElement root = bibliothek.util.xml.XIO.readUTF(new java.io.ByteArrayInputStream(bytes));
                control.readXML(root);
                logger.info("Applied saved docking layout (XML, {} bytes)", bytes.length);
                return;
            } catch (IOException | RuntimeException e) {
                logger.info("Saved layout is not XML ({}); falling back to legacy binary format", e.toString());
            }
        }

        // Binary fallback (legacy / pre-B2-XML saves).
        try (DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            control.read(dis);
        } catch (IOException | RuntimeException e) {
            logger.info("Layout bytes weren't a DockingFrames CControl.write — using whatever's deployed. ({})",
                    e.toString());
        }
    }

    /**
     * Translate each JIDE-imported {@code mode} (FLOATING / AUTOHIDE / DOCKED) into the
     * DockingFrames-equivalent {@link bibliothek.gui.dock.common.mode.ExtendedMode} and apply
     * it. Called immediately after {@link #deployInitialLayout} when we know the bytes were in
     * JIDE format. Idempotent: DOCKED panels stay {@code NORMALIZED} (the default), AUTOHIDE
     * panels become {@code MINIMIZED}, FLOATING panels become {@code EXTERNALIZED}.
     *
     * <p>The mode-set may fail per-panel for reasons we can't influence here (panel removed,
     * mode unavailable in the current view); we log and skip. Failures are non-fatal — at worst
     * the panel stays in its NORMALIZED dock position, which is the same fallback the user
     * would see if they'd never run release WP at all.
     *
     * <p>Floating-group reconstruction (multiple panels in one external window via
     * {@code groupMemberIds}) is a TODO — for now each FLOATING panel gets its own external
     * window, which is functionally correct (the right panels become external) just visually
     * less compact than what release WP showed.
     */
    private void applyJideModesPostDeploy(java.util.Map<String, JideLayoutImporter.FramePlacement> placements) {
        int minimized = 0, externalized = 0, skipped = 0;
        // Track the first FLOATING dockable processed so we can bring it to front + force a
        // tab-strip layout pass at the end (without that, only the LAST externalized panel
        // renders properly — the others' tabs aren't drawn until the user interacts).
        bibliothek.gui.dock.common.DefaultSingleCDockable firstFloating = null;

        // For floating groups: the FIRST panel encountered per group goes to its bounded
        // external location (CExternalizedLocation at the JIDE-saved x, y, w, h). Subsequent
        // members of the same group go to that location's .stack() — telling DockingFrames
        // "tab onto whatever's already there." This is what merges all three members of a
        // group into ONE external window, instead of DockingFrames spawning a separate
        // window for the latecomers like it did with bare setExtendedMode(EXTERNALIZED).
        // Group identity is the smallest member id (the canonical "leader" used elsewhere).
        final java.util.Map<Integer, bibliothek.gui.dock.common.CLocation> groupExternalLocation = new java.util.HashMap<>();

        for (java.util.Map.Entry<String, JideLayoutImporter.FramePlacement> e : placements.entrySet()) {
            final bibliothek.gui.dock.common.intern.DefaultCommonDockable ignored = null;
            final bibliothek.gui.dock.common.DefaultSingleCDockable d = dockables.get(e.getKey());
            if (d == null) {
                continue; // unknown panel key; harmless
            }
            try {
                switch (e.getValue().mode) {
                    case JideLayoutImporter.MODE_AUTOHIDE:
                        // U4: per-dockable setMinimizedSize used to be set here from each panel's
                        // preferred size. Removed because it overrode the per-station default set
                        // in deployInitialLayout — so each panel popped open at its own width
                        // instead of the shared per-side width Arag wanted. The station default
                        // (DEFAULT_FLAP_WIDTH_PX / DEFAULT_FLAP_HEIGHT_PX) now applies to every
                        // autohide popup unless the user explicitly resizes one (DockingFrames
                        // remembers per-dockable user resizes; that's expected and acceptable).
                        // Explicitly target the correct flap station. By default
                        // setExtendedMode(MINIMIZED) sends the dockable to whichever flap is
                        // "closest" to its current location — at grid (0, 0) that ends up being
                        // the NORTH flap (under the toolbar, app-wide), not the WEST one where
                        // JIDE had it. Force the right side based on the JIDE-imported side.
                        d.setLocation(flapLocationFor(e.getValue()));
                        d.setExtendedMode(bibliothek.gui.dock.common.mode.ExtendedMode.MINIMIZED);
                        minimized++;
                        break;
                    case JideLayoutImporter.MODE_FLOATING:
                        // Pick the canonical group leader (smallest member id).
                        int groupLeader = e.getValue().id;
                        for (int memberId : e.getValue().groupMemberIds) {
                            if (memberId < groupLeader) groupLeader = memberId;
                        }
                        final int[] bnds = e.getValue().floatingBounds;
                        bibliothek.gui.dock.common.CLocation groupLoc = groupExternalLocation.get(groupLeader);
                        if (groupLoc == null && bnds != null) {
                            // First member of this floating group — externalize it at the
                            // JIDE-saved screen-coordinates rectangle. Remember the location
                            // so the other members can stack on it.
                            groupLoc = bibliothek.gui.dock.common.CLocation.external(
                                    bnds[0], bnds[1], bnds[2], bnds[3]);
                            d.setLocation(groupLoc);
                            groupExternalLocation.put(groupLeader, groupLoc);
                        } else if (groupLoc != null) {
                            // Subsequent member — tab onto the leader's external window. Cast
                            // is safe: CLocation.external returns a CExternalizedLocation,
                            // which extends AbstractStackholdingLocation and exposes .stack().
                            if (groupLoc instanceof bibliothek.gui.dock.common.location.AbstractStackholdingLocation) {
                                d.setLocation(((bibliothek.gui.dock.common.location.AbstractStackholdingLocation) groupLoc).stack());
                            }
                        }
                        d.setExtendedMode(bibliothek.gui.dock.common.mode.ExtendedMode.EXTERNALIZED);
                        externalized++;
                        if (firstFloating == null) {
                            firstFloating = d;
                        }
                        break;
                    case JideLayoutImporter.MODE_DOCKED:
                    default:
                        // Already NORMALIZED from the deploy — nothing to do.
                        break;
                }
            } catch (RuntimeException ex) {
                logger.debug("Could not apply JIDE mode {} to '{}': {}",
                        e.getValue().mode, e.getKey(), ex.toString());
                skipped++;
            }
        }
        logger.info("JIDE mode application: {} minimized, {} externalized, {} skipped",
                minimized, externalized, skipped);

        // Floating group display fix-up: explicit toFront on the first floating panel,
        // followed by a deferred validate on its window, to make the tab strip render
        // properly. Without this, only the last-externalized member appears as the active
        // tab and the others' tabs aren't drawn until the user interacts.
        if (firstFloating != null) {
            final bibliothek.gui.dock.common.DefaultSingleCDockable front = firstFloating;
            SwingUtilities.invokeLater(() -> {
                try {
                    front.toFront();
                    final java.awt.Component c = front.getContentPane();
                    if (c != null) {
                        java.awt.Window w = SwingUtilities.getWindowAncestor(c);
                        if (w != null) {
                            w.validate();
                            w.repaint();
                        }
                    }
                } catch (RuntimeException ex) {
                    logger.debug("Could not refresh floating-group front tab: {}", ex.toString());
                }
            });
        }
    }

    @Override
    public void attachIconChooserAction(String panelId, Runnable onChooseIcon) {
        if ((panelId == null) || (onChooseIcon == null)) {
            return;
        }
        final DefaultSingleCDockable d = dockables.get(panelId);
        if (d == null) {
            logger.debug("attachIconChooserAction: no dockable registered for id '{}'", panelId);
            return;
        }
        // Task 8: CButton appears in DockingFrames Common's native title-bar context menu
        // (the one with Minimize / Externalize that the user already sees on right-click).
        // setText supplies the menu item label; setTooltip supplies the hover text. The
        // listener fires on either menu pick or title-bar button click (theme-dependent).
        final bibliothek.gui.dock.common.action.CButton btn =
                new bibliothek.gui.dock.common.action.CButton();
        btn.setText("Change icon...");
        btn.setTooltip("Pick or restore the icon shown on this panel's tab/title");
        btn.addActionListener(e -> onChooseIcon.run());
        d.addAction(btn);
    }

    @Override
    public void setFrameIcon(String panelId, javax.swing.Icon icon) {
        if (panelId == null) {
            return;
        }
        final DefaultSingleCDockable d = dockables.get(panelId);
        if (d != null) {
            d.setTitleIcon(icon);
        }
    }

    @Override
    public void attachIconChooserActionToAll(java.util.function.BiConsumer<String, String> pickerLauncher) {
        this.globalIconChooserLauncher = pickerLauncher;
        if (pickerLauncher == null) {
            return;
        }
        // Attach to every dockable currently registered (i.e. all standard panels installed
        // during deployInitialLayout, plus any custom panels added pre-deploy).
        for (Map.Entry<String, DefaultSingleCDockable> e : dockables.entrySet()) {
            final String pid = e.getKey();
            final DockableFrame f = frames.get(pid);
            final String pt = (f != null) ? f.getTitle() : pid;
            attachIconChooserAction(pid, () -> pickerLauncher.accept(pid, pt));
        }
        logger.info("attachIconChooserActionToAll: wired 'Change icon...' to {} dockable(s)",
                dockables.size());
    }

    /**
     * Task 8: when set, every {@link #addFrame} call that lands AFTER deploy auto-attaches
     * the "Change icon..." action to the new dockable. Lets custom layer palettes (which are
     * created post-world-load via {@code CustomLayerController.registerCustomLayer} → addFrame)
     * pick up the action without the caller needing to know about it.
     */
    private java.util.function.BiConsumer<String, String> globalIconChooserLauncher;

    @Override
    public void applyIconOverridesToAll(java.util.function.BiFunction<String, String, javax.swing.Icon> iconResolver) {
        this.globalIconResolver = iconResolver;
        if (iconResolver == null) {
            return;
        }
        int applied = 0;
        for (Map.Entry<String, DefaultSingleCDockable> e : dockables.entrySet()) {
            final String pid = e.getKey();
            final DockableFrame f = frames.get(pid);
            final String pt = (f != null) ? f.getTitle() : pid;
            final javax.swing.Icon icon = iconResolver.apply(pid, pt);
            if (icon != null) {
                e.getValue().setTitleIcon(icon);
                applied++;
            }
        }
        logger.info("applyIconOverridesToAll: applied {} stored override(s) across {} dockable(s)",
                applied, dockables.size());
    }

    /**
     * Task 8: when set, every {@link #addFrame} call that lands AFTER deploy invokes this
     * resolver to look up any stored icon override for the new panel. Used so custom layer
     * palettes registered after world load also get their stored override (if any) applied
     * automatically at registration time.
     */
    private java.util.function.BiFunction<String, String, javax.swing.Icon> globalIconResolver;

    @Override
    public byte[] getLayoutRawData() {
        if (!layoutDeployed) {
            return new byte[0];
        }
        // B2: use the XML format instead of the binary DataOutputStream format. The binary
        // format's `Version.read` rejects round-tripped bytes with `IOException: Unknown
        // format for version` even when the top-level envelope is valid (first byte = 1) —
        // somewhere a nested resource's own Version.read fails on a sub-stream. The XML
        // format is per-resource version-aware via XML attributes and survives that edge
        // case. Round-trip storage cost: ~5x larger byte[], but the layout descriptor is
        // small (~10-50 KB) so it's negligible inside the much larger Configuration object.
        //
        // Backwards-compat: existing user configs hold the binary format. loadLayoutFrom()
        // tries XML first, then falls back to the binary read for those legacy bytes — so
        // upgrading WP doesn't nuke previously-saved layouts; the first save after upgrade
        // re-writes them as XML and subsequent loads succeed cleanly.
        try {
            bibliothek.util.xml.XElement root = new bibliothek.util.xml.XElement("root");
            control.writeXML(root);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bibliothek.util.xml.XIO.writeUTF(root, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            logger.warn("Failed to serialise docking layout (XML)", e);
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

        // ---- Coordinate scheme ---------------------------------------------------------------
        // West is laid out vertically: a single column at x=0 with each distinct initIndex
        //   getting its own row top-to-bottom (lower index = top). This matches release WP
        //   visible layout, where Tool Settings sits above Terrain when both are docked on
        //   the west.
        // East gets one column PER distinct initIndex laid out horizontally: lower index =
        //   further right (outer edge), higher index = closer to the workspace. This matches
        //   release WP's east arrangement, where Brushes (index 1) is at the far right and
        //   Info+BrushSettings (index 2) sit closer to the map.
        // Workspace occupies columns 1..4 (a generous 4-column-wide centre); east columns
        //   start at x=5 and continue outwards.
        // Same-index frames stack as tabs in the same cell — that's how Info and Brush
        //   Settings (both EAST, index=2) come back as a tab-pair.
        CGrid grid = new CGrid(control);

        layoutWestColumn(grid, west, 0);

        DefaultSingleCDockable workspaceDockable = new DefaultSingleCDockable(WORKSPACE_ID, "Map", workspace);
        workspaceDockable.setCloseable(false);
        workspaceDockable.setMaximizable(true);
        // The map area is the central canvas in JIDE-based WP — no title bar is shown there.
        // Suppress the dockable's title bar to match.
        workspaceDockable.setTitleShown(false);
        int rows = Math.max(Math.max(distinctIndices(west), distinctIndices(east)), 1);
        grid.add(1, 0, 4, rows, workspaceDockable);

        layoutEastColumns(grid, east, /* startX = */ 5);

        control.getContentArea().deploy(grid);
        layoutDeployed = true;

        // U4: set a shared default autohide popup size per flap station. Without this, each
        // panel pops open at its own preferred-size width (set per-dockable via setMinimizedSize
        // below) — Arag's complaint was that resizing one autohide popup didn't carry over to
        // the others on the same side. FlapDockStation.setDefaultWindowSize(int) is the
        // per-station default: every popup from that flap uses this size unless the dockable
        // has its own explicit setWindowSize override.
        //
        // Values: 280 px for west/east (a comfortable panel width that doesn't dominate the map);
        // 200 px for north/south (rare in WP — toolbars usually stay docked at top — but
        // configured for completeness).
        final CContentArea contentArea = control.getContentArea();
        contentArea.getWestArea().getStation().setDefaultWindowSize(DEFAULT_FLAP_WIDTH_PX);
        contentArea.getEastArea().getStation().setDefaultWindowSize(DEFAULT_FLAP_WIDTH_PX);
        contentArea.getNorthArea().getStation().setDefaultWindowSize(DEFAULT_FLAP_HEIGHT_PX);
        contentArea.getSouthArea().getStation().setDefaultWindowSize(DEFAULT_FLAP_HEIGHT_PX);

        // Workaround for JScrollPane preferred-size caching: panels initially in the BACK of a
        // tab stack never get their JScrollPane laid out against a real viewport size, so their
        // scrollbar policy is permanently mis-decided. Force every dockable to be the front of
        // its stack briefly — fires the SHOWING_CHANGED HierarchyListener on each DockableFrame,
        // which re-runs the scrollpane refresh against a real width. The user's idea: "create
        // all visible, then group". DockingFrames batches these toFront() calls in one EDT cycle
        // so there's no visible flicker.
        SwingUtilities.invokeLater(() -> {
            // First, pick the dockable that should remain in front per-stack, so we can restore
            // it at the end. The "winner" is whichever frame had the lowest initIndex within
            // its (side, index) bucket — i.e. the one that would have been on top organically.
            java.util.Set<DefaultSingleCDockable> originalFronts = new java.util.HashSet<>();
            DockableFrame westFront = west.isEmpty() ? null : west.get(0);
            DockableFrame eastFront = east.isEmpty() ? null : east.get(0);
            if (westFront != null) originalFronts.add(dockables.get(westFront.getKey()));
            if (eastFront != null) originalFronts.add(dockables.get(eastFront.getKey()));

            // Cycle through every dockable so each gets one moment as the active tab.
            // CAVEAT: skip MINIMIZED dockables — toFront() on a minimized dockable pops its
            // flap fully open. Skip EXTERNALIZED dockables too — toFront() across an
            // externalized tab-stack would arbitrarily promote whichever one happens to be
            // last in our iteration order. The "make this floating panel the active tab"
            // decision is made explicitly in applyJideModesPostDeploy, not here.
            // Normal docked tab-stacks DO need the toFront cycle to fix stale JScrollPane
            // preferred-size caching (panels born at the back never got their scroll panes
            // laid out against a real viewport width).
            for (DefaultSingleCDockable d : dockables.values()) {
                final bibliothek.gui.dock.common.mode.ExtendedMode mode = d.getExtendedMode();
                if (mode == bibliothek.gui.dock.common.mode.ExtendedMode.MINIMIZED
                        || mode == bibliothek.gui.dock.common.mode.ExtendedMode.EXTERNALIZED) {
                    continue;
                }
                d.toFront();
            }
            // Restore the originally-fronting tabs (docked panels only).
            for (DefaultSingleCDockable d : originalFronts) {
                final bibliothek.gui.dock.common.mode.ExtendedMode mode = d.getExtendedMode();
                if (mode != bibliothek.gui.dock.common.mode.ExtendedMode.MINIMIZED
                        && mode != bibliothek.gui.dock.common.mode.ExtendedMode.EXTERNALIZED) {
                    d.toFront();
                }
            }
        });
    }

    /**
     * Lay out the west side as a single column at {@code x} with each distinct {@code initIndex}
     * stacked top-to-bottom (lower index = top). Same-index frames become tabs in the same cell.
     * Matches release WP's visible west arrangement (Tool Settings on top, Terrain below it).
     */
    private void layoutWestColumn(CGrid grid, List<DockableFrame> frames, int x) {
        int row = 0;
        Integer prevIndex = null;
        List<DefaultSingleCDockable> currentCell = new ArrayList<>();
        for (DockableFrame f : frames) {
            DefaultSingleCDockable d = wrap(f);
            dockables.put(f.getKey(), d);
            if (prevIndex != null && f.getInitIndex() != prevIndex) {
                grid.add(x, row++, 1, 1, currentCell.toArray(new DefaultSingleCDockable[0]));
                currentCell.clear();
            }
            currentCell.add(d);
            prevIndex = f.getInitIndex();
        }
        if (!currentCell.isEmpty()) {
            grid.add(x, row, 1, 1, currentCell.toArray(new DefaultSingleCDockable[0]));
        }
    }

    /**
     * Lay out the east side as multiple columns starting at {@code startX}, one column per
     * distinct {@code initIndex}: lower index → further right (outer), higher index → closer to
     * the workspace (inner). Same-index frames tab-stack within the cell at that column. Matches
     * release WP's east arrangement (Brushes far right; Info+BrushSettings tabbed pair closer
     * to the map).
     *
     * <p>Single-pass: sort the frames by index ascending, then walk them, assigning each
     * distinct index its own column starting from the outermost. The {@code frames} list is
     * already sorted by {@code initIndex} ascending by the caller.
     */
    private void layoutEastColumns(CGrid grid, List<DockableFrame> frames, int startX) {
        if (frames.isEmpty()) return;
        // Count distinct indices to know how many east columns we'll need.
        final java.util.SortedSet<Integer> distinctIndices = new java.util.TreeSet<>();
        for (DockableFrame f : frames) distinctIndices.add(f.getInitIndex());
        final int columnCount = distinctIndices.size();

        // Assign each distinct index to an x: ascending index → descending x (outer-first).
        // First (lowest) index gets the outermost x (= startX + columnCount - 1); last (highest)
        // gets startX itself, adjacent to the workspace.
        final java.util.Map<Integer, Integer> indexToX = new java.util.HashMap<>();
        int xPos = startX + columnCount - 1;
        for (Integer idx : distinctIndices) {
            indexToX.put(idx, xPos--);
        }

        // Group frames by index and add each group as a separate cell at its assigned x.
        Integer prevIndex = null;
        List<DefaultSingleCDockable> currentCell = new ArrayList<>();
        for (DockableFrame f : frames) {
            DefaultSingleCDockable d = wrap(f);
            dockables.put(f.getKey(), d);
            if (prevIndex != null && f.getInitIndex() != prevIndex) {
                grid.add(indexToX.get(prevIndex), 0, 1, 1, currentCell.toArray(new DefaultSingleCDockable[0]));
                currentCell.clear();
            }
            currentCell.add(d);
            prevIndex = f.getInitIndex();
        }
        if (!currentCell.isEmpty()) {
            grid.add(indexToX.get(prevIndex), 0, 1, 1, currentCell.toArray(new DefaultSingleCDockable[0]));
        }
    }

    private static int distinctIndices(List<DockableFrame> frames) {
        return (int) frames.stream().mapToInt(DockableFrame::getInitIndex).distinct().count();
    }

    /**
     * Map a panel's parsed-JIDE side onto the DockingFrames flap location that puts the
     * minimized side-tab on the matching edge of the workspace. Without this, DockingFrames
     * picks "the nearest flap" heuristically, which for a dockable at grid origin (0, 0)
     * ends up being the NORTH flap (full app width, under the toolbar) — not the WEST flap
     * where the user originally had Tools docked.
     */
    private static bibliothek.gui.dock.common.CLocation flapLocationFor(
            JideLayoutImporter.FramePlacement placement) {
        switch (placement.toDockContextSide()) {
            case DockContext.DOCK_SIDE_EAST:  return bibliothek.gui.dock.common.CLocation.base().minimalEast();
            case DockContext.DOCK_SIDE_NORTH: return bibliothek.gui.dock.common.CLocation.base().minimalNorth();
            case DockContext.DOCK_SIDE_SOUTH: return bibliothek.gui.dock.common.CLocation.base().minimalSouth();
            case DockContext.DOCK_SIDE_WEST:
            default:                          return bibliothek.gui.dock.common.CLocation.base().minimalWest();
        }
    }

    /** Does this container's tree already include a JScrollPane? */
    private static boolean containsScrollPane(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JScrollPane) {
                return true;
            }
            if (child instanceof Container && containsScrollPane((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private DefaultSingleCDockable wrap(final DockableFrame f) {
        // WP only inserts a JScrollPane when DockableFrameBuilder.scrollable() was called by the
        // caller — Tools/Layers/Terrain/Annotations don't have that flag. With JIDE these panels
        // got enough vertical space to never need scrolling; with our tab grouping the cells are
        // shorter, content overflows, and there's no JScrollPane to provide a scrollbar. We
        // auto-wrap any frame that isn't already scrollable so users can always reach overflow.
        JComponent dockableContent = f;
        if (!containsScrollPane(f)) {
            JScrollPane sp = new JScrollPane(f,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
            dockableContent = sp;
        }
        DefaultSingleCDockable d = new DefaultSingleCDockable(f.getKey(), f.getTitle(), dockableContent);
        Icon icon = f.getFrameIcon();
        if (icon == null) {
            // Fallback so ICON_ONLY tabs always render something visible and clickable.
            icon = makeFallbackIcon(f.getTitle());
        }
        d.setTitleIcon(icon);
        // Tooltip with the full title so the user can identify icon-only tabs by hovering.
        d.setTitleToolTip(f.getTitle());
        d.setMaximizable(f.isMaximizable());
        // JIDE distinguishes "hidable" (can be hidden by the user) from "closeable" (permanent
        // close). DockingFrames only has "closeable", which means a close-X is shown. WP wants the
        // hide-via-autohide-button behaviour, not permanent close, so leave closeable=false.
        d.setCloseable(false);
        // When a panel becomes visible for the first time after being hidden behind a tab, its
        // JScrollPane viewport may have been laid out before the content had a real size, leaving
        // the scrollbar permanently missing. Force a revalidate on every visibility transition.
        d.addCDockableStateListener(new CDockableStateListener() {
            @Override
            public void visibilityChanged(CDockable dockable) {
                if (dockable.isVisible()) {
                    SwingUtilities.invokeLater(() -> {
                        f.revalidate();
                        f.repaint();
                    });
                }
            }
            @Override
            public void extendedModeChanged(CDockable dockable, ExtendedMode mode) { /* no-op */ }
        });
        return d;
    }

    /**
     * A 16-px rounded square in WP-blue with the panel title's first letter on top. Used as a
     * fallback when WP doesn't supply an explicit frame icon — guarantees that ICON_ONLY tabs
     * always render something visible and clickable.
     */
    private static Icon makeFallbackIcon(String title) {
        final int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x4080C0));
            g.fillRoundRect(0, 0, size - 1, size - 1, 4, 4);
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            String letter = (title == null || title.isEmpty())
                    ? "?"
                    : String.valueOf(Character.toUpperCase(title.charAt(0)));
            FontMetrics fm = g.getFontMetrics();
            int x = (size - fm.stringWidth(letter)) / 2;
            int y = (size + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(letter, x, y);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private void installFrameLive(DockableFrame f) {
        DefaultSingleCDockable d = wrap(f);
        control.addDockable(d);
        CLocation base = (f.getInitSide() == DockContext.DOCK_SIDE_EAST)
                ? CLocation.base().normalEast(0.25)
                : CLocation.base().normalWest(0.25);
        d.setLocation(base);
        d.setVisible(true);
        dockables.put(f.getKey(), d);
    }

    /**
     * Glass color scheme tweaked for WP. The default focused/selected gradients have a
     * near-black middle stop that hides black PNG icons. We override every focused- and
     * selected-tab slot with a light cool-blue palette. Overrides are applied in
     * {@link #updateUI()} (after super) so they survive the theme's periodic refresh.
     */
    private static class WPGlassColorScheme extends CGlassEclipseColorSchemeExtension {
        // The Glass painter uses three distinct slot families per state — top edge, bottom edge,
        // and outer border — plus the inner glass.* gradient. Out-of-the-box the *border* slot
        // for the focused state is Color.DARK_GRAY and the *bottom* uses system active colour
        // (typically dark blue), which together produce the dark mid-band that bleeds through
        // even after we lighten the glass overlay. Overriding all three families keeps the tab
        // uniformly cyan.

        // Cyan family that harmonises with WP's pale-cyan map background:
        private static final Color WP_CYAN_TOP      = new Color(0xC8, 0xEA, 0xEF);
        private static final Color WP_CYAN_BOTTOM   = new Color(0xA0, 0xD0, 0xDC);
        private static final Color WP_CYAN_BORDER   = new Color(0x70, 0xA8, 0xC4);
        private static final Color WP_CYAN_CENTER   = new Color(0xB8, 0xDE, 0xE8);
        private static final Color WP_CYAN_LIGHT    = new Color(0xE6, 0xF4, 0xF8);
        private static final Color WP_CYAN_BOUNDARY = new Color(0x60, 0xA0, 0xC0);

        // Slightly muted variants for "selected but not focused" so focus state is still visible:
        private static final Color WP_MUTED_TOP    = new Color(0xDC, 0xEA, 0xEE);
        private static final Color WP_MUTED_BOTTOM = new Color(0xC4, 0xD8, 0xE0);
        private static final Color WP_MUTED_BORDER = new Color(0x9C, 0xB8, 0xC8);

        @Override
        protected void updateUI() {
            super.updateUI();

            // Focused (active stack with keyboard focus)
            setColor("stack.tab.top.selected.focused.glass",    WP_CYAN_TOP);
            setColor("stack.tab.bottom.selected.focused.glass", WP_CYAN_BOTTOM);
            setColor("stack.tab.border.selected.focused.glass", WP_CYAN_BORDER);
            setColor("glass.focused.center",                    WP_CYAN_CENTER);
            setColor("glass.focused.light",                     WP_CYAN_LIGHT);
            setColor("glass.focused.boundary",                  WP_CYAN_BOUNDARY);
            setColor("stack.tab.text.selected.focused.glass",   new Color(0x33, 0x33, 0x33));

            // Selected (active tab whose stack lacks focus): muted so focus is still distinguishable
            setColor("stack.tab.top.selected.glass",            WP_MUTED_TOP);
            setColor("stack.tab.bottom.selected.glass",         WP_MUTED_BOTTOM);
            setColor("stack.tab.border.selected.glass",         WP_MUTED_BORDER);
            setColor("glass.selected.center",                   new Color(0xC8, 0xDC, 0xE4));
            setColor("glass.selected.light",                    new Color(0xEE, 0xF4, 0xF6));
            setColor("glass.selected.boundary",                 new Color(0x90, 0xB0, 0xC4));
            setColor("stack.tab.text.selected.glass",           new Color(0x44, 0x44, 0x44));

            // "Focus lost" (used by some painter paths) shares the muted look
            setColor("stack.tab.top.selected.focuslost.glass",    WP_MUTED_TOP);
            setColor("stack.tab.bottom.selected.focuslost.glass", WP_MUTED_BOTTOM);
            setColor("stack.tab.border.selected.focuslost.glass", WP_MUTED_BORDER);
        }
    }
}
