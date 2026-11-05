import { api, ApiError } from "../../api/client";
import type { Accepted } from "../../types/contracts";

interface Attempt {
  key: string;
  inFlight?: Promise<Accepted>;
}
// Request identity belongs to the operation, not a mounted component or a bag version.
// Keep unresolved attempts for this page session, including selection/App remounts.
const unresolved = new Map<string, Attempt>();

/** Called only after explicit operator confirmation; never automatically retries. */
export function requestRecovery(bagId: string): Promise<Accepted> {
  let attempt = unresolved.get(bagId);
  if (!attempt) {
    attempt = { key: crypto.randomUUID() };
    unresolved.set(bagId, attempt);
  }
  if (attempt.inFlight) return attempt.inFlight;
  const current = attempt;
  current.inFlight = api.retry(bagId, current.key)
    .then((accepted) => {
      if (accepted.bagId !== bagId)
        throw new Error("Recovery response could not be verified. Retry the same request.");
      unresolved.delete(bagId);
      return accepted;
    })
    .catch((error: unknown) => {
      // Only documented API rejections resolve the attempt. An opaque proxy
      // response with the same HTTP status still leaves the outcome uncertain.
      if (error instanceof ApiError && (
        (error.status === 400 && error.code === "INVALID_REQUEST") ||
        (error.status === 404 && error.code === "BAG_NOT_FOUND") ||
        (error.status === 409 && ["BAG_NOT_RETRYABLE", "IDEMPOTENCY_KEY_CONFLICT"].includes(error.code))
      ))
        unresolved.delete(bagId);
      throw error;
    })
    .finally(() => {
      current.inFlight = undefined;
    });
  return current.inFlight;
}
