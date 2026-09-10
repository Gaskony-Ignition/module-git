# Automation — design note

Status: **proposal, awaiting sign-off.** Nothing here is built.

Three features, in the order they should be built. Each is independently useful;
each later one assumes the earlier ones exist.

1. **Git events** — a git operation raises an event the gateway can act on.
2. **Outbound triggers** — a successful push calls out to GitHub Actions, or anything else.
3. **Inbound sync** — a merge on the remote lands on the gateway.

---

## What the existing code gives us for free

**Every git operation already runs in gateway scope.** The Designer holds no
JGit; `GitActionManager` makes RPC calls that land in `GatewayScriptModule`
(project repositories) or `DataDirGitManager` (the data-directory config
repository). So instrumenting "a commit happened" is two choke points, not a
search for call sites, and it captures Designer activity, Versioning-page
activity and scripted activity with the same code.

**Credentials already exist.** `GitUserHttpsCredentialRecord` is host +
username + secret, where the secret is either inline or a reference into a
Secret Provider. A GitHub PAT fits that shape unchanged.

**Config storage has a house pattern.** `GitConfigRemoteRecord` is the model to
copy: a `record Config(...)`, a `ResourceTypeMeta`, a `NamedResourceHandler`.
New settings follow it rather than inventing storage.

---

## 1. Git events

### Mechanism

A `GitEvents.fire(GitEvent)` call at the end of each successful mutating
operation — commit, push, pull, checkout, branch create/delete, revert, and the
config repository's auto-commit. Failures fire too, with the reason; "the
nightly push has been failing for a week" is the thing you actually want to
know about.

The event is a flat map, because it has to survive the trip into Jython:

```
type        commit | push | pull | checkout | branch | revert | autocommit
outcome     success | failure
scope       project | config
project     "Mining_Demo"        (empty for the config repository)
user        the Ignition user who caused it
branch      "main"
remote      "origin" / the URL, when the operation had one
commit      full hash, when the operation produced or moved to one
message     commit message, or the failure reason
files       list of paths
timestamp   ISO-8601
```

### Delivery

The module runs the event through a configured **project script function** in
gateway scope — `Git.Events.onGitEvent(event)` or whatever the site names it —
using the gateway's script manager. One handler, one dict, and everything a
site wants to do with it (tag write, alarm, database row, `system.net.httpPost`,
notify a second gateway) is ordinary Jython in a project library where it can be
edited without a module rebuild.

Firing is asynchronous on a small bounded queue. A handler that blocks must not
be able to stall a commit, and a slow handler must not silently drop events —
so the queue is bounded and a full queue logs and counts rather than blocking.

> Unverified: whether a module can also raise a **gateway message handler**
> (`system.util.sendMessage`-style) cleanly from Java on 8.3. If it can, it is a
> nicer fit than naming a script function and should be offered alongside. The
> script-function path is certain to work, so it is the one specified here.

### Config surface

Versioning page, a new **Automation** tab: enable/disable, the project and
script path of the handler, and the event types to fire. Plus a live tail of the
last ~50 events, which doubles as the diagnostic when a handler does nothing.

### Scope note

This is a notification bus, not a policy engine. It does not veto a commit —
that is a pre-commit hook, and running shell out of the data directory on a
gateway is not something this module should do.

---

## 2. Outbound triggers

### Mechanism

On a git event matching a configured rule, POST to a URL. Deliberately generic —
GitHub Actions is one target, but the same mechanism covers GitLab, Jenkins,
Teams, a Perspective session on another gateway, or the toolkit.

A rule is:

```
when        event type + outcome + optional project/branch filter
url         https://api.github.com/repos/OWNER/REPO/dispatches
method      POST
headers     Authorization / Accept / X-Custom-*  (values may reference a credential)
body        JSON template, with ${project} ${branch} ${commit} ${user} substitution
```

Two presets ship, because getting GitHub's dispatch API right from the docs is
a twenty-minute job nobody should repeat:

- **`repository_dispatch`** — `POST /repos/{owner}/{repo}/dispatches`, body
  `{"event_type": "ignition-push", "client_payload": {…}}`. The workflow keys on
  `on: repository_dispatch`. Best for "the gateway pushed, go do something".
