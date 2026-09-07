import React from "react";
import { Button, Loading, TextArea, useToastNotifications } from "../../webui";
import {
  TreeEntry,
  useGetTreeQuery,
  useGetIgnoreQuery,
  useSaveIgnoreMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";

// A tick means "versioned". Pending edits are held here and sent as ONE request on Save, so a
// session of ticking produces a single .gitignore write and a single auto-commit rather than one
// per checkbox.
type Pending = Map<string, boolean>; // path -> versioned?

interface RowProps {
  entry: TreeEntry;
  depth: number;
  pending: Pending;
  onToggle: (entry: TreeEntry, versioned: boolean) => void;
}

const Chevron = ({ open }: { open: boolean }) => (
  <svg
    viewBox="0 -960 960 960"
    width="16"
    height="16"
    fill="currentColor"
    style={{
      transform: open ? "rotate(90deg)" : "none",
      transition: "transform 120ms",
      display: "block",
    }}
  >
    <path d="M400-280v-400l200 200-200 200Z" />
  </svg>
);

const FolderIcon = () => (
  <svg viewBox="0 -960 960 960" width="16" height="16" fill="currentColor">
    <path d="M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h240l80 80h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z" />
  </svg>
);

const FileIcon = () => (
  <svg viewBox="0 -960 960 960" width="16" height="16" fill="currentColor">
    <path d="M320-240h320v-80H320v80Zm0-160h320v-80H320v80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h320l240 240v480q0 33-23.5 56.5T720-80H240Zm280-520v-200H240v640h480v-440H520Z" />
  </svg>
);

/**
 * One row plus, when expanded, its children. Each expanded folder runs its own query, so the tree
 * loads a level at a time — a data directory carries history, logs and caches, and the biggest
 * directories in it are exactly the excluded ones.
 */
const Row = ({ entry, depth, pending, onToggle }: RowProps) => {
  const [open, setOpen] = React.useState(false);
  const box = React.useRef<HTMLInputElement>(null);

  const pendingState = pending.get(entry.path);
  // A folder can be included itself while every file under it is excluded — `**/db/*` excludes the
  // contents, not the directory. Showing that folder fully ticked would be a lie, so the roll-up
  // decides the folder's tick, not its own flag.
  const selfVersioned =
    !entry.excluded && !(entry.directory && entry.childState === "EXCLUDED");
  const versioned = pendingState !== undefined ? pendingState : selfVersioned;
  // A folder whose children disagree shows a partial tick, the way a file picker does — but only
  // while the user has not overridden it.
  const mixed =
    pendingState === undefined &&
    entry.directory &&
    entry.childState === "MIXED";
  // Git will not re-include anything below an excluded directory, so offering the tick would be
  // offering an edit that silently does nothing.
  const locked = entry.excluded && !entry.reincludable;

  React.useEffect(() => {
    if (box.current) {
      box.current.indeterminate = mixed;
    }
  }, [mixed]);

  const hint = locked
    ? `Excluded with its parent folder (${entry.rule || "inherited rule"})`
    : entry.excluded && !entry.ownRule
    ? `Excluded by ${entry.rule}`
    : entry.excluded
    ? "Excluded"
    : entry.directory && entry.childState === "EXCLUDED"
    ? "Everything inside is excluded"
    : entry.directory && entry.childState === "UNKNOWN"
    ? "Too large to summarise — open it to see"
    : entry.tracked
    ? "Versioned"
    : "Not yet versioned";

  return (
    <>
      <div
        className={`gitcfg-tree-row${locked ? " is-locked" : ""}`}
        style={{ paddingLeft: `${depth * 1.25 + 0.5}rem` }}
      >
        <span
          className="gitcfg-tree-twisty"
          onClick={() => entry.directory && setOpen(!open)}
        >
          {entry.directory ? <Chevron open={open} /> : null}
        </span>
        <input
          type="checkbox"
          ref={box}
          checked={versioned}
          disabled={locked}
          title={hint}
          onChange={(e) => onToggle(entry, e.target.checked)}
        />
        <span className="gitcfg-tree-icon">
          {entry.directory ? <FolderIcon /> : <FileIcon />}
        </span>
        <span
          className={`gitcfg-tree-name${versioned ? "" : " is-excluded"}`}
          onClick={() => entry.directory && setOpen(!open)}
        >
          {entry.name}
        </span>
        {entry.excluded && !entry.ownRule && entry.rule ? (
          <span className="gitcfg-tree-rule" title={hint}>
            {entry.rule}
          </span>
        ) : null}
        {selfVersioned && !entry.tracked ? (
          <span className="gitcfg-tree-rule">not yet committed</span>
        ) : null}
      </div>
      {open ? (
        <Children
          path={entry.path}
          depth={depth + 1}
          pending={pending}
          onToggle={onToggle}
        />
      ) : null}
    </>
  );
};

const Children = ({
  path,
  depth,
  pending,
  onToggle,
}: {
  path: string;
  depth: number;
  pending: Pending;
  onToggle: (entry: TreeEntry, versioned: boolean) => void;
}) => {
  const { data, isFetching, error } = useGetTreeQuery(path);
  if (isFetching) {
    return (
      <div
        className="gitcfg-tree-note"
        style={{ paddingLeft: `${depth * 1.25 + 2.25}rem` }}
      >
        Loading…
      </div>
    );
  }
  if (error) {
    return (
      <div
        className="gitcfg-tree-note is-error"
        style={{ paddingLeft: `${depth * 1.25 + 2.25}rem` }}
      >
        Could not read this folder
      </div>
    );
  }
  const entries = data ? data.entries : [];
  if (!entries.length) {
    return (
      <div
        className="gitcfg-tree-note"
        style={{ paddingLeft: `${depth * 1.25 + 2.25}rem` }}
      >
        Empty
      </div>
    );
  }
  return (
    <>
      {entries.map((e) => (
        <Row
          key={e.path}
          entry={e}
          depth={depth}
          pending={pending}
          onToggle={onToggle}
        />
      ))}
    </>
  );
};

const ExcludedFiles = () => {
  const [pending, setPending] = React.useState<Pending>(new Map());
  const [source, setSource] = React.useState(false);
  const [draft, setDraft] = React.useState<string | null>(null);
  const { data: ignore } = useGetIgnoreQuery();
  const [save, { isLoading: saving }] = useSaveIgnoreMutation();
  const toasts = useToastNotifications();

  const onToggle = (entry: TreeEntry, versioned: boolean) => {
    setPending((prev) => {
      const next = new Map(prev);
      // Ticking a row back to the state git is already in is not an edit — drop it, so Save
      // never sends a no-op and the button honestly reflects whether anything changed.
      const current =
        !entry.excluded &&
        !(entry.directory && entry.childState === "EXCLUDED");
      if (versioned === current) {
        next.delete(entry.path);
      } else {
        next.set(entry.path, versioned);
      }
      return next;
    });
  };

  const exclude: string[] = [];
  const include: string[] = [];
  pending.forEach((versioned, path) =>
    (versioned ? include : exclude).push(path)
  );
  const dirty = source
    ? draft !== null && draft !== (ignore ? ignore.text : "")
    : pending.size > 0;

  const commit = () => {
    const body = source ? { text: draft || "" } : { exclude, include };
    save(body)
      .unwrap()
      .then((res) => {
        setPending(new Map());
        setDraft(null);
        const n = res && res.untracked ? res.untracked : 0;
        toasts.notifySuccess(
          n > 0
            ? `Exclusions saved. ${n} file${
                n === 1 ? "" : "s"
              } removed from version control.`
            : "Exclusions saved."
        );
      })
      .catch(errorToast(toasts, "Could not save exclusions"));
  };

  return (
    <div className="gitcfg-excluded">
      <div className="gitcfg-excluded-head">
        <div>
          <h3>Excluded files</h3>
          <p>
            Ticked folders and files are versioned. Everything else is listed in{" "}
            <code>.gitignore</code> and left out of the config repository.
          </p>
        </div>
        <div className="gitcfg-excluded-actions">
          <Button
            colorClass="secondary"
            onClick={() => {
              setSource(!source);
              setDraft(null);
              setPending(new Map());
            }}
          >
            {source ? "Tree" : "Edit .gitignore"}
          </Button>
          <Button
            colorClass="primary"
            disabled={!dirty || saving}
            onClick={commit}
          >
            {saving ? "Saving…" : "Save"}
          </Button>
        </div>
      </div>

      {source ? (
        ignore ? (
          <TextArea
            className="gitcfg-ignore-source"
            rows={24}
            value={draft !== null ? draft : ignore.text}
            onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
              setDraft(e.target.value)
            }
          />
        ) : (
          <Loading isLoading={true} />
        )
      ) : (
        <div className="gitcfg-tree">
          <Children path="" depth={0} pending={pending} onToggle={onToggle} />
        </div>
      )}
    </div>
  );
};

export default ExcludedFiles;
