# Changelog

Gaskony builds of the OperaMetrix Git module. Versions up to 2.1.0 are
upstream's; everything below is this fork.

## [2.12.6] - 2026-09-09

### Changed
- **Importing images now merges instead of replacing.** A pull or clone adds and updates
  the images the project carries and leaves everything else in the gateway store alone.
  Previously the whole store was cleared first, which made one project's `images/` folder
  authoritative for a resource that is gateway-wide, not per project — so importing a
  project could delete another project's images even when the import itself succeeded.
  2.12.5 stopped the empty-snapshot case; this removes the destructive behaviour entirely.

  An image dropped from a project therefore stays on the gateway and needs deleting by
  hand. That is the deliberate trade: a stale image is a tidy-up, someone else's deleted
  image is a restore from backup.

  Imports are also idempotent now — an image already present with identical bytes is
  skipped rather than re-inserted, so a routine pull no longer churns the whole store.

## [2.12.5] - 2026-09-09

### Fixed
- **Cloning a project no longer wipes the gateway's image library.** `importImages`
  cleared the ENTIRE gateway image store and then uploaded whatever the project
  carried in `images/`. The store is gateway-scoped, not per project, so a project
  with no `images/` folder cleared it and restored nothing — deleting the platform's
  704 Builtin icons and any other project's images along with them. The clone path
  calls this unconditionally, so it happened on the first clone. It now returns
  early when the project has no snapshot to import, matching `importTagManager` and
  `importTheme`, which have always no-opped in that case.

  A project that *does* carry `images/` still replaces the whole gateway store, which
  is upstream's deliberate design. That remains a sharp edge: it is a gateway-wide
  operation driven by one project's contents.

## [2.12.4] - 2026-09-09

### Fixed
- **Cloning a project this gateway has never had now works.** The import step called
  `createOrReplace`, which refuses a non-empty directory for a collection Ignition
  does not already know — and a clone produces exactly that. The files landed, the
  call threw *"exists but is not empty"*, and the rollback removed the repository
  and the config records while leaving the checked-out files on disk. A project new
  to the gateway is now adopted from disk by a scan instead. Replacing an existing
  project is unchanged.

## [2.12.3] - 2026-09-09

### Fixed
- **A resource deleted on the remote no longer survives a clone.** The checkout's
  cleanup was `git.clean().setForce(true)` — `git clean -f` with no `-d` — and JGit
  leaves an entirely untracked directory alone, as does `reset --hard`. A view
  removed from the remote therefore stayed on disk through the checkout and came
  back as *Added* in the pulling gateway's Changes list. Cleanup now sets
  `setCleanDirectories(true)`; ignored files are still left alone.

## [2.12.2] - 2026-09-08

### Fixed
- **Dropdown selections are saved.** The platform's `SelectInput` spreads its rest
  props onto a MUI Select, so `onChange` receives MUI's event, not the chosen value.
  Reading it as a string stored the event object instead, and the save then failed on
  the gateway with `UnsupportedOperationException: JsonObject` — a 500 naming a Gson
  type rather than the field at fault. As with the `values` prop, the same mistake was
  in the Credentials and Projects tabs shipped in 2.11.0.
- `optString` ignores a non-primitive field instead of throwing, so a wrong-shaped
  request can no longer produce an opaque 500.

## [2.12.1] - 2026-09-08

### Fixed
- **Dropdowns render instead of blanking the page.** The platform's `SelectInput`
  takes its items as `values`, not `options`; with the wrong prop it read `.find` off
  `undefined` and React unmounted the whole page to "Application Error". This also
  fixes two paths shipped in 2.11.0 that had the same mistake and were never exercised:
  choosing a stored secret on the Credentials tab, and picking a credential when
  cloning from the Projects tab. Both blanked the page as soon as the dropdown
  appeared.

## [2.12.0] - 2026-09-08

### Added
- **Automation tab** on the Versioning page — three things that previously needed
  scripts written on a gateway you could already reach.
- **Git events.** Every commit, push, pull, checkout, branch, revert and config
  auto-commit raises an event, successes and failures alike, delivered to a project
  library function and/or a Gateway Event message handler as a dictionary. Failures
  carry the reason, because "the nightly push has been failing for a week" is the
  thing worth knowing. Delivery is asynchronous on a bounded queue: a broken handler
  cannot slow a commit down, let alone fail one.
- **Outbound triggers.** A matching event calls a URL, with GitHub's
  `repository_dispatch` and `workflow_dispatch` as one-click presets. Generic on
  purpose — the same rule shape serves GitLab, Jenkins, Teams or another gateway.
  Owner and repo are derived from the repository's own remote, so one rule can serve
  every project. The token is a stored HTTPS credential referenced by id, never held
  in the rule and never logged.
