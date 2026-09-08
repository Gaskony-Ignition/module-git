# Gaskony fork — feature investigation

Two additions to the OperaMetrix module, investigated against the 8.3.6 SDK
(`com.inductiveautomation.ignition:designer:8.3.6`, `client-api:8.3.6`) and
JGit 6.10.1. Both are feasible. Findings, design and risks below.

Fork context: `Gaskony-Ignition/module-git-integration` (public since 08/09/2026), `upstream` =
`operametrix/ignition-git-module` at 2.1.0. Licence is Beerware — retain the
notice in `LICENSE.md`.

---

## 1. Gitignore management on the gateway Versioning page

**Goal:** edit `.gitignore` for the config-as-code repo through a tree of the
data directory, ticking folders/files in or out, instead of hand-editing a file.

### What exists today

`DataDirGitManager` writes `.gitignore` **once**, at `initRepo()`, from the
hard-coded `GITIGNORE_LINES` list (IA's version-control-guide template plus
`projects/`). `writeGitignore()` is guarded by `if (!Files.exists(...))`, so it
never rewrites. After init there is no way to change it from the UI at all —
which is the gap. `.gitignore` is itself in `SCOPE`, so edits to it are tracked
and auto-committed like any other config change.

### The APIs that make this work

JGit gives per-path answers, not just a bulk list:

| Need | API |
| --- | --- |
| Is this path ignored? | `IgnoreNode.isIgnored(path, isDirectory)` → `MatchResult` (IGNORED / NOT_IGNORED / CHECK_PARENT) |
| **Which rule** ignored it | `IgnoreNode.getRules()` + `FastIgnoreRule.isMatch(path, isDir)`, walked last-to-first (last match wins, per gitignore semantics) |
| Negations (`!foo`) | `FastIgnoreRule.getNegation()` / `getResult()` |
| Bulk cross-check | `Status.getIgnoredNotInIndex()` |
| Is it tracked? | the `DirCache` / index — tracked files stay tracked even if a later rule ignores them |

Reporting the matching rule is what turns this from a checkbox list into
something explainable: a row can say *excluded by `**/logs`* and disable its own
checkbox, because unticking one path cannot undo a glob that covers a hundred.

### Design

**Gateway — three routes**, following the existing `mountRouteHandlers` pattern
in `GatewayHook` (`/data/git-config/…`, `READ` for gets, `WRITE` for posts, CSRF
on mutations):

