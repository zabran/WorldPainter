package org.pepsoft.worldpainter;

import org.pepsoft.minecraft.Material;
import org.pepsoft.util.Version;

import java.util.HashMap;
import java.util.Map;

import static org.pepsoft.minecraft.Constants.*;
import static org.pepsoft.worldpainter.DefaultPlugin.ATTRIBUTE_MC_VERSION;

/**
 * Per-Minecraft-block version constraints. Some Minecraft blocks only exist in a particular range
 * of game versions, either because they were introduced in a specific release (e.g. {@code
 * minecraft:dripstone_block} in 1.17) or because they were renamed (e.g. {@code minecraft:grass_path}
 * → {@code minecraft:dirt_path} in 1.17). When the user picks an incompatible block for the
 * current map's target platform the exported world will show those blocks as missing in-game.
 *
 * <p>This class is the single source of truth for those constraints. Use {@link
 * #checkCompatibility} when committing a user material choice to surface a non-blocking warning;
 * use {@link #versionRangeText} when rendering an entry in a block-picker UI to annotate it with
 * a small version badge.
 *
 * <p>The lists below are deliberately conservative — they cover the cases Arag has actually been
 * bitten by plus a handful of obvious 1.17 / 1.19 / 1.20 / 1.20.5 additions. Add more entries
 * here as users report issues. <strong>Do not</strong> try to enumerate every block in every
 * version; the goal is to catch the high-traffic mistakes, not to validate the whole MC block
 * catalogue.
 */
public final class MaterialVersions {
    private MaterialVersions() {}

    /** First MC version that supports this block, inclusive. */
    private static final Map<String, Version> SINCE = new HashMap<>();
    /** First MC version where this block is NO LONGER supported (renamed/removed), exclusive. */
    private static final Map<String, Version> UNTIL = new HashMap<>();

    static {
        // The case that prompted this whole class: grass_path was renamed to dirt_path in 1.17.
        // Arag spent a year painting with the wrong one because both appear in the picker.
        UNTIL.put(MC_GRASS_PATH, new Version(1, 17));
        SINCE.put(MC_DIRT_PATH, new Version(1, 17));

        // --- 1.17 Caves & Cliffs Part 1 -----------------------------------------------------
        for (String name : new String[]{
                "minecraft:copper_block",      "minecraft:copper_ore",
                "minecraft:deepslate",         "minecraft:cobbled_deepslate",
                "minecraft:amethyst_block",    "minecraft:budding_amethyst",
                "minecraft:dripstone_block",   "minecraft:pointed_dripstone",
                "minecraft:tinted_glass",      "minecraft:tuff",
                "minecraft:smooth_basalt",     "minecraft:calcite",
                "minecraft:powder_snow",       "minecraft:moss_block",
                "minecraft:azalea",            "minecraft:flowering_azalea",
                "minecraft:big_dripleaf",      "minecraft:small_dripleaf",
                "minecraft:spore_blossom",     "minecraft:cave_vines",
                "minecraft:glow_lichen",       "minecraft:hanging_roots",
                "minecraft:rooted_dirt",       "minecraft:raw_iron_block",
                "minecraft:raw_copper_block",  "minecraft:raw_gold_block",
                "minecraft:lightning_rod"})   { SINCE.put(name, new Version(1, 17)); }

        // --- 1.19 The Wild --------------------------------------------------------------------
        for (String name : new String[]{
                "minecraft:mangrove_log",      "minecraft:mangrove_wood",
                "minecraft:mangrove_planks",   "minecraft:mangrove_leaves",
                "minecraft:mangrove_roots",    "minecraft:muddy_mangrove_roots",
                "minecraft:mangrove_propagule","minecraft:mud",
                "minecraft:packed_mud",        "minecraft:mud_bricks",
                "minecraft:reinforced_deepslate",
                "minecraft:sculk",             "minecraft:sculk_catalyst",
                "minecraft:sculk_shrieker",    "minecraft:sculk_vein",
                "minecraft:frogspawn",
                "minecraft:ochre_froglight",   "minecraft:verdant_froglight",
                "minecraft:pearlescent_froglight"})
                                              { SINCE.put(name, new Version(1, 19)); }

        // --- 1.20 Trails & Tales --------------------------------------------------------------
        for (String name : new String[]{
                "minecraft:cherry_log",        "minecraft:cherry_wood",
                "minecraft:cherry_planks",     "minecraft:cherry_leaves",
                "minecraft:cherry_sapling",
                "minecraft:bamboo_block",      "minecraft:bamboo_planks",
                "minecraft:bamboo_mosaic",
                "minecraft:pink_petals",       "minecraft:torchflower",
                "minecraft:torchflower_crop",  "minecraft:pitcher_plant",
                "minecraft:pitcher_crop",      "minecraft:sniffer_egg",
                "minecraft:suspicious_sand",   "minecraft:suspicious_gravel",
                "minecraft:chiseled_bookshelf","minecraft:decorated_pot",
                "minecraft:calibrated_sculk_sensor"})
                                              { SINCE.put(name, new Version(1, 20)); }

        // --- 1.20.5+ --------------------------------------------------------------------------
        for (String name : new String[]{
                "minecraft:copper_grate",      "minecraft:copper_bulb",
                "minecraft:chiseled_copper",   "minecraft:copper_door",
                "minecraft:copper_trapdoor",
                "minecraft:tuff_bricks",       "minecraft:chiseled_tuff",
                "minecraft:polished_tuff",     "minecraft:tuff_slab",
                "minecraft:tuff_stairs",       "minecraft:tuff_wall",
                "minecraft:trial_spawner",     "minecraft:vault",
                "minecraft:crafter",
                "minecraft:heavy_core",        "minecraft:wind_charge"})
                                              { SINCE.put(name, new Version(1, 20, 5)); }
    }

