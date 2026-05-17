/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.threedeeview;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.exporters.AnnotationsExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.singleton;
import static org.pepsoft.minecraft.Material.*;
import static org.pepsoft.worldpainter.Constants.*;
import static org.pepsoft.worldpainter.TileRenderer.ALL_TUNNELS_AS_LAYER;
import static org.pepsoft.worldpainter.TileRenderer.FLUIDS_AS_LAYER;
import static org.pepsoft.worldpainter.threedeeview.ThreeDeeView.TILE_NOT_RENDERABLE;

/**
 *
 * @author pepijn
 */
// TODO: adapt for new dynamic maximum level height
public class Tile3DRenderer {
    public Tile3DRenderer(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, int rotation, LayerVisibilityMode layerVisibility, Set<Layer> hiddenLayers) {
        this(dimension, colourScheme, customBiomeManager, rotation, layerVisibility, hiddenLayers, TileRenderer.LightOrigin.NORTHWEST);
    }

    /**
     * D1: full constructor including the <strong>screen-relative</strong> {@code LightOrigin}.
     * The 3D iso view picks one of the four cardinal-diagonal directions on screen (or ABOVE);
     * we map it to the corresponding world-space LightOrigin given the current view rotation,
     * so that the bright/dark slopes stay locked to the chosen screen direction as the user
     * spins the view. See {@link #worldLightFor(TileRenderer.LightOrigin, int)} for the math.
     */
    public Tile3DRenderer(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, int rotation, LayerVisibilityMode layerVisibility, Set<Layer> hiddenLayers, TileRenderer.LightOrigin screenLightOrigin) {
        this.dimension = dimension;
        minHeight = dimension.getMinHeight();
        this.colourScheme = colourScheme;
        this.rotation = rotation;
        this.screenLightOrigin = (screenLightOrigin != null) ? screenLightOrigin : TileRenderer.LightOrigin.NORTHWEST;
        tileRenderer = new TileRenderer(dimension, colourScheme, customBiomeManager, 0, true, null);
        // D1: configure the embedded 2D renderer's light direction. The 2D renderer applies
        // its directional shading to the top row of pixels we sample for each terrain column
        // (via tileImgBuffer.getRGB below). Without this call, the embedded TileRenderer keeps
        // its default NORTHWEST and the top-row shading stays in world-space regardless of
        // 3D-view rotation — i.e. the shadows look right at rotation 0 but slide to a different
        // screen direction at every other rotation. With the screen→world mapping below, the
        // shadows stay locked to the chosen screen direction at all rotations.
        //
        // {@link #render} re-applies this on every render so a runtime call to
        // {@link #setScreenLightOrigin} from the EDT takes effect on the next rendered tile
        // without recreating the renderer. The embedded TileRenderer is documented as
        // single-thread per instance and we own it on the render thread, so the per-render
        // {@code setLightOrigin} call is safe and cheap (just a field assignment).
        tileRenderer.setLightOrigin(worldLightFor(this.screenLightOrigin, rotation));
        switch (layerVisibility) {
            case NONE:
                tileRenderer.setHideAllLayers(true);
                tileRenderer.addHiddenLayers(ALWAYS_HIDDEN_LAYERS);
                hideFrost = true;
                hideFluids = false;
                break;
            case SYNC:
                tileRenderer.addHiddenLayers(hiddenLayers);
                tileRenderer.addHiddenLayers(ALWAYS_HIDDEN_LAYERS);
                hideFrost = hiddenLayers.contains(Frost.INSTANCE);
                hideFluids = hiddenLayers.contains(FLUIDS_AS_LAYER);
                break;
            case SURFACE:
                tileRenderer.addHiddenLayers(DEFAULT_HIDDEN_LAYERS);
                hideFrost = false;
                hideFluids = false;
                break;
            default:
                throw new InternalError();
        }
        if ((layerVisibility != LayerVisibilityMode.NONE) && ((dimension.getLayerSettings(Annotations.INSTANCE) == null) || (! ((AnnotationsExporter.AnnotationsSettings) dimension.getLayerSettings(Annotations.INSTANCE)).isExport()))) {
            tileRenderer.addHiddenLayers(singleton(Annotations.INSTANCE));
        }
        tileRenderer.setContourLines(false);
        switch (dimension.getAnchor().dim) {
            case DIM_NETHER:
                stoneColour = colourScheme.getColour(NETHERRACK);
                break;
            case DIM_END:
                stoneColour = colourScheme.getColour(END_STONE);
                break;
            default:
                stoneColour = colourScheme.getColour(STONE);
                break;
        }
        waterColour = colourScheme.getColour(WATER);
        lavaColour = colourScheme.getColour(LAVA);
        iceColour = colourScheme.getColour(ICE);
        platform = tileRenderer.getPlatform();
    }
    
