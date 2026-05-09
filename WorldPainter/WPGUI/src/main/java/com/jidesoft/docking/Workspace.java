package com.jidesoft.docking;

import javax.swing.*;
import java.awt.*;

/**
 * Adapter for JIDE's {@code Workspace}: a plain container that the DockingManager mounts in the
 * centre of the workspace area. WorldPainter calls {@link #setLayout(LayoutManager)} on it and
 * then {@link #add(Component, Object)} the central map viewport.
 */
public class Workspace extends JPanel {
    public Workspace() {
        super(new BorderLayout());
    }
}
