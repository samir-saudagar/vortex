import { useEffect, useState } from "react";
import { bagStates } from "../types/contracts";
import { ErrorNotice, label, Status, timestamp } from "../components/Status";
import { CheckIn } from "../features/checkin/CheckIn";
import { BagDetails } from "../features/bags/BagDetails";
import { useOperations } from "./useOperations";

export function App() {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [state, setState] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [showCheckin, setShowCheckin] = useState(false);
  useEffect(() => {
    if (searchInput.trim() === search) return;
    const timer = window.setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 250);
    return () => clearTimeout(timer);
  }, [searchInput, search]);
  const ops = useOperations(search, state, page, selected);
  const refresh = () => {
    void ops.refresh().catch(() => {});
  };
  const metrics = [
    { title: "Total baggage", key: "total" },
    { title: "In progress", key: "active" },
    { title: "At makeup", key: "completed" },
    { title: "Failed / blocked", key: "failed" },
  ] as const;
  return (
    <main className="page-shell">
      <header className="masthead">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            V
          </span>
          <div>
            <h1>VORTEX</h1>
            <p>Airport baggage operations</p>
          </div>
        </div>
        <div className="header-meta">
          <span className="local-label">LOCAL OPERATIONS</span>
          <span className={`connection ${ops.connection}`} role="status">
            {ops.connection === "live"
              ? "Live feed"
              : ops.connection === "syncing"
                ? "Live feed resynchronizing"
                : ops.connection === "connecting"
                  ? "Connecting live feed"
                  : "Live feed offline"}
            {" · "}
            {ops.restSync === "synced"
              ? "REST synced"
              : ops.restSync === "stale"
                ? "REST refresh failed · showing last successful data"
                : "REST synchronizing…"}
          </span>
        </div>
      </header>
      <div className="page-title">
        <div>
          <p className="eyebrow">OPERATIONS CONTROL</p>
          <h2>Baggage overview</h2>
          <p className="muted">
            Track every journey from check-in to scanner-confirmed arrival.
          </p>
        </div>
        <div className="actions">
          <button onClick={refresh}>Refresh data</button>
          <button
            className="primary"
            onClick={() => setShowCheckin((value) => !value)}
            aria-expanded={showCheckin}
          >
            {showCheckin ? "Close check-in" : "+ Check in baggage"}
          </button>
        </div>
      </div>
      {(ops.connection !== "live" || ops.restSync === "stale") && (
        <p className="notice">
          Live notifications are {ops.connection}. Last successful authoritative
          refresh: {timestamp(ops.updatedAt)}. Data refreshes through REST every
          10 seconds.
        </p>
      )}
      {ops.summary.error && (
        <ErrorNotice message={ops.summary.error} retry={refresh} />
      )}
      <section className="metrics" aria-label="Operations metrics">
        {metrics.map((metric) => (
          <article key={metric.key} className={`metric ${metric.key}`}>
            <p>{metric.title}</p>
            <strong>{ops.summary.data?.[metric.key] ?? "—"}</strong>
            <span>
              {metric.key === "completed"
                ? "Final scanner confirmed"
                : metric.key === "failed"
                  ? "Requires operator attention"
                  : metric.key === "active"
                    ? "Active workflow journeys"
                    : "All persisted baggage"}
            </span>
          </article>
        ))}
      </section>
      {showCheckin && (
        <CheckIn
          onAccepted={(id) => {
            setSelected(id);
            refresh();
          }}
        />
      )}
      <section className="panel" aria-labelledby="bag-list-title">
        <div className="section-heading">
          <div>
            <h2 id="bag-list-title">Baggage journeys</h2>
            <p className="muted">
              {ops.list.data
                ? `${ops.list.data.total} matching bags`
                : "Authoritative baggage records"}
            </p>
          </div>
          <span className="muted">Updated {timestamp(ops.updatedAt)}</span>
        </div>
        <div className="filters">
          <label>
            Search bags
            <input
              type="search"
              maxLength={128}
              placeholder="Bag tag, flight or bag ID"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
            />
          </label>
          <label>
            Workflow state
            <select
              value={state}
              onChange={(e) => {
                setState(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All states</option>
              {bagStates.map((s) => (
                <option key={s} value={s}>
                  {label(s)}
                </option>
              ))}
            </select>
          </label>
        </div>
        {ops.list.error && (
          <ErrorNotice message={ops.list.error} retry={refresh} />
        )}
        {ops.list.loading && <p role="status">Loading baggage…</p>}
        {ops.list.data &&
          (ops.list.data.items.length ? (
            <div className="table-scroll">
              <table className="bag-table">
                <caption className="sr-only">
                  Baggage journeys. Select a bag tag to inspect its journey.
                </caption>
                <thead>
                  <tr>
                    <th>Bag tag</th>
                    <th>Flight</th>
                    <th>Workflow state</th>
                    <th>Confirmed location</th>
                    <th>Route progress</th>
                    <th>Last update</th>
                    <th>Failure</th>
                  </tr>
                </thead>
                <tbody>
                  {ops.list.data.items.map(
                    ({
                      bag,
                      destinationNodeId,
                      confirmedSteps,
                      totalSteps,
                    }) => (
                      <tr
                        key={bag.bagId}
                        className={selected === bag.bagId ? "selected" : ""}
                      >
                        <td>
                          <button
                            className="bag-link"
                            aria-pressed={selected === bag.bagId}
                            onClick={() => setSelected(bag.bagId)}
                          >
                            {bag.bagTag}
                          </button>
                        </td>
                        <td>{bag.flightId}</td>
                        <td>
                          <Status value={bag.state} />
                        </td>
                        <td>{bag.confirmedNodeId}</td>
                        <td>
                          {totalSteps ? (
                            <>
                              {confirmedSteps} / {totalSteps} checkpoints
                              <progress
                                max={totalSteps}
                                value={confirmedSteps}
                                aria-label={`${bag.bagTag} confirmed route steps`}
                              />
                            </>
                          ) : (
                            "No traversable route"
                          )}
                          <small>
                            {destinationNodeId ?? "Destination not assigned"}
                          </small>
                        </td>
                        <td>{timestamp(bag.updatedAt)}</td>
                        <td>
                          {bag.failureCode ? (
                            <span className="failure-text">
                              {bag.failureCode}
                              <small>{bag.failureReason}</small>
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                      </tr>
                    ),
                  )}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="empty">
              No baggage matches these filters. Adjust the search or check in a
              bag.
            </p>
          ))}
        <nav className="pagination" aria-label="Baggage pages">
          <button
            disabled={page === 0 || ops.list.loading}
            onClick={() => setPage((n) => n - 1)}
          >
            Previous
          </button>
          <span>
            Page {page + 1}
            {ops.list.data
              ? ` of ${Math.max(1, Math.ceil(ops.list.data.total / ops.list.data.size))}`
              : ""}
          </span>
          <button
            disabled={
              !ops.list.data ||
              (page + 1) * ops.list.data.size >= ops.list.data.total ||
              ops.list.loading
            }
            onClick={() => setPage((n) => n + 1)}
          >
            Next
          </button>
        </nav>
      </section>
      {selected ? (
        <>
          <div className="section-heading">
            <h2>Selected journey</h2>
            <button onClick={() => setSelected(null)}>Close details</button>
          </div>
          {ops.journey.error && (
            <ErrorNotice message={ops.journey.error} retry={refresh} />
          )}
          {ops.journey.loading && <p role="status">Loading bag details…</p>}
          {ops.journey.data && (
            <BagDetails
              key={selected}
              journey={ops.journey.data}
              onRefresh={refresh}
            />
          )}
        </>
      ) : (
        <section className="panel empty">
          <h2>Inspect a baggage journey</h2>
          <p>
            Select a bag tag to see its route, commands, scanner evidence and
            history.
          </p>
        </section>
      )}
      <section className="panel">
        <div className="section-heading">
          <h2>Equipment status</h2>
          <span className="muted">Current routing availability</span>
        </div>
        {ops.equipment.error && (
          <ErrorNotice message={ops.equipment.error} retry={refresh} />
        )}
        {ops.equipment.loading && <p role="status">Loading equipment…</p>}
        <div className="equipment-grid">
          {ops.equipment.data?.map((item) => (
            <article key={item.equipmentId}>
              <div>
                <strong>{item.equipmentId}</strong>
                <small>{label(item.type)}</small>
              </div>
              <Status value={item.status} />
              <small>{timestamp(item.lastStatusAt)}</small>
            </article>
          ))}
        </div>
        {ops.equipment.data?.length === 0 && (
          <p>No equipment records available.</p>
        )}
      </section>
      <footer>
        VORTEX · Local airport simulator · PostgreSQL is the source of truth ·
        All timestamps UTC
      </footer>
    </main>
  );
}
