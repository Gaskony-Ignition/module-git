# Git Integration

Version control for Ignition 8.3 — project resources from the Designer, gateway
configuration from the gateway. A Gaskony build of
[OperaMetrix's Git module](https://github.com/operametrix/ignition-git-module),
under the Beerware licence.

## Why this exists

Two people editing the same gateway is two people overwriting each other, and a
gateway backup is a snapshot, not a history: it cannot tell you what changed
between Tuesday and Friday or who changed it. Ignition has no built-in answer,
so the answer is the one every other trade already uses — put it in git.

Upstream covers the Designer half well. This build adds the two things that were
missing in practice: seeing at a glance which resources you have not committed,
and controlling what the gateway-config repository actually versions. The second
one was not cosmetic — before it, a config repo on a working gateway grew to
827 MB of SQLite databases and log files while showing an empty change list.

## What it looks like

Uncommitted work is marked in the Project Browser, so you find it without
opening the Commit panel. Green is new, amber is changed, and a folder carries
the state of what is inside it — red if something under it was deleted, since a
deleted resource has no tree node of its own to mark.

![Change badges in the Designer's Project Browser](docs/images/project-browser-badges.png)

The gateway's Versioning page decides what config-as-code covers. The tree is
rooted at `config/`, because that is the only thing the repository versions, and
a ticked row is a versioned one. Rows struck through are excluded, with the
`.gitignore` rule that excluded them shown on the right.

![The Excluded files tree on the gateway Versioning page](docs/images/excluded-files.png)

## What it does

**In the Designer** — clone or initialise a project repository, manage remotes
and credentials, commit from a dockable panel with per-resource selection, amend
the last commit, browse history and diffs, and push or pull. Uncommitted
resources are badged in the Project Browser.

**On the gateway** — versions `config/` as code, commits automatically when a
config resource changes, shows history and per-commit diffs, restores a previous
commit, and pushes to a remote. The Excluded files tree edits `.gitignore`
directly: ticking a tracked path also untracks it, and rules you wrote by hand
are never rewritten.

Changes over upstream 2.1.0:

- Excluded-files tree on the Versioning page, and `.gitignore` editing.
- Change badges in the Designer's Project Browser.
- Commits stage exactly what the change list shows. They previously staged the
  whole data directory.
- A nested repository is no longer reported as a change, which otherwise made
  the repo permanently dirty and produced an empty commit per config change.
- `initRepo` no longer races the tag value store — `*-wal`/`*-shm` are ignored.
  Without this, config versioning could not be initialised on a running gateway.

## How to use it

Install the signed `.modl` from the latest release through the gateway's
**Config → Modules → Install or Upgrade Module**, then restart the gateway.

Project versioning: open a project, click **Configure** in the Designer's status
bar, and either clone a remote or initialise locally. Commit from the **Commit**
tab beside the Project Browser.

Gateway config versioning: **Platform → System → Versioning → Initialize
versioning**. Adjust what is covered under **Excluded files**.

To build from source you need `gradle.properties` with the signing block —
copy it from `gradle.template.properties` and fill in the keystore details:

```bash
./gradlew build      # -> build/GitIntegration.modl, signed
```

The module version lives in `version.properties` and nowhere else.

## Licence

Beerware (Revision 42) — see [LICENSE.md](LICENSE.md) and `license.html`. The
original notice is retained; this build is modified and distributed by Gaskony.
