package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.GatewayHook;
import com.operametrix.ignition.git.managers.GitManager;
import com.operametrix.ignition.git.managers.GitProjectManager;
import com.operametrix.ignition.git.records.GitSyncRecord;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Inbound sync: fetch each configured project repository on a timer and fast-forward it when the
 * tracked branch has moved.
 *
 * <p>This is the default inbound mechanism rather than a GitHub webhook because a webhook needs
 * GitHub to open a connection <em>to</em> the gateway, which is not possible on most OT networks
 * — see {@code docs/AUTOMATION.md}. Polling reaches the same state with no inbound exposure and
 * works against any remote, not only GitHub.
 */
public final class SyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SyncScheduler.class);

    /** How often the scheduler wakes to see what is due; per-repository intervals are longer. */
    private static final int TICK_SECONDS = 15;

    private static ScheduledExecutorService executor;

    /** Repositories with a sync in flight — one pull per repository at a time. */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Project name to epoch-second of its last attempt. */
    private static final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    private SyncScheduler() {
    }

    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-sync");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(SyncScheduler::tick, TICK_SECONDS, TICK_SECONDS,
                TimeUnit.SECONDS);
        logger.debug("Git sync scheduler started.");
    }

    public static synchronized void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        inFlight.clear();
        lastRun.clear();
    }

    private static void tick() {
        try {
            long now = System.currentTimeMillis() / 1000L;
            for (GitSyncRecord cfg : GitSyncRecord.listEnabled()) {
                Long last = lastRun.get(cfg.getProject());
                if (last != null && now - last < cfg.getIntervalSeconds()) {
                    continue;
                }
                lastRun.put(cfg.getProject(), now);
                syncNow(cfg);
            }
        } catch (Throwable t) {
            // The scheduler must survive a bad configuration; scheduleWithFixedDelay cancels the
            // task permanently if this ever propagates.
            logger.error("Git sync tick failed.", t);
        }
    }

    /** Runs one repository's sync. Safe to call outside the timer (the Sync now button). */
    public static String syncNow(GitSyncRecord cfg) {
        String project = cfg.getProject();
        if (!inFlight.add(project)) {
            return "already running";
        }
        try {
            return run(cfg);
        } catch (Exception e) {
            logger.warn("Git sync failed for project '{}'.", project, e);
            GitEvents.fire(GitEvent.of(GitEvent.SYNC)
                    .project(project)
                    .user(cfg.getIgnitionUser())
                    .remote(cfg.getRemoteName())
                    .failure(GitEvents.reason(e)));
            return "failed: " + GitEvents.reason(e);
        } finally {
            inFlight.remove(project);
        }
    }

    private static String run(GitSyncRecord cfg) throws Exception {
        String project = cfg.getProject();
        String user = cfg.getIgnitionUser();
        String remoteName = cfg.getRemoteName();

        Path dir = GitManager.getProjectFolderPath(project);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            throw new IllegalStateException("project is not under version control");
        }

        try (Git git = Git.open(dir.toFile())) {
            Repository repo = git.getRepository();

            // An unborn repository needs a clone, not a merge. Sync is unattended, so it reports
            // the condition rather than deciding to materialise a whole project by itself.
            if (repo.resolve("HEAD") == null) {
                throw new IllegalStateException(
                        "repository has no commits yet — run Pull once from the Designer to finish it");
            }

            String branch = cfg.getBranch().isBlank() ? repo.getBranch() : cfg.getBranch();

            // Someone has a Designer open with unsaved work. Refuse: silently discarding or
            // stashing an engineer's changes is worse than not syncing.
            Status status = git.status().call();
            if (!status.isClean()) {
                String note = "local changes present (" + status.getUncommittedChanges().size()
                        + " uncommitted); not pulling";
                GitEvents.fire(GitEvent.of(GitEvent.SYNC).project(project).user(user)
                        .branch(branch).remote(remoteName).failure(note));
                return note;
            }

            FetchCommand fetch = git.fetch().setRemote(remoteName);
            GitManager.setAuthentication(fetch, project, user, remoteName);
            fetch.call();

            ObjectId local = repo.resolve(branch);
            ObjectId tracked = repo.resolve("refs/remotes/" + remoteName + "/" + branch);
            if (tracked == null) {
                throw new IllegalStateException(
                        "remote '" + remoteName + "' has no branch '" + branch + "'");
            }
            if (local != null && local.equals(tracked)) {
                return "up to date";
            }

            PullCommand pull = git.pull().setRemote(remoteName).setRemoteBranchName(branch);
            GitManager.setAuthentication(pull, project, user, remoteName);
            PullResult result = pull.call();

            if (!result.isSuccessful()) {
                MergeResult merge = result.getMergeResult();
                String reason = merge == null ? "pull rejected" : ("merge " + merge.getMergeStatus());
                throw new IllegalStateException(reason);
            }

            ObjectId after = repo.resolve(branch);
            List<String> changed = changedPaths(repo, local, after);

            // The pull changed files under data/projects; Ignition does not notice on its own.
            GitProjectManager.importProject(project);
            requestScan();

            GitEvents.fire(GitEvent.of(GitEvent.SYNC)
                    .project(project)
                    .user(user)
                    .branch(branch)
                    .remote(remoteName)
                    .commit(after == null ? "" : after.getName())
                    .message("Pulled " + changed.size() + " changed file(s) from " + remoteName
                            + "/" + branch)
                    .files(changed)
                    .success());
            return "pulled " + changed.size() + " file(s)";
        }
    }

    private static void requestScan() {
        try {
            if (GatewayHook.getContext() != null) {
                GatewayHook.getContext().getProjectManager().requestScan();
            }
        } catch (Exception e) {
            logger.warn("Project scan request after a git sync failed.", e);
        }
    }

    /** Paths that differ between two commits; empty when either end is unknown. */
    private static List<String> changedPaths(Repository repo, ObjectId from, ObjectId to) {
        List<String> out = new ArrayList<>();
        if (from == null || to == null || from.equals(to)) {
            return out;
        }
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             Git git = new Git(repo)) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, walk.parseCommit(from).getTree());
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, walk.parseCommit(to).getTree());
            for (DiffEntry entry : git.diff().setOldTree(oldTree).setNewTree(newTree).call()) {
                out.add(DiffEntry.ChangeType.DELETE.equals(entry.getChangeType())
                        ? entry.getOldPath() : entry.getNewPath());
            }
        } catch (Exception e) {
            logger.debug("Could not diff {}..{} for the sync event.", from.getName(), to.getName(), e);
        }
        return out;
    }

}
