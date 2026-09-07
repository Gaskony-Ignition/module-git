package com.operametrix.ignition.git;

import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.client.icons.VectorIcon;
import com.inductiveautomation.ignition.client.util.gui.tree.Badge;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.ignition.designer.navtree.NavTreePanel;
import com.inductiveautomation.ignition.designer.navtree.model.AbstractResourceNavTreeNode;
import com.inductiveautomation.ignition.designer.navtree.model.BadgeTreeCellRenderer;
import com.jidesoft.docking.DockableFrame;
import com.jidesoft.docking.DockingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.geom.Ellipse2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Marks resources in the Designer's Project Browser that have changed and are not yet committed,
 * the way an editor marks dirty files.
 *
 * <p>The platform already has the right mechanism for this: every node implements
 * {@code AbstractNavTreeNode.addBadges(BadgeTreeCellRenderer, boolean)}, and the Designer ships
 * badges of exactly this kind (concurrent users, overridden resource, notes). What it does not
 * offer is a way to badge nodes we do not own — a Perspective view's node belongs to the
 * Perspective module. So this wraps the tree's cell renderer instead: the wrapper delegates
 * rendering, then adds our badge to the component that comes back.
 *
 * <p>That is safe because {@code PanelBasedTreeCellRenderer.getTreeCellRendererComponent} returns
 * the renderer itself and Swing paints it afterwards, so a badge added on the way out is still
 * painted. It is verified rather than assumed — {@link #wrap} checks the returned component IS the
 * delegate and silently does nothing if a future release changes that.
 *
 * <p>None of this is a documented extension point, so every lookup is guarded: if the Designer's
 * internals move, the badges quietly disappear and nothing else breaks.
 *
 * <p><b>Known limit.</b> The mark rolls up to ancestor folders by resource path, so a collapsed
 * folder still shows that something inside it changed — but only for folders that ARE resource
 * nodes. Several top-level module folders are not ({@code PerspectiveNavNode} and
 * {@code VisionModuleNode} report no resource path at all), so a change under one of those is
 * visible from the first resource-backed folder downwards and not on the module root itself.
 * Verified in the Designer 07/09/2026: Scripting -> Project Library carries the roll-up dot,
 * Perspective does not.
 */
public final class GitChangeBadges {
    private static final Logger logger = LoggerFactory.getLogger(GitChangeBadges.class);

    /** The Designer's own project-browser docking key. */
    private static final String PROJECT_BROWSER_KEY = "Project Browser";

    /** Badge diameter in px — matches the platform's own 12px badges. */
    private static final int DOT = 9;
    private static final int BOX = 12;

    // VS Code's colour grammar, which is what people already read without being told.
    private static final Color MODIFIED = new Color(0xE2, 0xA0, 0x3F);
    private static final Color CREATED = new Color(0x58, 0xA6, 0x4B);
    private static final Color DELETED = new Color(0xD1, 0x3B, 0x3B);

    /** Resource path -> change type, including ancestor folders so a collapsed folder still shows. */
    private static volatile Map<String, String> state = Collections.emptyMap();

    private static JTree tree;
    private static Wrapper installed;

    private GitChangeBadges() {}

    // ------------------------------------------------------------------ data

    /**
     * Take the same {@code getUncommitedChanges} dataset the Commit panel already polls — no second
     * poll, no extra RPC — and index it by resource path. The dataset's {@code resource} column is
     * already a resource-path string (the gateway strips the file name off a resource directory),
     * which is what the nav tree nodes report, so no path translation is needed.
     */
    public static void update(Dataset ds) {
        Map<String, String> next = new HashMap<>();
        if (ds != null) {
            for (int i = 0; i < ds.getRowCount(); i++) {
                Object res = ds.getValueAt(i, "resource");
                Object type = ds.getValueAt(i, "type");
                if (res == null) {
                    continue;
                }
                String path = res.toString();
                next.put(path, type == null ? "Modified" : type.toString());
                // Roll the mark up the tree. Without this a change is invisible until you have
                // expanded every folder above it, which is most of what makes the editor version
                // of this readable.
                for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
                    next.putIfAbsent(path.substring(0, slash), "Contains");
                }
            }
        }
        state = next;
        repaint();
    }

    private static void repaint() {
        JTree t = tree;
        if (t != null) {
            SwingUtilities.invokeLater(t::repaint);
        }
    }

    // --------------------------------------------------------------- install

    /** Wrap the Project Browser's cell renderer. Idempotent; never throws into the caller. */
    public static void install(DesignerContext context) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (installed != null) {
                    return;
                }
                DockingManager dm = context.getDockingManager();
                DockableFrame frame = dm == null ? null : dm.getFrame(PROJECT_BROWSER_KEY);
                if (!(frame instanceof NavTreePanel)) {
                    logger.debug("Project Browser frame is not a NavTreePanel; change badges not installed");
                    return;
                }
                JTree t = ((NavTreePanel) frame).getTree();
                TreeCellRenderer delegate = t == null ? null : t.getCellRenderer();
                if (!(delegate instanceof BadgeTreeCellRenderer)) {
                    logger.debug("Project Browser renderer is not a BadgeTreeCellRenderer; change badges not installed");
                    return;
                }
                installed = new Wrapper((BadgeTreeCellRenderer) delegate);
                t.setCellRenderer(installed);
                tree = t;
                t.repaint();
                logger.info("Git change badges installed on the Project Browser");
            } catch (Exception e) {
                // A missing internal is not worth breaking the Designer over.
                logger.debug("Could not install git change badges", e);
            }
        });
    }

    /** Put the Designer's own renderer back. Safe to call when nothing was installed. */
    public static void uninstall() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (tree != null && installed != null && tree.getCellRenderer() == installed) {
                    tree.setCellRenderer(installed.delegate);
                }
            } catch (Exception ignored) {
                // Shutdown path — nothing useful to do.
            } finally {
                installed = null;
                tree = null;
                state = Collections.emptyMap();
            }
        });
    }

    // ---------------------------------------------------------------- render

    private static Badge badge(String type) {
        Color colour;
        String tip;
        switch (type) {
            case "Created":
                colour = CREATED;
                tip = "Created — not yet committed";
                break;
            case "Deleted":
                colour = DELETED;
                tip = "Deleted — not yet committed";
                break;
            case "Contains":
                colour = MODIFIED;
                tip = "Contains uncommitted changes";
                break;
            default:
                colour = MODIFIED;
                tip = "Modified — not yet committed";
                break;
        }
        int off = (BOX - DOT) / 2;
        VectorIcon icon = new VectorIcon(new Ellipse2D.Double(off, off, DOT, DOT), BOX, BOX, colour);
        return new Badge(icon, tip);
    }

    /**
     * Delegating renderer. Everything about the row is still drawn by the Designer; the only
     * addition is one badge, appended after the delegate has finished with the row.
     */

    private static final class Wrapper implements BadgeTreeCellRenderer {
        private final BadgeTreeCellRenderer delegate;

        Wrapper(BadgeTreeCellRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            Component c = delegate.getTreeCellRendererComponent(t, value, selected, expanded, leaf,
                    row, hasFocus);
            // The badge is added to the component the delegate returns. Panel-based renderers
            // return themselves; if a future release returns something else, adding a badge would
            // decorate the wrong component, so leave the row alone.
            if (c != delegate) {
                return c;
            }
            try {
                String type = typeFor(value);
                if (type != null) {
                    delegate.addBadge(badge(type), selected);
                }
            } catch (Exception ignored) {
                // Painting must never throw — a bad row would repeat on every repaint.
            }
            return c;
        }

        private String typeFor(Object value) {
            if (!(value instanceof AbstractResourceNavTreeNode)) {
                return null;
            }
            Map<String, String> current = state;
            if (current.isEmpty()) {
                return null;
            }
            var path = ((AbstractResourceNavTreeNode) value).getResourcePath();
            return path == null ? null : current.get(path.toString());
        }

        @Override
        public void addBadge(Badge badge) {
            delegate.addBadge(badge);
        }

        @Override
        public void addBadge(Badge badge, boolean selected) {
            delegate.addBadge(badge, selected);
        }
    }
}