- **Scheduled sync.** The gateway fetches each enabled project repository on a timer
  and fast-forwards when the tracked branch has moved, then requests a project scan.
  Polling rather than a GitHub webhook is deliberate: a webhook needs GitHub to reach
  *into* the gateway, which is not possible on most OT networks. A sync refuses when
  the working tree has local changes rather than discarding someone's unsaved work,
  and one repository syncs at a time.
- **Event log** on the same tab — the last 50 events and what the gateway did with
  each, which is where a handler that silently does nothing becomes visible.

### Fixed
- `checkoutRemote` honours the remote it was given. It hardcoded `origin`, so a pull
  on an unborn repository configured with a differently named remote would silently
  contact the wrong one.

## [2.11.1] - 2026-09-08

### Fixed
- **Initialize-from-remote no longer strands a project on an unborn branch.** An init
  that failed after creating `.git` — a token awaiting approval, say — left the
  directory behind when it rolled its records back. The retry then registered the
  project and skipped the clone, because the check for existing work was "does `.git`
  exist" rather than "does this repository have a commit". The project sat on an
  unborn `master` with every resource showing as a change, and Pull asked the remote
  for a branch that had never existed there: *Remote origin did not advertise Ref for
  branch master*.

  An existing `.git` with no HEAD is now treated as an unfinished clone and completed,
  a `.git` created by a failed attempt is deleted so the retry starts clean, and Pull
  on a repository with no HEAD finishes the clone instead of running a merge. A
  project already in this state recovers with one Pull after upgrading.

## [2.11.0] - 2026-09-08

### Added
- **Credentials tab** on the Versioning page. SSH keys and HTTPS credentials could
  only be created from the Designer's setup wizard, which put them behind the thing
  they are needed for: you cannot clone a project without a key, and you could not
  make the key without opening a Designer. The gateway routes already existed and
  nothing called them.
- **Projects tab** listing every project on the gateway with its branch, remote and
  uncommitted count, and whether it is under version control at all. Unversioned
  projects are listed too — "not in git" and "not on this gateway" are otherwise
  indistinguishable. A project can be initialised, cloned, or given a remote from
  here without opening a Designer.

### Fixed
- The page no longer hides everything behind "config versioning is not initialized".
  That gate is specific to the History and Excluded files tabs, and applying it to
  the whole page put the credentials you need BEFORE any setup behind the setup you
  cannot do without them.

## [2.10.0] - 2026-09-08

### Changed
- The Changes list and the Project Browser badges refresh when you save, rather
  than on the next tick of the 15-second poll. Saving is when the change set
  changes, so waiting on a timer (or pressing refresh) was the wrong default.
  The poll remains as a backstop.

## [2.9.0] - 2026-09-08

### Fixed
- Change badges no longer overlap the resource name. The dot is drawn on the
  corner of the resource's icon instead of after its text: the nav tree's
  renderer sizes itself from icon and text and ignores a border's insets, so the
  trailing space the badge relied on was never reserved.

## [2.8.0] - 2026-09-07

### Fixed
- A nested git repository is no longer reported as a config change. `config/`
  can contain one, which left the repository permanently dirty and produced an
  empty commit on every config change.

## [2.7.0] - 2026-09-07

### Changed
- The Excluded files tree is rooted at `config/` — the only path the repository
  versions — and opens its top level. Rooted at the data directory it showed
  forty rows of runtime state with the versioned folder collapsed among them.
- The tree box is sized to its content rather than always claiming the window.
- Renamed to "Git Integration" with a Gaskony description. The module id and the
  `com.operametrix.*` packages are unchanged so upstream merges still apply.

### Fixed
- Commits stage exactly the paths the change list reports. They previously
  staged the whole data directory, so databases, caches and logs were committed
  silently while the change list showed only `config/`.

## [2.5.0] - 2026-09-07

### Fixed
- Badges keep rendering after the first commit of a Designer session. They are
  drawn by a border rather than through the platform's badge collection, which
  silently stops painting once a commit has been made.

## [2.4.0] - 2026-09-07

### Added
- Deleted resources show as a red badge on the ancestor folder. A deleted
  resource has no tree node left, so the roll-up is the only place it can appear.

## [2.3.0] - 2026-09-07

### Added
- Change badges in the Designer's Project Browser: green created, amber changed,
  with a roll-up on ancestor folders.
- Excluded files tab on the gateway Versioning page — a tree of the data
  directory where a tick means versioned, editing `.gitignore` directly.

### Fixed
- `initRepo` no longer races the tag value store. The scan listed
  `valueStore.idb-wal`, SQLite deleted it, and the add died with
  `FileNotFoundException` — so config versioning could never be initialised on a
  running gateway. `*-wal` and `*-shm` are now ignored.
