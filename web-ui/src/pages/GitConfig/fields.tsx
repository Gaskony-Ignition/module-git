import React from "react";
import {
  SelectInput as BaseSelectInput,
  TextArea as BaseTextArea,
  TextInput as BaseTextInput,
} from "../../webui";

/**
 * Label-painting wrappers around the platform inputs.
 *
 * The platform's TextInput/SelectInput/TextArea accept a `label` and render it ONLY into MUI's
 * notched-outline legend, which ships at opacity 0 — it is there to cut the notch, not to be
 * read. Measured on 8.3.8: every bare field on these tabs had its label in the DOM and invisible
 * on screen, and forcing the legend opaque only clips it against the border.
 *
 * These render the label above the control and pass everything else straight through, so every
 * existing `label=` call site works unchanged. `label` is deliberately NOT forwarded — without it
 * the notch stays closed and the outline is unbroken.
 */
type Props = { label?: React.ReactNode } & Record<string, unknown>;

const labelled = (Base: React.ComponentType<Record<string, unknown>>) =>
  function Labelled({ label, ...rest }: Props) {
    if (!label) return <Base {...rest} />;
    return (
      <div className="gitcfg-field">
        <span className="gitcfg-field-label">{label}</span>
        <Base {...rest} />
      </div>
    );
  };

export const TextInput = labelled(BaseTextInput);
export const SelectInput = labelled(BaseSelectInput);
export const TextArea = labelled(BaseTextArea);
