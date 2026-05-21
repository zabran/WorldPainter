/*
 * WorldPainter, a Minecraft map editor. Copyright 2011-2026 pepsoft.org, The Netherlands.
 *
 * Custom panel icon picker — task 8.
 */
package org.pepsoft.worldpainter.util;

import org.pepsoft.util.IconUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Task 8 — modal dialog that lets the user pick an icon for a custom dock panel.
 *
 * <p>Three tabs:
 * <ul>
 *   <li><strong>Gallery</strong>: scrollable grid of every PNG under
 *       {@code org/pepsoft/worldpainter/icons/} in the WP classpath. Click a thumbnail to
 *       select; tooltip shows the icon's filename (without extension), which is the
 *       resource name we round-trip through Configuration as {@code "builtin:NAME"}.</li>
 *   <li><strong>From file</strong>: standard {@link JFileChooser} restricted to image
 *       types. Selection is stored as {@code "file:ABSOLUTE_PATH"}.</li>
 *   <li><strong>Default (auto)</strong>: shows the auto-generated letter+colour icon for
 *       the panel's name (also used as fallback when no override is set). Clicking the
 *       "Use auto" button signals "clear any stored override". Round-trip value:
 *       {@link Result#isUseAuto()} returns true.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 *     IconPickerDialog.Result r = IconPickerDialog.showDialog(parent, "Sever", currentIcon);
 *     if (r == null) return;     // user cancelled
 *     if (r.isUseAuto()) ...      // clear override
 *     else config.put(panelId, r.toSentinel());
 * }</pre>
 */
public class IconPickerDialog extends JDialog {

    private static final Logger logger = LoggerFactory.getLogger(IconPickerDialog.class);
    private static final long serialVersionUID = 1L;

    /** Classpath prefix for bundled icon resources. */
    private static final String ICON_RESOURCE_DIR = "org/pepsoft/worldpainter/icons/";
    /** Grid layout for the gallery — columns per row. */
    private static final int GALLERY_COLUMNS = 10;

    /** Holds the user's pick. {@code null} if cancelled. */
    public static final class Result {
        private final String sentinel;  // null = use auto
        private Result(String sentinel) { this.sentinel = sentinel; }
        /** True if the user picked "Use auto" — caller should clear any stored override. */
        public boolean isUseAuto() { return sentinel == null; }
        /** {@code "builtin:NAME"} or {@code "file:PATH"} sentinel. Null if {@link #isUseAuto()}. */
        public String toSentinel() { return sentinel; }
        static Result builtin(String name) { return new Result("builtin:" + name); }
        static Result file(String absolutePath) { return new Result("file:" + absolutePath); }
        static Result useAuto() { return new Result(null); }
    }

    private final String panelTitle;
    private final Icon autoIcon;
    private final ButtonGroup galleryGroup = new ButtonGroup();
    private Result result;
    private File chosenFile;

