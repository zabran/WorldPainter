/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.threedeeview;

import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Void;
import org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode;

import javax.swing.Timer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;

import static java.awt.image.BufferedImage.TYPE_BYTE_BINARY;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static org.pepsoft.minecraft.Constants.DEFAULT_WATER_LEVEL;
import static org.pepsoft.util.AwtUtils.doOnEventThread;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode.SURFACE;
import static org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode.SYNC;

/**
 *
 * @author pepijn
 */
public class ThreeDeeView extends JComponent implements Dimension.Listener, Tile.Listener, HierarchyListener, ActionListener, Scrollable {
    public ThreeDeeView(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, int rotation, int zoom) {
        this(dimension, colourScheme, customBiomeManager, rotation, zoom, TileRenderer.LightOrigin.NORTHWEST);
    }

    /**
     * D1: full constructor taking a <em>screen-relative</em> {@link TileRenderer.LightOrigin}.
     * The view stores it to thread through to both the live render manager and the one-off
     * Tile3DRenderer used by {@link #getImage} when exporting the view as a still image —
     * so the export looks like what's on screen.
     */
    public ThreeDeeView(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, int rotation, int zoom, TileRenderer.LightOrigin screenLightOrigin) {
        this.dimension = dimension;
        this.colourScheme = colourScheme;
        this.customBiomeManager = customBiomeManager;
        this.rotation = rotation;
        this.zoom = zoom;
        this.screenLightOrigin = (screenLightOrigin != null) ? screenLightOrigin : TileRenderer.LightOrigin.NORTHWEST;
        scale = (int) Math.pow(2.0, Math.abs(zoom - 1));
//        System.out.println("Zoom " + zoom + " -> scale " + scale);
        minHeight = dimension.getMinHeight();
        maxHeight = dimension.getMaxHeight();
        if (dimension.getTileFactory() instanceof HeightMapTileFactory) {
            waterLevel = ((HeightMapTileFactory) dimension.getTileFactory()).getWaterHeight();
        } else {
            waterLevel = DEFAULT_WATER_LEVEL;
        }
        upsideDown = dimension.getAnchor().invert; // Ceiling dimension
        zSortedTiles = new TreeMap<>();
        // C1: single pass over dimension.getTiles() instead of two. Under B-mode (lazy paging)
        // each pass force-loads every region; doing it twice doubled the open-time RAM cost.
        // We also track the tiles we're listening to in `listenedTiles` so the close path can
        // detach listeners without re-iterating dimension.getTiles() (another force-load).
        for (Tile tile: dimension.getTiles()) {
            final Rectangle tileBaseBounds = getTileBounds(tile.getX(), tile.getY(), 0, 0, 0);
            zSortedTiles.computeIfAbsent(tileBaseBounds.y, y -> new TreeMap<>()).put(tileBaseBounds.x, tile);
            tile.addListener(this);
            listenedTiles.add(tile);
        }
        threeDeeRenderManager = new ThreeDeeRenderManager(dimension, colourScheme, customBiomeManager, rotation, this.screenLightOrigin);

        dimension.addDimensionListener(this);

        int width = dimension.getWidth() * TILE_SIZE + dimension.getHeight() * TILE_SIZE;
        int height = width / 2 + maxHeight - minHeight - 1;
//        maxX = dimension.getHighestX();
//        maxY = dimension.getHighestY();
        maxX = maxY = 0;
//        xOffset = 512;
//        yOffset = 256;
//        xOffset = yOffset = 0;
        switch (rotation) {
            case 0:
                xOffset = -getTileBounds(dimension.getLowestX(), dimension.getHighestY(), maxHeight).x;
                yOffset = -getTileBounds(dimension.getLowestX(), dimension.getLowestY(), maxHeight).y;
                break;
            case 1:
                xOffset = -getTileBounds(dimension.getHighestX(), dimension.getHighestY(), maxHeight).x;
                yOffset = -getTileBounds(dimension.getLowestX(), dimension.getHighestY(), maxHeight).y;
                break;
            case 2:
                xOffset = -getTileBounds(dimension.getHighestX(), dimension.getLowestY(), maxHeight).x;
                yOffset = -getTileBounds(dimension.getHighestX(), dimension.getHighestY(), maxHeight).y;
                break;
            case 3:
                xOffset = -getTileBounds(dimension.getLowestX(), dimension.getLowestY(), maxHeight).x;
                yOffset = -getTileBounds(dimension.getHighestX(), dimension.getLowestY(), maxHeight).y;
                break;
            default:
                throw new IllegalArgumentException();
        }
//        System.out.println("xOffset: " + xOffset + ", yOffset: " + yOffset);
        java.awt.Dimension preferredSize = zoom(new java.awt.Dimension(width, height));
        setPreferredSize(preferredSize);
        setMinimumSize(preferredSize);
        setMaximumSize(preferredSize);
        setSize(preferredSize);

        addHierarchyListener(this);
    }

