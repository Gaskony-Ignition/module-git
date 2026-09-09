package com.operametrix.ignition.git.automation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.operametrix.ignition.git.managers.GitProjectManager;
import com.operametrix.ignition.git.records.GitSyncRecord;
import com.operametrix.ignition.git.records.GitWebhookRecord;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Receives GitHub webhook deliveries.
 *
 * <p>This is the one route in the module with no permission check and no CSRF token, because
 * GitHub can present neither. Everything that would normally be the session's job is done here
 * instead, and the order matters: reject before parsing, and parse before acting.
 */
public final class WebhookReceiver {

    private static final Logger logger = LoggerFactory.getLogger(WebhookReceiver.class);

    /** Bodies above this are refused unread. GitHub's own limit is 25 MB; we need far less. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** Recently-seen delivery ids, so a replayed request does not pull twice. */
    private static final int REPLAY_MEMORY = 512;

    private static final Map<String, Boolean> seenDeliveries =
            Collections.synchronizedMap(new LinkedHashMap<>(REPLAY_MEMORY * 2, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REPLAY_MEMORY;
                }
            });

    private WebhookReceiver() {
    }

    public static Object handle(RequestContext req, HttpServletResponse resp) {
        GitWebhookRecord cfg = GitWebhookRecord.get();

        // Fail closed, and say as little as possible. A gateway that has not enabled the webhook
        // should be indistinguishable from one that does not have this module at all.
        if (!cfg.isEnabled() || !cfg.hasSecret()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "{}";
        }

        byte[] body;
        try {
            body = readBody(req);
        } catch (IllegalStateException tooBig) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return "{\"error\":\"body too large\"}";
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"unreadable body\"}";
        }

        byte[] key = cfg.secretBytes();
        try {
            if (!signatureValid(req, body, key)) {
                // Deliberately not logged with the offered signature: an attacker probing the
                // route should learn nothing from the gateway log either.
                logger.warn("Rejected a webhook delivery with a bad or missing signature.");
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "{\"error\":\"bad signature\"}";
            }
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }

        String delivery = header(req, "X-GitHub-Delivery");
        if (delivery != null && !delivery.isBlank() && seenDeliveries.put(delivery, Boolean.TRUE) != null) {
            // GitHub retries on a timeout, and a retry of a push we already pulled is not a
            // second push. Answer 200 so it stops retrying.
            return "{\"ok\":true,\"note\":\"duplicate delivery ignored\"}";
        }

        String event = header(req, "X-GitHub-Event");
        event = event == null ? "" : event.trim().toLowerCase(Locale.ROOT);

        if ("ping".equals(event)) {
            return "{\"ok\":true,\"note\":\"pong\"}";
        }

        JsonObject payload;
        try {
            JsonElement parsed = new Gson().fromJson(new String(body, StandardCharsets.UTF_8), JsonElement.class);
            payload = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"body is not JSON\"}";
        }

        return dispatch(cfg, event, payload);
    }

    /** Raises the event, then fast-forwards the project when this event type is configured to. */
    private static String dispatch(GitWebhookRecord cfg, String event, JsonObject payload) {
        JsonObject repository = obj(payload, "repository");
        String fullName = str(repository, "full_name");
        String branch = branchFrom(event, payload);
        String sender = str(obj(payload, "sender"), "login");

        String project = matchProject(repository);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("githubEvent", event);
        details.put("repository", fullName);
        details.put("action", str(payload, "action"));
        if ("workflow_run".equals(event)) {
            JsonObject run = obj(payload, "workflow_run");
            details.put("workflow", str(run, "name"));
            details.put("status", str(run, "status"));
            details.put("conclusion", str(run, "conclusion"));
            details.put("runUrl", str(run, "html_url"));
        }

        String note;
        boolean shouldSync = cfg.syncEventSet().contains(event);
        if (!shouldSync) {
            note = "event raised";
        } else if (project == null) {
            note = "no project on this gateway has a remote for " + fullName;
        } else {
            GitSyncRecord sync = GitSyncRecord.findByProject(project);
            if (sync == null) {
                // The sync record carries the credential and the branch. Without one there is
                // nothing to authenticate a fetch with, and inventing a default would guess at
                // which gateway user's credentials to spend.
                note = "no sync configuration for " + project + "; configure Scheduled sync to let"
                        + " the webhook pull";
            } else {
                note = SyncScheduler.syncNow(sync);
            }
        }
        details.put("result", note);

        GitEvents.fire(GitEvent.of(GitEvent.WEBHOOK)
                .project(project == null ? "" : project)
                .user(sender)
                .branch(branch)
                .remote(fullName)
                .commit(str(payload, "after"))
                .message(summary(event, details) + " — " + note)
                .details(details)
                .success());

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("event", event);
        out.addProperty("project", project == null ? "" : project);
        out.addProperty("note", note);
        return out.toString();
    }

    /**
     * The log line's subject.
     *
     * <p>A workflow run's conclusion is the only thing anyone reads it for, and it lives in
     * {@code details} where the Event log does not show it — so it goes in the summary too.
     */
    private static String summary(String event, Map<String, String> details) {
        StringBuilder sb = new StringBuilder(event);
        String action = details.getOrDefault("action", "");
        if (!action.isEmpty()) {
            sb.append(' ').append(action);
        }
        String workflow = details.getOrDefault("workflow", "");
        String conclusion = details.getOrDefault("conclusion", "");
        if (!workflow.isEmpty()) {
            sb.append(" (").append(workflow);
            if (!conclusion.isEmpty()) {
                sb.append(": ").append(conclusion);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * The project whose remote points at the same repository.
     *
     * <p>Matched on owner/repo rather than on the URL, because the gateway may hold
     * {@code git@github.com:o/r.git} while the payload offers {@code https://github.com/o/r} —
     * the same repository written two ways.
     */
    private static String matchProject(JsonObject repository) {
        String fullName = str(repository, "full_name");
        if (fullName.isEmpty()) {
            return null;
        }
        String wanted = fullName.toLowerCase(Locale.ROOT);
        try {
            for (GitProjectManager.ProjectStatus p : GitProjectManager.listProjectStatus()) {
                if (!p.versioned() || p.remoteUrl() == null || p.remoteUrl().isBlank()) {
                    continue;
                }
                String[] ownerRepo = TriggerDelivery.ownerAndRepo(p.remoteUrl());
                if (ownerRepo[0].isEmpty() || ownerRepo[1].isEmpty()) {
                    continue;
                }
                if (wanted.equals((ownerRepo[0] + "/" + ownerRepo[1]).toLowerCase(Locale.ROOT))) {
                    return p.name();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not match webhook repository '{}' to a project.", fullName, e);
        }
        return null;
    }

    /** {@code refs/heads/main} is a ref, not a branch; a push payload never says "main" outright. */
    private static String branchFrom(String event, JsonObject payload) {
        if ("push".equals(event)) {
            String ref = str(payload, "ref");
            return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        }
        if ("workflow_run".equals(event)) {
            return str(obj(payload, "workflow_run"), "head_branch");
        }
        return "";
    }

    private static boolean signatureValid(RequestContext req, byte[] body, byte[] key) {
        if (key == null || key.length == 0) {
            return false;
        }
        String offered = header(req, "X-Hub-Signature-256");
        if (offered == null || !offered.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String expected = "sha256=" + hex(mac.doFinal(body));
            // Constant time: a byte-by-byte comparison leaks the signature one byte per request.
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    offered.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("Webhook signature check failed.", e);
            return false;
        }
    }

    /**
     * The body as raw bytes.
     *
     * <p>Raw, not {@code readBody()}'s String: the HMAC is over exactly what GitHub sent, and a
     * decode-then-re-encode round trip is not guaranteed to reproduce it byte for byte.
     */
    private static byte[] readBody(RequestContext req) throws Exception {
        try (InputStream in = req.getRequest().getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new IllegalStateException("body too large");
                }
            }
            return out.toByteArray();
        }
    }

    private static String header(RequestContext req, String name) {
        try {
            return req.getRequest().getHeader(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static JsonObject obj(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(key);
    }

    private static String str(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return "";
        }
        JsonElement e = parent.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
