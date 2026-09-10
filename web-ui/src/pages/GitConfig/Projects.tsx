import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextInput } from "./fields";
import {
  ProjectStatus,
  useGetCredentialsQuery,
  useGetProjectsQuery,
  useInitProjectMutation,
  useSetProjectImagesMutation,
  useSnapshotProjectImagesMutation,
  useSetProjectRemoteMutation,
  useSetProjectCredentialMutation,
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
  const [setImages, { isLoading: settingImages }] =
    useSetProjectImagesMutation();
  const [setCredential, { isLoading: settingCredential }] =
    useSetProjectCredentialMutation();
  const [snapshotImages, { isLoading: snapshotting }] =
    useSnapshotProjectImagesMutation();
  const toasts = useToastNotifications();

  const [target, setTarget] = React.useState<ProjectStatus | null>(null);
  const [url, setUrl] = React.useState("");
  const [credId, setCredId] = React.useState("");
  const [imagePrefix, setImagePrefix] = React.useState("");

  const credentials = creds?.credentials ?? [];
  const projects = data?.projects ?? [];
  const imageFolders = data?.imageFolders ?? [];

  const close = () => {
    setTarget(null);
    setUrl("");
    setCredId("");
    setImagePrefix("");
  };

  const submit = () => {
    if (!target) return;
    const chosen = credentials.find((c) => String(c.id) === credId);

    // A versioned project's panel edits two independent things. Only send what changed, so
    // adjusting the image folder does not require re-entering a remote URL that is already set.
    if (target.versioned) {
      const remoteChanged =
        url.trim() !== "" && url.trim() !== (target.remoteUrl || "");
      const imagesChanged = imagePrefix !== (target.imagePrefix || "");
      const steps: Promise<unknown>[] = [];
      if (remoteChanged) {
        steps.push(
          setRemote({ project: target.name, url: url.trim() }).unwrap()
        );
      }
      if (imagesChanged) {
        steps.push(setImages({ project: target.name, imagePrefix }).unwrap());
      }
      // Attaching the credential was previously only possible in the Designer's Remotes popup,
      // so a remote set from this page could never authenticate.
      if (chosen) {
        steps.push(
          setCredential({
            project: target.name,
            remoteName: target.remoteName || "origin",
            sshKeyId: chosen.type === "SSH" ? chosen.id : 0,
            httpsCredentialId: chosen.type === "HTTPS" ? chosen.id : 0,
          }).unwrap()
        );
      }
      if (steps.length === 0) {
        close();
        return;
      }
      Promise.all(steps)
        .then(() => {
          toasts.notifySuccess(`Saved ${target.name}`);
          close();
        })
        .catch(errorToast(toasts, `Could not save ${target.name}`));
      return;
    }

    init({
      project: target.name,
      url: url.trim() || undefined,
      sshKeyId: chosen?.type === "SSH" ? chosen.id : undefined,
      httpsCredentialId: chosen?.type === "HTTPS" ? chosen.id : undefined,
    })
      .unwrap()
      .then(() => {
        toasts.notifySuccess(`${target.name} is now under version control`);
        close();
      })
      .catch(errorToast(toasts, "Could not initialise the repository"));
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
              <th>Images</th>
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
                <td className="gitcfg-proj-remote">
                  {p.versioned ? p.imagePrefix || "None" : "—"}
                </td>
                <td>{state(p)}</td>
                <td className="gitcfg-proj-act">
                  <Button
                    colorClass="secondary"
                    onClick={() => {
                      setTarget(p);
                      setUrl(p.remoteUrl || "");
                      setCredId("");
                      setImagePrefix(p.imagePrefix || "");
                    }}
                  >
                    {p.versioned ? "Edit" : "Set up"}
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
              ? target.name
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
          {target.versioned ? (
            <>
              <SelectInput
                label="Images folder — which part of the gateway image store this project versions"
                value={imagePrefix}
                values={[
                  { label: "None — version no images", value: "" },
                  ...imageFolders.map((f: string) => ({ label: f, value: f })),
                ]}
                onChange={(e: unknown) => setImagePrefix(selectValue(e))}
              />
              {target.imagePrefix ? (
                <div className="gitcfg-cred-actions">
                  <Button
                    colorClass="secondary"
                    disabled={snapshotting}
                    onClick={() =>
                      snapshotImages({ project: target.name })
                        .unwrap()
                        .then(() =>
                          toasts.notifySuccess(
                            `Wrote ${target.imagePrefix} into ${target.name}`
                          )
                        )
                        .catch(
                          errorToast(toasts, "Could not snapshot the images")
                        )
                    }
                  >
                    {snapshotting ? "Writing…" : "Snapshot images now"}
                  </Button>
                </div>
              ) : null}
              <p className="gitcfg-hint">
                The image store belongs to the gateway, not to any one project.
                Versioning a folder here exports only that folder, so two
                projects never fight over the same images. Importing never
                deletes, so an image you stop versioning stays on the gateway.
              </p>
            </>
          ) : null}
          {url.trim() !== "" ? (
            <SelectInput
              label="Credential — how this gateway authenticates to the remote"
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
                settingImages ||
                settingCredential
              }
              onClick={submit}
            >
              {initialising || settingRemote || settingImages
                ? "Working…"
                : target.versioned
                ? "Save"
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
