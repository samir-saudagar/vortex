import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import { bag, journey } from "./fixtures";
afterEach(() => vi.unstubAllGlobals());
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status });
describe("Phase 4 REST mapping", () => {
  it("retains movement snake_case and treats only ROUTE_NOT_FOUND as an absent route", async () => {
    const fetch = vi.fn(async (path: string) =>
      path.endsWith("/history")
        ? json({ items: journey.history })
        : path.endsWith("/movement")
          ? json(journey.movement)
          : path.endsWith("/route")
            ? json({ code: "ROUTE_NOT_FOUND", message: "Not planned" }, 404)
            : json(bag),
    );
    vi.stubGlobal("fetch", fetch);
    expect((await api.journey("bag-1")).movement.commands[0].command_id).toBe(
      "command-1",
    );
    expect((await api.journey("bag-1")).route).toBeNull();
    fetch.mockImplementation(async () =>
      json(
        { code: "DATABASE_UNAVAILABLE", message: "Database unavailable" },
        503,
      ),
    );
    await expect(api.journey("bag-1")).rejects.toBeInstanceOf(ApiError);
  });
  it("encodes search/filter pagination and preserves the retry key after uncertain responses", async () => {
    const fetch = vi.fn(async (_path: string, _init?: RequestInit) => json({}));
    vi.stubGlobal("fetch", fetch);
    await api.bags("tag & flight", "MOVEMENT_FAILED", 2);
    expect(fetch.mock.calls[0][0]).toContain(
      "search=tag+%26+flight&state=MOVEMENT_FAILED&page=2&size=25",
    );
    await api.retry("bag-1", "stable-key");
    expect(fetch).toHaveBeenLastCalledWith(
      "/api/bags/bag-1/retry",
      expect.objectContaining({
        headers: expect.objectContaining({ "Idempotency-Key": "stable-key" }),
        body: "{}",
      }),
    );
  });
  it("retries a read straddling state versions", async () => {
    let reads = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/history")
          ? json({ items: [] })
          : path.endsWith("/movement")
            ? json(journey.movement)
            : path.endsWith("/route")
              ? json(journey.route)
              : json({ ...bag, stateVersion: ++reads === 1 ? 5 : 6 }),
      ),
    );
    expect((await api.journey("bag-1")).bag.stateVersion).toBe(6);
    expect(reads).toBe(4);
  });
});
