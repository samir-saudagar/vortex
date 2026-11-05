import { useEffect, useRef, useState } from "react";
import { api, errorMessage } from "../../api/client";
import type { Flight } from "../../types/contracts";
import { ErrorNotice, label } from "../../components/Status";

export function CheckIn({ onAccepted }: { onAccepted: (id: string) => void }) {
  const [flights, setFlights] = useState<Flight[]>([]);
  const [tag, setTag] = useState("");
  const [flightId, setFlightId] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const attempt = useRef<{ body: string; key: string } | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    api
      .flights(abort.signal)
      .then((value) => {
        setFlights(value.items);
        setError("");
      })
      .catch((error) => {
        if (!abort.signal.aborted) setError(errorMessage(error));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [reload]);
  return (
    <section className="panel">
      <h2>Check in baggage</h2>
      <p className="muted">
        Flight eligibility is verified after durable acceptance.
      </p>
      {error && (
        <ErrorNotice message={error} retry={() => setReload((n) => n + 1)} />
      )}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setPending(true);
          setError("");
          const body = JSON.stringify([tag.trim(), flightId]);
          if (attempt.current?.body !== body)
            attempt.current = { body, key: crypto.randomUUID() };
          try {
            const accepted = await api.checkin(
              tag.trim(),
              flightId,
              attempt.current.key,
            );
            setTag("");
            attempt.current = null;
            onAccepted(accepted.bagId);
          } catch (error) {
            setError(errorMessage(error));
          } finally {
            setPending(false);
          }
        }}
        className="checkin-form"
      >
        <label>
          Bag tag
          <input
            required
            maxLength={128}
            pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
            value={tag}
            onChange={(e) => setTag(e.target.value)}
            disabled={pending}
            placeholder="Scan or enter bag tag"
          />
        </label>
        <label>
          Flight
          <select
            required
            value={flightId}
            onChange={(e) => setFlightId(e.target.value)}
            disabled={pending || loading}
          >
            <option value="">
              {loading ? "Loading flights…" : "Select a flight"}
            </option>
            {flights.map((f) => (
              <option key={f.flightId} value={f.flightId}>
                {f.carrierCode} {f.flightNumber} · {f.departureAirport} →{" "}
                {f.destinationAirport} · {f.serviceDate} · {label(f.status)}
              </option>
            ))}
          </select>
        </label>
        <button
          className="primary"
          disabled={pending || loading || !flights.length}
        >
          {pending ? "Accepting…" : "Check in bag"}
        </button>
      </form>
      {!loading && !flights.length && (
        <p>No flights available in the local catalog.</p>
      )}
    </section>
  );
}
