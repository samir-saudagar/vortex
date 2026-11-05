import type {
  Accepted,
  Bag,
  BagPage,
  Equipment,
  Flight,
  HistoryItem,
  Journey,
  Movement,
  Route,
  Summary,
} from "../types/contracts";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public correlationId?: string,
  ) {
    super(message);
  }
}
async function request<T>(
  path: string,
  signal?: AbortSignal,
  init?: RequestInit,
): Promise<T> {
  const timeout = AbortSignal.timeout(15000);
  const response = await fetch(`/api${path}`, {
    ...init,
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(
      response.status,
      error.code ?? "REQUEST_FAILED",
      error.message ?? `Request failed (${response.status}). Try refreshing.`,
      error.correlationId,
    );
  }
  return response.json() as Promise<T>;
}
export const api = {
  bags: (search: string, state: string, page: number, signal?: AbortSignal) =>
    request<BagPage>(
      `/bags?${new URLSearchParams({ search, ...(state ? { state } : {}), page: String(page), size: "25" })}`,
      signal,
    ),
  summary: (signal?: AbortSignal) => request<Summary>("/bags/summary", signal),
  flights: (signal?: AbortSignal) =>
    request<{ items: Flight[] }>("/flights", signal),
  equipment: (signal?: AbortSignal) =>
    request<{ items: Equipment[] }>("/equipment", signal),
  async journey(id: string, signal?: AbortSignal): Promise<Journey> {
    // Independent Phase 4 projections can straddle a workflow commit. Retry on version/route changes.
    for (let attempt = 0; attempt < 3; attempt++) {
      const before = await request<Bag>(`/bags/${id}`, signal);
      const [history, route, movement] = await Promise.all([
        request<{ items: HistoryItem[] }>(`/bags/${id}/history`, signal),
        request<Route>(`/bags/${id}/route`, signal).catch((error) => {
          if (error instanceof ApiError && error.code === "ROUTE_NOT_FOUND")
            return null;
          throw error;
        }),
        request<Movement>(`/bags/${id}/movement`, signal),
      ]);
      const bag = await request<Bag>(`/bags/${id}`, signal);
      if (
        bag.stateVersion === before.stateVersion &&
        bag.routeId === before.routeId
      )
        return { bag, history: history.items, route, movement };
    }
    throw new Error(
      "Journey is changing. Refreshing again will load the latest state.",
    );
  },
  checkin: (bagTag: string, flightId: string, key: string) =>
    request<Accepted>("/bags/check-in", undefined, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: JSON.stringify({ bagTag, flightId }),
    }),
  retry: (id: string, key: string) =>
    request<Accepted>(`/bags/${id}/retry`, undefined, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: "{}",
    }),
};
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError)
    return `${error.message} (${error.code}${error.correlationId ? ` · ${error.correlationId}` : ""})`;
  return error instanceof Error
    ? error.message
    : "Unable to load data. Check the backend and try again.";
}
