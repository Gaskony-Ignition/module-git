# Changelog

Gaskony builds of the OperaMetrix Git module. Versions up to 2.1.0 are
upstream's; everything below is this fork.

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
