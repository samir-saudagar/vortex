import type { BagState } from "../types/contracts";
export const isFailure = (state: string) =>
  ["FLIGHT_VERIFICATION_FAILED", "ROUTING_BLOCKED", "MOVEMENT_FAILED"].includes(
    state,
  );
export const label = (value: string) =>
  value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (c) => c.toUpperCase());
const dates = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium",
  timeStyle: "medium",
  timeZone: "UTC",
});
export function timestamp(value: string | null): string {
  return value ? `${dates.format(new Date(value))} UTC` : "—";
}
export function Status({ value }: { value: BagState | string }) {
  return (
    <span
      className={`badge ${isFailure(value) || ["FAILED", "REJECTED", "TIMED_OUT", "BLOCKED", "OFFLINE"].includes(value) ? "bad" : ["AT_MAKEUP", "COMPLETE", "AVAILABLE", "ACCEPTED"].includes(value) ? "good" : ""}`}
    >
      {label(value)}
    </span>
  );
}
export function ErrorNotice({
  message,
  retry,
}: {
  message: string;
  retry?: () => void;
}) {
  return (
    <div className="notice error" role="alert">
      {message} {retry && <button onClick={retry}>Try again</button>}
    </div>
  );
}
