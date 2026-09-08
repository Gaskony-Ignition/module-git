// The platform's SelectInput spreads its rest props onto a MUI Select, so onChange receives
// MUI's (event, child) — not the value. Reading it as a string silently stored an event object.
export const selectValue = (e: unknown): string => {
  if (typeof e === "string") return e;
  const target = (e as { target?: { value?: unknown } } | null)?.target;
  return target && target.value != null ? String(target.value) : "";
};