    public RefreshMode getRefreshMode() {
        return refreshMode;
    }

    public void setRefreshMode(RefreshMode refreshMode) {
        this.refreshMode = refreshMode;
    }

    public Rectangle getImageBounds() {
        Rectangle imageBounds = null;
        for (Map<Integer, Tile> row: zSortedTiles.values()) {
            for (Tile tile: row.values()) {
                if (imageBounds == null) {
                    imageBounds = getTileBounds(tile);
                } else {
                    imageBounds = imageBounds.union(getTileBounds(tile));
                }
            }
        }
        return imageBounds;
    }

    public BufferedImage getImage(Rectangle imageBounds, ProgressReceiver progressReceiver) throws ProgressReceiver.OperationCancelled {
        final Tile3DRenderer renderer = new Tile3DRenderer(dimension, colourScheme, customBiomeManager, rotation, layerVisibility, hiddenLayers, screenLightOrigin);

        // Paint the complete image
        final int tileCount = zSortedTiles.values().stream().mapToInt(Map::size).sum();
        final BufferedImage image = new BufferedImage(imageBounds.width, imageBounds.height, TYPE_INT_ARGB);
        final Graphics2D g2 = image.createGraphics();
        try {
            int tileNo = 0;
            for (Map<Integer, Tile> row: zSortedTiles.values()) {
                for (Tile tile: row.values()) {
                    final Rectangle tileBounds = getTileBounds(tile);
                    g2.drawImage(renderer.render(tile), tileBounds.x - imageBounds.x, tileBounds.y - imageBounds.y, null);
                    if (progressReceiver != null) {
                        tileNo++;
                        progressReceiver.setProgress((float) tileNo / tileCount);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
        return image;
    }

    public Point worldToView(int x, int y) {
//        highlightTile = new Point(x >> 7, y >> 7);
        switch (rotation) {
            case 0:
                return zoom(new Point(xOffset + TILE_SIZE + x - y, yOffset + maxHeight - minHeight - 1 - TILE_SIZE / 2 + (y + x) / 2));
            case 1:
                return zoom(new Point(xOffset + TILE_SIZE * 2 - x - y, yOffset + maxHeight - minHeight - 1 - (y - x) / 2));
            case 2:
                return zoom(new Point(xOffset + TILE_SIZE - x + y, yOffset + maxHeight - minHeight - 1 + TILE_SIZE / 2 - (y + x) / 2));
            case 3:
                return zoom(new Point(xOffset + x + y, yOffset + maxHeight - minHeight - 1 + (y - x) / 2));
            default:
                throw new IllegalArgumentException();
        }
    }
    
    public Tile getCentreMostTile() {
        return centreTile;
    }

    public Point getHighlightTile() {
        return highlightTile;
    }

    public void setHighlightTile(Point highlightTile) {
        this.highlightTile = highlightTile;
        repaint();
    }

    public int getZoom() {
        return zoom;
    }

    public void setZoom(int zoom) {
        this.zoom = zoom;
        scale = (int) Math.pow(2.0, Math.abs(zoom - 1));
//        System.out.println("Zoom " + zoom + " -> scale " + scale);
        int width = dimension.getWidth() * TILE_SIZE + dimension.getHeight() * TILE_SIZE;
        int height = width / 2 + maxHeight - minHeight - 1;
        java.awt.Dimension preferredSize = zoom(new java.awt.Dimension(width, height));
        setPreferredSize(preferredSize);
        setMinimumSize(preferredSize);
        setMaximumSize(preferredSize);
        setSize(preferredSize);
        repaint();
    }

    /**
     * Whether the C1 tile-cache optimisations are active. When true (default), the
     * paint path keeps rendered tiles in memory across paints, schedules off-screen
     * margin tiles for pre-render, and caches tile bounds. When false, all caches
     * are flushed and the paint path falls back to the upstream behaviour: tiles
     * are rendered on demand only, no pre-render, no bounds cache. Useful as an
     * escape hatch if caching ever misbehaves on a particular world.
     */
    public boolean isCachingEnabled() {
        return cachingEnabled;
    }

    public void setCachingEnabled(boolean cachingEnabled) {
        if (this.cachingEnabled != cachingEnabled) {
            this.cachingEnabled = cachingEnabled;
            if (! cachingEnabled) {
                // Trim the rendered-tile cache down to the bounded cap. Keeps the most
                // recently inserted tiles (LinkedHashMap insertion order ≈ recency of
                // render for a panning user), so the visible viewport usually survives
                // and only "stale" off-screen tiles are evicted. Going forward, new
                // inserts will trigger LinkedHashMap's removeEldestEntry hook to keep
                // the map at the cap.
                final int before = renderedTiles.size();
                trimRenderedTilesToCap();
                flushAndClear(dirtyTiles);
                tilesWaitingToBeRendered.clear();
                dimension.trimResidentTilesToBudget();
                if (logger.isInfoEnabled()) {
                    logger.info("ThreeDeeView.setCachingEnabled(false): trimmed renderedTiles {} -> {} (cap {})",
                            before, renderedTiles.size(), BOUNDED_CACHE_TILES);
                }
                repaint();
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("ThreeDeeView.setCachingEnabled(true): unlimited cache resumes (currently {} entries)",
                            renderedTiles.size());
                }
            }
        }
    }

    /**
     * Evict oldest LinkedHashMap entries from renderedTiles until size <= BOUNDED_CACHE_TILES.
     * Called on toggle-off; the {@code removeEldestEntry} override handles steady-state
     * eviction after that.
     */
    private void trimRenderedTilesToCap() {
        if (renderedTiles.size() <= BOUNDED_CACHE_TILES) {
            return;
        }
        final Iterator<Map.Entry<Tile, BufferedImage>> it = renderedTiles.entrySet().iterator();
        while (it.hasNext() && renderedTiles.size() > BOUNDED_CACHE_TILES) {
            final Map.Entry<Tile, BufferedImage> e = it.next();
            final BufferedImage img = e.getValue();
            if (img != null && img != TILE_NOT_RENDERABLE) {
                img.flush();
            }
            it.remove();
        }
    }

    public void setLayerVisibility(LayerVisibilityMode layerVisibility) {
        if (layerVisibility != this.layerVisibility) {
            this.layerVisibility = layerVisibility;
            threeDeeRenderManager.setLayerVisibility(layerVisibility);
            refresh(false);
        }
    }

    public void setHiddenLayers(Set<Layer> hiddenLayers) {
        if (! Objects.equals(hiddenLayers, this.hiddenLayers)) {
            this.hiddenLayers = hiddenLayers;
            threeDeeRenderManager.setHiddenLayers(hiddenLayers);
            if (layerVisibility == SYNC) {
                refresh(false);
            }
        }
    }

    /**
     * Centre the view on a particular tile. Specifically, centre the view on a
     * point <em>waterLevel</em> above the centre of the floor of the tile.
     * 
     * @param tile The tile to centre the view on.
     */
    public void moveToTile(Tile tile) {
        Rectangle tileBounds = zoom(getTileBounds(tile));
        moveTo(new Point(tileBounds.x + tileBounds.width / 2, tileBounds.y + tileBounds.height - TILE_SIZE / 2));
//        highlightTile = new Point(tileX, tileY);
    }
    
    /**
     * Centre the view on a particular point in the world. Specifically, centre
     * the view on a point <em>waterLevel</em> above the floor of the world at
     * the specified coordinates.
     * 
     * @param x The X coordinate in blocks on which to centre the view.
     * @param y The Y coordinate in blocks on which to centre the view. 
     */
    public void moveTo(int x, int y) {
        Point coords = worldToView(x, y);
//        highlightPoint = coords;
        moveTo(coords);
    }

    public void refresh(boolean clear) {
        threeDeeRenderManager.stop();
        if (clear) {
            renderedTiles.clear();
            dirtyTiles.clear();
        } else {
            dirtyTiles.putAll(renderedTiles);
            renderedTiles.clear();
        }
        repaint();
    }

    /**
     * D1: hot-swap the screen-relative light direction without rebuilding the whole view.
     * Updates the render manager (which propagates to running threads via Tile3DRenderer's
     * volatile field) and then triggers refresh so previously-rendered tiles re-render with
     * the new direction. Much cheaper than the rebuild path used for rotation: avoids
     * re-iterating {@code dimension.getTiles()} (which under B-mode force-loads every region),
     * keeps the bounds cache, keeps listenedTiles, keeps zSortedTiles.
     *
     * Per-cycle allocation drops from ~1.3 GB (full rebuild including a fresh ThreeDeeView
     * and 1824 BufferedImage renders) to roughly the size of the visible viewport's worth
     * of fresh BufferedImages — ~30 to 200 tiles × 360 KB.
     */
    public void setScreenLightOrigin(TileRenderer.LightOrigin newScreenLightOrigin) {
        if (newScreenLightOrigin == null || newScreenLightOrigin == this.screenLightOrigin) {
            return;
        }
        this.screenLightOrigin = newScreenLightOrigin;
        threeDeeRenderManager.setScreenLightOrigin(newScreenLightOrigin);
        // Re-render everything currently cached. refresh(false) moves the existing renders
        // to dirtyTiles so the user sees the (stale-lit) old tiles during the re-render
        // instead of empty space — same UX as a 2D light-direction rotate.
        refresh(false);
    }

    public TileRenderer.LightOrigin getScreenLightOrigin() {
        return screenLightOrigin;
    }
    
    // Dimension.Listener
    
    @Override
    public void tilesAdded(Dimension dimension, Set<Tile> tiles) {
//        threeDeeRenderManager.renderTile(tile);
        tiles.forEach(tile -> {
            final Rectangle tileBaseBounds = getTileBounds(tile.getX(), tile.getY(), 0, 0, 0);
            zSortedTiles.computeIfAbsent(tileBaseBounds.y, y -> new TreeMap<>()).put(tileBaseBounds.x, tile);
            tile.addListener(this);
            listenedTiles.add(tile);
        });
    }

    @Override
    public void tilesRemoved(Dimension dimension, Set<Tile> tiles) {
        for (Tile tile: tiles) {
            tile.removeListener(this);
            listenedTiles.remove(tile);
            final Rectangle tileBaseBounds = getTileBounds(tile.getX(), tile.getY(), 0, 0, 0);
            if (zSortedTiles.containsKey(tileBaseBounds.y)) {
                zSortedTiles.get(tileBaseBounds.y).remove(tileBaseBounds.x);
            }
            tileBoundsCache.remove(tile);
        }
//        renderedTiles.remove(new Point(tile.getX(), tile.getY()));
        // TODO: the tile will be re-added if it was on the render queue, but
        // since this can currently never happen anyway we will deal with that
        // when it becomes necessary
    }

    @Override public void overlayAdded(Dimension dimension, int index, Overlay overlay) {}
    @Override public void overlayRemoved(Dimension dimension, int index, Overlay overlay) {}

    // Tile.Listener
    
    @Override
    public void heightMapChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    @Override
    public void terrainChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    @Override
    public void waterLevelChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    @Override
    public void seedsChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    @Override
    public void layerDataChanged(Tile tile, Set<Layer> changedLayers) {
        for (Layer layer: changedLayers) {
            if (((layerVisibility == SURFACE) && (! Tile3DRenderer.DEFAULT_HIDDEN_LAYERS.contains(layer)))
                    || (layerVisibility == SYNC)
                    || (layer instanceof Void)) {
                scheduleTileForRendering(tile);
                return;
            }
        }
    }

    @Override
    public void allBitLayerDataChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    @Override
    public void allNonBitlayerDataChanged(Tile tile) {
        tileBoundsCache.remove(tile);
        scheduleTileForRendering(tile);
    }

    // HierarchyListener

    @Override
    public void hierarchyChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
            if (isDisplayable()) {
//                for (Tile tile: dimension.getTiles()) {
//                    threeDeeRenderManager.renderTile(tile);
//                }
                timer = new Timer(250, this);
                timer.start();
            } else {
                disposeResources();
            }
        }
    }

    /**
     * Eagerly release all heavy caches and detach listeners. Safe to call multiple
     * times. Called from hierarchyChanged when the view becomes non-displayable
     * (e.g. frame closed), and may also be called explicitly by ThreeDeeFrame
     * before disposal.
     *
     * The 3D view's open path bulk-loads every tile in the dimension twice (once for
     * zSortedTiles, once to attach listeners — now combined into a single pass).
     * Under B-mode (lazy region paging) those loads fill the resident map past the
     * configured budget; eviction only runs on subsequent loads, so the resident
     * stays fat until something forces a miss. This method:
     *   1. Stops the timer and the render manager (no more async work scheduled).
     *   2. Detaches Tile.Listener and Dimension.Listener using our own
     *      {@code listenedTiles} set, NOT {@code dimension.getTiles()} — the
     *      latter would itself force-load every tile, redoing the open-time cost
     *      on close.
     *   3. Flushes every cached BufferedImage to release its accelerated pixel
     *      backing on platforms where that's separate from the Java heap.
     *   4. Clears every internal cache so the view stops pinning Tile references.
     *   5. Asks the dimension to trim its resident-region set back to the budget
     *      now that the 3D view's strong references are gone. Without this the
     *      ~1.7 GB of tile data we bulk-loaded would sit in B-mode's resident map
     *      until the next eviction trigger.
     */
    public void disposeResources() {
        final int beforeRendered = renderedTiles.size();
        final int beforeListened = listenedTiles.size();
        final int beforeBounds = tileBoundsCache.size();
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        threeDeeRenderManager.stop();
        for (Tile tile : listenedTiles) {
            tile.removeListener(this);
        }
        listenedTiles.clear();
        dimension.removeDimensionListener(this);
        flushAndClear(renderedTiles);
        flushAndClear(dirtyTiles);
        tilesWaitingToBeRendered.clear();
        tileBoundsCache.clear();
        zSortedTiles.clear();
        // Trim B-mode resident back to budget. No-op in eager mode.
        dimension.trimResidentTilesToBudget();
        if (logger.isInfoEnabled()) {
            logger.info("ThreeDeeView.disposeResources: freed {} rendered tiles, {} listened tiles, {} cached bounds",
                    beforeRendered, beforeListened, beforeBounds);
        }
    }

    private static void flushAndClear(Map<Tile, BufferedImage> map) {
        for (BufferedImage img : map.values()) {
            if (img != null && img != TILE_NOT_RENDERABLE) {
                img.flush();
            }
        }
        map.clear();
    }

    // ActionListener
    
    @Override
    public void actionPerformed(ActionEvent e) {
        // Send tiles to be rendered
        if ((! tilesWaitingToBeRendered.isEmpty()) && ((System.currentTimeMillis() - lastTileChange) > 250)) {
            tilesWaitingToBeRendered.forEach(threeDeeRenderManager::renderTile);
            tilesWaitingToBeRendered.clear();
        }

        // Collect rendered tiles
        final Set<RenderResult> renderResults = threeDeeRenderManager.getRenderedTiles();
        Rectangle repaintArea = null;
        for (RenderResult renderResult : renderResults) {
            final Tile tile = renderResult.getTile();
            renderedTiles.put(tile, renderResult.getImage());
            dirtyTiles.remove(tile);
            final Rectangle tileBounds = zoom(getTileBounds(tile));
            if (repaintArea == null) {
                repaintArea = tileBounds;
            } else {
                repaintArea = repaintArea.union(tileBounds);
            }
        }
        if (repaintArea != null) {
//            System.out.println("Repainting " + repaintArea);
            repaint(repaintArea);
        }
    }

    // Scrollable
    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 10;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 100;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return false;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
//        System.out.println("Drawing");
        final Graphics2D g2 = (Graphics2D) g;
        if (zoom != 1) {
            final double scaleFactor = Math.pow(2.0, zoom - 1);
//            System.out.println("Scaling with factor " + scaleFactor);
            g2.scale(scaleFactor, scaleFactor);
            if (zoom > 1) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            } else {
                // C1: BILINEAR for downscale instead of BICUBIC. Bicubic samples 16 source
                // pixels per output pixel (vs 4 for bilinear) and Java2D's bicubic on
                // shrink doesn't pre-filter, so it's surprisingly slow at zoom -2 with
                // many tiles on screen. Bilinear visually fine for Minecraft tile art.
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
        }
        if (upsideDown) {
            g2.scale(1.0, -1.0);
            g2.translate(0, -getHeight());
        }
        final Rectangle visibleRect = unzoom(getVisibleRect());
//        System.out.println("Unzoomed visible rectangle: " + visibleRect);
        final int centerX = visibleRect.x + visibleRect.width / 2;
        final int centerY = visibleRect.y + visibleRect.height / 2 + waterLevel;
        Tile mostCentredTile = null;
        int smallestDistance = Integer.MAX_VALUE;
        final Rectangle clipBounds = g2.getClipBounds();

        for (SortedMap<Integer, Tile> row: zSortedTiles.subMap(clipBounds.y - yOffset - maxHeight, clipBounds.y + clipBounds.height - yOffset + maxHeight).values()) {
            for (Tile tile: row.subMap(clipBounds.x - xOffset - TILE_SIZE * 2, clipBounds.x + clipBounds.width - xOffset).values()) {
                final Rectangle tileBounds = getTileBounds(tile);
//                System.out.print("Tile bounds: " + tileBounds);
                if (tileBounds.intersects(clipBounds)) {
//                    System.out.println(" intersects");
                    final int dx = tileBounds.x + tileBounds.width / 2 - centerX;
                    final int dy = tileBounds.y + tileBounds.height - TILE_SIZE / 2 - centerY;
                    final int dist = (int) Math.sqrt((dx * dx) + (dy * dy));
                    if (dist < smallestDistance) {
                        smallestDistance = dist;
                        mostCentredTile = tile;
                    }
                    BufferedImage tileImg = renderedTiles.get(tile);
                    if (tileImg == null) {
                        if (cachingEnabled) {
                            // C1: schedule immediately instead of waiting for the 250 ms Timer.
                            // UniqueJobQueue dedupes, so re-paints during a drag are cheap.
                            threeDeeRenderManager.renderTile(tile);
                        } else {
                            // Upstream behaviour: queue for the Timer to drain.
                            tilesWaitingToBeRendered.add(0, tile);
                        }
                        tileImg = dirtyTiles.get(tile);
                    }
                    if (tileImg != null) {
                        if (tileImg != TILE_NOT_RENDERABLE) {
                            g2.drawImage(tileImg, tileBounds.x, tileBounds.y, null);
                        } else {
                            g2.setColor(Color.RED);
                            g2.drawRect(tileBounds.x, tileBounds.y, tileBounds.width, tileBounds.height);
                            g2.drawLine(tileBounds.x, tileBounds.y, tileBounds.x + tileBounds.width, tileBounds.y + tileBounds.height);
                            g2.drawLine(tileBounds.x + tileBounds.width, tileBounds.y, tileBounds.x, tileBounds.y + tileBounds.height);
                        }
                    }
//                } else {
//                    System.out.println(" does NOT intersect");
                }
            }
        }
        if (mostCentredTile != null) {
            centreTile = mostCentredTile;
        }

        // C1: pre-render an off-screen margin so panning into fresh tiles
        // finds them already rendered (or at least already queued). UniqueJobQueue
        // dedupes, so re-walking on every paint during a drag is cheap.
        //
        // Gated to zoom == 1: when zoomed out, the margin (in user-space pixels) is
        // a small device-space band but covers a large world area — the visible-tile
        // loop above already queues lots of tiles, and adding margin there floods
        // the render queue without clear benefit. When zoomed in, the margin is much
        // larger than necessary. Revisit if smarter scale-aware pre-rendering
        // becomes worth the complexity.
        //
        // Also gated on cachingEnabled so the toolbar toggle can disable pre-render
        // entirely as an escape hatch.
        if (cachingEnabled && zoom == 1) {
            final Rectangle marginBounds = new Rectangle(
                    clipBounds.x - PRE_RENDER_MARGIN_PX,
                    clipBounds.y - PRE_RENDER_MARGIN_PX,
                    clipBounds.width + 2 * PRE_RENDER_MARGIN_PX,
                    clipBounds.height + 2 * PRE_RENDER_MARGIN_PX);
            for (SortedMap<Integer, Tile> row: zSortedTiles.subMap(marginBounds.y - yOffset - maxHeight, marginBounds.y + marginBounds.height - yOffset + maxHeight).values()) {
                for (Tile tile: row.subMap(marginBounds.x - xOffset - TILE_SIZE * 2, marginBounds.x + marginBounds.width - xOffset).values()) {
                    if (renderedTiles.containsKey(tile)) {
                        continue;
                    }
                    final Rectangle tileBounds = getTileBounds(tile);
                    if (! tileBounds.intersects(clipBounds) && tileBounds.intersects(marginBounds)) {
                        threeDeeRenderManager.renderTile(tile);
                    }
                }
            }
        }

        if (highlightTile != null) {
            g2.setColor(Color.RED);
            Rectangle rect = getTileBounds(highlightTile.x, highlightTile.y, maxHeight);
            g2.drawRect(rect.x, rect.y, rect.width, rect.height);
        }
        if (highlightPoint != null) {
            g2.setColor(Color.RED);
            g2.drawLine(highlightPoint.x - 2, highlightPoint.y, highlightPoint.x + 2, highlightPoint.y);
            g2.drawLine(highlightPoint.x, highlightPoint.y - 2, highlightPoint.x, highlightPoint.y + 2);
        }
//        for (Map.Entry<Point, BufferedImage> entry: renderedTiles.entrySet()) {
//            Point tileCoords = entry.getKey();
//            BufferedImage tileImg = entry.getValue();
//            Rectangle tileBounds = getTileBounds(tileCoords.x, tileCoords.y);
//            if (tileBounds.intersects(clipBounds)) {
//                g2.drawImage(tileImg, tileBounds.x, tileBounds.y, null);
////                g2.setColor(Color.RED);
////                g2.drawRect(tileBounds.x, tileBounds.y, tileBounds.width, tileBounds.height);
//            }
//        }
    }

