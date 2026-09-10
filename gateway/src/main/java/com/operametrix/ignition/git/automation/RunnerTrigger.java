package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import com.operametrix.ignition.git.records.GitSyncRecord;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The one route a self-hosted runner calls: pull the named project now.
 *
 * <p>It carries no permission check and no CSRF token, because a workflow step has neither a
 * gateway session nor a way to obtain one. Everything the session would normally do is therefore
 * done here instead, and the order matters: reject before parsing, and parse before acting.
 *
 * <p>Unlike the webhook this replaced, the caller is on the local network — the runner dials out
 * to GitHub and is handed the job over its own connection, so nothing about this route needs to
 * be reachable from the internet.
 */
public final class RunnerTrigger {

    private static final Logger logger = LoggerFactory.getLogger(RunnerTrigger.class);

    /** Bodies above this are refused unread. The real body is a few dozen bytes. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private RunnerTrigger() {
    }

    public static Object handle(RequestContext req, HttpServletResponse resp) {
        GitRunnerRecord cfg = GitRunnerRecord.get();

        // Fail closed, and say as little as possible. A gateway with the runner turned off should
        // be indistinguishable from one that does not have this module at all.
        if (!cfg.isEnabled() || !cfg.hasToken()) {
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

        byte[] expected = cfg.tokenBytes();
        try {
            if (!tokenValid(req, expected)) {
                // Deliberately not logged with the offered token: someone probing this route
                // should learn nothing from the gateway log either.
                logger.warn("Rejected a runner sync request with a bad or missing token.");
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "{\"error\":\"bad token\"}";
            }
        } finally {
            if (expected != null) {
                Arrays.fill(expected, (byte) 0);
            }
        }

        String project;
        try {
            JsonObject o = new Gson().fromJson(new String(body, StandardCharsets.UTF_8),
                    JsonObject.class);
            project = o != null && o.has("project") && !o.get("project").isJsonNull()
                    ? o.get("project").getAsString() : null;
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"body is not JSON\"}";
        }

        if (project == null || project.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"no project named\"}";
        }

        GitSyncRecord sync = GitSyncRecord.findByProject(project);
        if (sync == null) {
            // The runner named a project this gateway does not sync. That is a workflow error
            // worth reporting plainly — the token already proved the caller is ours.
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "{\"error\":\"sync is not configured for '" + project + "'\"}";
        }

        try {
            // Deliberately runs even when the project's scheduled sync is disabled: turning the
            // timer off is how you say "pull on demand only", not "never pull".
            String result = SyncScheduler.syncNow(sync);
            logger.info("Runner requested a sync of '{}': {}", project, result);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("project", project);
            o.addProperty("result", result);
            return o.toString();
        } catch (Exception e) {
            logger.warn("Runner-requested sync of '{}' failed.", project, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    /** Constant-time comparison of the bearer token, so a wrong guess leaks no timing. */
    private static boolean tokenValid(RequestContext req, byte[] expected) {
        if (expected == null) {
            return false;
        }
        String offered = header(req, "Authorization");
        if (offered == null) {
            return false;
        }
        offered = offered.trim();
        if (offered.regionMatches(true, 0, "Bearer ", 0, 7)) {
            offered = offered.substring(7).trim();
        }
        return MessageDigest.isEqual(offered.getBytes(StandardCharsets.UTF_8), expected);
    }

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
}
