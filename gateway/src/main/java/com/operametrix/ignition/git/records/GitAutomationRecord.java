package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway-level automation settings (singleton, fixed id) — where git events are delivered.
 *
 * <p>Two delivery targets, either or both: a project library function, and a Gateway Event
 * message handler. Both take the same event dictionary; a site picks whichever it already
 * organises its scripting around.
 */
public class GitAutomationRecord {

    public record Config(long id, boolean enabled, String eventTypes,
                         String handlerProject, String handlerScript,
                         String messageProject, String messageHandler) {}

    public static final ResourceType TYPE =
            new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-automation");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Automation")
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

    private static final long SINGLETON_ID = 1L;
    private static final String SINGLETON_NAME = String.valueOf(SINGLETON_ID);

    private boolean enabled;
    private String eventTypes = "";
    private String handlerProject = "";
    private String handlerScript = "";
    private String messageProject = "";
    private String messageHandler = "";

    public GitAutomationRecord() {
    }

    private GitAutomationRecord(Config c) {
        this.enabled = c.enabled();
        this.eventTypes = c.eventTypes() == null ? "" : c.eventTypes();
        this.handlerProject = c.handlerProject() == null ? "" : c.handlerProject();
        this.handlerScript = c.handlerScript() == null ? "" : c.handlerScript();
        this.messageProject = c.messageProject() == null ? "" : c.messageProject();
        this.messageHandler = c.messageHandler() == null ? "" : c.messageHandler();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Comma-separated event types; empty means every type. */
    public String getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(String eventTypes) {
        this.eventTypes = eventTypes == null ? "" : eventTypes;
    }

    public String getHandlerProject() {
        return handlerProject;
    }

    public void setHandlerProject(String v) {
        this.handlerProject = v == null ? "" : v;
    }

    /** Dotted path of the project library function, e.g. {@code Git.Events.onGitEvent}. */
    public String getHandlerScript() {
        return handlerScript;
    }

    public void setHandlerScript(String v) {
        this.handlerScript = v == null ? "" : v;
    }

    public String getMessageProject() {
        return messageProject;
    }

    public void setMessageProject(String v) {
        this.messageProject = v == null ? "" : v;
    }

    /** Name of a Gateway Event Script message handler. */
    public String getMessageHandler() {
        return messageHandler;
    }

    public void setMessageHandler(String v) {
        this.messageHandler = v == null ? "" : v;
    }

    /** Whether this event type should be delivered. An empty selection means all of them. */
    public boolean wants(String type) {
        Set<String> selected = selectedTypes();
        return selected.isEmpty() || selected.contains(type);
    }

    public Set<String> selectedTypes() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setSelectedTypes(List<String> types) {
        this.eventTypes = types == null ? "" : String.join(",", types);
    }

    public boolean hasScriptTarget() {
        return !handlerProject.isBlank() && !handlerScript.isBlank();
    }

    public boolean hasMessageTarget() {
        return !messageProject.isBlank() && !messageHandler.isBlank();
    }

    public void save() {
        try {
            Config c = new Config(SINGLETON_ID, enabled, eventTypes, handlerProject, handlerScript,
                    messageProject, messageHandler);
            if (handler.findResource(SINGLETON_NAME).isPresent()) {
                handler.modify(SINGLETON_NAME, c).join();
            } else {
                handler.create(SINGLETON_NAME, c).join();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Never null — an unsaved gateway reads as "automation off", which is the correct default. */
    public static GitAutomationRecord get() {
        Handler h = handler;
        if (h == null) {
            return new GitAutomationRecord();
        }
        return h.findResource(SINGLETON_NAME)
                .map(d -> new GitAutomationRecord(d.config()))
                .orElseGet(GitAutomationRecord::new);
    }
}