    private void scheduleTileForRendering(final Tile tile) {
//        System.out.println("Scheduling tile for rendering: " + tile.getX() + ", " + tile.getY());
        final JViewport parent = (JViewport) getParent();
        if (parent == null) {
            // This has been observed in the wild; possible after the 3D view has been removed from the 3D frame?
            return;
        }
        doOnEventThread(() -> {
            final Rectangle visibleArea = parent.getViewRect();
            final Rectangle tileBounds = zoom(getTileBounds(tile));
            if (tileBounds.intersects(visibleArea)) {
                // The tile is (partially) visible, so it should be repainted immediately
                switch (refreshMode) {
                    case IMMEDIATE:
                        threeDeeRenderManager.renderTile(tile);
                        break;
                    case DELAYED:
                        tilesWaitingToBeRendered.add(tile);
                        lastTileChange = System.currentTimeMillis();
                        break;
                    case MANUAL:
                        // Do nothing
                        break;
                    default:
                        throw new InternalError();
                }
            } else {
                // The tile is not visible, so repaint it when it becomes visible
                tilesWaitingToBeRendered.remove(tile);
                renderedTiles.remove(tile);
            }
        });
    }

    private Rectangle getTileBounds(final Tile tile) {
        // C1: cache per-tile bounds. Computing them touches tile.getHighestIntHeight()
        // / getHighestWaterLevel(), which under B-mode (lazy paging) may trigger an
        // archive read. Caching keeps panning off-hot-path; entries are invalidated
        // in the height-affecting Tile.Listener events below.
        //
        // IMPORTANT: this returns a defensive copy because zoom(Rectangle) mutates
        // its argument in place. Callers like actionPerformed/scheduleTileForRendering
        // do `zoom(getTileBounds(tile))`; without the copy the cached Rectangle would
        // be repeatedly scaled and break drawing as soon as zoom != 1.
        //
        // Gated on cachingEnabled so the toolbar toggle can fall back to the original
        // compute-every-time behaviour.
        if (! cachingEnabled) {
            return getTileBounds(tile.getX(), tile.getY(), Math.max(tile.getHighestIntHeight(), tile.getHighestWaterLevel()) + 1);
        }
        Rectangle cached = tileBoundsCache.get(tile);
        if (cached == null) {
            cached = getTileBounds(tile.getX(), tile.getY(), Math.max(tile.getHighestIntHeight(), tile.getHighestWaterLevel()) + 1);
            tileBoundsCache.put(tile, cached);
        }
        return new Rectangle(cached);
    }

