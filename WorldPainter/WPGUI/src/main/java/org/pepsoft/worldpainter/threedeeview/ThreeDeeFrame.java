/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.threedeeview;

import org.pepsoft.minecraft.Direction;
import org.pepsoft.util.IconUtils;
import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.util.swing.ProgressDialog;
import org.pepsoft.util.swing.ProgressTask;
import org.pepsoft.worldpainter.App;
import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileRenderer;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode;
import org.pepsoft.worldpainter.util.BetterAction;
import org.pepsoft.worldpainter.util.ImageUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.Set;

import static org.pepsoft.util.GUIUtils.scaleToUI;
import static org.pepsoft.util.swing.MessageUtils.beepAndShowError;
import static org.pepsoft.worldpainter.App.INT_NUMBER_FORMAT;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode.*;
import static org.pepsoft.worldpainter.util.LayoutUtils.setDefaultSizeAndLocation;

/**
 *
 * @author pepijn
 */
public class ThreeDeeFrame extends JFrame implements WindowListener {
    public ThreeDeeFrame(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, Point initialCoords) throws HeadlessException {
        super("WorldPainter - 3D View");
        setIconImage(App.ICON);
        this.colourScheme = colourScheme;
        this.customBiomeManager = customBiomeManager;
        this.coords = initialCoords;
        
        scrollPane = new JScrollPane();
        
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                previousX = e.getX();
                previousY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - previousX;
                int dy = e.getY() - previousY;
                previousX = e.getX();
                previousY = e.getY();
                JScrollBar scrollBar = scrollPane.getHorizontalScrollBar();
                scrollBar.setValue(scrollBar.getValue() - dx);
                scrollBar = scrollPane.getVerticalScrollBar();
                scrollBar.setValue(scrollBar.getValue() - dy);
            }

            @Override public void mouseClicked(MouseEvent e) {}
            @Override public void mouseReleased(MouseEvent e) {}
            @Override public void mouseEntered(MouseEvent e) {}
            @Override public void mouseExited(MouseEvent e) {}
            @Override public void mouseMoved(MouseEvent e) {}
            
