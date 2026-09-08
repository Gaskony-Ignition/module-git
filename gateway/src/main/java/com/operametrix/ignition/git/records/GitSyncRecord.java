package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Poll-and-pull settings for one project repository, keyed by project name.
 *
 * <p>Inbound sync is a scheduled fetch rather than a webhook because a gateway is usually not
 * reachable from GitHub — see {@code docs/AUTOMATION.md}. The {@code ignitionUser} names whose
 * stored credential authenticates the fetch; sync is unattended, so it cannot borrow the
 * credential of whoever happens to be in a Designer.
 */
public class GitSyncRecord {

    public record Config(long id, String project, boolean enabled, String remoteName,
                         String branch, int intervalSeconds, String ignitionUser) {}

    public static final ResourceType TYPE =
            new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-sync");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Sync")
                    .build();

    public static final class Handler extends NamedResourceHandler<Config> {
        public Handler(GatewayContext context) {
            super(context, META);
        }
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    /** Below this the fetch cost stops being negligible; the UI clamps to it too. */
    public static final int MIN_INTERVAL_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_SECONDS = 300;

    private long id;
    private String project = "";
    private boolean enabled;
    private String remoteName = "origin";
    private String branch = "";
    private int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    private String ignitionUser = "";

    public GitSyncRecord() {
    }

    private GitSyncRecord(Config c) {
        this.id = c.id();
        this.project = nz(c.project());
        this.enabled = c.enabled();
        this.remoteName = c.remoteName() == null || c.remoteName().isBlank() ? "origin" : c.remoteName();
        this.branch = nz(c.branch());
        this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, c.intervalSeconds());
        this.ignitionUser = nz(c.ignitionUser());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public long getId() {
        return id;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String v) {
        this.project = nz(v);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public String getRemoteName() {
        return remoteName;
    }

    public void setRemoteName(String v) {
        this.remoteName = v == null || v.isBlank() ? "origin" : v;
    }

    /** Tracked branch; empty means whatever the repository currently has checked out. */
    public String getBranch() {
        return branch;
    }

    public void setBranch(String v) {
        this.branch = nz(v);
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int v) {
        this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, v);
    }

    public String getIgnitionUser() {
        return ignitionUser;
    }

    public void setIgnitionUser(String v) {
        this.ignitionUser = nz(v);
    }

    private static final Object SAVE_LOCK = new Object();

    public void save() {
        try {
            synchronized (SAVE_LOCK) {
                if (id == 0L) {
                    GitSyncRecord existing = findByProject(project);
                    id = existing != null ? existing.id
                            : handler.getResources().stream()
                                    .map(DecodedResource::config)
                                    .mapToLong(Config::id)
                                    .max()
                                    .orElse(0L) + 1L;
                }
                Config c = new Config(id, project, enabled, remoteName, branch, intervalSeconds,
                        ignitionUser);
                String key = String.valueOf(id);
                if (handler.findResource(key).isPresent()) {
                    handler.modify(key, c).join();
                } else {
                    handler.create(key, c).join();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void delete() {
        try {
            handler.delete(String.valueOf(id)).join();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static GitSyncRecord findByProject(String project) {
        Handler h = handler;
        if (h == null || project == null) {
            return null;
        }
        return h.getResources().stream()
                .map(DecodedResource::config)
                .filter(c -> project.equals(c.project()))
                .findFirst()
                .map(GitSyncRecord::new)
                .orElse(null);
    }

    public static List<GitSyncRecord> listAll() {
        Handler h = handler;
        List<GitSyncRecord> out = new ArrayList<>();
        if (h == null) {
            return out;
        }
        for (DecodedResource<Config> d : h.getResources()) {
            out.add(new GitSyncRecord(d.config()));
        }
        return out;
    }

    public static List<GitSyncRecord> listEnabled() {
        List<GitSyncRecord> out = new ArrayList<>();
        for (GitSyncRecord r : listAll()) {
            if (r.enabled && !r.project.isBlank()) {
                out.add(r);
            }
        }
        return out;
    }
}