    private Rectangle getTileBounds(final int x, final int y, final int maxHeight) {
        return getTileBounds(x, y, maxHeight, xOffset, yOffset);
    }

    private Rectangle getTileBounds(final int x, final int y, final int maxHeight, final int xOffset, final int yOffset) {
        switch (rotation) {
            case 0:
                return new Rectangle(xOffset + (x - y) * TILE_SIZE,
                        yOffset + (x + y) * TILE_SIZE / 2 + (this.maxHeight - maxHeight),
                        2 * TILE_SIZE,
                        TILE_SIZE + maxHeight - minHeight - 1);
            case 1:
                return new Rectangle(xOffset + ((maxY - y) - x) * TILE_SIZE,
                        yOffset + ((maxY - y) + x) * TILE_SIZE / 2 + (this.maxHeight - maxHeight),
                        2 * TILE_SIZE,
                        TILE_SIZE + maxHeight - minHeight - 1);
            case 2:
                return new Rectangle(xOffset + ((maxX - x) - (maxY - y)) * TILE_SIZE,
                        yOffset + ((maxX - x) + (maxY - y)) * TILE_SIZE / 2 + (this.maxHeight - maxHeight),
                        2 * TILE_SIZE,
                        TILE_SIZE + maxHeight - minHeight - 1);
            case 3:
                return new Rectangle(xOffset + (y - (maxX - x)) * TILE_SIZE,
                        yOffset + (y + (maxX - x)) * TILE_SIZE / 2 + (this.maxHeight - maxHeight),
                        2 * TILE_SIZE,
                        TILE_SIZE + maxHeight - minHeight - 1);
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     * Centre the view on a particular point in view coordinates.
     * 
     * @param coords The point to centre on in view coordinates.
     */
    private void moveTo(Point coords) {
        Rectangle visibleRect = getVisibleRect();
        scrollRectToVisible(new Rectangle(coords.x - visibleRect.width / 2, coords.y - visibleRect.height / 2, visibleRect.width, visibleRect.height));
    }
    
    private java.awt.Dimension zoom(java.awt.Dimension dimension) {
        if (zoom < 1) {
            dimension.width /= scale;
            dimension.height /= scale;
        } else if (zoom > 1) {
            dimension.width *= scale;
            dimension.height *= scale;
        }
        return dimension;
    }

    private Point zoom(Point point) {
        if (zoom < 1) {
            point.x /= scale;
            point.y /= scale;
        } else if (zoom > 1) {
            point.x *= scale;
            point.y *= scale;
        }
        return point;
    }
    
    private Rectangle zoom(Rectangle rectangle) {
        if (zoom < 1) {
            rectangle.x /= scale;
            rectangle.y /= scale;
            rectangle.width /= scale;
            rectangle.height /= scale;
        } else if (zoom > 1) {
            rectangle.x *= scale;
            rectangle.y *= scale;
            rectangle.width *= scale;
            rectangle.height *= scale;
        }
        return rectangle;
    }

    private Rectangle unzoom(Rectangle rectangle) {
        if (zoom < 1) {
            rectangle.x *= scale;
            rectangle.y *= scale;
            rectangle.width *= scale;
            rectangle.height *= scale;
        } else if (zoom > 1) {
            rectangle.x /= scale;
            rectangle.y /= scale;
            rectangle.width /= scale;
            rectangle.height /= scale;
        }
        return rectangle;
    }
    
    private java.awt.Dimension unzoom(java.awt.Dimension dimension) {
        if (zoom < 1) {
            dimension.width *= scale;
            dimension.height *= scale;
        } else if (zoom > 1) {
            dimension.width /= scale;
            dimension.height /= scale;
        }
        return dimension;
    }

    private final Dimension dimension;
    /**
     * C1: rendered-tile cache with true LRU semantics. When cachingEnabled=false the
     * removeEldestEntry hook caps the map at {@link #BOUNDED_CACHE_TILES}, evicting the
     * LEAST RECENTLY USED tile. The {@code accessOrder=true} flag makes every get(tile)
     * call (e.g. paintComponent looking up a visible tile to draw) move that entry to the
     * tail, so the visible viewport is naturally protected from eviction — older
     * off-screen tiles drop out first.
     * <p>
     * When cachingEnabled=true (default) the hook returns false and the map grows
     * unbounded, matching previous behaviour.
     */
    private final Map<Tile, BufferedImage> renderedTiles = new LinkedHashMap<Tile, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Tile, BufferedImage> eldest) {
            if (! cachingEnabled && size() > BOUNDED_CACHE_TILES) {
                final BufferedImage img = eldest.getValue();
                if (img != null && img != TILE_NOT_RENDERABLE) {
                    img.flush();
                }
                return true;
            }
            return false;
        }
    };
    private final Map<Tile, BufferedImage> dirtyTiles = new HashMap<>();
    /** C1: cached per-tile screen-space bounds. Invalidated by height-affecting Tile.Listener events. */
    private final Map<Tile, Rectangle> tileBoundsCache = new HashMap<>();
    /**
     * C1: tiles we've attached a Tile.Listener to. Used by {@link #disposeResources} to detach
     * the listener on close WITHOUT re-iterating {@code dimension.getTiles()} (which under B-mode
     * would force-load every region a second time).
     */
    private final Set<Tile> listenedTiles = new HashSet<>();
    private final ThreeDeeRenderManager threeDeeRenderManager;
    private final ColourScheme colourScheme;
    private final List<Tile> tilesWaitingToBeRendered = new LinkedList<>();
    private final int minHeight, maxHeight;
    private final int xOffset, yOffset, maxX, maxY;
    private final int rotation;
    /**
     * D1: the screen-relative light direction. Threaded to the render manager (for live
     * rendering) and to one-off {@link Tile3DRenderer} instances created by {@link #getImage}
     * (so the exported image matches what's on screen). Mutable at runtime via
     * {@link #setScreenLightOrigin}, which hot-swaps the value into the running renderers
     * without rebuilding the view.
     */
    private TileRenderer.LightOrigin screenLightOrigin;
    private final SortedMap<Integer, SortedMap<Integer, Tile>> zSortedTiles;
    private final CustomBiomeManager customBiomeManager;
    private final boolean upsideDown;
    private Timer timer;
    private long lastTileChange;
    private RefreshMode refreshMode = RefreshMode.DELAYED;
    private Tile centreTile;
    private int waterLevel, zoom = 1, scale = 1;
    private Point highlightTile, highlightPoint;
    private LayerVisibilityMode layerVisibility;
    private Set<Layer> hiddenLayers;
    /** C1: master toggle for the tile-cache optimisations. See setCachingEnabled. */
    private boolean cachingEnabled = true;

    public static final BufferedImage TILE_NOT_RENDERABLE = new BufferedImage(1, 1, TYPE_BYTE_BINARY);

    /**
     * C1: how far outside the current clip rectangle (in pre-zoom screen pixels) we
     * pre-render tiles so that panning into them finds them already rendered.
     * TILE_SIZE * 2 ≈ one tile worth of margin in screen-space — enough to cover a
     * mouse drag of typical speed without queueing the entire map.
     */
    private static final int PRE_RENDER_MARGIN_PX = TILE_SIZE * 2;

    /**
     * C1: hard cap on cached rendered tiles when the "Cache tiles" checkbox is off.
     * 2000 tiles ≈ 720 MB at typical world heights — large enough to cover the entire
     * visible area at max zoom-out (1/8) without thrashing, while still bounding RAM for
     * really-large maps where the full tile set is many thousands of tiles. Tiles outside
     * this window re-render when the user pans back to them; the LRU on access order
     * (see renderedTiles) keeps the currently-visible viewport safe from eviction.
     */
    private static final int BOUNDED_CACHE_TILES = 2000;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ThreeDeeView.class);
    private static final long serialVersionUID = 2011101701L;

    public enum RefreshMode { IMMEDIATE, DELAYED, MANUAL }
}