            private int previousX, previousY;
        };
        scrollPane.addMouseListener(mouseAdapter);
        scrollPane.addMouseMotionListener(mouseAdapter);
        scrollPane.addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.getWheelRotation() < 0) {
                    if (zoom < MAX_ZOOM) {
                        ZOOM_IN_ACTION.actionPerformed(new ActionEvent(e.getSource(), ActionEvent.ACTION_PERFORMED, null, e.getWhen(), e.getModifiers()));
                    }
                } else {
                    if (zoom > MIN_ZOOM) {
                        ZOOM_OUT_ACTION.actionPerformed(new ActionEvent(e.getSource(), ActionEvent.ACTION_PERFORMED, null, e.getWhen(), e.getModifiers()));
                    }
                }
            }
        });
        
        getContentPane().add(scrollPane, BorderLayout.CENTER);
        
        alwaysOnTopButton.setToolTipText("Set the 3D view window to be always on top");
        alwaysOnTopButton.addActionListener(e -> {
            if (alwaysOnTopButton.isSelected()) {
                ThreeDeeFrame.this.setAlwaysOnTop(true);
            } else {
                ThreeDeeFrame.this.setAlwaysOnTop(false);
            }
        });

        // C1: master toggle for the tile-cache optimisations. Default on (sticky across opens
        // via lastCachingEnabled). Unchecking flushes the rendered-tile cache, asks B-mode to
        // evict regions outside the budget, and disables the C1 paint optimisations going
        // forward. Visible tiles will re-render at the next paint.
        cacheCheckBox.setToolTipText("<html>When on, all rendered tiles are kept cached (no RAM cap). When off, the cache is limited to the 2000 least-recently-used tiles (~720 MB) and tiles outside that window re-render when revisited. The currently visible viewport is protected from eviction.</html>");
        cacheCheckBox.addActionListener(e -> {
            cachingEnabled = cacheCheckBox.isSelected();
            lastCachingEnabled = cachingEnabled;
            if (threeDeeView != null) {
                threeDeeView.setCachingEnabled(cachingEnabled);
            }
        });

        // D1: light-direction dropdown. The arrow in each item shows the direction the light
        // TRAVELS (so the source is at the *opposite* corner): ↘ = light from NW going SE,
        // ↓ = light from above going straight down, etc. On selection change we rebuild the
        // ThreeDeeView so the embedded 2D TileRenderer picks up the new world-space LightOrigin
        // and every tile re-renders. Combobox replaces the original cycle button because the
        // rebuild takes a noticeable time on zoomed-out views and a click-to-cycle UI made it
        // too easy to over-shoot the desired value with double-clicks.
        lightCombo.setMaximumRowCount(LIGHT_ORIGIN_VALUES.length);
        lightCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, (value instanceof TileRenderer.LightOrigin) ? lightOriginLabel((TileRenderer.LightOrigin) value) : value, index, isSelected, cellHasFocus);
            }
        });
        lightCombo.setSelectedItem(screenLightOrigin);
        // Don't let the combobox stretch vertically in the JToolBar — it would balloon to
        // toolbar height. Lock width too so it doesn't change as the user picks values with
        // different label widths.
        final java.awt.Dimension lightComboSize = lightCombo.getPreferredSize();
        lightCombo.setMaximumSize(lightComboSize);
        lightCombo.setMinimumSize(lightComboSize);
        lightCombo.setToolTipText("<html>Pick which edge of the screen the light comes from. The arrow shows the direction the light travels:<br>" +
                "↓ From top = sun above the top edge, rays travel downward on screen<br>" +
                "← From right, ↑ From bottom, → From left — analogous<br>" +
                "Above = overhead, no slope shading<br>" +
                "The choice is locked to the screen, so rotating the view doesn't move the bright slopes.</html>");
        lightCombo.addActionListener(e -> {
            final TileRenderer.LightOrigin selected = (TileRenderer.LightOrigin) lightCombo.getSelectedItem();
            if ((selected != null) && (selected != screenLightOrigin)) {
                applyLightDirection(selected);
            }
        });

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(alwaysOnTopButton);
        toolBar.addSeparator();
        toolBar.add(ROTATE_LEFT_ACTION);
        toolBar.add(ROTATE_RIGHT_ACTION);
        toolBar.addSeparator();
        toolBar.add(ZOOM_OUT_ACTION);
        toolBar.add(RESET_ZOOM_ACTION);
        toolBar.add(ZOOM_IN_ACTION);
        toolBar.addSeparator();
        toolBar.add(EXPORT_IMAGE_ACTION);
        toolBar.addSeparator();
        toolBar.add(MOVE_TO_SPAWN_ACTION);
        toolBar.add(MOVE_TO_ORIGIN_ACTION);
        toolBar.addSeparator();
        toolBar.add(new JLabel("Visible layers:"));
        final JRadioButton radioButtonLayersNone = new JRadioButton(NO_LAYERS_ACTION);
        layerVisibilityButtonGroup.add(radioButtonLayersNone);
        toolBar.add(radioButtonLayersNone);
        final JRadioButton radioButtonLayersSync = new JRadioButton(SYNC_LAYERS_ACTION);
        layerVisibilityButtonGroup.add(radioButtonLayersSync);
        toolBar.add(radioButtonLayersSync);
        final JRadioButton radioButtonLayersAll = new JRadioButton(SURFACE_LAYERS_ACTION);
        layerVisibilityButtonGroup.add(radioButtonLayersAll);
        toolBar.add(radioButtonLayersAll);
        // C1: push the cache checkbox to the right end of the toolbar with a separator before it.
        // D1: the light combobox sits with the cache checkbox on the right side, preceded by a
        // small "Light:" label so it's clear what's being picked. Separator between the two
        // right-side widgets to visually group them.
        toolBar.add(Box.createHorizontalGlue());
        toolBar.addSeparator();
        toolBar.add(new JLabel("Light:"));
        toolBar.add(lightCombo);
        toolBar.addSeparator();
        toolBar.add(cacheCheckBox);
        getContentPane().add(toolBar, BorderLayout.NORTH);

        glassPane = new GlassPane();
        setGlassPane(glassPane);
        getGlassPane().setVisible(true);
        
        ActionMap actionMap = rootPane.getActionMap();
        actionMap.put("rotateLeft", ROTATE_LEFT_ACTION);
        actionMap.put("rotateRight", ROTATE_RIGHT_ACTION);
        actionMap.put("zoomIn", ZOOM_IN_ACTION);
        actionMap.put("resetZoom", RESET_ZOOM_ACTION);
        actionMap.put("zoomOut", ZOOM_OUT_ACTION);

        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        inputMap.put(KeyStroke.getKeyStroke('l'), "rotateLeft");
        inputMap.put(KeyStroke.getKeyStroke('r'), "rotateRight");
        inputMap.put(KeyStroke.getKeyStroke('-'), "zoomOut");
        inputMap.put(KeyStroke.getKeyStroke('0'), "resetZoom");
        inputMap.put(KeyStroke.getKeyStroke('+'), "zoomIn");
        
        setSize(800, 600);
        scaleToUI(this);
        setDefaultSizeAndLocation(this, 60);
        
        setDimension(dimension);
        
        addWindowListener(this);
    }

    public final Dimension getDimension() {
        return dimension;
    }

    public final void setDimension(Dimension dimension) {
        this.dimension = dimension;
        // C1: explicit cleanup of the OLD view before replacing it. setViewportView
        // alone removes it from the scroll pane but doesn't always trigger eager
        // release of its rendered-tile cache in all reachability conditions.
        if (threeDeeView != null) {
            threeDeeView.disposeResources();
        }
        if (dimension != null) {
            threeDeeView = new ThreeDeeView(dimension, colourScheme, customBiomeManager, rotation, zoom, screenLightOrigin);
            threeDeeView.setLayerVisibility(layerVisibility);
            threeDeeView.setHiddenLayers(hiddenLayers);
            threeDeeView.setCachingEnabled(cachingEnabled);
            scrollPane.setViewportView(threeDeeView);
            MOVE_TO_SPAWN_ACTION.setEnabled(dimension.getAnchor().equals((dimension.getWorld().getSpawnPointDimension() == null) ? NORMAL_DETAIL : dimension.getWorld().getSpawnPointDimension()));
            glassPane.setRotation(DIRECTIONS[rotation], dimension.getAnchor().invert);
        }
    }

    /**
     * C1: explicit cleanup before native peer disposal. Frees the rendered-tile
     * cache eagerly so that even if some Swing/AWT internal still holds the
     * ThreeDeeView briefly, the BufferedImages it pinned are already gone.
     */
    @Override
    public void dispose() {
        if (threeDeeView != null) {
            threeDeeView.disposeResources();
        }
        super.dispose();
    }

    public void setHiddenLayers(Set<Layer> hiddenLayers) {
        this.hiddenLayers = hiddenLayers;
        if (threeDeeView != null) {
            threeDeeView.setHiddenLayers(hiddenLayers);
        }
    }

    public void resetAlwaysOnTop() {
        if (isAlwaysOnTop()) {
            setAlwaysOnTop(false);
            alwaysOnTopButton.setSelected(false);
        }
    }

    public void moveTo(Point coords) {
        this.coords = coords;
        threeDeeView.moveTo(coords.x, coords.y);
    }

    public void refresh(boolean clear) {
        if (threeDeeView != null) {
            threeDeeView.refresh(clear);
        }
    }

    private boolean imageFitsInJavaArray(Rectangle imageBounds) {
        final long area = (long) imageBounds.width * imageBounds.height;
        return (area >= 0L) && (area <= Integer.MAX_VALUE);
    }

    private void setLayerVisibility(LayerVisibilityMode layerVisibility) {
        this.layerVisibility = layerVisibility;
        if (threeDeeView != null) {
            threeDeeView.setLayerVisibility(layerVisibility);
        }
    }

    /**
     * D1: change the screen-relative light direction. The first iteration of D1 rebuilt the
     * whole {@link ThreeDeeView} on every pick — that ran the open-time path (force-loads
     * every tile, rebuilds zSortedTiles, ~1.3 GB of allocation per pick) which piled up
     * faster than G1 could collect when the user cycled through values. Now we hot-swap the
     * light direction into the existing view via {@link ThreeDeeView#setScreenLightOrigin},
     * which propagates the new {@code LightOrigin} to every running renderer and triggers
     * a refresh that only re-allocates BufferedImages for tiles in the visible viewport.
     */
    private void applyLightDirection(TileRenderer.LightOrigin newDirection) {
        if (newDirection == null || newDirection == screenLightOrigin) {
            return;
        }
        screenLightOrigin = newDirection;
        lastScreenLightOrigin = newDirection;
        if (threeDeeView != null) {
            threeDeeView.setScreenLightOrigin(newDirection);
        }
    }

    /**
     * D1: label used in the light-direction combobox. Pure screen-space; rotation-invariant.
     * The arrow shows the direction the light <em>travels</em> on the screen; the text names
     * the screen edge the source is on.
     * <p>
     * Why screen-relative names instead of compass: in the iso projection each cardinal
     * world direction (NW/NE/SE/SW) projects to a <em>screen cardinal</em> (top/right/bottom/
     * left), not the diagonal of the same name. So naming the options NW/NE/SE/SW was
     * misleading — they actually behave as top/right/bottom/left light. Plus the GlassPane's
     * "N" arrow rotates with the view, so any world-compass label drifts visually as the
     * user rotates. Cardinal screen labels (top/right/bottom/left) sidestep all of that.
     * <p>
     * Mapping (with the current screen→world rotation in worldLightFor):
     * <ul>
     *   <li>NORTHWEST → source at screen top, rays going down ({@code ↓ From top})</li>
     *   <li>NORTHEAST → source at screen right, rays going left ({@code ← From right})</li>
     *   <li>SOUTHEAST → source at screen bottom, rays going up ({@code ↑ From bottom})</li>
     *   <li>SOUTHWEST → source at screen left, rays going right ({@code → From left})</li>
     *   <li>ABOVE → overhead, no slope shading ({@code Above})</li>
     * </ul>
     */
    private static String lightOriginLabel(TileRenderer.LightOrigin origin) {
        switch (origin) {
            case NORTHWEST: return "↓ From top";
            case NORTHEAST: return "← From right";
            case SOUTHEAST: return "↑ From bottom";
            case SOUTHWEST: return "→ From left";
            case ABOVE:     return "Above";
            default: throw new InternalError();
        }
    }

    // WindowListener

    @Override
    public void windowOpened(WindowEvent e) {
        moveTo(coords);
    }

    @Override public void windowClosing(WindowEvent e) {}
    @Override public void windowClosed(WindowEvent e) {}
    @Override public void windowIconified(WindowEvent e) {}
    @Override public void windowDeiconified(WindowEvent e) {}
    @Override public void windowActivated(WindowEvent e) {}
    @Override public void windowDeactivated(WindowEvent e) {}
    
    private final Action ROTATE_LEFT_ACTION = new BetterAction("rotate3DViewLeft", "Rotate left", ICON_ROTATE_LEFT) {
        {
            setShortDescription("Rotate the view 90 degrees anticlockwise (l)");
        }

        @Override
        public void performAction(ActionEvent e) {
            rotation--;
            if (rotation < 0) {
                rotation = 3;
            }
            final Tile centreMostTile = threeDeeView.getCentreMostTile();
            if (centreMostTile != null) {
                // C1: release the OLD view's caches before swapping in the new one.
                threeDeeView.disposeResources();
                threeDeeView = new ThreeDeeView(dimension, colourScheme, customBiomeManager, rotation, zoom, screenLightOrigin);
                threeDeeView.setLayerVisibility(layerVisibility);
                threeDeeView.setHiddenLayers(hiddenLayers);
                threeDeeView.setCachingEnabled(cachingEnabled);
                scrollPane.setViewportView(threeDeeView);
//                scrollPane.getViewport().setViewPosition(new Point((threeDeeView.getWidth() - scrollPane.getWidth()) / 2, (threeDeeView.getHeight() - scrollPane.getHeight()) / 2));
                threeDeeView.moveToTile(centreMostTile);
                glassPane.setRotation(DIRECTIONS[rotation], dimension.getAnchor().invert);
            }
        }

        private static final long serialVersionUID = 1L;
    };

    private final Action ROTATE_RIGHT_ACTION = new BetterAction("rotate3DViewRight", "Rotate right", ICON_ROTATE_RIGHT) {
        {
            setShortDescription("Rotate the view 90 degrees clockwise (r)");
        }

        @Override
        public void performAction(ActionEvent e) {
            rotation++;
            if (rotation > 3) {
                rotation = 0;
            }
            final Tile centreMostTile = threeDeeView.getCentreMostTile();
            if (centreMostTile != null) {
                // C1: release the OLD view's caches before swapping in the new one.
                threeDeeView.disposeResources();
                threeDeeView = new ThreeDeeView(dimension, colourScheme, customBiomeManager, rotation, zoom, screenLightOrigin);
                threeDeeView.setLayerVisibility(layerVisibility);
                threeDeeView.setHiddenLayers(hiddenLayers);
                threeDeeView.setCachingEnabled(cachingEnabled);
                scrollPane.setViewportView(threeDeeView);
//                scrollPane.getViewport().setViewPosition(new Point((threeDeeView.getWidth() - scrollPane.getWidth()) / 2, (threeDeeView.getHeight() - scrollPane.getHeight()) / 2));
                threeDeeView.moveToTile(centreMostTile);
                glassPane.setRotation(DIRECTIONS[rotation], dimension.getAnchor().invert);
            }
        }

        private static final long serialVersionUID = 1L;
    };

    private final Action EXPORT_IMAGE_ACTION = new BetterAction("export3DViewImage", "Export image", ICON_EXPORT_IMAGE) {
        {
            setShortDescription("Export to an image file");
        }
        
        @Override
        public void performAction(ActionEvent e) {
            final Rectangle imageBounds = threeDeeView.getImageBounds();
            if (! imageFitsInJavaArray(imageBounds)) {
                beepAndShowError(ThreeDeeFrame.this, "The 3D image is too large to export to an image.\nThe area (width x height) may not be more than " + INT_NUMBER_FORMAT.format(Integer.MAX_VALUE), "3D Image Too Large");
                return;
            }
            final String defaultname = dimension.getWorld().getName().replaceAll("\\s", "").toLowerCase() + ((dimension.getAnchor().dim == DIM_NORMAL) ? "" : ("_" + dimension.getName().toLowerCase())) + "_3d.png";
            File selectedFile = ImageUtils.selectImageForSave(ThreeDeeFrame.this, "image file", new File(defaultname));
            if (selectedFile != null) {
                final String type;
                int p = selectedFile.getName().lastIndexOf('.');
                if (p != -1) {
                    type = selectedFile.getName().substring(p + 1).toUpperCase();
                } else {
                    type = "PNG";
                    selectedFile = new File(selectedFile.getParentFile(), selectedFile.getName() + ".png");
                }
                if (selectedFile.exists()) {
                    if (JOptionPane.showConfirmDialog(ThreeDeeFrame.this, "The file already exists!\nDo you want to overwrite it?", "Overwrite File?", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                final File file = selectedFile;
                Boolean result = ProgressDialog.executeTask(ThreeDeeFrame.this, new ProgressTask<Boolean>() {
                        @Override
                        public String getName() {
                            return "Exporting image...";
                        }

                        @Override
                        public Boolean execute(ProgressReceiver progressReceiver) throws OperationCancelled {
                            try {
                                return ImageIO.write(threeDeeView.getImage(imageBounds, progressReceiver), type, file);
                            } catch (IOException e) {
                                throw new RuntimeException("I/O error while exporting image", e);
                            }
                        }
                    });
                if ((result != null) && result.equals(Boolean.FALSE)) {
                    JOptionPane.showMessageDialog(ThreeDeeFrame.this, "Format " + type + " not supported!");
                }
            }
        }
        
        private static final long serialVersionUID = 1L;
    };
    
    private final Action MOVE_TO_SPAWN_ACTION = new BetterAction("move3DViewToSpawn", "Move to spawn", ICON_MOVE_TO_SPAWN) {
        {
            setShortDescription("Move the view to the spawn location");
        }
        
        @Override
        public void performAction(ActionEvent e) {
            if (dimension.getAnchor().dim == DIM_NORMAL) {
                Point spawn = dimension.getWorld().getSpawnPoint();
                threeDeeView.moveTo(spawn.x, spawn.y);
            }
        }
        
        private static final long serialVersionUID = 1L;
    };
    
    private final Action MOVE_TO_ORIGIN_ACTION = new BetterAction("move3DViewToOrigin", "Move to origin", ICON_MOVE_TO_ORIGIN) {
        {
            setShortDescription("Move the view to the origin (coordinates 0,0)");
        }
        
        @Override
        public void performAction(ActionEvent e) {
            threeDeeView.moveTo(0, 0);
        }
        
        private static final long serialVersionUID = 1L;
    };
    
    private final Action ZOOM_IN_ACTION = new BetterAction("zoom3DViewIn", "Zoom in", ICON_ZOOM_IN) {
        {
            setShortDescription("Zoom in");
        }
        
        @Override
        public void performAction(ActionEvent e) {
            final Rectangle visibleRect = threeDeeView.getVisibleRect();
            zoom++;
            threeDeeView.setZoom(zoom);
            visibleRect.x *= 2;
            visibleRect.y *= 2;
            visibleRect.x += visibleRect.width / 2;
            visibleRect.y += visibleRect.height / 2;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    threeDeeView.scrollRectToVisible(visibleRect);
                }
            });
            if (zoom >= MAX_ZOOM) {
                setEnabled(false);
            }
            ZOOM_OUT_ACTION.setEnabled(true);
            RESET_ZOOM_ACTION.setEnabled(zoom != 1);
        }
        
        private static final long serialVersionUID = 1L;
    };
    
    private final Action RESET_ZOOM_ACTION = new BetterAction("reset3DViewZoom", "Reset zoom", ICON_RESET_ZOOM) {
        {
            setShortDescription("Reset the zoom level to 1:1");
            setEnabled(false);
        }
        
        @Override
        public void performAction(ActionEvent e) {
            final Rectangle visibleRect = threeDeeView.getVisibleRect();
            if (zoom < 1) {
                while (zoom < 1) {
                    zoom++;
                    visibleRect.x *= 2;
                    visibleRect.y *= 2;
                    visibleRect.x += visibleRect.width / 2;
                    visibleRect.y += visibleRect.height / 2;
                }
                threeDeeView.setZoom(zoom);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        threeDeeView.scrollRectToVisible(visibleRect);
                    }
                });
            } else if (zoom > 1) {
                while (zoom > 1) {
                    zoom--;
                    visibleRect.x /= 2;
                    visibleRect.y /= 2;
                    visibleRect.x -= visibleRect.width / 4;
                    visibleRect.y -= visibleRect.height / 4;
                }
                threeDeeView.setZoom(zoom);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        threeDeeView.scrollRectToVisible(visibleRect);
                    }
                });
            }
            ZOOM_IN_ACTION.setEnabled(true);
            ZOOM_OUT_ACTION.setEnabled(true);
            setEnabled(false);
        }
        
        private static final long serialVersionUID = 1L;
    };
    
    private final Action ZOOM_OUT_ACTION = new BetterAction("zoom3DViewOut", "Zoom out", ICON_ZOOM_OUT) {
        {
            setShortDescription("Zoom out");
        }
        
        @Override
        public void performAction(ActionEvent e) {
            final Rectangle visibleRect = threeDeeView.getVisibleRect();
            zoom--;
            threeDeeView.setZoom(zoom);
            visibleRect.x /= 2;
            visibleRect.y /= 2;
            visibleRect.x -= visibleRect.width / 4;
            visibleRect.y -= visibleRect.height / 4;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    threeDeeView.scrollRectToVisible(visibleRect);
                }
            });
            if (zoom <= MIN_ZOOM) {
                setEnabled(false);
            }
            ZOOM_IN_ACTION.setEnabled(true);
            RESET_ZOOM_ACTION.setEnabled(zoom != 1);
        }
        
        private static final long serialVersionUID = 1L;
    };

    private final Action NO_LAYERS_ACTION = new BetterAction("layerVisibilityNone", "None") {
        {
            setShortDescription("Show no layers");
        }

        @Override
        protected void performAction(ActionEvent e) {
            setLayerVisibility(NONE);
        }
    };

    private final Action SYNC_LAYERS_ACTION = new BetterAction("layerVisibilitySync", "Sync") {
        {
            setShortDescription("Synchronise layer visibility with editor");
        }

        @Override
        protected void performAction(ActionEvent e) {
            setLayerVisibility(SYNC);
        }
    };

    private final Action SURFACE_LAYERS_ACTION = new BetterAction("layerVisibilitySurface", "Surface") {
        {
            setShortDescription("Show all above-ground layers");
            setSelected(true);
        }

        @Override
        protected void performAction(ActionEvent e) {
            setLayerVisibility(SURFACE);
        }
    };

    private final JScrollPane scrollPane;
    private final GlassPane glassPane;
    private final CustomBiomeManager customBiomeManager;
    private final ButtonGroup layerVisibilityButtonGroup = new ButtonGroup();
    final JToggleButton alwaysOnTopButton = new JToggleButton(ICON_ALWAYS_ON_TOP);
    /** C1: toolbar checkbox for the tile-cache optimisations on the active ThreeDeeView. */
    final JCheckBox cacheCheckBox = new JCheckBox("Cache tiles", lastCachingEnabled);
    /** D1: toolbar combobox for the screen-relative light direction. */
    final JComboBox<TileRenderer.LightOrigin> lightCombo = new JComboBox<>(LIGHT_ORIGIN_VALUES);
    private Dimension dimension;
    private ThreeDeeView threeDeeView;
    private ColourScheme colourScheme;
    private int rotation = 3, zoom = 1;
    private Point coords;
    private LayerVisibilityMode layerVisibility = SURFACE;
    private Set<Layer> hiddenLayers;
    /** C1: master cache-on flag mirrored to every new ThreeDeeView created by this frame. */
    private boolean cachingEnabled = lastCachingEnabled;
    /** D1: current screen-relative light direction, propagated into each new ThreeDeeView. */
    private TileRenderer.LightOrigin screenLightOrigin = lastScreenLightOrigin;

    /**
     * C1: last value of {@link #cachingEnabled} from any ThreeDeeFrame this JVM has shown.
     * New frames pick up this value so the checkbox state persists across close + reopen.
     * In-memory only; not written to Configuration (3D view caching is a session preference,
     * not a saved one).
     */
    private static boolean lastCachingEnabled = true;

    /**
     * D1: last screen-relative light direction picked by the user in any ThreeDeeFrame this
     * JVM has shown. New frames pick up this value so the choice persists across close +
     * reopen. In-memory only; not written to Configuration. (The 2D view has its own light
     * direction in {@code Configuration.defaultLightOrigin}; we deliberately keep the 3D
     * pick separate because the 3D view's light is screen-relative whereas the 2D one is
     * world-relative — semantically different.)
     */
    private static TileRenderer.LightOrigin lastScreenLightOrigin = TileRenderer.LightOrigin.NORTHWEST;
    
    private static final Direction[] DIRECTIONS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
    /** D1: cached {@link TileRenderer.LightOrigin} values for the combobox model. */
    private static final TileRenderer.LightOrigin[] LIGHT_ORIGIN_VALUES = TileRenderer.LightOrigin.values();
    
    private static final Icon ICON_ROTATE_LEFT    = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_rotate_anticlockwise.png");
    private static final Icon ICON_ROTATE_RIGHT   = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_rotate_clockwise.png");
    private static final Icon ICON_EXPORT_IMAGE   = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/picture_save.png");
    private static final Icon ICON_MOVE_TO_SPAWN  = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/spawn_red.png");
    private static final Icon ICON_MOVE_TO_ORIGIN = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_in.png");
    private static final Icon ICON_ALWAYS_ON_TOP  = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/lock.png");
    private static final Icon ICON_ZOOM_IN        = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/magnifier_zoom_in.png");
    private static final Icon ICON_RESET_ZOOM     = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/magnifier.png");
    private static final Icon ICON_ZOOM_OUT       = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/magnifier_zoom_out.png");
    
    private static final int MIN_ZOOM = -2;
    private static final int MAX_ZOOM = 4;
    
    private static final long serialVersionUID = 1L;
}