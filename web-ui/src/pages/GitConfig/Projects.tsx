import React from "react";
import {
  Button,
  Loading,
  SelectInput,
  TextInput,
  useToastNotifications,
} from "../../webui";
import {
  ProjectStatus,
  useGetCredentialsQuery,
  useGetProjectsQuery,
  useInitProjectMutation,
  useSetProjectRemoteMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// Which projects are under version control was previously answerable only from the Designer, one
// project at a time, after opening it. Unversioned projects are listed here too: "not in git" and
// "not on this gateway" look identical if the list only shows repositories.
const Projects = () => {
  const { data, isFetching } = useGetProjectsQuery();
  const { data: creds } = useGetCredentialsQuery();
  const [init, { isLoading: initialising }] = useInitProjectMutation();
  const [setRemote, { isLoading: settingRemote }] =
    useSetProjectRemoteMutation();
  const toasts = useToastNotifications();

  const [target, setTarget] = React.useState<ProjectStatus | null>(null);
  const [url, setUrl] = React.useState("");
  const [credId, setCredId] = React.useState("");

  const credentials = creds?.credentials ?? [];
  const projects = data?.projects ?? [];

  const close = () => {
    setTarget(null);
    setUrl("");
    setCredId("");
  };

  const submit = () => {
    if (!target) return;
    const chosen = credentials.find((c) => String(c.id) === credId);
    const run = target.versioned
      ? setRemote({ project: target.name, url: url.trim() })
      : init({
          project: target.name,
          url: url.trim() || undefined,
          sshKeyId: chosen?.type === "SSH" ? chosen.id : undefined,
          httpsCredentialId: chosen?.type === "HTTPS" ? chosen.id : undefined,
        });
    run
      .unwrap()
      .then(() => {
        toasts.notifySuccess(
          target.versioned
            ? `Remote set on ${target.name}`
            : `${target.name} is now under version control`
        );
        close();
      })
      .catch(
        errorToast(
          toasts,
          target.versioned
            ? "Could not set the remote"
            : "Could not initialise the repository"
        )
      );
  };

  const state = (p: ProjectStatus) => {
    if (p.error) return <span className="gitcfg-proj-err">{p.error}</span>;
    if (!p.versioned)
      return <span className="gitcfg-proj-off">Not versioned</span>;
    if (p.changes < 0)
      return <span className="gitcfg-proj-err">Unreadable</span>;
    if (p.changes === 0) return <span className="gitcfg-proj-ok">Clean</span>;
    return <span className="gitcfg-proj-dirty">{p.changes} uncommitted</span>;
  };

  return (
    <div className="gitcfg-projects">
      <div className="gitcfg-excluded-head">
        <div>
          <h3>Projects</h3>
          <p>
            Every project on this gateway and whether it is under version
            control. Project repositories are separate from the gateway
            configuration repository on the other tabs — each project has its
            own.
          </p>
        </div>
      </div>

      {isFetching ? (
        <Loading isLoading={true} />
      ) : projects.length === 0 ? (
        <p className="gitcfg-empty">No projects on this gateway.</p>
      ) : (
        <table className="gitcfg-proj-table">
          <thead>
            <tr>
              <th>Project</th>
              <th>Branch</th>
              <th>Remote</th>
              <th>State</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {projects.map((p) => (
              <tr key={p.name}>
                <td>
                  <span className="gitcfg-proj-name">{p.name}</span>
                  {p.title && p.title !== p.name ? (
                    <span className="gitcfg-proj-title">{p.title}</span>
                  ) : null}
                </td>
                <td>{p.branch || "—"}</td>
                <td className="gitcfg-proj-remote">
                  {p.remoteUrl ? p.remoteUrl : p.versioned ? "Local only" : "—"}
                </td>
                <td>{state(p)}</td>
                <td className="gitcfg-proj-act">
                  <Button
                    colorClass="secondary"
                    onClick={() => {
                      setTarget(p);
                      setUrl(p.remoteUrl || "");
                      setCredId("");
                    }}
                  >
                    {p.versioned ? "Set remote" : "Set up"}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {target ? (
        <div className="gitcfg-cred-form">
          <h4>
            {target.versioned
              ? `Remote for ${target.name}`
              : `Put ${target.name} under version control`}
          </h4>
          <TextInput
            label={
              target.versioned
                ? "Remote URL"
                : "Remote URL — leave empty for a local-only repository"
            }
            placeholder="git@github.com:org/repo.git"
            value={url}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setUrl(e.target.value)
            }
          />
          {!target.versioned && url.trim() !== "" ? (
            <SelectInput
              label="Credential"
              value={credId}
              values={credentials.map((c) => ({
                label: `${c.type} — ${c.label}`,
                value: String(c.id),
              }))}
              onChange={(e: unknown) => setCredId(selectValue(e))}
            />
          ) : null}
          <div className="gitcfg-cred-actions">
            <Button colorClass="secondary" onClick={close}>
              Cancel
            </Button>
            <Button
              colorClass="primary"
              disabled={
                initialising ||
                settingRemote ||
                (target.versioned && url.trim() === "")
              }
              onClick={submit}
            >
              {initialising || settingRemote
                ? "Working…"
                : target.versioned
                ? "Save remote"
                : url.trim() === ""
                ? "Initialise locally"
                : "Clone"}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
};

export default Projects;
