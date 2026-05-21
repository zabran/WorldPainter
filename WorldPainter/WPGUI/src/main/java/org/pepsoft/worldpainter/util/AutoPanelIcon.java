/*
 * WorldPainter, a Minecraft map editor. Copyright 2011-2026 pepsoft.org, The Netherlands.
 *
 * Task 8 auto-generated panel icon.
 */
package org.pepsoft.worldpainter.util;

import org.pepsoft.util.IconUtils;

import javax.swing.Icon;
import java.awt.Color;

/**
 * Generates a deterministic 16×16 letter+colour icon for a dock-panel name. Used by custom
 * layer palettes (which would otherwise all share the same hardcoded {@code ICON_LAYERS}) and
 * by any other panel-creation point that wants distinguishable auto-icons.
 *
 * <p>Same name → same icon across launches, so the user's mental association between an icon
 * and a panel stays stable. Wraps {@link IconUtils#createScaledLetterIcon(char, Color)}; pure
 * function of the name.
 */
public final class AutoPanelIcon {

    private AutoPanelIcon() { /* utility */ }

    /**
     * Generate a deterministic icon for the given panel name. The first character (upper-cased)
     * is drawn on a saturated mid-brightness background colour derived from the name's hash.
     */
    public static Icon generate(String name) {
        if (name == null || name.isEmpty()) {
            return IconUtils.createScaledLetterIcon('?', Color.GRAY);
        }
        final char letter = Character.toUpperCase(name.charAt(0));
        // Hue varies per name; saturation + brightness fixed so the letter stays readable on
        // both light and dark themes. Saturation 0.65 / brightness 0.85 ⇒ pastel-ish background;
        // IconUtils.createScaledLetterIcon picks the foreground (black vs white) automatically
        // based on luminance.
        final int hash = name.hashCode();
        final float hue = ((hash & 0xFFFFFF) % 360) / 360.0f;
        final Color color = Color.getHSBColor(hue, 0.65f, 0.85f);
        return IconUtils.createScaledLetterIcon(letter, color);
    }
}