- **`workflow_dispatch`** — `POST /repos/{owner}/{repo}/actions/workflows/{file}/dispatches`,
  body `{"ref": "main", "inputs": {…}}`. Runs one named workflow. Best when you
  want to pass inputs to a specific pipeline.

Owner and repo are derived from the repository's own remote URL, so the common
case needs a token and nothing else.

### Credentials

Reuse `GitUserHttpsCredentialRecord`. The PAT needs `repo` scope for a private
repository (`public_repo` if not); classic tokens also need `workflow` scope for
the workflow-dispatch form. The token is referenced by id from the rule, never
stored in the rule, and never written to a log — the request logger records
method, host, path and status only.

### Safety

Outbound only, so no new attack surface on the gateway. Connect and read
timeouts, TLS verification on, redirects not followed across hosts, a retry
budget of two with backoff, and a per-rule circuit breaker so a dead endpoint
does not turn every commit into a 30-second stall.

### What this buys you

CI on Ignition project resources: a push from the Designer triggers a workflow
that lints the exported JSON, diffs view structure, or deploys the project to a
second gateway. It is the half of GitOps that works from behind a firewall.

---

## 3. Inbound sync

The goal: a merge to `main` on GitHub appears on the gateway, without anyone
opening a Designer.

### The reachability problem — read this before choosing

A GitHub webhook is GitHub making an inbound HTTPS connection **to your
gateway**. `ignition-module-testing` is on a VMware NAT network at
192.168.153.128; GitHub cannot reach it, and neither can it reach most customer
gateways, which is the normal condition for an OT network rather than an
accident. A webhook receiver would therefore be a feature that cannot be
demonstrated on the gateway it was built on, and cannot be used at most sites.

So inbound sync ships as **two mechanisms behind one setting**:

**3a. Polling (default, and what should be built first).**
A scheduled task per repository: `git fetch`, compare the tracked remote branch
to local, pull if it moved. No inbound exposure, works behind NAT, works with no
GitHub involvement at all — including against a local bare repo or a self-hosted
GitLab. Interval configurable, default 5 minutes. The cost is latency and a
fetch every interval, both of which are nothing.

**3b. Webhook (optimisation, for gateways that are actually reachable).**
Same pull logic, triggered by a POST instead of a timer. Worth building only
once polling works, because it is the same action with a harder front door.

### Webhook security model

Every existing route is `requirePermission(READ|WRITE)` and every mutation
requires an `X-CSRF-Token` from a logged-in web session. **GitHub has neither.**
The webhook route must therefore be mounted outside that model, which is the
whole security design:

- **HMAC.** GitHub signs the body with a shared secret and sends
  `X-Hub-Signature-256`. Verify HMAC-SHA256 over the **raw bytes**, with a
  constant-time compare. The raw body must be captured before any JSON parse —
  a re-serialised body will not match.
- **Fail closed.** No secret configured ⇒ the route 404s. Not enabled ⇒ 404.
  Never a default secret, never an "insecure mode" flag.
- **Narrow accept.** Only `X-GitHub-Event: push`, only when `ref` matches the
  repository's configured branch. Everything else is a 204 and a log line.
- **Replay.** Keep a bounded set of recent `X-GitHub-Delivery` ids and drop
  repeats.
- **Single-flight.** One pull per repository at a time; a burst of pushes
  coalesces into one pull, and the route returns 202 immediately rather than
  holding GitHub's connection open for the duration of a clone.
- **Optional source allowlist**, from GitHub's published hook ranges.

### The dirty-tree question

Either mechanism can find local uncommitted changes — someone has a Designer
open. **Refuse, do not stash and do not force.** Log it, fire a `pull/failure`
git event so feature 1 can raise an alarm, and leave the tree alone. Silently
reverting an engineer's unsaved work is worse than not syncing.

### After the pull

Pulling changes files under `data/projects/<name>/`, which Ignition does not
notice on its own. The pull is followed by a project scan request so the gateway
picks the resources up. Whether a Designer with the project open reloads
cleanly needs testing — that is the main unknown in this feature and the reason
it is third.

---

## Build order and rough size

| | Feature | Depends on | Size |
|---|---|---|---|
| 1 | Git events + Automation tab | — | small |
| 2 | Outbound triggers + GitHub presets | 1 | small |
| 3a | Poll-and-pull sync | 1 | medium |
| 3b | Webhook receiver | 3a | medium, security-sensitive |