    /**
     * Return a one-sentence warning if the given material is incompatible with the given target
     * platform, or {@code null} if there is no known compatibility issue. The caller is expected
     * to present the warning to the user without blocking — they may have a legitimate reason to
     * paint the block (e.g. preparing the map for a different target).
     */
    public static String checkCompatibility(Material material, Platform target) {
        if (material == null || target == null) {
            return null;
        }
        Version targetVersion = target.getAttribute(ATTRIBUTE_MC_VERSION);
        if (targetVersion == null) {
            return null;
        }
        Version since = SINCE.get(material.name);
        if (since != null && targetVersion.compareTo(since) < 0) {
            return "“" + material.name + "” was introduced in Minecraft " + since
                    + ".\nThis map targets " + target.displayName
                    + ", which is older — the exported block may render as missing in-game.";
        }
        Version until = UNTIL.get(material.name);
        if (until != null && targetVersion.compareTo(until) >= 0) {
            return "“" + material.name + "” was renamed or removed in Minecraft " + until
                    + ".\nThis map targets " + target.displayName
                    + ", which no longer recognises this block — exports may render as missing in-game.";
        }
        return null;
    }

    /**
     * Short label suitable for badging a block name in a picker UI — e.g. {@code "1.17+"} for a
     * block that's only available from 1.17 onwards, or {@code "≤ 1.16"} for one that was
     * removed in 1.17. Returns {@code null} when the block has no known version constraints.
     */
    public static String versionRangeText(String materialName) {
        Version since = SINCE.get(materialName);
        Version until = UNTIL.get(materialName);
        if (since != null && until != null) {
            // Block exists only in a finite window. Display the lower bound — that's the rare
            // case users misuse; an upper bound here is a curiosity.
            return since + "–" + previousVersionLabel(until);
        }
        if (since != null) {
            return since + "+";
        }
        if (until != null) {
            return "≤ " + previousVersionLabel(until);
        }
        return null;
    }

    /** "Last supported version" label given an exclusive UNTIL bound. {@code (1,17)} → "1.16". */
    private static String previousVersionLabel(Version exclusiveUntil) {
        int[] parts = exclusiveUntil.getParts();
        if (parts.length >= 2 && parts[1] > 0) {
            return parts[0] + "." + (parts[1] - 1);
        }
        return exclusiveUntil.toString();
    }
}
