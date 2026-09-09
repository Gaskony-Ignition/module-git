package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.operametrix.ignition.git.GatewayHook;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway-level singleton holding the inbound webhook configuration.
 *
 * <p>The shared secret is a {@link SecretConfig} for the same reason every other credential here
 * is: it is the only thing standing between an open route and anyone on the network, so it is
 * never written to disk in the clear and never returned to the browser.
 *
 * <p>{@code syncEvents} names the GitHub event types that additionally fast-forward the matching
 * project. Every accepted delivery raises a {@code webhook} git event regardless — a type with no
 * built-in behaviour still reaches a Jython handler, so new behaviour needs no module release.
 */
public class GitWebhookRecord {

    public static final String MODULE_ID = "com.operametrix.ignition.git";

    /** The one resource name; the webhook configuration is a gateway-level singleton. */
    private static final String NAME = "webhook";

    /** GitHub event types the gateway acts on by itself. Others are raised as events only. */
    public static final List<String> KNOWN_EVENTS = List.of("push", "workflow_run");

    public record Config(boolean enabled, SecretConfig secret, String syncEvents) {}

    public static final ResourceType TYPE = new ResourceType(MODULE_ID, "git-webhook");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Webhook")
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

    private boolean enabled;
    private SecretConfig secret;
    private String syncEvents = "push";

    public GitWebhookRecord() {
    }

    private GitWebhookRecord(Config c) {
        this.enabled = c.enabled();
        this.secret = c.secret();
        this.syncEvents = c.syncEvents() == null ? "" : c.syncEvents();
    }

    /** The stored configuration, or a disabled default when none has been saved. */
    public static GitWebhookRecord get() {
        try {
            return handler.findResource(NAME)
                    .map(DecodedResource::config)
                    .map(GitWebhookRecord::new)
                    .orElseGet(GitWebhookRecord::new);
        } catch (Exception e) {
            return new GitWebhookRecord();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public boolean hasSecret() {
        return secret != null;
    }

    /** Sets the shared secret from plaintext. A blank value clears it, which disables the route. */
    public void setSecret(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            this.secret = null;
            return;
        }
        try (Plaintext pt = Plaintext.fromString(plaintext, StandardCharsets.UTF_8)) {
            JsonElement ciphertext =
                    GatewayHook.getContext().getSystemEncryptionService().encryptToJson(pt);
            this.secret = SecretConfig.embedded(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Could not encrypt the webhook secret", e);
        }
    }

    /**
     * The shared secret's bytes, or null when unset or undecryptable.
     *
     * <p>Returned as bytes rather than a String so the caller can zero them; a HMAC key sitting in
     * an immutable String stays in the heap until the collector happens to reach it.
     */
    public byte[] secretBytes() {
        if (secret == null) {
            return null;
        }
        try {
            Secret<?> s = Secret.create(GatewayHook.getContext(), secret);
            Plaintext pt = s.getPlaintext();
            try {
                return pt.getAsString(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            } finally {
                pt.clear();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** GitHub event types that trigger a fast-forward, lower-cased and de-duplicated. */
    public Set<String> syncEventSet() {
        if (syncEvents == null || syncEvents.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(syncEvents.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String getSyncEvents() {
        return syncEvents == null ? "" : syncEvents;
    }

    public void setSyncEvents(String v) {
        this.syncEvents = v == null ? "" : v;
    }

    public void save() {
        try {
            Config c = new Config(enabled, secret, getSyncEvents());
            if (handler.findResource(NAME).isPresent()) {
                handler.modify(NAME, c).join();
            } else {
                handler.create(NAME, c).join();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
