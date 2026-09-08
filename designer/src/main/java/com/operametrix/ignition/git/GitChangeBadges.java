package com.operametrix.ignition.git;

import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.client.util.gui.tree.Badge;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.ignition.designer.navtree.NavTreePanel;
import com.inductiveautomation.ignition.designer.navtree.model.AbstractResourceNavTreeNode;
import com.inductiveautomation.ignition.designer.navtree.model.BadgeTreeCellRenderer;
import com.jidesoft.docking.DockableFrame;
import com.jidesoft.docking.DockingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
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
 * rendering, then puts a {@link DotBorder} on the component that comes back, and Swing draws the
 * dot as part of that component.
 *
 * <p>None of this is a documented extension point, so every lookup is guarded: if the Designer's
 * internals move, the badges quietly disappear and nothing else breaks.
 *
 * <p><b>Deletions have no node.</b> When a resource is deleted the Designer removes it from the
 * tree, so there is nothing left to badge and a red dot on the resource itself is impossible. The
 * roll-up is therefore the only way a deletion can be seen at all, and it carries the severity up:
 * a folder holding a deleted child reads red rather than the ordinary orange. Without that a
 * deletion was completely invisible in the Project Browser while the Commit panel beside it listed
 * the change — measured in the Designer 07/09/2026.
 *
 * <p><b>Why a border and not {@code addBadge}.</b> The platform's own badge API looked like the
 * right door and is not: the badge list belongs to the delegate, and after the first commit of a
 * Designer session it stops painting what we put in it. That was instrumented, not guessed -- the
 * poll still ran, the state map was still right, the live tree was still rendering through our
 * wrapper, {@code addBadge} was still called for every affected row, and nothing appeared until the
 * Designer was reopened. Two plausible fixes were built and measured and neither worked: adding the
 * badge before the delegate renders paints nothing at all (it clears the list on entry), and
 * {@code treeDidChange()} to drop cached row bounds changed nothing. Drawing the dot ourselves in a
 * {@link DotBorder} sidesteps the delegate entirely -- Swing paints a border as part of the
 * component and its insets reserve the width, so the row is measured with room for the dot rather
 * than clipping it. Verified across two commit-then-change cycles on 8.3.8, 07/09/2026.
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

    // VS Code's colour grammar, which is what people already read without being told.
    private static final Color MODIFIED = new Color(0xE2, 0xA0, 0x3F);
    private static final Color CREATED = new Color(0x58, 0xA6, 0x4B);
    private static final Color DELETED = new Color(0xD1, 0x3B, 0x3B);

    // The gateway's getUncommitedChanges dataset spells the type out in full; these are the values
    // it actually emits, confirmed against a live 8.3.8 Designer on 07/09/2026. "Uncommitted" is a
    // modification -- it used to badge correctly only because it fell through to the default branch,
    // which would have silently mislabelled any type added later.
    private static final String CREATED_TYPE = "Created";
    private static final String DELETED_TYPE = "Deleted";
    private static final String UNCOMMITTED = "Uncommitted";
    /** Synthetic marks, never emitted by the gateway. */
    private static final String CONTAINS = "Contains";
    private static final String CONTAINS_DELETED = "ContainsDeleted";

    /** Resource path -> change type, including ancestor folders so a collapsed folder still shows. */
    private static volatile Map<String, String> state = Collections.emptyMap();

    private static JTree tree;
    private static Wrapper installed;
    /** Kept so the wrapper can be re-asserted if the Designer swaps the tree or its renderer. */
    private static volatile DesignerContext ctx;
    /** So a re-assert does not repeat the install line on every poll. */
    private static final java.util.concurrent.atomic.AtomicBoolean announced =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Types the gateway emitted that we do not know about — logged once each, not per repaint. */
    private static final java.util.Set<String> unknownTypes =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                String kind = type == null ? UNCOMMITTED : type.toString();
                next.put(path, kind);
                // Roll the mark up the tree. Without this a change is invisible until you have
                // expanded every folder above it, which is most of what makes the editor version
                // of this readable.
                //
                // A DELETED resource has no node left to badge -- the Designer removed it from the
                // tree -- so the roll-up is the only place a deletion can be shown at all. Carry the
                // severity up rather than a flat "something changed": a folder whose child was
                // deleted reads red, which is the whole point of noticing a deletion.
                String mark = DELETED_TYPE.equals(kind) ? CONTAINS_DELETED : CONTAINS;
                for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
                    String ancestor = path.substring(0, slash);
                    String held = next.get(ancestor);
                    // A real change on the folder itself always wins over a roll-up mark, and
                    // CONTAINS_DELETED wins over plain CONTAINS.
                    if (held == null || (CONTAINS.equals(held) && CONTAINS_DELETED.equals(mark))) {
                        next.put(ancestor, mark);
                    }
                }
            }
        }
        state = next;
        // The Project Browser is not ours: the Designer is free to rebuild the tree or replace its
        // cell renderer, and when it does our wrapper goes with it and the badges quietly stop.
        // Re-asserting on every poll is idempotent and costs one reference comparison.
        reassert();
        repaint();
    }

    /**
     * Re-install the wrapper if the live Project Browser is no longer rendering through it. Cheap
     * (a docking lookup and a reference compare); runs on every poll.
     *
     * <p>It deliberately re-reads the tree from the docking manager rather than trusting the cached
     * reference. Committing replaces the Project Browser's JTree, and the old instance keeps our
     * wrapper on it quite happily — so a check of {@code tree.getCellRenderer()} passes while the
     * tree the user is actually looking at has the Designer's own renderer and no badges. That is
     * exactly how badges went silent after the first commit of a session.
     */
    private static void reassert() {
        DesignerContext c = ctx;
        if (c == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                JTree live = liveTree(c);
                if (live == null) {
                    return;
                }
                if (live == tree && live.getCellRenderer() == installed) {
                    return;
                }
                installed = null;
                tree = null;
                doInstall(c);
            } catch (Exception e) {
                logger.debug("Could not re-assert git change badges", e);
            }
        });
    }

    /** The Project Browser's current tree, or null if the Designer's internals have moved. */
    private static JTree liveTree(DesignerContext context) {
        DockingManager dm = context.getDockingManager();
        DockableFrame frame = dm == null ? null : dm.getFrame(PROJECT_BROWSER_KEY);
        return frame instanceof NavTreePanel ? ((NavTreePanel) frame).getTree() : null;
    }

    private static void repaint() {
        JTree t = tree;
        if (t == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            // repaint() alone is not enough. The badge is added on the way out of the renderer, so
            // a row whose bounds were cached BEFORE it had a badge is too narrow to draw one -- the
            // dot lands outside the row's clip and simply is not there. Expanding a folder or
            // reopening the Designer re-measures and it comes back, which is exactly the shape of
            // the "badges stop after the first commit" symptom: after a commit nothing re-measures.
            // treeDidChange() drops the UI's cached path bounds so every row is measured again with
            // its badge present.
            t.treeDidChange();
            t.repaint();
        });
    }

    // --------------------------------------------------------------- install

    /** Wrap the Project Browser's cell renderer. Idempotent; never throws into the caller. */
    public static void install(DesignerContext context) {
        ctx = context;
        SwingUtilities.invokeLater(() -> {
            if (installed != null) {
                return;
            }
            doInstall(context);
        });
    }

    /** Must run on the EDT. */
    private static void doInstall(DesignerContext context) {
        try {
            JTree t = liveTree(context);
            if (t == null) {
                logger.debug("Project Browser frame is not a NavTreePanel; change badges not installed");
                return;
            }
            TreeCellRenderer delegate = t.getCellRenderer();
            if (delegate instanceof Wrapper) {
                // Already ours. Wrapping again would nest wrappers and draw the badge twice --
                // which is exactly what a re-assert on every poll would do without this guard.
                installed = (Wrapper) delegate;
                tree = t;
                return;
            }
            if (!(delegate instanceof BadgeTreeCellRenderer)) {
                logger.debug("Project Browser renderer is not a BadgeTreeCellRenderer; change badges not installed");
                return;
            }
            installed = new Wrapper((BadgeTreeCellRenderer) delegate);
            t.setCellRenderer(installed);
            tree = t;
            t.repaint();
            if (announced.compareAndSet(false, true)) {
                logger.info("Git change badges installed on the Project Browser");
            } else {
                logger.debug("Git change badges re-asserted on the Project Browser");
            }
        } catch (Exception e) {
            // A missing internal is not worth breaking the Designer over.
            logger.debug("Could not install git change badges", e);
        }
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
                ctx = null;
                announced.set(false);
                state = Collections.emptyMap();
            }
        });
    }

    // ---------------------------------------------------------------- render

    private static Color colourFor(String type) {
        switch (type) {
            case CREATED_TYPE:
                return CREATED;
            case DELETED_TYPE:
            case CONTAINS_DELETED:
                return DELETED;
            default:
                if (!CONTAINS.equals(type) && !UNCOMMITTED.equals(type) && unknownTypes.add(type)) {
                    // Not a failure: draw it as a change and say what we saw, so a new gateway type
                    // is a log line rather than a silently mislabelled dot.
                    logger.info("Unrecognised git change type '{}'; showing it as modified", type);
                }
                return MODIFIED;
        }
    }

    /** Reserves room on the right of a row and draws the change dot in it. */
    private static final class DotBorder implements Border {
        private final Color colour;

        DotBorder(Color colour) {
            this.colour = colour;
        }

        @Override
        public Insets getBorderInsets(Component c) {
            // No space is reserved: the dot is an overlay on the leading icon, not a trailing
            // element. Reserving a trailing inset does not work here — the nav tree's renderer
            // sizes itself from its icon and text and ignores the border, so the row never grew
            // and the dot was painted straight over the last letter of the resource name.
            return new Insets(0, 0, 0, 0);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Bottom-left corner of the leading icon, the way an editor badges a changed
                // file. A halo in the row's own background keeps it readable on the icon and on
                // a selected row alike.
                int left = x + 1;
                int top = y + height - DOT - 1;
                Color halo = c.getBackground();
                if (halo != null) {
                    g2.setColor(halo);
                    g2.fillOval(left - 1, top - 1, DOT + 2, DOT + 2);
                }
                g2.setColor(colour);
                g2.fillOval(left, top, DOT, DOT);
            } finally {
                g2.dispose();
            }
        }
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
            // The dot is drawn by a BORDER on the row component, not handed to the delegate's
            // addBadge. addBadge looks like the right door and is not: the badge list is the
            // delegate's private business, and after the first commit of a session it stops
            // painting what we put in it -- instrumented and confirmed, with the badge computed and
            // addBadge called on every pass while nothing appeared. A border cannot be dropped like
            // that: Swing paints it as part of the component, and its insets reserve the width, so
            // the row is measured with room for the dot instead of clipping it.
            if (!(c instanceof JComponent)) {
                return c;
            }
            try {
                JComponent jc = (JComponent) c;
                Border current = jc.getBorder();
                // The delegate rebuilds the border per row, but unwrap defensively so a dot can
                // never nest inside a previous dot.
                if (current instanceof CompoundBorder
                        && ((CompoundBorder) current).getInsideBorder() instanceof DotBorder) {
                    current = ((CompoundBorder) current).getOutsideBorder();
                }
                String type = typeFor(value);
                if (type != null) {
                    jc.setBorder(new CompoundBorder(current, new DotBorder(colourFor(type))));
                } else if (jc.getBorder() != current) {
                    jc.setBorder(current);
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
