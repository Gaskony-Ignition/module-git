package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.records.GitTriggerRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends matching git events to configured HTTP endpoints — GitHub's dispatch APIs, or anything
 * else that accepts a POST.
 *
 * <p>Outbound only. Nothing here opens a port, so this adds no attack surface to the gateway;
 * the exposure it does add is a stored token, which lives in a {@code SecretConfig} and is never
 * logged, never echoed by a route, and never written into the event log.
 */
final class TriggerDelivery {

    private static final Logger logger = LoggerFactory.getLogger(TriggerDelivery.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Redirects are NOT followed. A 30x to another host would otherwise replay the Authorization
     * header — and the token — at whatever that host is.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z]+)}");

    private TriggerDelivery() {
    }

    static List<String> deliver(GitEvent event) {
        List<String> notes = new ArrayList<>();
        List<GitTriggerRecord> rules;
        try {
            rules = GitTriggerRecord.listAll();
        } catch (Exception e) {
            return List.of("triggers unavailable");
        }

        int matched = 0;
        for (GitTriggerRecord rule : rules) {
            if (!rule.matches(event.type(), event.outcome(), event.project(), event.branch())) {
                continue;
            }
            matched++;
            notes.add(send(rule, event));
        }
        if (matched == 0) {
            notes.add("no triggers matched");
        }
        return notes;
    }

    private static String send(GitTriggerRecord rule, GitEvent event) {
        String label = rule.getName().isBlank() ? ("trigger " + rule.getId()) : rule.getName();
        try {
            Map<String, String> vars = substitutions(event);
            String url = substitute(rule.getUrl(), vars, false);
            String body = substitute(rule.getBodyTemplate(), vars, true);

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json");

            for (Map.Entry<String, String> h : parseHeaders(rule.getHeaders(), vars).entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            applyCredential(rule, req);

            String method = rule.getMethod().toUpperCase(Locale.ROOT);
            HttpRequest.BodyPublisher publisher = body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.method(method, publisher);
            }

            HttpResponse<String> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code >= 200 && code < 300) {
                return label + " " + code;
            }
            // The response body can name the exact problem (a bad workflow file, a missing scope),
            // so keep a short prefix of it — it is the endpoint's own text, not ours.
            String detail = res.body() == null ? "" : res.body().strip();
            if (detail.length() > 200) {
                detail = detail.substring(0, 200) + "…";
            }
            logger.warn("Git trigger '{}' returned {} from {}", label, code, hostOf(rule.getUrl()));
            return label + " " + code + (detail.isEmpty() ? "" : " — " + detail);
        } catch (Exception e) {
            logger.warn("Git trigger '{}' to {} failed.", label, hostOf(rule.getUrl()), e);
            return label + " failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Injects the stored secret into the configured header. Failures are silent about the value. */
    private static void applyCredential(GitTriggerRecord rule, HttpRequest.Builder req) {
        if (rule.getCredentialId() <= 0 || rule.getCredentialHeader().isBlank()) {
            return;
        }
        GitUserHttpsCredentialRecord cred = GitUserHttpsCredentialRecord.findById(rule.getCredentialId());
        if (cred == null) {
            throw new IllegalStateException("credential " + rule.getCredentialId() + " not found");
        }
        String secret = cred.getPassword();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("credential has no secret");
        }
        int colon = rule.getCredentialHeader().indexOf(':');
        if (colon <= 0) {
            throw new IllegalStateException("credential header must be 'Name: value'");
        }
        String name = rule.getCredentialHeader().substring(0, colon).trim();
        String value = rule.getCredentialHeader().substring(colon + 1).trim()
                .replace("${secret}", secret)
                .replace("${username}", cred.getUserName() == null ? "" : cred.getUserName());
        req.header(name, value);
    }

    private static Map<String, String> parseHeaders(String raw, Map<String, String> vars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, colon).trim(),
                    substitute(trimmed.substring(colon + 1).trim(), vars, false));
        }
        return out;
    }

    private static Map<String, String> substitutions(GitEvent event) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : event.toMap().entrySet()) {
            if (e.getValue() instanceof List<?> list) {
                vars.put(e.getKey(), String.join(", ", list.stream().map(String::valueOf).toList()));
            } else {
                vars.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        // Derived so one rule can serve every project: a GitHub dispatch URL is the same shape
        // for all of them, with only owner/repo changing.
        String[] ownerRepo = ownerAndRepo(event.remote());
        vars.put("owner", ownerRepo[0]);
        vars.put("repo", ownerRepo[1]);
        vars.put("shortCommit", event.commit() == null || event.commit().length() < 7
                ? String.valueOf(event.commit()) : event.commit().substring(0, 7));
        return vars;
    }

    /**
     * Substitutes {@code ${…}} placeholders. In a JSON body the value is escaped — a commit
     * message containing a quote or a newline would otherwise produce a malformed request that
     * the endpoint rejects with a message about JSON rather than about the commit.
     */
    private static String substitute(String template, Map<String, String> vars, boolean jsonEscape) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(jsonEscape ? jsonEscape(value) : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Owner and repository from either git URL form; {@code ["", ""]} when it is neither. */
    static String[] ownerAndRepo(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return new String[] {"", ""};
        }
        String path = remoteUrl.trim();
        int at = path.indexOf('@');
        int colon = path.indexOf(':', at + 1);
        if (at > 0 && colon > at) {
            path = path.substring(colon + 1);            // git@host:owner/repo.git
        } else {
            int scheme = path.indexOf("://");
            if (scheme < 0) {
                return new String[] {"", ""};
            }
            int slash = path.indexOf('/', scheme + 3);
            if (slash < 0) {
                return new String[] {"", ""};
            }
            path = path.substring(slash + 1);            // https://host/owner/repo.git
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return new String[] {"", ""};
        }
        return new String[] {parts[parts.length - 2], parts[parts.length - 1]};
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "(unparseable url)";
        }
    }
}
