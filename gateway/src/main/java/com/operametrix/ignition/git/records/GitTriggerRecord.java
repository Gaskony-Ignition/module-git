package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One outbound trigger: when a git event matches, POST somewhere.
 *
 * <p>Deliberately a generic HTTP call rather than a GitHub client. The Automation tab offers
 * GitHub's two dispatch forms as presets that fill these fields in, so the common case is one
 * click, and a GitLab/Jenkins/Teams endpoint needs no module change.
 *
 * <p>The secret is never stored here — {@code credentialId} points at a
 * {@link GitUserHttpsCredentialRecord}, whose password is an encrypted {@code SecretConfig}.
 */
public class GitTriggerRecord {

    public record Config(long id, String name, boolean enabled, String eventTypes,
                         String outcomes, String projectFilter, String branchFilter,
                         String url, String method, String headers, String bodyTemplate,
                         long credentialId, String credentialHeader) {}

    public static final ResourceType TYPE =
            new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-trigger");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Triggers")
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

    private long id;
    private String name = "";
    private boolean enabled = true;
    private String eventTypes = "";
    private String outcomes = "";
    private String projectFilter = "";
    private String branchFilter = "";
    private String url = "";
    private String method = "POST";
    private String headers = "";
    private String bodyTemplate = "";
    private long credentialId;
    private String credentialHeader = "Authorization: Bearer ${secret}";

    public GitTriggerRecord() {
    }

    private GitTriggerRecord(Config c) {
        this.id = c.id();
        this.name = nz(c.name());
        this.enabled = c.enabled();
        this.eventTypes = nz(c.eventTypes());
        this.outcomes = nz(c.outcomes());
        this.projectFilter = nz(c.projectFilter());
        this.branchFilter = nz(c.branchFilter());
        this.url = nz(c.url());
        this.method = c.method() == null || c.method().isBlank() ? "POST" : c.method();
        this.headers = nz(c.headers());
        this.bodyTemplate = nz(c.bodyTemplate());
        this.credentialId = c.credentialId();
        this.credentialHeader = nz(c.credentialHeader());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = nz(v);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public String getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(String v) {
        this.eventTypes = nz(v);
    }

    /** Comma-separated {@code success}/{@code failure}; empty means both. */
    public String getOutcomes() {
        return outcomes;
    }

    public void setOutcomes(String v) {
        this.outcomes = nz(v);
    }

    public String getProjectFilter() {
        return projectFilter;
    }

    public void setProjectFilter(String v) {
        this.projectFilter = nz(v);
    }

    public String getBranchFilter() {
        return branchFilter;
    }

    public void setBranchFilter(String v) {
        this.branchFilter = nz(v);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String v) {
        this.url = nz(v);
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String v) {
        this.method = v == null || v.isBlank() ? "POST" : v;
    }

    /** Extra headers, one {@code Name: value} per line. */
    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String v) {
        this.headers = nz(v);
    }

    /** JSON body with {@code ${project}}, {@code ${branch}}, {@code ${commit}} … substitutions. */
    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String v) {
        this.bodyTemplate = nz(v);
    }

    public long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(long v) {
        this.credentialId = v;
    }

    /** Header the credential's secret is injected into, as {@code Name: prefix ${secret}}. */
    public String getCredentialHeader() {
        return credentialHeader;
    }

    public void setCredentialHeader(String v) {
        this.credentialHeader = nz(v);
    }

    /** Whether this rule fires for the given event. Empty filters match everything. */
    public boolean matches(String type, String outcome, String project, String branch) {
        if (!enabled || url.isBlank()) {
            return false;
        }
        if (!csvMatches(eventTypes, type)) {
            return false;
        }
        if (!csvMatches(outcomes, outcome)) {
            return false;
        }
        if (!projectFilter.isBlank() && !projectFilter.equals(project)) {
            return false;
        }
        return branchFilter.isBlank() || branchFilter.equals(branch);
    }

    private static boolean csvMatches(String csv, String value) {
        if (csv == null || csv.isBlank()) {
            return true;
        }
        Set<String> set = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return set.isEmpty() || set.contains(value);
    }

    private static final Object SAVE_LOCK = new Object();

    public void save() {
        try {
            synchronized (SAVE_LOCK) {
                if (id == 0L) {
                    id = handler.getResources().stream()
                            .map(DecodedResource::config)
                            .mapToLong(Config::id)
                            .max()
                            .orElse(0L) + 1L;
                }
                Config c = new Config(id, name, enabled, eventTypes, outcomes, projectFilter,
                        branchFilter, url, method, headers, bodyTemplate, credentialId,
                        credentialHeader);
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

    public static GitTriggerRecord findById(long id) {
        return handler.findResource(String.valueOf(id))
                .map(d -> new GitTriggerRecord(d.config()))
                .orElse(null);
    }

    public static List<GitTriggerRecord> listAll() {
        Handler h = handler;
        List<GitTriggerRecord> out = new ArrayList<>();
        if (h == null) {
            return out;
        }
        for (DecodedResource<Config> d : h.getResources()) {
            out.add(new GitTriggerRecord(d.config()));
        }
        out.sort((a, b) -> Long.compare(a.id, b.id));
        return out;
    }
}
