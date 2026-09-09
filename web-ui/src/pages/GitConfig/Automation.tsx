import React from "react";
import {
  Button,
  Loading,
  SelectInput,
  TextArea,
  TextInput,
  useToastNotifications,
} from "../../webui";
import {
  SyncSetting,
  TriggerRule,
  useClearAutomationLogMutation,
  useGetAutomationQuery,
  useGetCredentialsQuery,
  useGetProjectsQuery,
  useRemoveTriggerMutation,
  useSaveAutomationMutation,
  useSaveSyncMutation,
  useGetWebhookQuery,
  useSaveWebhookMutation,
  useSaveTriggerMutation,
  useSyncNowMutation,
  useTestAutomationMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// Three things that were previously only possible by editing scripts on a gateway you could
// already reach: react to git activity in Jython, call out to CI when the gateway pushes, and
// bring a merged branch down without anyone opening a Designer.
type Section = "delivery" | "triggers" | "sync" | "webhook";

const PRESETS: Record<string, Partial<TriggerRule>> = {
  "GitHub — repository_dispatch": {
    name: "GitHub repository_dispatch",
    url: "https://api.github.com/repos/${owner}/${repo}/dispatches",
    method: "POST",
    headers:
      "Accept: application/vnd.github+json\nX-GitHub-Api-Version: 2022-11-28",
    bodyTemplate:
      '{\n  "event_type": "ignition-push",\n  "client_payload": {\n' +
      '    "project": "${project}",\n    "branch": "${branch}",\n' +
      '    "commit": "${commit}",\n    "user": "${user}"\n  }\n}',
    credentialHeader: "Authorization: Bearer ${secret}",
    eventTypes: "push",
    outcomes: "success",
  },
  "GitHub — workflow_dispatch": {
    name: "GitHub workflow_dispatch",
    url: "https://api.github.com/repos/${owner}/${repo}/actions/workflows/ci.yml/dispatches",
    method: "POST",
    headers:
      "Accept: application/vnd.github+json\nX-GitHub-Api-Version: 2022-11-28",
    bodyTemplate:
      '{\n  "ref": "${branch}",\n  "inputs": {\n' +
      '    "project": "${project}",\n    "commit": "${shortCommit}"\n  }\n}',
    credentialHeader: "Authorization: Bearer ${secret}",
    eventTypes: "push",
    outcomes: "success",
  },
  "Plain webhook": {
    name: "Webhook",
    url: "https://example.invalid/hook",
    method: "POST",
    headers: "",
    bodyTemplate:
      '{\n  "type": "${type}",\n  "project": "${project}",\n' +
      '  "branch": "${branch}",\n  "commit": "${commit}",\n' +
      '  "user": "${user}",\n  "message": "${message}"\n}',
    credentialHeader: "",
    eventTypes: "",
    outcomes: "",
  },
};

// Listed rather than inferred: the set is small, and a reader needs to know what is available
// before writing a body template.
const SUBSTITUTIONS =
  "${project} ${branch} ${commit} ${shortCommit} ${user} ${message} " +
  "${type} ${outcome} ${files} ${owner} ${repo}";

const emptyTrigger = (): TriggerRule => ({
  id: 0,
  name: "",
  enabled: true,
  eventTypes: "",
  outcomes: "",
  projectFilter: "",
  branchFilter: "",
  url: "",
  method: "POST",
  headers: "",
  bodyTemplate: "",
  credentialId: 0,
  credentialHeader: "Authorization: Bearer ${secret}",
});

const csv = (s: string): string[] =>
  s
    .split(",")
    .map((v) => v.trim())
    .filter((v) => v !== "");

const Automation = () => {
  const { data, isFetching } = useGetAutomationQuery(undefined, {
    pollingInterval: 10000,
  });
  const { data: projectData } = useGetProjectsQuery();
  const { data: credData } = useGetCredentialsQuery();
  const [saveSettings, { isLoading: savingSettings }] =
    useSaveAutomationMutation();
  const [testEvent, { isLoading: testing }] = useTestAutomationMutation();
  const [clearLog] = useClearAutomationLogMutation();
  const [saveTrigger, { isLoading: savingTrigger }] = useSaveTriggerMutation();
  const [removeTrigger] = useRemoveTriggerMutation();
  const [saveSync, { isLoading: savingSync }] = useSaveSyncMutation();
  const [syncNow, { isLoading: syncing }] = useSyncNowMutation();
  const { data: webhook } = useGetWebhookQuery();
  const [saveWebhook, { isLoading: savingWebhook }] = useSaveWebhookMutation();
  const toasts = useToastNotifications();

  const [section, setSection] = React.useState<Section>("delivery");
  const [draft, setDraft] = React.useState<TriggerRule | null>(null);
  const [syncDraft, setSyncDraft] = React.useState<SyncSetting | null>(null);

  // Webhook form. The secret is write-only, so the field starts empty even when one is stored
  // and an empty field on save means "leave the stored secret alone".
  const [hook, setHook] = React.useState({
    enabled: false,
    syncEvents: "push",
    secret: "",
  });
  const hookSeeded = React.useRef(false);
  React.useEffect(() => {
    if (webhook && !hookSeeded.current) {
      hookSeeded.current = true;
      setHook({
        enabled: webhook.enabled,
        syncEvents: webhook.syncEvents,
        secret: "",
      });
    }
  }, [webhook]);

  // Local copy of the settings form, seeded once the gateway answers. Editing must not be
  // stamped on by the 10-second poll mid-keystroke.
  const [form, setForm] = React.useState({
    enabled: false,
    eventTypes: [] as string[],
    handlerProject: "",
    handlerScript: "",
    messageProject: "",
    messageHandler: "",
  });
  const seeded = React.useRef(false);
  React.useEffect(() => {
    if (data && !seeded.current) {
      seeded.current = true;
      setForm({ ...data.settings, eventTypes: data.settings.eventTypes ?? [] });
    }
  }, [data]);

  const allTypes = data?.allTypes ?? [];
  const projects = projectData?.projects ?? [];
  const versioned = projects.filter((p) => p.versioned);
  const credentials = (credData?.credentials ?? []).filter(
    (c) => c.type === "HTTPS"
  );
  const syncs = data?.syncs ?? [];

  const toggleType = (t: string) => {
    setForm((f) => ({
      ...f,
      eventTypes: f.eventTypes.includes(t)
        ? f.eventTypes.filter((x) => x !== t)
        : [...f.eventTypes, t],
    }));
  };

  const onSaveSettings = () => {
    saveSettings(form)
      .unwrap()
      .then(() => toasts.notifySuccess("Automation settings saved"))
      .catch(errorToast(toasts, "Could not save the settings"));
  };

  const onTest = () => {
    testEvent({ project: versioned[0]?.name })
      .unwrap()
      .then(() =>
        toasts.notifySuccess("Test event fired — check the Event log below")
      )
      .catch(errorToast(toasts, "Could not fire a test event"));
  };

  const onSaveTrigger = () => {
    if (!draft) return;
    saveTrigger({
      ...draft,
      eventTypes: csv(draft.eventTypes),
      outcomes: csv(draft.outcomes),
    })
      .unwrap()
      .then(() => {
        toasts.notifySuccess("Trigger saved");
        setDraft(null);
      })
      .catch(errorToast(toasts, "Could not save the trigger"));
  };

  const onSaveSync = () => {
    if (!syncDraft) return;
    saveSync(syncDraft)
      .unwrap()
      .then(() => {
        toasts.notifySuccess(`Sync saved for ${syncDraft.project}`);
        setSyncDraft(null);
      })
      .catch(errorToast(toasts, "Could not save the sync settings"));
  };

  const onSyncNow = (project: string) => {
    syncNow({ project })
      .unwrap()
      .then((r) => toasts.notifySuccess(`${project}: ${r.result}`))
      .catch(errorToast(toasts, "Sync failed"));
  };

  if (isFetching && !data) {
    return <Loading isLoading={true} />;
  }

  const stats = data?.stats;

  return (
    <div className="gitcfg-automation">
      <div className="gitcfg-excluded-head">
        <div>
          <h3>Automation</h3>
          <p>
            Raise git activity into Ignition, call out to CI when this gateway
            pushes, and pull a branch down on a schedule. Everything here is
            optional and off until configured.
          </p>
        </div>
        {stats ? (
          <div className="gitcfg-auto-stats">
            <span>{stats.fired} fired</span>
            <span className={stats.failures > 0 ? "is-bad" : ""}>
              {stats.failures} failed
            </span>
            {stats.dropped > 0 ? (
              <span className="is-bad">{stats.dropped} dropped</span>
            ) : null}
          </div>
        ) : null}
      </div>

      <div className="gitcfg-subtabs" role="tablist">
        {(
          [
            ["delivery", "Event delivery"],
            ["triggers", "Outbound triggers"],
            ["sync", "Scheduled sync"],
            ["webhook", "Webhook"],
          ] as [Section, string][]
        ).map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={section === key}
            className={section === key ? "is-active" : ""}
            onClick={() => setSection(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {section === "delivery" ? (
        <div className="gitcfg-cred-form">
          <label className="gitcfg-check">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
            />
            <span>Deliver git events to a script</span>
          </label>

          <div className="gitcfg-auto-types">
            <span className="gitcfg-auto-label">
              Event types — none ticked means all of them
            </span>
            <div className="gitcfg-auto-typelist">
              {allTypes.map((t) => (
                <label key={t} className="gitcfg-check">
                  <input
                    type="checkbox"
                    checked={form.eventTypes.includes(t)}
                    onChange={() => toggleType(t)}
                  />
                  <span>{t}</span>
                </label>
              ))}
            </div>
          </div>

          <h4>Project library function</h4>
          <p className="gitcfg-auto-hint">
            Called as <code>yourFunction(event)</code> with a dictionary — keys
            are type, outcome, scope, project, user, branch, remote, commit,
            message, files, timestamp.
          </p>
          <div className="gitcfg-auto-pair">
            <SelectInput
              label="Project"
              value={form.handlerProject}
              values={[{ label: "—", value: "" }].concat(
                projects.map((p) => ({ label: p.name, value: p.name }))
              )}
              onChange={(e: unknown) =>
                setForm({ ...form, handlerProject: selectValue(e) })
              }
            />
            <TextInput
              label="Function path"
              placeholder="Git.Events.onGitEvent"
              value={form.handlerScript}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setForm({ ...form, handlerScript: e.target.value })
              }
            />
          </div>

          <h4>Gateway message handler</h4>
          <p className="gitcfg-auto-hint">
            Optional alternative or addition — a Gateway Event Script message
            handler receives the same dictionary as its payload.
          </p>
          <div className="gitcfg-auto-pair">
            <SelectInput
              label="Project"
              value={form.messageProject}
              values={[{ label: "—", value: "" }].concat(
                projects.map((p) => ({ label: p.name, value: p.name }))
              )}
              onChange={(e: unknown) =>
                setForm({ ...form, messageProject: selectValue(e) })
              }
            />
            <TextInput
              label="Handler name"
              placeholder="onGitEvent"
              value={form.messageHandler}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setForm({ ...form, messageHandler: e.target.value })
              }
            />
          </div>

          <div className="gitcfg-cred-actions">
            <Button colorClass="secondary" disabled={testing} onClick={onTest}>
              {testing ? "Firing…" : "Fire a test event"}
            </Button>
            <Button
              colorClass="primary"
              disabled={savingSettings}
              onClick={onSaveSettings}
            >
              {savingSettings ? "Saving…" : "Save settings"}
            </Button>
          </div>
        </div>
      ) : null}

      {section === "triggers" ? (
        <>
          <div className="gitcfg-auto-actions">
            <Button
              colorClass="primary"
              onClick={() => setDraft(emptyTrigger())}
            >
              Add trigger
            </Button>
          </div>
          {(data?.triggers ?? []).length === 0 ? (
            <p className="gitcfg-empty">
              No triggers yet. A trigger calls a URL when a git event matches —
              start from the GitHub presets in the editor.
            </p>
          ) : (
            <table className="gitcfg-proj-table">
              <thead>
                <tr>
                  <th>Trigger</th>
                  <th>On</th>
                  <th>Endpoint</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {(data?.triggers ?? []).map((t) => (
                  <tr key={t.id}>
                    <td>
                      <span className="gitcfg-proj-name">
                        {t.name || `Trigger ${t.id}`}
                      </span>
                      {!t.enabled ? (
                        <span className="gitcfg-proj-title">disabled</span>
                      ) : null}
                    </td>
                    <td>{t.eventTypes || "any event"}</td>
                    <td className="gitcfg-proj-remote">{t.url}</td>
                    <td className="gitcfg-proj-act">
                      <Button
                        colorClass="secondary"
                        onClick={() => setDraft(t)}
                      >
                        Edit
                      </Button>
                      <Button
                        colorClass="secondary"
                        onClick={() =>
                          removeTrigger({ id: t.id })
                            .unwrap()
                            .then(() => toasts.notifySuccess("Trigger removed"))
                            .catch(errorToast(toasts, "Could not remove it"))
                        }
                      >
                        Remove
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {draft ? (
            <div className="gitcfg-cred-form">
              <h4>{draft.id > 0 ? `Edit ${draft.name}` : "New trigger"}</h4>
              <div className="gitcfg-auto-presets">
                <span className="gitcfg-auto-label">Start from</span>
                {Object.keys(PRESETS).map((name) => (
                  <Button
                    key={name}
                    colorClass="secondary"
                    onClick={() =>
                      setDraft({ ...draft, ...PRESETS[name] } as TriggerRule)
                    }
                  >
                    {name}
                  </Button>
                ))}
              </div>

              <label className="gitcfg-check">
                <input
                  type="checkbox"
                  checked={draft.enabled}
                  onChange={(e) =>
                    setDraft({ ...draft, enabled: e.target.checked })
                  }
                />
                <span>Enabled</span>
              </label>

              <TextInput
                label="Name"
                value={draft.name}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, name: e.target.value })
                }
              />
              <div className="gitcfg-auto-pair">
                <TextInput
                  label="Event types — comma separated, empty means all"
                  placeholder="push, commit"
                  value={draft.eventTypes}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDraft({ ...draft, eventTypes: e.target.value })
                  }
                />
                <TextInput
                  label="Outcomes — success, failure, or empty for both"
                  placeholder="success"
                  value={draft.outcomes}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDraft({ ...draft, outcomes: e.target.value })
                  }
                />
              </div>
              <div className="gitcfg-auto-pair">
                <TextInput
                  label="Only this project — empty for any"
                  value={draft.projectFilter}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDraft({ ...draft, projectFilter: e.target.value })
                  }
                />
                <TextInput
                  label="Only this branch — empty for any"
                  value={draft.branchFilter}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDraft({ ...draft, branchFilter: e.target.value })
                  }
                />
              </div>
              <TextInput
                label="URL"
                value={draft.url}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, url: e.target.value })
                }
              />
              <p className="gitcfg-auto-hint">
                Substitutions: <code>{SUBSTITUTIONS}</code>
              </p>
              <TextArea
                label="Headers — one Name: value per line"
                rows={3}
                value={draft.headers}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
                  setDraft({ ...draft, headers: e.target.value })
                }
              />
              <TextArea
                label="JSON body"
                rows={8}
                value={draft.bodyTemplate}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
                  setDraft({ ...draft, bodyTemplate: e.target.value })
                }
              />
              <div className="gitcfg-auto-pair">
                <SelectInput
                  label="Credential — an HTTPS credential holding the token"
                  value={String(draft.credentialId || "")}
                  values={[{ label: "None", value: "" }].concat(
                    credentials.map((c) => ({
                      label: c.label,
                      value: String(c.id),
                    }))
                  )}
                  onChange={(e: unknown) =>
                    setDraft({
                      ...draft,
                      credentialId: Number(selectValue(e)) || 0,
                    })
                  }
                />
                <TextInput
                  label="Injected as"
                  value={draft.credentialHeader}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDraft({ ...draft, credentialHeader: e.target.value })
                  }
                />
              </div>

              <div className="gitcfg-cred-actions">
                <Button colorClass="secondary" onClick={() => setDraft(null)}>
                  Cancel
                </Button>
                <Button
                  colorClass="primary"
                  disabled={savingTrigger || draft.url.trim() === ""}
                  onClick={onSaveTrigger}
                >
                  {savingTrigger ? "Saving…" : "Save trigger"}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}

      {section === "sync" ? (
        <>
          <p className="gitcfg-auto-hint">
            The gateway fetches each enabled repository on a timer and
            fast-forwards it when the tracked branch has moved, then requests a
            project scan. It refuses when the working tree has local changes —
            someone is editing — rather than discarding them. There is no
            inbound webhook: GitHub cannot usually reach a gateway.
          </p>
          {versioned.length === 0 ? (
            <p className="gitcfg-empty">
              No versioned projects. Set one up on the Projects tab first.
            </p>
          ) : (
            <table className="gitcfg-proj-table">
              <thead>
                <tr>
                  <th>Project</th>
                  <th>Sync</th>
                  <th>Branch</th>
                  <th>Every</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {versioned.map((p) => {
                  const s = syncs.find((x) => x.project === p.name);
                  return (
                    <tr key={p.name}>
                      <td>
                        <span className="gitcfg-proj-name">{p.name}</span>
                      </td>
                      <td>
                        {s?.enabled ? (
                          <span className="gitcfg-proj-ok">On</span>
                        ) : (
                          <span className="gitcfg-proj-off">Off</span>
                        )}
                      </td>
                      <td>{s?.branch || p.branch || "—"}</td>
                      <td>{s ? `${s.intervalSeconds}s` : "—"}</td>
                      <td className="gitcfg-proj-act">
                        <Button
                          colorClass="secondary"
                          onClick={() =>
                            setSyncDraft(
                              s ?? {
                                project: p.name,
                                enabled: true,
                                remoteName: p.remoteName || "origin",
                                branch: p.branch || "",
                                intervalSeconds: 300,
                                ignitionUser: "",
                              }
                            )
                          }
                        >
                          {s ? "Edit" : "Set up"}
                        </Button>
                        {s?.enabled ? (
                          <Button
                            colorClass="secondary"
                            disabled={syncing}
                            onClick={() => onSyncNow(p.name)}
                          >
                            Sync now
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}

          {syncDraft ? (
            <div className="gitcfg-cred-form">
              <h4>Scheduled sync for {syncDraft.project}</h4>
              <label className="gitcfg-check">
                <input
                  type="checkbox"
                  checked={syncDraft.enabled}
                  onChange={(e) =>
                    setSyncDraft({ ...syncDraft, enabled: e.target.checked })
                  }
                />
                <span>Fetch and fast-forward on a schedule</span>
              </label>
              <div className="gitcfg-auto-pair">
                <TextInput
                  label="Remote"
                  value={syncDraft.remoteName}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setSyncDraft({ ...syncDraft, remoteName: e.target.value })
                  }
                />
                <TextInput
                  label="Branch — empty follows whatever is checked out"
                  value={syncDraft.branch}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setSyncDraft({ ...syncDraft, branch: e.target.value })
                  }
                />
              </div>
              <TextInput
                label="Interval in seconds — minimum 30"
                value={String(syncDraft.intervalSeconds)}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setSyncDraft({
                    ...syncDraft,
                    intervalSeconds: Number(e.target.value) || 300,
                  })
                }
              />
              <p className="gitcfg-auto-hint">
                Sync runs unattended, so it authenticates with the stored
                credential of a named user rather than borrowing whoever is in a
                Designer. Left empty it uses yours.
              </p>
              <TextInput
                label="Authenticate as"
                placeholder="your username"
                value={syncDraft.ignitionUser}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setSyncDraft({ ...syncDraft, ignitionUser: e.target.value })
                }
              />
              <div className="gitcfg-cred-actions">
                <Button
                  colorClass="secondary"
                  onClick={() => setSyncDraft(null)}
                >
                  Cancel
                </Button>
                <Button
                  colorClass="primary"
                  disabled={savingSync}
                  onClick={onSaveSync}
                >
                  {savingSync ? "Saving…" : "Save"}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}

      {section === "webhook" ? (
        <div className="gitcfg-cred-form">
          <p className="gitcfg-auto-hint">
            GitHub posts here when something happens in the repository. Every
            accepted delivery raises a <code>webhook</code> git event, so a
            Jython handler can act on any event type without a module upgrade;
            the types listed below additionally fast-forward the matching
            project. Scheduled sync stays the reliable path — a gateway GitHub
            cannot reach will never receive a delivery.
          </p>

          <label className="gitcfg-check">
            <input
              type="checkbox"
              checked={hook.enabled}
              onChange={(e) => setHook({ ...hook, enabled: e.target.checked })}
            />
            <span>Accept inbound webhook deliveries</span>
          </label>

          <div className="gitcfg-auto-field">
            <span className="gitcfg-auto-label">
              Payload URL — set this in the repository&apos;s webhook settings
            </span>
            <code className="gitcfg-auto-url">
              {webhook ? `${window.location.origin}${webhook.url}` : "…"}
            </code>
          </div>

          <TextInput
            label={
              webhook?.hasSecret
                ? "Secret — stored; type a new one to replace it"
                : "Secret — the same value you paste into GitHub"
            }
            type="password"
            placeholder={webhook?.hasSecret ? "unchanged" : ""}
            value={hook.secret}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setHook({ ...hook, secret: e.target.value })
            }
          />
          <p className="gitcfg-auto-hint">
            The signature over this secret is the only thing authenticating a
            delivery — the route carries no session and no permission check,
            because GitHub can present neither. Until a secret is set the route
            answers 404 to everyone.
          </p>

          <TextInput
            label="Event types that pull the project — comma separated"
            placeholder="push"
            value={hook.syncEvents}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setHook({ ...hook, syncEvents: e.target.value })
            }
          />
          <p className="gitcfg-auto-hint">
            Acted on by the gateway itself:{" "}
            {(webhook?.knownEvents ?? []).join(", ") || "push, workflow_run"}. A
            pull needs the project to have a Scheduled sync configuration, which
            is where its credential and branch come from — disable the schedule
            and the webhook still drives it.
          </p>

          <div className="gitcfg-cred-actions">
            <Button
              colorClass="primary"
              disabled={savingWebhook}
              onClick={() => {
                saveWebhook({
                  enabled: hook.enabled,
                  syncEvents: hook.syncEvents,
                  secret: hook.secret || undefined,
                })
                  .unwrap()
                  .then(() => {
                    setHook({ ...hook, secret: "" });
                    toasts.notifySuccess("Webhook settings saved");
                  })
                  .catch(
                    errorToast(toasts, "Could not save the webhook settings")
                  );
              }}
            >
              {savingWebhook ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      ) : null}

      <div className="gitcfg-auto-log">
        <div className="gitcfg-excluded-head">
          <div>
            <h4>Event log</h4>
            <p>
              The last 50 events and what the gateway did with each. This is
              where a handler that silently does nothing shows up.
            </p>
          </div>
          <div className="gitcfg-excluded-actions">
            <Button
              colorClass="secondary"
              onClick={() =>
                clearLog()
                  .unwrap()
                  .catch(errorToast(toasts, "Could not clear the log"))
              }
            >
              Clear
            </Button>
          </div>
        </div>
        {(data?.log ?? []).length === 0 ? (
          <p className="gitcfg-empty">
            Nothing yet. Commit something, or use Fire a test event above.
          </p>
        ) : (
          <table className="gitcfg-proj-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Event</th>
                <th>Where</th>
                <th>Delivery</th>
              </tr>
            </thead>
            <tbody>
              {(data?.log ?? []).map((e, i) => (
                <tr key={`${e.timestamp}-${i}`}>
                  <td className="gitcfg-auto-when">
                    {e.timestamp.replace("T", " ").replace(/\..*$/, "")}
                  </td>
                  <td>
                    <span
                      className={
                        e.outcome === "failure"
                          ? "gitcfg-proj-err"
                          : "gitcfg-proj-ok"
                      }
                    >
                      {e.type}
                    </span>
                    {e.message ? (
                      <span className="gitcfg-proj-title">{e.message}</span>
                    ) : null}
                  </td>
                  <td>
                    {e.scope === "config"
                      ? "gateway config"
                      : `${e.project}${e.branch ? ` · ${e.branch}` : ""}`}
                  </td>
                  <td className="gitcfg-proj-remote">{e.delivery}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default Automation;
