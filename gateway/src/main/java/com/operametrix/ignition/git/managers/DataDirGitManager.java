package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RemoteSetUrlCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.operametrix.ignition.git.GatewayHook.getContext;

/**
 * Gateway data-directory ("config-as-code") versioning. A single git repo rooted at the Ignition
 * data directory tracks gateway config (primarily {@code <dataDir>/config/}); the per-project repos
 * under {@code <dataDir>/projects/} are versioned separately and are excluded here via {@code .gitignore}.
 *
 * <p>This is a thin orchestration layer over the static {@link GitManager} primitives — all of which
 * take a working-dir {@link Path} and are reused directly against {@link #dataDir()}. The only
 * config-specific logic lives here: init/{@code .gitignore}, a plain porcelain status (the
 * project-resource actor/metadata helpers in {@link GitManager} must NOT be applied to config files),
 * restore-to-version, and applying restored files to the running gateway via {@code requestScan()}.
 */
public class DataDirGitManager {
    private static final LoggerEx logger = LoggerEx.newBuilder().build(DataDirGitManager.class);

    /**
     * Serializes our own git mutations and status reads so a {@code /dirty} poll never observes a
     * half-staged index while the gateway concurrently rewrites {@code config/} files.
     */
    private static final Object DATA_DIR_LOCK = new Object();

    /** Config-as-code lives under {@code config/}; the {@code .gitignore} sits at the repo root. */
    private static final String[] SCOPE = { "config", ".gitignore" };

    /** Based on Inductive Automation's version-control-guide template, plus {@code projects/}. */
    private static final List<String> GITIGNORE_LINES = List.of(
            "# Ignition data-directory config-as-code — managed by the Git module",
            "**/db/*",
            "**/metricsdb/*",
            "**/autobackup/*",
            "**/db_backup_sqlite.idb",
            "**/valueStore.idb",
            // SQLite write-ahead-log sidecars. Without these the baseline `git add .` races the
            // tag value store: the directory scan lists valueStore.idb-wal, SQLite checkpoints and
            // deletes it, and the add dies with FileNotFoundException — so init could never
            // complete on a gateway that was actually running. Ignoring the .idb itself is not
            // enough; the sidecars are separate paths. (Gaskony fork, 07/09/2026.)
            "**/*-wal",
            "**/*-shm",
            "**/jar-cache/*",
            "**/request*",
            "**/response*",
            "*.tmp",
            "*.bak",
            "**/var",
            "*.log",
            "**/logs",
            "**/certificates/*",
            "**/keystore/",
            "**/config/local",
            "**/config/resources/local",
            "**/.container-init.conf",
            "**/conversion-report.txt",
            "**.digest.json",
            "**/migration-log-*.md",
            "**/.resources/",
            "**/.alarms_*",
            "",
            "# Per-project resources are versioned separately by this module's per-project repos",
            "projects/",
            "",
            "# Module-internal state at the data-dir root (not config), never versioned",
            ".git-module-legacy-migrated"
    );

    /** A single uncommitted config change. {@code type} ∈ ADDED | MODIFIED | DELETED | UNTRACKED. */
    public record ConfigChange(String path, String type) {}

    public static Path dataDir() {
        return GitManager.getDataFolderPath();
    }

    /** v1 "is config versioning enabled" signal — the repo state lives entirely in {@code .git}. */
    public static boolean isInitialized() {
        return Files.exists(dataDir().resolve(".git"));
    }