- `GET /tree?path=<rel>` — one directory level, lazily. Per entry: name, is-dir,
  `state` ∈ `TRACKED | IGNORED | UNTRACKED`, `ignoredBy` (the rule text, or null
  if the rule is the entry's own explicit line), and `hasIgnoredChildren` so a
  collapsed folder can show a partial tick. Lazy is not optional — a data dir
  with history and logs is large, and `**/db/*` alone can hide thousands of
  files.
- `GET /ignore` — the raw `.gitignore` text, for a "source" tab. People who know
  gitignore should not be forced through a tree.
- `POST /ignore` — write it back. Two shapes: `{text}` for the raw editor, or
  `{add:[…], remove:[…]}` for tick/untick, so the UI never has to reconstruct a
  file it did not author.

All three take `DATA_DIR_LOCK`, like every other mutation in
`DataDirGitManager`.

**Write semantics** — the part worth getting right:

- Ticking a path *in* when a glob excludes it appends a negation (`!config/foo`)
  rather than deleting the glob. Deleting `**/logs` because someone wanted one
  file back is the kind of edit that quietly starts versioning a gigabyte of
  logs.
- Unticking a path appends a literal, anchored line (`/config/foo/`).
- Never rewrite or reorder existing lines. Append into a clearly marked block:

      # --- managed by the Versioning page below this line ---

  Everything above it stays exactly as the user or the template left it.
- **A tracked file is not un-tracked by a `.gitignore` line.** git ignores
  ignore-rules for files already in the index. Unticking a tracked path must
  also `git rm --cached` it, or the UI will claim an exclusion that has not
  happened. This is the single most likely defect in the feature.

**UI** — a new section on the Versioning page, in the existing `ConfigDrawer`
or as a second tab. `@inductiveautomation/ignition-web-ui` has no tree
component, so the tree is a `DataGrid` with an indent column and expand
chevrons (the pattern the platform's own file pickers use), or a small
hand-rolled list. Tri-state ticks: checked / unchecked / partial for a folder
whose children differ. Rows excluded by an inherited glob render disabled with
the rule as their tooltip.

### Risks

- Large directories: mitigated by lazy loading, but the first level of a data
  dir on a busy gateway still wants a cap and a "showing N of M" line.
- `.gitignore` edits are themselves auto-committed by `ConfigAutoCommitter`
  within the 2 s quiesce window, so each tick makes a commit. Debouncing the
  save (one commit per drawer-close, not per checkbox) is worth doing.

---

## 2. Designer change indicators — Project Browser and Tag Browser

**Goal:** VS Code-style decoration showing which resources are modified and not
yet committed, in the left-hand Project Browser and in the Tag Browser.

### The Project Browser has a real, public badge API

This was the open question and the answer is good. `AbstractNavTreeNode` (the
base of every project-browser node) exposes:

```java
public void addBadges(BadgeTreeCellRenderer renderer, boolean selected);
```

and `BadgeTreeCellRenderer` (extends `TreeCellRenderer`):

```java
void addBadge(Badge badge);
void addBadge(Badge badge, boolean selected);
```

`Badge` (`client.util.gui.tree.Badge`) takes a `VectorIcon` or `ImageIcon` plus
a tooltip, with `SIZE_12` / `SIZE_16` and selected/focused/disabled/inherited
variants. The Designer already ships badges of exactly this kind —
`BadgeFetcher.BadgeKeys` includes `CONCURRENT_USERS`, `RESOURCE_OVERRIDDEN`,
`RESOURCE_INHERITED`, `NOTES`, `UPDATE_CONFLICT`. So the visual language exists
and a git badge will look native rather than bolted on.

**The catch:** `addBadges` is called *on the node*, and the nodes belong to the
platform and to other modules — we cannot subclass Perspective's view nodes.
The injection point is the renderer instead:

1. `context.getDockingManager().getFrame(NavTreePanel.DOCKING_KEY)` → cast to
   `NavTreePanel`. `DesignerHook` already does exactly this lookup for
   `PROJECT_BROWSER_KEY` when docking the Commit panel, so the handle is free.
2. `navTreePanel.getTree()` is **public** → the `JTree`.
3. Wrap `tree.getCellRenderer()` in a delegating `BadgeTreeCellRenderer` that
   calls through, then adds our badge when the node's `ResourcePath` is in the
   changed set.

Step 3 is the one unverified assumption: whether a badge added *after* the
delegate's `getTreeCellRendererComponent` returns is still painted, or whether
badges must be added during it. If ordering turns out to be wrong, the fallback
is to add the badge before delegating, or to intercept `addBadge` calls. This
needs testing in the real Designer, not reasoning — use the `designer-drive`
skill.

### The Tag Browser needs a different mechanism

Tag nodes are not nav-tree nodes and have no badge API. They render through
`TagRenderer extends PanelBasedTreeCellRenderer`, which has:

```java
protected void addIcon(Icon icon);
protected void addIcon(Icon icon, String tooltip);
```

So the route is a **subclass** of `TagRenderer` overriding
`getTreeCellRendererComponent` — call `super`, then `addIcon(dirtyIcon,
"Modified since last snapshot")` for a matching tag path. Reachable via
`context.getTagBrowser()` (public on `DesignerContext`) → `getTabbedPanel()` →
the `TagBrowserTree` (which exposes `getTagRenderer()`), then `setCellRenderer`.
Slightly deeper reflection than the project side, but every hop is a public
member.

### Where the data comes from

For the Project Browser, nothing new is needed on the gateway.
`GitScriptInterface.getUncommitedChanges(projectName, userName)` already returns
a `Dataset` of `resource | type | actor | timestamp`, where `type` ∈
`Created | Modified | Deleted | Uncommitted` — the same feed `CommitPanel`
polls every 15 s. The Designer work is:

- map each repo-relative path to a `ResourcePath`
  (`projects/<p>/com.inductiveautomation.perspective/views/Foo/view.json` →
  `com.inductiveautomation.perspective/views/Foo`);
- roll the state up to ancestor folders, so a collapsed folder shows that
  something inside it changed — this is most of what makes VS Code's version
  readable;
- share one cached snapshot between the badge painter and `CommitPanel` instead
  of adding a second 15 s poll, and repaint via `tree.repaint()` on change.

Colour/glyph should follow VS Code's grammar because that is what Nigel asked
for: modified, added, deleted, untracked as distinct badges, and the tooltip
carrying the change type.

### The tag caveat — read this before promising the feature

**Tags are not versioned continuously.** This module versions tags only when
someone presses the "Tags" snapshot button, which writes the live tag
configuration into the project tree as files. So "changed and not pushed" for a
tag cannot mean what it means for a view. It can only mean *the live tag differs
from the last snapshot that was committed*, and computing that needs a real
diff of the live provider against the snapshot files — new gateway work, not a
read of the existing status feed.

That is a defensible and useful indicator, but it is a different promise from
the project-browser one, it costs a tag-tree-wide comparison, and the snapshot
read is already bounded by a 30 s timeout in `GitTagManager` for good reason.
Worth deciding deliberately: ship the project browser first, and treat the tag
browser as a second, larger piece.

### Risks

- Both mechanisms reach into Designer internals. `getTree()`, `getTagBrowser()`,
  `addIcon` and the badge API are public, but none is a documented extension
  point, so an 8.3.x update can move them. The codebase already carries this
  risk deliberately (`GitBaseAction` reflects into `IgnitionDesigner`), so the
  precedent and the failure style are established: guard every lookup and
  degrade to no badges rather than throwing.
- Renderer wrapping runs on every cell paint. The changed-path lookup must be a
  prepared `Set`/`Map` — no string building, no RPC, nothing allocating.
- Another module wrapping the same renderer would compose badly. Wrap once, at
  Designer startup, and keep a reference so we never double-wrap.

---

## Suggested order

1. Feature 1 — self-contained, all-public APIs, no reflection.
2. Feature 2, project browser only — verify the renderer-wrap question in the
   real Designer first, before building anything on top of it.
3. Feature 2, tag browser — only after deciding what "uncommitted" should mean
   for a snapshot-versioned resource.
