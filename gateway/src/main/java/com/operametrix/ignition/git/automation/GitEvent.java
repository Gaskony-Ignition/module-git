package com.operametrix.ignition.git.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One git operation, as delivered to script handlers and outbound triggers.
 *
 * <p>Built through {@link #of(String)} rather than the canonical constructor: eleven positional
 * arguments at a dozen call sites is unreadable, and most operations set four or five of them.
 */
public record GitEvent(String type, String outcome, String scope, String project, String user,
                       String branch, String remote, String commit, String message,
                       List<String> files, String timestamp) {

    public static final String COMMIT = "commit";
    public static final String PUSH = "push";
    public static final String PULL = "pull";
    public static final String FETCH = "fetch";
    public static final String CHECKOUT = "checkout";
    public static final String BRANCH = "branch";
    public static final String REVERT = "revert";
    public static final String AUTOCOMMIT = "autocommit";
    public static final String SYNC = "sync";

    /** Every type, in the order the Automation tab lists them. */
    public static final List<String> TYPES =
            List.of(COMMIT, PUSH, PULL, FETCH, CHECKOUT, BRANCH, REVERT, AUTOCOMMIT, SYNC);

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";

    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_CONFIG = "config";

    public GitEvent {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static Builder of(String type) {
        return new Builder(type);
    }

    public boolean failed() {
        return FAILURE.equals(outcome);
    }

    /**
     * Flat map for the Jython payload, the JSON the Automation tab reads, and the {@code ${…}}
     * substitutions in an outbound trigger body. Null strings become "" so a handler can index
     * every key without a KeyError and a template never renders the word "null".
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", nz(type));
        m.put("outcome", nz(outcome));
        m.put("scope", nz(scope));
        m.put("project", nz(project));
        m.put("user", nz(user));
        m.put("branch", nz(branch));
        m.put("remote", nz(remote));
        m.put("commit", nz(commit));
        m.put("message", nz(message));
        m.put("files", Collections.unmodifiableList(new ArrayList<>(files)));
        m.put("timestamp", nz(timestamp));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static final class Builder {
        private final String type;
        private String scope = SCOPE_PROJECT;
        private String project;
        private String user;
        private String branch;
        private String remote;
        private String commit;
        private String message;
        private List<String> files = List.of();

        private Builder(String type) {
            this.type = type;
        }

        public Builder scope(String v) {
            this.scope = v;
            return this;
        }

        public Builder config() {
            this.scope = SCOPE_CONFIG;
            return this;
        }

        public Builder project(String v) {
            this.project = v;
            return this;
        }

        public Builder user(String v) {
            this.user = v;
            return this;
        }

        public Builder branch(String v) {
            this.branch = v;
            return this;
        }

        public Builder remote(String v) {
            this.remote = v;
            return this;
        }

        public Builder commit(String v) {
            this.commit = v;
            return this;
        }

        public Builder message(String v) {
            this.message = v;
            return this;
        }

        public Builder files(List<String> v) {
            this.files = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public GitEvent success() {
            return build(SUCCESS);
        }

        /** Failure carries the reason in {@code message}, replacing any commit message set. */
        public GitEvent failure(String reason) {
            this.message = reason;
            return build(FAILURE);
        }

        private GitEvent build(String outcome) {
            return new GitEvent(type, outcome, scope, project, user, branch, remote, commit,
                    message, files, Instant.now().toString());
        }
    }
}
