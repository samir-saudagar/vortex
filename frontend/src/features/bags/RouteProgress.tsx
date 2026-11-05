import type { Bag, Movement, Route } from "../../types/contracts";
import { isFailure, label } from "../../components/Status";

export function routeNodes(bag: Bag, route: Route, movement: Movement) {
  // The command's SUCCEEDED status is intentionally irrelevant to arrival.
  const accepted = movement.observations.filter(
    (o) => o.route_id === route.routeId && o.decision === "ACCEPTED",
  );
  return [route.sourceNodeId, ...route.steps.map((s) => s.toNodeId)].map(
    (node, index) => {
      const confirmed =
        index === 0 ||
        accepted.some((o) => o.route_step_no === index && o.node_id === node);
      const current = node === bag.confirmedNodeId;
      const blocked =
        !confirmed &&
        isFailure(bag.state) &&
        (route.status === "BLOCKED" ||
          index ===
            route.steps.find((s) => s.fromNodeId === bag.confirmedNodeId)
              ?.stepNo);
      return {
        node,
        status: current
          ? "current"
          : confirmed
            ? "completed"
            : blocked
              ? "blocked"
              : "pending",
      };
    },
  );
}
export function RouteProgress({
  bag,
  route,
  movement,
}: {
  bag: Bag;
  route: Route | null;
  movement: Movement;
}) {
  if (!route)
    return (
      <p className="muted">
        No route planned yet. Flight verification or route planning may still be
        pending.
      </p>
    );
  return (
    <>
      <div className="section-heading">
        <h3>Planned route</h3>
        <span>
          {label(route.status)} · {route.networkVersion}
        </span>
      </div>
      <p className="muted">
        Destination makeup: <strong>{route.destinationNodeId}</strong> ·
        Location advances only after an accepted scanner observation. Check-in
        is the initial location.
      </p>
      <ol className="route-nodes" aria-label="Scanner-confirmed route progress">
        {routeNodes(bag, route, movement).map((item, i) => (
          <li key={`${i}-${item.node}`} className={item.status}>
            <span className="node-mark" aria-hidden="true">
              {i}
            </span>
            <strong>{item.node}</strong>
            <small>{label(item.status)}</small>
          </li>
        ))}
      </ol>
      <div className="table-scroll">
        <table>
          <caption className="sr-only">Route steps</caption>
          <thead>
            <tr>
              <th>Step</th>
              <th>Movement</th>
              <th>Equipment / expected scanner</th>
              <th>Cost</th>
            </tr>
          </thead>
          <tbody>
            {route.steps.map((step) => (
              <tr key={step.stepNo}>
                <td>{step.stepNo}</td>
                <td>
                  {step.fromNodeId} → {step.toNodeId}
                </td>
                <td>
                  {step.equipmentId}
                  <small>{step.expectedScannerId}</small>
                </td>
                <td>{step.cost}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details>
        <summary>
          Routing decision · {route.algorithm} · cost {route.totalCost}
        </summary>
        <pre>{route.decisionSummary}</pre>
      </details>
    </>
  );
}
