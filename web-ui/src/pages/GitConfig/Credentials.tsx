import React from "react";
import { Button, Loading, Radio, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextArea, TextInput } from "./fields";
import {
  AddCredentialReq,
  useAddCredentialMutation,
  useGetCredentialsQuery,
  useGetSecretProvidersQuery,
  useRemoveCredentialMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// Credentials are per Ignition user and are what a project repository authenticates with. They
// could only be created from the Designer's setup wizard, which puts them behind the thing they
// are needed for: you cannot clone a project until the key exists, and you could not make the key
// without opening a Designer. This tab is the same records, reachable before that point.
type Kind = "SSH" | "HTTPS";
type Mode = "inline" | "reference";

const Credentials = () => {
  const { data, isFetching } = useGetCredentialsQuery();
  const { data: providers } = useGetSecretProvidersQuery();
  const [add, { isLoading: adding }] = useAddCredentialMutation();
  const [remove] = useRemoveCredentialMutation();
  const toasts = useToastNotifications();

  const [open, setOpen] = React.useState(false);
  const [kind, setKind] = React.useState<Kind>("SSH");
  const [mode, setMode] = React.useState<Mode>("inline");
  const [name, setName] = React.useState("");
  const [key, setKey] = React.useState("");
  const [host, setHost] = React.useState("");
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [providerName, setProviderName] = React.useState("");
  const [secretName, setSecretName] = React.useState("");

  const providerList = providers?.providers ?? [];
  const secrets =
    providerList.find((p) => p.name === providerName)?.secrets ?? [];

  const reset = () => {
    setName("");
    setKey("");
    setHost("");
    setUsername("");
    setPassword("");
    setProviderName("");
    setSecretName("");
  };

  const ready =
    kind === "SSH"
      ? name.trim() !== "" &&
        (mode === "inline"
          ? key.trim() !== ""
          : providerName !== "" && secretName !== "")
      : host.trim() !== "" &&
        (mode === "inline"
          ? password !== ""
          : providerName !== "" && secretName !== "");

  const save = () => {
    let body: AddCredentialReq;
    if (kind === "SSH") {
      body =
        mode === "inline"
          ? { type: "SSH", name: name.trim(), mode: "inline", key }
          : {
              type: "SSH",
              name: name.trim(),
              mode: "reference",
              providerName,
              secretName,
            };
    } else {
      body =
        mode === "inline"
          ? {
              type: "HTTPS",
              host: host.trim(),
              username: username.trim(),
              mode: "inline",
              password,
            }
          : {
              type: "HTTPS",
              host: host.trim(),
              username: username.trim(),
              mode: "reference",
              providerName,
              secretName,
            };
    }
    add(body)
      .unwrap()
      .then(() => {
        // The secret is cleared from component state the moment it is stored, so a left-open
        // form is not a copy of the key sitting in the browser.
        reset();
        setOpen(false);
        toasts.notifySuccess("Credential saved");
      })
      .catch(errorToast(toasts, "Could not save the credential"));
  };

  const del = (type: Kind, id: number, label: string) => {
    remove({ type, id })
      .unwrap()
      .then(() => toasts.notifySuccess(`Removed ${label}`))
      .catch(errorToast(toasts, "Could not remove the credential"));
  };

  const rows = data?.credentials ?? [];

  return (
    <div className="gitcfg-creds">
      <div className="gitcfg-excluded-head">
        <div>
          <h3>Credentials</h3>
          <p>
            SSH keys and HTTPS credentials for the repositories this gateway
            authenticates with. They belong to your Ignition user, and a project
            repository picks one when its remote is set — so create them here
            first, then set the remote from the Designer or the Projects tab.
          </p>
        </div>
        <div className="gitcfg-excluded-actions">
          <Button colorClass="primary" onClick={() => setOpen(!open)}>
            {open ? "Cancel" : "Add credential"}
          </Button>
        </div>
      </div>

      {open ? (
        <div className="gitcfg-cred-form">
          {/*
            Radio is a radio GROUP: it takes a `radios` array and maps over it. Used as a single
            radio with `label`/`checked` it reads `radios.map` on undefined and takes the whole
            page down with an Application Error — which is what opening this form used to do.
          */}
          <div className="gitcfg-cred-row">
            <Radio
              name="cred-kind"
              value={kind}
              radios={[
                { label: "SSH key", value: "SSH" },
                { label: "HTTPS username and token", value: "HTTPS" },
              ]}
              onChange={(_e: unknown, value: string) =>
                setKind(value as "SSH" | "HTTPS")
              }
            />
          </div>

          {kind === "SSH" ? (
            <TextInput
              label="Key name"
              value={name}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setName(e.target.value)
              }
            />
          ) : (
            <>
              <TextInput
                label="Host"
                placeholder="github.com"
                value={host}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setHost(e.target.value)
                }
              />
              <TextInput
                label="Username"
                value={username}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setUsername(e.target.value)
                }
              />
            </>
          )}

          <div className="gitcfg-cred-row">
            <Radio
              name="cred-mode"
              value={mode}
              radios={[
                { label: "Type the secret", value: "inline" },
                { label: "Use a stored secret", value: "reference" },
              ]}
              onChange={(_e: unknown, value: string) =>
                setMode(value as "inline" | "reference")
              }
            />
          </div>

          {mode === "inline" ? (
            kind === "SSH" ? (
              <TextArea
                label="Private key"
                rows={8}
                value={key}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
                  setKey(e.target.value)
                }
              />
            ) : (
              <TextInput
                label="Password or token"
                type="password"
                value={password}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setPassword(e.target.value)
                }
              />
            )
          ) : (
            <>
              <SelectInput
                label="Secret provider"
                value={providerName}
                values={providerList.map((p) => ({
                  label: p.name,
                  value: p.name,
                }))}
                onChange={(e: unknown) => {
                  setProviderName(selectValue(e));
                  setSecretName("");
                }}
              />
              <SelectInput
                label="Secret"
                value={secretName}
                values={secrets.map((sname) => ({
                  label: sname,
                  value: sname,
                }))}
                onChange={(e: unknown) => setSecretName(selectValue(e))}
              />
            </>
          )}

          <div className="gitcfg-cred-actions">
            <Button
              colorClass="primary"
              disabled={!ready || adding}
              onClick={save}
            >
              {adding ? "Saving…" : "Save credential"}
            </Button>
          </div>
        </div>
      ) : null}

      {isFetching ? (
        <Loading isLoading={true} />
      ) : rows.length === 0 ? (
        <p className="gitcfg-empty">
          No credentials yet. A local-only repository needs none; add one when a
          project has to reach a remote.
        </p>
      ) : (
        <table className="gitcfg-cred-table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Credential</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={`${c.type}-${c.id}`}>
                <td>{c.type}</td>
                <td>{c.label}</td>
                <td className="gitcfg-cred-del">
                  <Button
                    colorClass="secondary"
                    onClick={() => del(c.type, c.id, c.label)}
                  >
                    Remove
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
};

export default Credentials;