    private IconPickerDialog(Window owner, String panelTitle, Icon autoIcon) {
        super(owner, "Choose icon — " + panelTitle, ModalityType.APPLICATION_MODAL);
        this.panelTitle = panelTitle;
        this.autoIcon = autoIcon;
        buildUi();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Show the modal dialog. Returns the user's pick, or {@code null} if cancelled.
     *
     * @param parent     The parent window (typically the App frame).
     * @param panelTitle Human-readable panel name, used for window title + default-tab preview.
     * @param autoIcon   The auto-generated icon for this panel, shown on the Default tab.
     */
    public static Result showDialog(Component parent, String panelTitle, Icon autoIcon) {
        final Window owner = (parent != null) ? SwingUtilities.getWindowAncestor(parent) : null;
        final IconPickerDialog dlg = new IconPickerDialog(owner, panelTitle, autoIcon);
        dlg.setVisible(true);
        return dlg.result;
    }

    private void buildUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Gallery", buildGalleryTab());
        tabs.addTab("From file", buildFromFileTab());
        tabs.addTab("Default (auto)", buildDefaultTab());

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton ok = new JButton("OK");
        final JButton cancel = new JButton("Cancel");
        ok.addActionListener(this::onOk);
        cancel.addActionListener(e -> { result = null; dispose(); });
        buttons.add(ok);
        buttons.add(cancel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        // Remember which tab is selected so onOk knows which result to produce.
        tabs.addChangeListener(e -> selectedTabIndex = tabs.getSelectedIndex());
        selectedTabIndex = 0;
    }

    private int selectedTabIndex;

    private void onOk(ActionEvent e) {
        switch (selectedTabIndex) {
            case 0:
                // Gallery: walk the button group for the selected item.
                final Enumeration<AbstractButton> en = galleryGroup.getElements();
                while (en.hasMoreElements()) {
                    final AbstractButton b = en.nextElement();
                    if (b.isSelected()) {
                        result = Result.builtin((String) b.getClientProperty("iconName"));
                        dispose();
                        return;
                    }
                }
                // Nothing selected — treat as cancel.
                result = null;
                dispose();
                return;
            case 1:
                // From file.
                if (chosenFile != null && chosenFile.isFile()) {
                    result = Result.file(chosenFile.getAbsolutePath());
                } else {
                    JOptionPane.showMessageDialog(this, "No file selected.", "Choose icon",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                dispose();
                return;
            case 2:
                // Use auto.
                result = Result.useAuto();
                dispose();
                return;
            default:
                result = null;
                dispose();
        }
    }

    // ---- Tab 1: Gallery ---------------------------------------------------------------------

    private JComponent buildGalleryTab() {
        final List<String> iconNames = discoverBundledIcons();
        final JPanel grid = new JPanel(new GridLayout(0, GALLERY_COLUMNS, 4, 4));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (String name : iconNames) {
            // IconUtils.loadScaledIcon takes a full classpath resource path. App.java's private
            // helper (which we mirror here) is the bare-name-to-icon-path convention used
            // throughout WP for bundled icons. Several bundled icons (MC block textures) are
            // larger than the 16-pixel tab slot — scaleIcon clamps them so the gallery shows
            // uniformly-sized thumbnails that also represent how the icon will look once
            // applied to a panel title bar.
            final ImageIcon raw = IconUtils.loadScaledIcon(ICON_RESOURCE_DIR + name + ".png");
            if (raw == null) continue;
            final Icon icon = IconUtils.scaleIcon(raw, 16);
            final JToggleButton b = new JToggleButton(icon);
            b.setToolTipText(name);
            b.setPreferredSize(new Dimension(28, 28));
            b.putClientProperty("iconName", name);
            galleryGroup.add(b);
            grid.add(b);
        }
        final JScrollPane scroll = new JScrollPane(grid);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setPreferredSize(new Dimension(GALLERY_COLUMNS * 32 + 48, 320));
        return scroll;
    }

    /**
     * Enumerate the PNGs under {@code org/pepsoft/worldpainter/icons/} on the classpath.
     * Uses the classloader's {@link ClassLoader#getResources} to find every directory that
     * contributes to the package, then lists *.png entries. Falls back to a small static
     * list if the resource probe finds nothing (e.g. inside a shaded JAR where directory
     * listing isn't supported).
     */
    private static List<String> discoverBundledIcons() {
        final List<String> names = new ArrayList<>();
        try {
            // Try filesystem-style resource enumeration first. Works when WP is run from an
            // exploded classpath (mvn exec:exec) — which is the only path users hit in our fork.
            final URL url = Thread.currentThread().getContextClassLoader().getResource(ICON_RESOURCE_DIR);
            if (url != null && "file".equals(url.getProtocol())) {
                final File dir = new File(url.toURI());
                if (dir.isDirectory()) {
                    final File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
                    if (files != null) {
                        for (File f : files) {
                            final String fn = f.getName();
                            names.add(fn.substring(0, fn.length() - 4));  // strip .png
                        }
                        Collections.sort(names);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not enumerate bundled icons: {}", e.toString());
        }
        if (names.isEmpty()) {
            // Fallback when running from a shaded JAR (no directory listing). Limited set,
            // but ensures the dialog is functional even in that case. Users will mostly see
            // the file-chooser tab in that scenario.
            Collections.addAll(names,
                    "annotations", "arrow_in", "arrow_out", "biome", "brush_grass",
                    "bucket", "cog", "compass", "contours", "cursor",
                    "edit_selection", "eraser", "fill", "flood", "frost",
                    "globals", "grass", "height_map", "information", "layers",
                    "lock", "magnifier", "mountain", "paintbrush", "spawn_red",
                    "transparent", "wand", "wrench");
        }
        return names;
    }

    // ---- Tab 2: From file -------------------------------------------------------------------

    private JComponent buildFromFileTab() {
        final JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        final JFileChooser fc = new JFileChooser();
        fc.setControlButtonsAreShown(false);  // we use the dialog's own OK/Cancel
        fc.setFileFilter(new FileNameExtensionFilter("Image files (PNG, GIF, JPG)", "png", "gif", "jpg", "jpeg"));

        final JLabel preview = new JLabel("(no file selected)", SwingConstants.CENTER);
        preview.setBorder(BorderFactory.createTitledBorder("Preview"));
        preview.setPreferredSize(new Dimension(120, 64));

        fc.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, e -> {
            final File f = fc.getSelectedFile();
            chosenFile = (f != null && f.isFile()) ? f : null;
            updatePreview(preview, chosenFile);
        });

        panel.add(fc, BorderLayout.CENTER);
        panel.add(preview, BorderLayout.EAST);
        return panel;
    }

    private static void updatePreview(JLabel preview, File f) {
        if (f == null) {
            preview.setText("(no file selected)");
            preview.setIcon(null);
            return;
        }
        try {
            final BufferedImage img = ImageIO.read(f);
            if (img != null) {
                preview.setText(null);
                preview.setIcon(new ImageIcon(IconUtils.scaleIcon(img, 16)));
                return;
            }
        } catch (IOException ex) {
            logger.debug("Could not read preview image {}: {}", f, ex.toString());
        }
        preview.setText("(unsupported)");
        preview.setIcon(null);
    }

    // ---- Tab 3: Default (auto) --------------------------------------------------------------

    private JComponent buildDefaultTab() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        final JLabel info = new JLabel("<html>The icon currently used for <b>" + panelTitle + "</b><br>" +
                "when no override is set. Click OK on this tab to clear any stored override<br>" +
                "and fall back to this auto-generated icon.</html>");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));

        final JLabel iconLabel = new JLabel(autoIcon != null ? autoIcon : new ImageIcon());
        iconLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        iconLabel.setBorder(BorderFactory.createTitledBorder("Auto-generated icon"));

        panel.add(info);
        panel.add(iconLabel);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @SuppressWarnings("unused")
    private static class GalleryButtonMouseListener extends MouseAdapter {
        // Reserved for future double-click-to-OK behaviour. Currently unused.
        @Override public void mouseClicked(MouseEvent e) { }
    }
}