    /**
     * D1: hot-swap the screen-relative light direction. Safe to call from any thread; the
     * volatile write ensures visibility on the render thread that owns this Tile3DRenderer.
     * Effect lands on the next {@link #render} call.
     */
    public void setScreenLightOrigin(TileRenderer.LightOrigin newScreenLightOrigin) {
        if (newScreenLightOrigin != null) {
            this.screenLightOrigin = newScreenLightOrigin;
        }
    }

    @SuppressWarnings("SuspiciousNameCombination") // Don't worry about it, we're rotating the tile
    public BufferedImage render(Tile tile) {
//        System.out.println("Rendering tile " + tile);
        // D1: re-apply the (possibly updated) screen-relative light direction. Cheap field
        // assignment; ensures any setScreenLightOrigin() from the EDT has taken effect.
        tileRenderer.setLightOrigin(worldLightFor(screenLightOrigin, rotation));
        tileRenderer.renderTile(tile, tileImgBuffer, 0, 0);
//        Terrain subSurfaceMaterial = dimension.getSubsurfaceMaterial();
        final long seed = dimension.getSeed();
        final boolean coverSteepTerrain = dimension.isCoverSteepTerrain(), topLayersRelativeToTerrain = dimension.getTopLayerAnchor() == Dimension.LayerAnchor.TERRAIN;
        final int tileOffsetX = tile.getX() * TILE_SIZE, tileOffsetY = tile.getY() * TILE_SIZE;
        int currentColour = -1;
        final int imgWidth = TILE_SIZE * 2;
        final int maxZ = Math.max(tile.getHighestIntHeight(), tile.getHighestWaterLevel());
        final int imgHeight = TILE_SIZE + maxZ - minHeight;
        final long tileImgSize = (long) imgWidth * imgHeight;
        if ((tileImgSize < 0L) || (tileImgSize > Integer.MAX_VALUE)) {
            return TILE_NOT_RENDERABLE;
        }
        final BufferedImage img = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleImage(imgWidth, imgHeight, Transparency.TRANSLUCENT);
        final Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (int x = 0; x < TILE_SIZE; x++) {
                for (int y = 0; y < TILE_SIZE; y++) {
                    // Coordinates of the block in the world
                    final int xInTile, yInTile;
                    switch (rotation) {
                        case 0:
                            xInTile = x;
                            yInTile = y;
                            break;
                        case 1:
                            xInTile = y;
                            yInTile = TILE_SIZE - 1 - x;
                            break;
                        case 2:
                            xInTile = TILE_SIZE - 1 - x;
                            yInTile = TILE_SIZE - 1 - y;
                            break;
                        case 3:
                            xInTile = TILE_SIZE - 1 - y;
                            yInTile = x;
                            break;
                        default:
                            throw new IllegalArgumentException();
                    }
                    if (tile.getBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, xInTile, yInTile)
                            || tile.getBitLayerValue(NotPresent.INSTANCE, xInTile, yInTile)
                            || tile.getBitLayerValue(NotPresentBlock.INSTANCE, xInTile, yInTile)) {
                        continue;
                    }
                    final int blockX = tileOffsetX + xInTile, blockY = tileOffsetY + yInTile;
                    final int terrainHeight = tile.getIntHeight(xInTile, yInTile);
                    final int fluidLevel = tile.getWaterLevel(xInTile, yInTile);
                    final boolean floodWithLava;
                    if (fluidLevel > terrainHeight) {
                        floodWithLava = tile.getBitLayerValue(FloodWithLava.INSTANCE, xInTile, yInTile);
                    } else {
                        floodWithLava = false;
                    }
                    // Image coordinates of the bottom of the world in this column. Image origin is in the top left
                    // corner
                    final float imgX = TILE_SIZE + x - y - 0.5f, imgY = (x + y) / 2f + maxZ - minHeight + 0.5f;
//                    System.out.println(blockX + ", " + blockY + " -> " + blockXTranslated + ", " + blockYTranslated + " -> " + imgX + ", " + imgY);

                    // First draw the sub surface part of the world in a single solid colour
                    int subsurfaceHeight = Math.max(terrainHeight - minHeight - dimension.getTopLayerDepth(blockX, blockY, terrainHeight), 0);
                    if (coverSteepTerrain) {
                        subsurfaceHeight = Math.min(subsurfaceHeight,
                            Math.min(Math.min(dimension.getIntHeightAt(blockX - 1, blockY, Integer.MAX_VALUE),
                            dimension.getIntHeightAt(blockX + 1, blockY, Integer.MAX_VALUE)),
                            Math.min(dimension.getIntHeightAt(blockX, blockY - 1, Integer.MAX_VALUE),
                            dimension.getIntHeightAt(blockX, blockY + 1, Integer.MAX_VALUE))) - minHeight);
                    }
                    int colour = stoneColour; // TODO use the actual (average? most representative?) colour
                    if (colour != currentColour) {
                        g2.setColor(new Color(colour));
                        currentColour = colour;
                    }
                    if (subsurfaceHeight > 0) {
                        g2.fill(new Rectangle2D.Float(imgX, imgY - subsurfaceHeight, 2, subsurfaceHeight));
                    }
//                    for (int z = 0; z <= subsurfaceHeight; z++) {
//                        colour = colourScheme.getColour(subSurfaceMaterial.getMaterial(seed, blockX, blockY, z, terrainHeight));
//                        if (colour != currentColour) {
//    //                                        g2.setColor(new Color(ColourUtils.multiply(colour, brightenAmount)));
//                            g2.setColor(new Color(colour));
//                            currentColour = colour;
//                        }
//                        g2.draw(new Line2D.Float(imgX, imgY - z, imgX + 1, imgY - z));
//                    }

                    // Draw the top layer of the terrain, not including the surface block. Do this per block because
                    // they might have different colours
                    final Terrain terrain = tile.getTerrain(xInTile, yInTile);
                    final MixedMaterial mixedMaterial;
                    final int topLayerOffset;
                    if (terrain.isCustom()) {
                        mixedMaterial = Terrain.getCustomMaterial(terrain.getCustomTerrainIndex());
                        if (topLayersRelativeToTerrain && (mixedMaterial.getMode() == MixedMaterial.Mode.LAYERED)) {
                            topLayerOffset = -(terrainHeight - mixedMaterial.getPatternHeight() + 1);
                        } else {
                            topLayerOffset = 0;
                        }
                    } else {
                        mixedMaterial = null;
                        topLayerOffset = 0;
                    }
                    if ((mixedMaterial != null) && (mixedMaterial.getColour() != null)) {
                        // A custom terrain with a configured colour; use that
                        // colour throughout
                        colour = mixedMaterial.getColour();
                        if (colour != currentColour) {
                            g2.setColor(new Color(colour));
                            currentColour = colour;
                        }
                        g2.fill(new Rectangle2D.Float(imgX, imgY - terrainHeight + minHeight + 1, 2, terrainHeight - minHeight - subsurfaceHeight - 1));
                    } else {
                        Material nextMaterial = terrain.getMaterial(platform, seed, blockX, blockY, subsurfaceHeight + minHeight + 1, terrainHeight);
                        for (int z = subsurfaceHeight + minHeight + 1; z <= terrainHeight - 1; z++) {
                            Material material = nextMaterial;
                            if (z < maxZ) {
                                nextMaterial = terrain.getMaterial(platform, seed, blockX, blockY, z + 1, terrainHeight);
                                if (! nextMaterial.veryInsubstantial) {
                                    // Block above is solid
                                    if ((material == Material.GRASS_BLOCK) || (material == Material.MYCELIUM) || (material == Material.FARMLAND)) {
                                        material = Material.DIRT;
                                    }
                                }
                            }
                            if (topLayerOffset != 0) {
                                // This means the terrain is a custom terrain with a
                                // layered material
                                colour = colourScheme.getColour(mixedMaterial.getMaterial(seed, blockX, blockY, z + topLayerOffset));
                            } else {
                                colour = colourScheme.getColour(material);
                            }
                            if (colour != currentColour) {
//                                g2.setColor(new Color(ColourUtils.multiply(colour, brightenAmount)));
                                g2.setColor(new Color(colour));
                                currentColour = colour;
                            }
                            g2.draw(new Line2D.Float(imgX, imgY - z + minHeight, imgX + 1, imgY - z + minHeight));
                        }
                    }

                    // Draw the surface of the terrain. Use the TileRenderer for this so that it includes layers, etc.
                    colour = tileImgBuffer.getRGB(xInTile, yInTile);
                    if (colour != currentColour) {
                        g2.setColor(new Color(colour));
                        currentColour = colour;
                    }
                    g2.draw(new Line2D.Float(imgX, imgY - terrainHeight + minHeight, imgX + 1, imgY - terrainHeight + minHeight));

                    // Draw the water or lava, if any
                    if ((! hideFluids) && (fluidLevel > terrainHeight)) {
                        colour = floodWithLava ? lavaColour : waterColour;
    //                                    currentColour = 0x80000000 | ColourUtils.multiply(colour, brightenAmount);
                        currentColour = (colour & 0x00ffffff) | 0x60000000;
                        g2.setColor(new Color(currentColour, true));
                        boolean ice = (! floodWithLava) && (! hideFrost) && tile.getBitLayerValue(Frost.INSTANCE, xInTile, yInTile);
                        for (int z = terrainHeight + 1; z <= fluidLevel; z++) {
                            if ((z == fluidLevel) && ice) {
                                colour = iceColour;
                                g2.setColor(new Color(colour));
                                currentColour = colour;
                            }
                            g2.draw(new Line2D.Float(imgX, imgY - z + minHeight, imgX + 1, imgY - z + minHeight));
                        }
                    }
                }
            }
        } catch (MissingCustomTerrainException | NullPointerException e) {
            logger.error("Could not render tile {},{} due to {}", tile.getX(), tile.getY(), e.getClass().getSimpleName(), e);
            return TILE_NOT_RENDERABLE;
        } finally {
            g2.dispose();
        }
        return img;
    }
 
    private final Dimension dimension;
    private final ColourScheme colourScheme;
    private final TileRenderer tileRenderer;
    private final int minHeight, rotation, stoneColour, waterColour, lavaColour, iceColour;
    private final Platform platform;
    private final boolean hideFrost, hideFluids;
    /**
     * D1: current screen-relative light direction. {@code volatile} because {@link #setScreenLightOrigin}
     * is called from the EDT while {@link #render} reads it on the render thread that owns
     * this Tile3DRenderer. Enum reference reads/writes are atomic; volatile gives happens-before.
     */
    private volatile TileRenderer.LightOrigin screenLightOrigin;

    private final BufferedImage tileImgBuffer = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);

    static final Set<Layer> ALWAYS_HIDDEN_LAYERS = singleton(FLUIDS_AS_LAYER);
    static final Set<Layer> DEFAULT_HIDDEN_LAYERS = new HashSet<>(Arrays.asList(Biome.INSTANCE, Caverns.INSTANCE, Caves.INSTANCE, Chasms.INSTANCE, ReadOnly.INSTANCE, Resources.INSTANCE, ALL_TUNNELS_AS_LAYER));

    static {
        DEFAULT_HIDDEN_LAYERS.addAll(ALWAYS_HIDDEN_LAYERS);
    }

    private static final Logger logger = LoggerFactory.getLogger(Tile3DRenderer.class);

    public enum LayerVisibilityMode { NONE, SYNC, SURFACE }

    /**
     * D1: map a <em>screen-relative</em> {@link TileRenderer.LightOrigin} (the direction the
     * user picked, expressed as one of NW/NE/SE/SW/ABOVE on the screen) to the world-space
     * LightOrigin to feed the embedded 2D {@link TileRenderer}, given the current 3D-view
     * rotation.
     * <p>
     * Derivation: the iso projection used by this renderer (imgX = TILE_SIZE + x - y, imgY =
     * (x + y) / 2 + ...) places, at rotation 0, world-NW at the top of the screen, world-NE
     * on the right, world-SE at the bottom and world-SW on the left. Each rotation step
     * (the {@code rotation} parameter, 0..3) remaps {@code (xInTile, yInTile)} so that the
     * world appears rotated 90° clockwise on the screen (camera circles 90° anticlockwise
     * around the world). A light source attached to the world would therefore visually
     * rotate CW on screen too — so to keep it at a <em>fixed</em> screen direction, the
     * world-space LightOrigin must rotate 90° <em>counter</em>-clockwise per rotation step
     * (opposite the world's apparent CW screen rotation, cancelling it).
     * <p>
     * The {@code LightOrigin} enum's ordinals are CW (NW=0, NE=1, SE=2, SW=3), so CCW
     * movement is a modular subtract. {@code ABOVE} is rotation-invariant.
     * <p>
     * Sanity-check: at each rotation, which world direction lands at screen-top (pixel
     * direction {@code (0, -1)} under the iso formula)? Solving gives rotation 0→NW, 1→SW,
     * 2→SE, 3→NE — and exactly this {@code ordinal - rotation} table.
     *
     * @param screen   The screen-relative light direction the user picked.
     * @param rotation The current 3D-view rotation (0..3).
     * @return The world-space {@code LightOrigin} for the embedded 2D renderer.
     */
    static TileRenderer.LightOrigin worldLightFor(TileRenderer.LightOrigin screen, int rotation) {
        if (screen == null) {
            return TileRenderer.LightOrigin.NORTHWEST;
        }
        if (screen == TileRenderer.LightOrigin.ABOVE) {
            return screen;
        }
        final TileRenderer.LightOrigin[] cw = {
                TileRenderer.LightOrigin.NORTHWEST,
                TileRenderer.LightOrigin.NORTHEAST,
                TileRenderer.LightOrigin.SOUTHEAST,
                TileRenderer.LightOrigin.SOUTHWEST
        };
        // CCW step per rotation: subtract. Java's `& 3` on negative ints wraps via
        // two's complement (-1 & 3 == 3, -2 & 3 == 2, -3 & 3 == 1), giving the desired
        // modular subtraction without an explicit `+ 4`.
        return cw[(screen.ordinal() - rotation) & 3];
    }
}