    /**
     * First-run initialization: {@code git init} at the data dir, write {@code .gitignore}, stage all
     * non-ignored files, and make the baseline commit. Explicit (never auto-run at startup).
     */
    public static void initRepo() {
        synchronized (DATA_DIR_LOCK) {
            if (isInitialized()) {
                throw new RuntimeException("Config versioning is already initialized.");
            }
            try (Git git = Git.init().setDirectory(dataDir().toFile()).call()) {
                GitManager.disableSsl(git);
                writeGitignore();
                stageScope(git, false);
                // Authored as the gateway (like the auto-commits), not the acting web user, so the
                // whole config history is uniformly attributed to the gateway.
                git.commit().setMessage("Initial config-as-code commit").setAuthor(gatewayAuthor(), "").call();
            } catch (Exception e) {
                logger.error("Error initializing config versioning repo", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** The gateway's system name — the author for config-repo commits (init + auto-commits). */
    private static String gatewayAuthor() {
        return getContext().getSystemPropertiesManager().getSystemName();
    }

    private static void writeGitignore() throws IOException {
        Path gitignore = dataDir().resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.write(gitignore,
                    (String.join("\n", GITIGNORE_LINES) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Porcelain list of uncommitted config changes, scoped to {@link #SCOPE} (JGit honors
     * {@code .gitignore}, so db/logs/keystore/projects never appear). JSON key-ordering-only
     * changes are suppressed via {@link GitManager#filterJsonOrderingChanges}.
     */
    public static List<ConfigChange> getStatus() {
        synchronized (DATA_DIR_LOCK) {
            List<ConfigChange> changes = new ArrayList<>();
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                Status s = scopedStatus(git);

                // Build path -> type with deletions taking precedence over modifications.
                TreeMap<String, String> byPath = new TreeMap<>();
                for (String p : s.getUntracked()) byPath.put(p, "UNTRACKED");
                for (String p : s.getAdded()) byPath.put(p, "ADDED");
                for (String p : s.getModified()) byPath.put(p, "MODIFIED");
                for (String p : s.getChanged()) byPath.put(p, "MODIFIED");
                for (String p : s.getMissing()) byPath.put(p, "DELETED");
                for (String p : s.getRemoved()) byPath.put(p, "DELETED");

                Set<String> survivors = GitManager.filterJsonOrderingChanges(repo, dataDir(), byPath.keySet());
                for (Map.Entry<String, String> e : byPath.entrySet()) {
                    if (survivors.contains(e.getKey())) {
                        changes.add(new ConfigChange(e.getKey(), e.getValue()));
                    }
                }
            } catch (Exception e) {
                logger.error("Error computing data-dir git status", e);
                throw new RuntimeException(e);
            }
            return changes;
        }
    }

    /**
     * Stage the same paths {@link #scopedStatus} reports, so what gets committed is exactly what the
     * page listed as changed. Staging "." instead committed anything outside {@code config/} that a
     * .gitignore edit happened to re-include — silently, since the change list never showed it.
     * {@code update} stages deletions of tracked files; the first pass adds new and modified ones.
     */
    private static void stageScope(Git git, boolean update) throws Exception {
        var add = git.add().setUpdate(update);
        for (String path : SCOPE) {
            add = add.addFilepattern(path);
        }
        add.call();
    }

    private static Status scopedStatus(Git git) throws Exception {
        var status = git.status();
        for (String path : SCOPE) {
            status = status.addPath(path);
        }
        return status.call();
    }

    /**
     * Auto-commit path ({@link ConfigAutoCommitter}): commit all dirty config authored as the
     * gateway (its system name), or no-op on a clean tree (avoids empty commits when a change
     * batch was already covered, e.g. the scan after a restore). Committing does not change
     * on-disk config, so the running gateway is already consistent — no {@code requestScan()}
     * here. Returns whether a commit was made.
     */
    public static boolean commitAllIfDirty(String message) {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || getStatus().isEmpty()) {
                return false;
            }
            try (Git git = GitManager.getGit(dataDir())) {
                stageScope(git, false);
                stageScope(git, true);
                git.commit().setMessage(message).setAuthor(gatewayAuthor(), "").call();
            } catch (Exception e) {
                logger.error("Error committing data-dir config", e);
                throw new RuntimeException(e);
            }
            return true;
        }
    }

    /** Paginated commit history for the config repo. */
    public static List<String[]> history(int skip, int limit) {
        return GitManager.getCommitLog(dataDir(), skip, limit);
    }

    /** Files changed in a commit, as {@code "CHANGE_TYPE:path"}. */
    public static List<String> commitFiles(String commitHash) {
        return GitManager.getCommitFileList(dataDir(), commitHash);
    }

    /**
     * {@code [oldContent, newContent]} for a file (JSON-normalized): at a commit when
     * {@code commitHash} is given, otherwise HEAD vs working tree (uncommitted changes).
     */
    public static List<String> fileDiff(String commitHash, String filePath) {
        if (commitHash == null || commitHash.isBlank()) {
            return GitManager.getWorkingTreeDiffContent(dataDir(), filePath);
        }
        return GitManager.getCommitFileDiffContent(dataDir(), commitHash, filePath);
    }

    // ----- Remote (manual push only — never pushed automatically) -----

    private static final String ORIGIN = "origin";

    /** Last manual push outcome for the page's status line (in-memory; resets on restart). */
    private static volatile long lastPushTime;
    private static volatile String lastPushError;

    public static long getLastPushTime() {
        return lastPushTime;
    }

    public static String getLastPushError() {
        return lastPushError;
    }

    /** Save (create or update) the config remote: persist the record and sync origin in .git/config. */
    public static void saveRemote(String uri, String branch, long sshKeyId, long httpsCredentialId) {
        if (uri == null || uri.isBlank()) {
            throw new RuntimeException("Remote URI cannot be empty.");
        }
        if (branch == null || branch.isBlank()) {
            throw new RuntimeException("Branch cannot be empty.");
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                URIish urIish = new URIish(uri.trim());
                if (git.remoteList().call().stream().anyMatch(r -> ORIGIN.equals(r.getName()))) {
                    RemoteSetUrlCommand setUrl = git.remoteSetUrl();
                    setUrl.setRemoteName(ORIGIN);
                    setUrl.setRemoteUri(urIish);
                    setUrl.call();
                } else {
                    git.remoteAdd().setName(ORIGIN).setUri(urIish).call();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            GitConfigRemoteRecord record = GitConfigRemoteRecord.get();
            if (record == null) {
                record = new GitConfigRemoteRecord();
            }
            record.setUri(uri.trim());
            record.setBranch(branch.trim());
            record.setSshKeyId(sshKeyId);
            record.setHttpsCredentialId(httpsCredentialId);
            record.save();
        }
    }

    public static void removeRemote() {
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                git.remoteRemove().setRemoteName(ORIGIN).call();
            } catch (Exception e) {
                logger.warn("Could not remove origin from the config repo", e);
            }
            GitConfigRemoteRecord record = GitConfigRemoteRecord.get();
            if (record != null) {
                record.delete();
            }
            lastPushTime = 0;
            lastPushError = null;
        }
    }

    /**
     * Completely remove config versioning: delete {@code <dataDir>/.git} (all history), the
     * {@code .gitignore}, and the remote record. **Credential records are kept** (they are shared
     * with the Designer per-project repos). Afterward {@link #isInitialized()} is false, so the
     * page reverts to the Initialize screen. Opens no JGit handle (so the working tree isn't
     * locked during the recursive delete).
     */
    public static void deleteRepo() {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized()) {
                throw new RuntimeException("Config versioning is not initialized.");
            }
            try {
                GitConfigRemoteRecord record = GitConfigRemoteRecord.get();
                if (record != null) {
                    record.delete();
                }
                deleteRecursively(dataDir().resolve(".git"));
                Files.deleteIfExists(dataDir().resolve(".gitignore"));
                lastPushTime = 0;
                lastPushError = null;
            } catch (Exception e) {
                logger.error("Failed to remove config versioning", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** Recursively delete a path (depth-first), tolerating a missing root. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not delete " + p, e);
                        }
                    });
        }
    }

    /** Manual push of the local history to the configured remote branch. */
    public static void push() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote configured.");
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                PushCommand push = git.push()
                        .setRemote(ORIGIN)
                        .setRefSpecs(new RefSpec("HEAD:refs/heads/" + remote.getBranch()));
                GitManager.setAuthenticationFromIds(push, remote.getUri(),
                        remote.getSshKeyId(), remote.getHttpsCredentialId());
                for (PushResult result : push.call()) {
                    for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                        RemoteRefUpdate.Status st = update.getStatus();
                        if (st != RemoteRefUpdate.Status.OK
                                && st != RemoteRefUpdate.Status.UP_TO_DATE) {
                            if (st == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                                throw new RuntimeException("Push rejected: the remote branch '"
                                        + remote.getBranch() + "' has commits this gateway doesn't "
                                        + "have (non-fast-forward). Reconcile them before pushing — "
                                        + "bring the remote's commits into this gateway, or reset the "
                                        + "remote branch if its history isn't needed (e.g. it was "
                                        + "seeded with a README).");
                            }
                            throw new RuntimeException("Push rejected: " + st
                                    + (update.getMessage() != null ? " — " + update.getMessage() : ""));
                        }
                    }
                }
                // Advance the local remote-tracking ref so the history can mark which commits are
                // on the remote (our refspec doesn't update it automatically).
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head != null) {
                    RefUpdate ru = repo.updateRef(trackingRef(remote.getBranch()));
                    ru.setNewObjectId(head);
                    ru.setForceUpdate(true);
                    ru.update();
                }
                lastPushTime = System.currentTimeMillis();
                lastPushError = null;
            } catch (Exception e) {
                String friendly = friendlyPushError(e);
                lastPushTime = System.currentTimeMillis();
                lastPushError = friendly;
                logger.error("Push of the config repo failed", e);
                throw new RuntimeException(friendly, e);
            }
        }
    }

    /** Map a raw push failure (transport/auth/JGit) to a concise, user-facing message. */
    private static String friendlyPushError(Exception e) {
        String raw = e.getMessage() == null ? e.toString() : e.getMessage();
        // Already-friendly messages we threw ourselves (e.g. the non-fast-forward explanation).
        if (raw.startsWith("Push rejected:")) {
            return raw;
        }
        String low = raw.toLowerCase();
        // Authorization: authenticated OK, but this credential can't push (read-only / no write
        // scope). JGit surfaces the refused receive-pack service, usually an HTTP 403.
        if (low.contains("receive-pack") || low.contains("not permitted")
                || low.contains(" 403") || low.contains("forbidden")
                || low.contains("permission denied") || low.contains("write access")) {
            return "Push denied: the credential can read this repository but isn't allowed to push "
                    + "to it. Grant it write access, or use a token/key with write (push) permission.";
        }
        // Authentication: the gateway couldn't sign in at all — wrong or expired credential.
        if (low.contains("not authorized") || low.contains("authentication")
                || low.contains("auth fail") || low.contains(" 401")
                || low.contains("invalid username") || low.contains("invalid credential")) {
            return "Authentication failed: the gateway couldn't sign in to the remote. Check the "
                    + "remote's credential — the token, password or SSH key may be wrong or expired.";
        }
        if (low.contains("unable to access") || low.contains("cannot open")
                || low.contains("unknownhost") || low.contains("unknown host")
                || low.contains("connection") || low.contains("timed out")
                || low.contains("not found") || low.contains("could not read")) {
            return "Could not reach the remote repository. Check the URL and that the gateway has "
                    + "network access to it.";
        }
        return "Push failed: " + raw;
    }

    private static String trackingRef(String branch) {
        return "refs/remotes/" + ORIGIN + "/" + branch;
    }

    /**
     * Ref pointers for the history: {@code [localHeadHash, remoteHeadHash]} (full hashes). The
     * remote head is the local remote-tracking ref that {@link #push()} advances; it is {@code ""}
     * when no remote is configured or nothing has been pushed. These mark just the two tip commits
     * (like git's branch pointers), not every commit.
     */
    public static String[] pointerHashes() {
        String local = "";
        String remote = "";
        if (!isInitialized()) {
            return new String[]{local, remote};
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head != null) {
                    local = head.getName();
                }
                GitConfigRemoteRecord r = GitConfigRemoteRecord.get();
                if (r != null) {
                    ObjectId trackingId = repo.resolve(trackingRef(r.getBranch()));
                    if (trackingId != null) {
                        remote = trackingId.getName();
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not resolve config repo ref pointers", e);
            }
        }
        return new String[]{local, remote};
    }

    /**
     * Number of commits on the local branch that are not yet on the remote tracking ref — the
     * "unsynced" count for the page's sync indicator. When nothing has been pushed yet, every
     * commit counts. 0 when no remote is configured or the branches are level.
     */
    public static int aheadCount() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        // Guard on the repo existing too: after a gwbk restore the remote record survives but
        // .git does not, and calling getGit() on the missing repo would log a spurious ERROR
        // on every /remote poll. Not-initialized ⇒ nothing pushed ⇒ 0 ahead.
        if (remote == null || !isInitialized()) {
            return 0;
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head == null) {
                    return 0;
                }
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.markStart(walk.parseCommit(head));
                    ObjectId trackingId = repo.resolve(trackingRef(remote.getBranch()));
                    if (trackingId != null) {
                        walk.markUninteresting(walk.parseCommit(trackingId));
                    }
                    int count = 0;
                    for (RevCommit ignored : walk) {
                        count++;
                    }
                    return count;
                }
            } catch (Exception e) {
                logger.warn("Could not compute the unsynced commit count", e);
                return 0;
            }
        }
    }

    /** Validate URI + credential with an ls-remote — touches no local or remote state. */
    public static void testRemote(String uri, long sshKeyId, long httpsCredentialId) {
        try {
            LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(uri).setHeads(true);
            GitManager.setAuthenticationFromIds(lsRemote, uri, sshKeyId, httpsCredentialId);
            lsRemote.call();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** Test with raw plaintext secrets (not yet persisted) — used by the inline Configure drawer. */
    public static void testRemoteRaw(String uri, String sshKeyPlaintext, String httpsUser,
                                     String httpsPassword) {
        try {
            LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(uri).setHeads(true);
            GitManager.setAuthenticationRaw(lsRemote, uri, sshKeyPlaintext, httpsUser, httpsPassword);
            lsRemote.call();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /**
     * Bring config up to the configured remote's HEAD and apply it to the running gateway. Handles
     * two entry states:
     * <ul>
     *   <li><b>Not initialized</b> — e.g. after a gateway-backup restore, which brings back
     *       {@code config/} and the surviving {@link GitConfigRemoteRecord} but <em>not</em>
     *       {@code <dataDir>/.git}: the repo is re-created, {@code origin} is wired from the record,
     *       and the remote branch is fetched and laid down.</li>
     *   <li><b>Initialized but behind</b> — fetch and fast-forward the working tree to the remote tip.</li>
     * </ul>
     * Refuses when the local branch has diverged (commits not on the remote) so nothing is discarded —
     * push or restore those first. The working tree is forced to exactly the remote tree the same way
     * {@link GitManager#restoreTree} does (overwrite tracked, {@code git clean} untracked), so gitignored
     * key material (keystore / projects / db / {@code config/local}) is preserved. Returns the new HEAD
     * short hash.
     */
    public static String updateFromRemote() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote is configured to update from.");
        }
        String branch = remote.getBranch();
        synchronized (DATA_DIR_LOCK) {
            boolean fresh = !isInitialized();
            try {
                if (fresh) {
                    // Re-establish the repo from the saved remote (post-restore recovery).
                    try (Git git = Git.init().setDirectory(dataDir().toFile()).call()) {
                        GitManager.disableSsl(git);
                        git.remoteAdd().setName(ORIGIN).setUri(new URIish(remote.getUri())).call();
                    }
                    if (!Files.exists(dataDir().resolve(".gitignore"))) {
                        writeGitignore();
                    }
                }
                try (Git git = GitManager.getGit(dataDir())) {
                    Repository repo = git.getRepository();

                    FetchCommand fetch = git.fetch().setRemote(ORIGIN)
                            .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
                    GitManager.setAuthenticationFromIds(fetch, remote.getUri(),
                            remote.getSshKeyId(), remote.getHttpsCredentialId());
                    fetch.call();

                    ObjectId remoteId = repo.resolve(trackingRef(branch));
                    if (remoteId == null) {
                        throw new RuntimeException("Branch '" + branch + "' was not found on the remote.");
                    }

                    ObjectId head = fresh ? null : repo.resolve(Constants.HEAD);
                    if (head != null) {
                        if (head.equals(remoteId)) {
                            return remoteId.abbreviate(7).name();  // already up to date
                        }
                        try (RevWalk walk = new RevWalk(repo)) {
                            boolean remoteContainsLocal = walk.isMergedInto(
                                    walk.parseCommit(head), walk.parseCommit(remoteId));
                            if (!remoteContainsLocal) {
                                throw new RuntimeException("Local config has commit(s) not on the remote. "
                                        + "Push or restore first — update-from-remote won't discard them.");
                            }
                        }
                    }

                    // Point the branch (and HEAD) at the remote tip, then bring the working tree to it.
                    git.branchCreate().setName(branch).setStartPoint(remoteId.name()).setForce(true).call();
                    repo.updateRef(Constants.HEAD).link("refs/heads/" + branch);
                    if (fresh) {
                        // Fresh repo laid over a restored working tree: config/ exists but is untracked,
                        // so a hard reset would hit checkout conflicts. Stage the remote tree (MIXED)
                        // then overwrite the tracked files via a path-checkout. clean() runs WITHOUT
                        // setCleanDirectories(true): a directory clean deletes untracked dirs wholesale,
                        // taking gitignored runtime data nested inside them with it (e.g. the tag value
                        // store config/ignition/tags/valueStore.idb) — JGit does not spare it.
                        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(remoteId.name()).call();
                        git.checkout().setStartPoint(remoteId.name()).setAllPaths(true).call();
                        git.clean().call();
                    } else {
                        // Existing repo (fast-forward): a hard reset moves tracked files (add/modify/
                        // delete) to the remote tip and leaves untracked/ignored runtime data — the tag
                        // value store, db, keystore — untouched. No clean needed.
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteId.name()).call();
                    }

                    applyConfigToRunningGateway();
                    return remoteId.abbreviate(7).name();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Update-from-remote failed", e);
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }

    /**
     * Restore config to exactly the state of {@code commitHash} and apply it to the running gateway.
     * Uses {@link GitManager#restoreTree} (keeps HEAD on the branch) then makes a forward
     * "Restore config to &lt;shortHash&gt;" commit, then triggers a config scan so the gateway picks
     * up the on-disk changes without a restart. No-op commit is skipped when nothing changed.
     */
    public static void restoreToCommit(String commitHash, String actingUser) {
        synchronized (DATA_DIR_LOCK) {
            String shortHash;
            try (Git git = GitManager.getGit(dataDir())) {
                ObjectId id = git.getRepository().resolve(commitHash);
                if (id == null) {
                    throw new RuntimeException("Commit not found: " + commitHash);
                }
                shortHash = id.abbreviate(7).name();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            GitManager.restoreTree(dataDir(), commitHash);

            try (Git git = GitManager.getGit(dataDir())) {
                Status s = git.status().call();
                boolean staged = !s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty();
                if (!staged) {
                    logger.infof("Restore to %s produced no changes; skipping commit.", shortHash);
                    return;
                }
                CommitCommand commit = git.commit().setMessage("Restore config to " + shortHash);
                commit.setAuthor(actingUser, GitManager.resolveUserEmail(actingUser));
                commit.call();
            } catch (Exception e) {
                logger.error("Error committing restore to " + commitHash, e);
                throw new RuntimeException(e);
            }
        }
        applyConfigToRunningGateway();
    }

    /** Apply on-disk config to the running gateway in-process (no restart). */
    public static void applyConfigToRunningGateway() {
        try {
            getContext().getConfigurationManager().requestScan().join();
        } catch (Exception e) {
            logger.error("Error requesting config scan after restore", e);
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // .gitignore management (the Versioning page's Excluded-files tree)
    // ------------------------------------------------------------------

    /**
     * Everything the page appends lives below this marker, so the template above it — and any
     * hand-written rule a user added — is never rewritten, reordered or lost.
     */
    private static final String MANAGED_MARKER = "# --- managed by the Versioning page below this line ---";

    /** How many entries a folder's include/exclude roll-up will visit before giving up. */
    private static final int ROLLUP_BUDGET = 2000;

    /**
     * One row of the exclusion tree.
     *
     * @param name      the entry's own name
     * @param path      repo-relative path, {@code /}-separated, no trailing slash
     * @param directory whether it is a folder
     * @param excluded  whether git currently ignores it
     * @param tracked   whether it is in the index — a tracked file stays tracked whatever
     *                  {@code .gitignore} says, which is why the two are reported separately
     * @param rule      the {@code .gitignore} line that decided it, or null if nothing matched
     * @param ownRule   true when {@code rule} is this exact path's own line (so unticking can
     *                  delete the line); false when an inherited glob decided it, and the row
     *                  must be read-only because unticking one path cannot undo a glob
     * @param childState for folders: INCLUDED, EXCLUDED, MIXED or UNKNOWN (roll-up budget spent)
     * @param reincludable whether ticking this row can actually re-include it. Git will not
     *                     re-include anything whose PARENT DIRECTORY is excluded, so a negation
     *                     under an excluded folder is silently ineffective — the row must be
     *                     read-only rather than offering an edit that does nothing
     */
    public record TreeEntry(String name, String path, boolean directory, boolean excluded,
                            boolean tracked, String rule, boolean ownRule, String childState,
                            boolean reincludable) {}

    /** Parsed {@code .gitignore} rules, newest last (git's last-match-wins order). */
    private static List<FastIgnoreRule> ignoreRules() throws IOException {
        Path gitignore = dataDir().resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            return List.of();
        }
        IgnoreNode node = new IgnoreNode();
        try (InputStream in = Files.newInputStream(gitignore)) {
            node.parse(in);
        }
        return node.getRules();
    }

    /**
     * Decide one path against the rules, git-style: the LAST matching rule wins, and a negation
     * (`!foo`) re-includes. Returns {@code null} when nothing matched.
     *
     * <p>Matching is done on the full repo-relative path AND on each trailing path segment, because
     * a rule without a slash (e.g. {@code *.log}) matches at any depth — {@code FastIgnoreRule}
     * itself only compares what it is handed.
     */
    private static FastIgnoreRule matchRule(List<FastIgnoreRule> rules, String path, boolean isDir) {
        FastIgnoreRule match = null;
        for (FastIgnoreRule r : rules) {
            if (r.isEmpty()) {
                continue;
            }
            boolean hit = r.isMatch("/" + path, isDir, true);
            if (!hit && r.getNameOnly()) {
                // A name-only rule applies to every segment, so test the basename too.
                int slash = path.lastIndexOf('/');
                hit = r.isMatch("/" + (slash >= 0 ? path.substring(slash + 1) : path), isDir, true);
            }
            if (hit) {
                match = r;
            }
        }
        return match;
    }

    /**
     * Whether a path is excluded, taking ancestors into account. Git cannot re-include a file whose
     * PARENT DIRECTORY is excluded, so an ancestor's exclusion is final for everything beneath it —
     * a negation deeper down is silently ineffective, and the UI must not offer it.
     *
     * @return the deciding rule and whether an ancestor (not the path itself) settled it
     */
    private static Decision decide(List<FastIgnoreRule> rules, String path, boolean isDir) {
        // Walk ancestors root-downwards; the first excluded ancestor settles it.
        StringBuilder walked = new StringBuilder();
        for (String segment : path.split("/")) {
            if (walked.length() > 0) {
                walked.append('/');
            }
            walked.append(segment);
            String sofar = walked.toString();
            boolean last = sofar.equals(path);
            FastIgnoreRule m = matchRule(rules, sofar, last ? isDir : true);
            if (m != null && m.getResult() && !last) {
                // An ancestor is excluded — nothing below it can be re-included.
                return new Decision(m, true);
            }
            if (last) {
                return new Decision(m, false);
            }
        }
        return new Decision(null, false);
    }

    /** The rule that settled a path, and whether it was inherited from an excluded ancestor. */
    private record Decision(FastIgnoreRule rule, boolean fromAncestor) {
        boolean excluded() {
            return rule != null && rule.getResult();
        }
    }

    /** Is this path in the index? Tracked files ignore {@code .gitignore} entirely. */
    private static boolean isTracked(Repository repo, String path, boolean isDir) throws IOException {
        DirCache cache = repo.readDirCache();
        if (!isDir) {
            return cache.findEntry(path) >= 0;
        }
        String prefix = path + "/";
        for (int i = 0; i < cache.getEntryCount(); i++) {
            if (cache.getEntry(i).getPathString().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * List one directory level of the data dir, with each entry's exclusion state. Lazy by design:
     * a data directory carries history, logs and caches, and eagerly walking it would be both slow
     * and pointless — the large directories are exactly the excluded ones.
     *
     * @param relPath repo-relative directory, or "" / null for the data-dir root
     */
    public static List<TreeEntry> listTree(String relPath) {
        synchronized (DATA_DIR_LOCK) {
            String base = normalize(relPath);
            Path dir = base.isEmpty() ? dataDir() : dataDir().resolve(base);
            if (!dir.normalize().startsWith(dataDir().normalize())) {
                throw new RuntimeException("Path escapes the data directory: " + relPath);
            }
            if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Not a directory: " + relPath);
            }
            List<TreeEntry> out = new ArrayList<>();
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                List<FastIgnoreRule> rules = ignoreRules();
                try (var stream = Files.list(dir)) {
                    List<Path> entries = stream.sorted(DIR_FIRST).toList();
                    for (Path p : entries) {
                        String name = p.getFileName().toString();
                        if (name.equals(".git")) {
                            continue; // the repo's own plumbing is not a config choice
                        }
                        boolean isDir = Files.isDirectory(p);
                        String path = base.isEmpty() ? name : base + "/" + name;
                        Decision d = decide(rules, path, isDir);
                        boolean excluded = d.excluded();
                        FastIgnoreRule own = matchRule(rules, path, isDir);
                        boolean ownRule = own != null && own == d.rule() && isOwnLine(own, path, isDir);
                        out.add(new TreeEntry(name, path, isDir, excluded,
                                isTracked(repo, path, isDir),
                                d.rule() == null ? null : d.rule().toString(), ownRule,
                                isDir ? rollUp(p, path, rules, excluded) : null,
                                !d.fromAncestor()));
                    }
                }
            } catch (Exception e) {
                logger.error("Error listing config tree at '" + relPath + "'", e);
                throw new RuntimeException(e);
            }
            return out;
        }
    }

    /** Folders before files, then case-insensitive by name — the order a file browser uses. */
    private static final java.util.Comparator<Path> DIR_FIRST =
            java.util.Comparator.<Path, Boolean>comparing(p -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

    /**
     * Whether the deciding rule is this path's own explicit line rather than an inherited glob.
     * Only an own line can be removed by unticking; a glob has to be negated instead.
     */
    private static boolean isOwnLine(FastIgnoreRule rule, String path, boolean isDir) {
        String text = rule.toString().trim();
        if (text.startsWith("!")) {
            text = text.substring(1);
        }
        String bare = text.startsWith("/") ? text.substring(1) : text;
        if (bare.endsWith("/")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        return bare.equals(path) || (isDir && bare.equals(path + "/"));
    }

    /**
     * Roll a folder's children up to INCLUDED / EXCLUDED / MIXED so a collapsed folder can show a
     * partial tick. Recursion stops at excluded directories — everything under one is excluded, and
     * those are the directories with a hundred thousand files in them.
     */
    private static String rollUp(Path dir, String path, List<FastIgnoreRule> rules, boolean selfExcluded) {
        if (selfExcluded) {
            return "EXCLUDED";
        }
        int[] budget = { ROLLUP_BUDGET };
        boolean[] seen = { false, false }; // included, excluded
        boolean complete = walkRollUp(dir, path, rules, budget, seen);
        if (!complete) {
            return "UNKNOWN";
        }
        if (seen[0] && seen[1]) {
            return "MIXED";
        }
        return seen[1] ? "EXCLUDED" : "INCLUDED";
    }

    private static boolean walkRollUp(Path dir, String path, List<FastIgnoreRule> rules,
                                      int[] budget, boolean[] seen) {
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (budget[0]-- <= 0) {
                    return false;
                }
                String name = p.getFileName().toString();
                if (name.equals(".git")) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p);
                String child = path + "/" + name;
                boolean excluded = decide(rules, child, isDir).excluded();
                seen[excluded ? 1 : 0] = true;
                if (isDir && !excluded && !walkRollUp(p, child, rules, budget, seen)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The raw {@code .gitignore}, for the page's source view. Empty string when there is none. */
    public static String readIgnoreFile() {
        synchronized (DATA_DIR_LOCK) {
            try {
                Path gitignore = dataDir().resolve(".gitignore");
                return Files.exists(gitignore)
                        ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                logger.error("Error reading .gitignore", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** Replace {@code .gitignore} wholesale — the source view's Save. */
    public static void writeIgnoreFile(String text) {
        synchronized (DATA_DIR_LOCK) {
            try {
                String body = text.endsWith("\n") ? text : text + "\n";
                Files.write(dataDir().resolve(".gitignore"), body.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.error("Error writing .gitignore", e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Apply tick/untick edits from the tree.
     *
     * <p>Excluding appends an anchored literal line. Including removes that line if the path owns
     * one, and otherwise appends a negation — never deleting the glob, because deleting
     * {@code **}{@code /logs} to recover one file is how a gateway starts versioning a gigabyte of
     * logs. Appends land under {@link #MANAGED_MARKER} so the template is left alone.
     *
     * <p>Excluding a TRACKED path also removes it from the index. A {@code .gitignore} line has no
     * effect on a file git is already tracking, so without this the page would report an exclusion
     * that had not happened.
     *
     * @return the number of paths whose index entry was dropped
     */
    public static int applyIgnoreEdits(List<String> exclude, List<String> include) {
        synchronized (DATA_DIR_LOCK) {
            int untracked = 0;
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                List<String> lines = new ArrayList<>(
                        List.of(readIgnoreFile().split("\n", -1)));
                // Drop the trailing empty produced by the split so appends don't drift downwards.
                if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                    lines.remove(lines.size() - 1);
                }

                for (String raw : include == null ? List.<String>of() : include) {
                    String path = normalize(raw);
                    if (path.isEmpty()) {
                        continue;
                    }
                    boolean isDir = Files.isDirectory(dataDir().resolve(path));
                    boolean removed = lines.removeIf(l -> isLiteralFor(l, path, isDir));
                    if (!removed) {
                        // An inherited glob excluded it — negate rather than weaken the glob.
                        appendManaged(lines, "!/" + path + (isDir ? "/" : ""));
                        if (isDir) {
                            // A rule like `**/certificates/*` excludes the CONTENTS, not the
                            // folder, so negating the folder alone re-includes nothing visible.
                            // Negate the contents too. Safe because the caller only offers the
                            // tick when no ancestor directory is excluded (see `reincludable`),
                            // which is the one case git refuses to re-include.
                            appendManaged(lines, "!/" + path + "/**");
                        }
                    }
                }

                for (String raw : exclude == null ? List.<String>of() : exclude) {
                    String path = normalize(raw);
                    if (path.isEmpty()) {
                        continue;
                    }
                    boolean isDir = Files.isDirectory(dataDir().resolve(path));
                    // A stale negation would beat the new exclusion, so clear it first.
                    lines.removeIf(l -> isNegationFor(l, path, isDir)
                            || (isDir && l.trim().equals("!/" + path + "/**")));
                    String literal = "/" + path + (isDir ? "/" : "");
                    if (lines.stream().noneMatch(l -> l.trim().equals(literal))) {
                        appendManaged(lines, literal);
                    }
                    if (isTracked(repo, path, isDir)) {
                        git.rm().setCached(true).addFilepattern(path).call();
                        untracked++;
                    }
                }

                writeIgnoreFile(String.join("\n", lines));
            } catch (Exception e) {
                logger.error("Error applying .gitignore edits", e);
                throw new RuntimeException(e);
            }
            return untracked;
        }
    }

    private static void appendManaged(List<String> lines, String line) {
        int marker = lines.indexOf(MANAGED_MARKER);
        if (marker < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(MANAGED_MARKER);
        }
        lines.add(line);
    }

    private static boolean isLiteralFor(String line, String path, boolean isDir) {
        String t = line.trim();
        return t.equals("/" + path) || t.equals(path)
                || (isDir && (t.equals("/" + path + "/") || t.equals(path + "/")));
    }

    private static boolean isNegationFor(String line, String path, boolean isDir) {
        String t = line.trim();
        return t.equals("!/" + path) || t.equals("!" + path)
                || (isDir && (t.equals("!/" + path + "/") || t.equals("!" + path + "/")));
    }

    /** Trim slashes and reject traversal, so a path parameter can never leave the data dir. */
    private static String normalize(String relPath) {
        if (relPath == null) {
            return "";
        }
        String p = relPath.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("..") || p.startsWith("../") || p.contains("/../") || p.endsWith("/..")) {
            throw new RuntimeException("Invalid path: " + relPath);
        }
        return p;
    }
}