1 and 2 together are one release. 3a is the next. 3b only if a gateway that
GitHub can reach is actually in scope.

## Open questions for sign-off

- **Handler shape.** One project script function for all events, or a script
  path per event type? One function with a `type` field is simpler and is what
  is specified; per-type is more discoverable in the Designer tree.
- **Which gateway runs a scheduled sync in a redundant pair?** Master only,
  presumably — but it needs stating before 3a is built.
- **Config repository as well as projects?** The data-directory repository can
  push already. Should it also poll and pull? Restoring gateway configuration
  from a remote automatically is a much larger blast radius than a project, and
  the current design says no.

---

## 3b: built in 2.13.0, removed in 2.14.0

It was built and then taken out again, so the reasoning is worth keeping.

The receiver worked. It dispatched on `X-GitHub-Event`, authenticated each
delivery by HMAC over the raw bytes, rejected replays, and pulled the matching
project — proved on the test gateway with signed synthetic deliveries.

What it could never do here is receive a delivery from GitHub. A webhook is
GitHub opening a connection **to** the gateway, and a credential cannot create
an inbound route: an HTTPS token or an SSH key authenticates the gateway calling
**out**, which is the direction that already works. Proving the feature would
have meant putting a gateway on the public internet behind a tunnel, and keeping
it working would mean every site doing the same.

So the module carries a second inbound mechanism nobody could use, with the
security surface of an unauthenticated route, to save a poll interval. Scheduled
sync reaches the same state over the connection every gateway already has.

Do not rebuild this without a concrete gateway that GitHub can reach.

---

## 3c: the runner, built in 2.16.0

The webhook's problem was direction, not intent. Push-time sync is still worth
having; it just cannot be GitHub dialling in.

A GitHub Actions **self-hosted runner** inverts the connection. It is a service
on your own network that long-polls GitHub over outbound HTTPS and is handed
workflow jobs on that same connection. Nothing has to reach the gateway from
outside, no public address exists, and no HMAC secret is shared with a third
party. The workflow step then calls the gateway across the local network.

So the module does two things:

- **Generates the setup.** The `config.sh` registration command carrying the
  project's own repository URL and the runner labels, the workflow YAML
  targeting those same labels, and the `curl` that proves reachability before a
  workflow depends on it. These four values — repository, labels, gateway
  address, token — have to agree across two systems and three files, and one of
  them being subtly wrong produces a workflow that queues forever with no error.
  That failure is the whole reason the tab exists.
- **Opens one route for the runner to call.** `POST /runner-sync`, bearer token
  compared in constant time, 64 KB body cap, 404 until a token is generated. The
  token is generated by the gateway, stored encrypted, and returned exactly
  once.

### What it deliberately does not do

The module does not install, register or supervise the runner. A runner executes
whatever the workflow file says. Hosting one from inside the module would put
repository-supplied shell next to the project store under the gateway's own
identity, would need a stored GitHub admin credential to keep re-fetching hourly
registration tokens, and would vanish on a container recreate. Generating the
commands costs nothing and leaves the trust boundary where it belongs.

### Why this is not the webhook again

The route is unauthenticated by the platform's own session machinery, exactly as
the webhook's was, and that is the fair objection. The difference is who can
reach it. A webhook route is only useful if the internet can address it; this
one is only ever called from inside the same network, so it can sit behind
whatever already protects the gateway. Turning the feature off closes it
entirely — with no token, it is a 404.


### The workflow file is committed by the gateway (2.17.0)

The module holds push rights for the project repository already. Generating a
file for someone to copy into that same repository by hand was a step with no
purpose, so *Commit the workflow to the repository* does it: write, commit
through the ordinary project-commit path, push.

The file goes to `.github/workflows/ignition-sync.yml` **inside the project
folder**, because for a project repository the project folder is the repository
root, and GitHub reads workflows only from the root. The leading dot keeps it
out of Ignition's resource scan — verified on 8.3.8, no scan error and the
project stays healthy.

Two refusals worth keeping: it will not overwrite a workflow that is already
there and differs (it may have gained steps that are nothing to do with this
module), and re-running it with nothing changed reports `unchanged` rather than
making an empty commit. A commit that cannot be pushed reports exactly that; the
commit stands.
