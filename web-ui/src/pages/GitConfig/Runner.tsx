import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextInput } from "./fields";
import {
  useCommitRunnerWorkflowMutation,
  useGetRunnerQuery,
  useSaveRunnerMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// A self-hosted runner needs no inbound path to the gateway, which is the whole reason it is
// here rather than a webhook. Everything on this tab is generated setup: the same four values
// (repository, labels, gateway address, token) have to agree across two systems and three files,
// and one of them being subtly wrong produces a workflow that queues forever with no error.

/** A copyable block. The copy button matters — these are long and retyping one is how it breaks. */
function Snippet({ text, label }: { text: string; label: string }) {
  const toasts = useToastNotifications();
  return (
    <div className="gitcfg-snippet">
      <div className="gitcfg-snippet-bar">
        <span>{label}</span>
        <button
          type="button"
          onClick={() => {
            navigator.clipboard
              ?.writeText(text)
              .then(() => toasts.notifySuccess("Copied"))
              .catch(() =>
                toasts.notifyError("Could not copy — select the text instead")
              );
          }}
        >
          Copy
        </button>
      </div>
      <pre>{text}</pre>
    </div>
  );
}

export default function Runner() {
  const toasts = useToastNotifications();
  const [project, setProject] = React.useState<string | undefined>(undefined);
  const { data, isLoading, refetch } = useGetRunnerQuery(project);
  const [save, { isLoading: saving }] = useSaveRunnerMutation();
  const [commitWorkflow, { isLoading: committing }] =
    useCommitRunnerWorkflowMutation();

  const [gatewayUrl, setGatewayUrl] = React.useState("");
  const [labels, setLabels] = React.useState("");
  const [enabled, setEnabled] = React.useState(false);
  // Held only until the page is left. The gateway returns it once and cannot return it again.
  const [issued, setIssued] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!data) return;
    setGatewayUrl(data.gatewayUrl);
    setLabels(data.labels);
    setEnabled(data.enabled);
    if (project === undefined && data.project) setProject(data.project);
  }, [data, project]);

  if (isLoading || !data) return <Loading />;

  const onSave = async (extra: Record<string, unknown> = {}) => {
    try {
      const res = await save({
        enabled,
        gatewayUrl,
        labels,
        ...extra,
      }).unwrap();
      if (res.token) setIssued(res.token);
      else toasts.notifySuccess("Saved");
      refetch();
    } catch (e) {
      errorToast(toasts, "Runner settings failed")(e);
    }
  };

  return (
    <>
      <p className="gitcfg-auto-hint">
        A self-hosted runner is a small service on your own network that
        connects out to GitHub and is handed workflow jobs over that same
        connection. Nothing has to reach in, so this works behind a firewall
        where a webhook cannot. The runner then asks this gateway to pull —
        immediately on merge, rather than at the next scheduled sync.
      </p>

      {data.projects.length === 0 ? (
        <p className="gitcfg-empty">
          No versioned project has a remote yet. Set one up on the Projects tab
          first — the runner registers against that project&apos;s repository.
        </p>
      ) : (
        <>
          {data.projects.length > 1 ? (
            <SelectInput
              label="Project"
              value={data.project}
              values={data.projects.map((p) => ({ label: p, value: p }))}
              onChange={(e: unknown) => setProject(selectValue(e))}
            />
          ) : null}

          <h4 className="gitcfg-step">1 · Let the runner call this gateway</h4>
          <label className="gitcfg-check">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />
            <span>Accept sync requests from a runner</span>
          </label>
          <div className="gitcfg-auto-pair">
            <TextInput
              label="Gateway address the runner will use"
              value={gatewayUrl}
              placeholder="http://gateway.plant.local:8088"
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setGatewayUrl(e.target.value)
              }
            />
            <TextInput
              label="Runner labels"
              value={labels}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setLabels(e.target.value)
              }
            />
          </div>
          <p className="gitcfg-auto-hint">
            The address is the one the <em>runner</em> reaches this gateway on,
            which on a container or behind a proxy is not the address in your
            browser bar.
          </p>
          <Button disabled={saving} onClick={() => onSave()}>
            Save
          </Button>

          <h4 className="gitcfg-step">2 · Generate a token</h4>
          <p className="gitcfg-auto-hint">
            {data.hasToken
              ? "A token is set. Generating a new one immediately stops the old one working."
              : "No token yet. Until one exists the gateway answers the runner with a 404."}{" "}
            Save it in the repository as the secret{" "}
            <code>IGNITION_SYNC_TOKEN</code> — that is the name the workflow
            reads.
          </p>
          <Button
            colorClass="secondary"
            disabled={saving}
            onClick={() => onSave({ generateToken: true })}
          >
            {data.hasToken ? "Generate a new token" : "Generate token"}
          </Button>

          {issued ? (
            <div className="gitcfg-token-once">
              <strong>Copy this now — it is not shown again.</strong>
              <Snippet
                label={`Add as repository secret IGNITION_SYNC_TOKEN`}
                text={issued}
              />
            </div>
          ) : null}

          <h4 className="gitcfg-step">3 · Install the runner</h4>
          <p className="gitcfg-auto-hint">
            Run this on a machine that can reach the gateway — not inside the
            gateway container. The registration token comes from{" "}
            {data.repoUrl ? (
              <code>{data.repoUrl}/settings/actions/runners/new</code>
            ) : (
              "the repository's Actions settings"
            )}{" "}
            and expires in an hour.
          </p>
          <Snippet label="On the runner machine" text={data.installScript} />

          <h4 className="gitcfg-step">4 · Add the workflow</h4>
          <p className="gitcfg-auto-hint">
            The gateway already has push rights to this repository, so it can
            commit the workflow itself. It refuses to overwrite a different
            workflow that is already there.
          </p>
          <Button
            disabled={committing || !gatewayUrl}
            onClick={async () => {
              try {
                const r = await commitWorkflow({
                  project: data.project,
                }).unwrap();
                if (r.unchanged) toasts.notifySuccess("Already committed");
                else if (r.pushed) toasts.notifySuccess("Committed and pushed");
                else
                  toasts.notify({
                    type: "error",
                    title: "Committed, but the push failed",
                    message:
                      r.pushError || "Push the project from the Designer.",
                    autoClose: false,
                    isDismissible: true,
                  });
              } catch (e) {
                errorToast(toasts, "Could not commit the workflow")(e);
              }
            }}
          >
            Commit the workflow to the repository
          </Button>
          <p className="gitcfg-auto-hint">Or copy it in by hand:</p>
          <Snippet
            label={".github/workflows/ignition-sync.yml"}
            text={data.workflowYaml}
          />

          <h4 className="gitcfg-step">Check it before you rely on it</h4>
          <p className="gitcfg-auto-hint">
            From the runner machine, with the token in place of the placeholder.
            A success means the workflow will work.
          </p>
          <Snippet label="Test from the runner" text={data.testCommand} />
        </>
      )}
    </>
  );
}
