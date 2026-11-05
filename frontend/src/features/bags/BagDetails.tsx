import { useState } from "react";
import { errorMessage } from "../../api/client";
import type { Journey } from "../../types/contracts";
import {
  ErrorNotice,
  isFailure,
  label,
  Status,
  timestamp,
} from "../../components/Status";
import { RouteProgress } from "./RouteProgress";
import { requestRecovery } from "./recoveryRequests";

export function BagDetails({
  journey,
  onRefresh,
}: {
  journey: Journey;
  onRefresh: () => void;
}) {
  const { bag, route, movement, history } = journey;
  const [confirm, setConfirm] = useState(false);
  const [pending, setPending] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");
  return (
    <section className="panel detail-panel" aria-labelledby="detail-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">JOURNEY INSPECTOR</p>
          <h2 id="detail-title">{bag.bagTag}</h2>
        </div>
        <Status value={bag.state} />
      </div>
      <dl className="facts">
        <div>
          <dt>Bag ID</dt>
          <dd className="mono">{bag.bagId}</dd>
        </div>
        <div>
          <dt>Flight</dt>
          <dd>{bag.flightId}</dd>
        </div>
        <div>
          <dt>Confirmed location</dt>
          <dd>{bag.confirmedNodeId}</dd>
        </div>
        <div>
          <dt>Destination makeup</dt>
          <dd>{route?.destinationNodeId ?? "Not assigned"}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{timestamp(bag.updatedAt)}</dd>
        </div>
        <div>
          <dt>State version</dt>
          <dd>{bag.stateVersion}</dd>
        </div>
      </dl>
      {isFailure(bag.state) && (
        <div className="notice error">
          <strong>{bag.failureCode ?? label(bag.state)}</strong>
          <p>
            {bag.failureReason ??
              "No additional failure reason was recorded. Inspect the timeline and movement diagnostics."}
          </p>
          <button onClick={() => setConfirm(true)} disabled={pending}>
            Review recovery
          </button>
        </div>
      )}
      {confirm && (
        <div
          role="dialog"
          aria-modal="false"
          aria-labelledby="recovery-title"
          className="notice confirmation"
        >
          <h3 id="recovery-title">Confirm workflow retry</h3>
          <p>
            Correct the reported cause before retrying. Flight failures repeat
            verification; routing and movement failures plan from the last
            confirmed node, {bag.confirmedNodeId}. Uncertain equipment commands
            are never replayed.
          </p>
          <div className="actions">
            <button
              autoFocus
              onClick={() => setConfirm(false)}
              disabled={pending}
            >
              Cancel
            </button>
            <button
              className="primary"
              disabled={pending}
              onClick={async () => {
                setPending(true);
                setError("");
                setFeedback("");
                try {
                  await requestRecovery(bag.bagId);
                  setFeedback(
                    "Retry accepted. Processing is asynchronous; the timeline will show the outcome.",
                  );
                  setConfirm(false);
                  onRefresh();
                } catch (error) {
                  setError(errorMessage(error));
                } finally {
                  setPending(false);
                }
              }}
            >
              {pending ? "Requesting…" : "Confirm retry"}
            </button>
          </div>
        </div>
      )}
      {error && <ErrorNotice message={error} />}
      {feedback && (
        <p role="status" className="notice">
          {feedback}
        </p>
      )}
      <RouteProgress bag={bag} route={route} movement={movement} />
      <h3>Movement commands</h3>
      <p className="muted">
        Includes previous route attempts. A successful command awaits scanner
        confirmation.
      </p>
      {!movement.commands.length ? (
        <p>No commands issued.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Command / route</th>
                <th>Step / equipment</th>
                <th>Movement</th>
                <th>Status</th>
                <th>Issued / completed</th>
                <th>Failure</th>
              </tr>
            </thead>
            <tbody>
              {movement.commands.map((c) => (
                <tr key={c.command_id}>
                  <td className="mono">
                    {c.command_id}
                    <small>{c.route_id}</small>
                  </td>
                  <td>
                    {c.route_step_no} · attempt {c.attempt}
                    <small>{c.equipment_id}</small>
                  </td>
                  <td>
                    {c.from_node_id} → {c.to_node_id}
                  </td>
                  <td>
                    <Status value={c.status} />
                  </td>
                  <td>
                    {timestamp(c.issued_at)}
                    <small>{timestamp(c.completed_at)}</small>
                  </td>
                  <td>
                    {c.failure_code ?? "—"}
                    <small>{c.failure_reason}</small>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {movement.progress.length > 0 && (
        <details>
          <summary>Route execution and observation deadlines</summary>
          {movement.progress.map((p) => (
            <p key={p.route_id}>
              <span className="mono">{p.route_id}</span> · {label(p.status)} ·
              next step {p.next_step_no} · last confirmed{" "}
              {p.last_confirmed_node_id} · observation deadline{" "}
              {timestamp(p.observation_deadline_at)}
            </p>
          ))}
        </details>
      )}
      <h3>Scanner observations</h3>
      {!movement.observations.length ? (
        <p>No scanner observations recorded.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Scanner / node</th>
                <th>Route / step</th>
                <th>Decision</th>
                <th>Observed</th>
              </tr>
            </thead>
            <tbody>
              {movement.observations.map((o) => (
                <tr key={o.observation_id}>
                  <td>
                    {o.scanner_id}
                    <small>{o.node_id}</small>
                  </td>
                  <td className="mono">
                    {o.route_id}
                    <small>Step {o.route_step_no}</small>
                  </td>
                  <td>
                    <Status value={o.decision} />
                    <small>{o.rejection_code}</small>
                  </td>
                  <td>{timestamp(o.observed_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>Journey timeline</h3>
      <p className="muted">
        Durable history in backend acceptance order. All times are UTC.
      </p>
      {!history.length ? (
        <p>No history recorded.</p>
      ) : (
        <ol className="timeline">
          {history.map((item) => (
            <li key={item.historyId}>
              <div>
                <strong>
                  {item.currentState
                    ? label(item.currentState)
                    : label(item.recordType)}
                </strong>
                <span>{item.reasonCode}</span>
                <small>{item.nodeId ?? "No location change"}</small>
                {item.details && (
                  <details>
                    <summary>Decision details</summary>
                    <pre>{item.details}</pre>
                  </details>
                )}
              </div>
              <time dateTime={item.occurredAt}>
                {timestamp(item.occurredAt)}
                <small>Recorded {timestamp(item.recordedAt)}</small>
              </time>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
