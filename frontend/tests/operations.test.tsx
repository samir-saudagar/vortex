import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { api } from "../src/api/client";
import { createStompClient } from "../src/realtime/feed";
import { useOperations } from "../src/app/useOperations";
import { page } from "./fixtures";
import type { Summary } from "../src/types/contracts";

vi.mock("../src/api/client", async (original) => ({
  ...(await original<typeof import("../src/api/client")>()),
  api: { bags: vi.fn(), summary: vi.fn(), equipment: vi.fn(), journey: vi.fn() },
}));
vi.mock("../src/realtime/feed", async (original) => ({
  ...(await original<typeof import("../src/realtime/feed")>()),
  createStompClient: vi.fn(),
}));
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => { resolve = finish; });
  return { promise, resolve };
}
const summary = { total: 1, active: 1, completed: 0, failed: 0 };
beforeEach(() => {
  vi.mocked(api.bags).mockImplementation(async (_search, _state, index) => ({
    ...page, page: index,
  }));
  vi.mocked(api.summary).mockResolvedValue(summary);
  vi.mocked(api.equipment).mockResolvedValue({ items: [] });
  vi.mocked(createStompClient).mockReturnValue({
    connected: false, activate: vi.fn(), deactivate: vi.fn(async () => {}),
    subscribe: vi.fn(),
  } as never);
});
it("M01 aborted obsolete refresh cannot restore freshness before the new page completes", async () => {
  const { result, rerender } = renderHook(
    ({ index }) => useOperations("", "", index, null),
    { initialProps: { index: 0 } },
  );
  await waitFor(() => expect(result.current.restSync).toBe("synced"));
  const timestamp = result.current.updatedAt;
  const old = deferred<Summary>();
  const latest = deferred<Summary>();
  vi.mocked(api.summary).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  let pending!: Promise<void>;
  act(() => { pending = result.current.refresh(); });
  const signal = vi.mocked(api.summary).mock.calls.at(-1)![0]!;
  rerender({ index: 1 });
  expect(signal.aborted).toBe(true);
  await act(async () => { old.resolve(summary); });
  expect(result.current.restSync).not.toBe("synced");
  expect(result.current.updatedAt).toBe(timestamp);
  await act(async () => { latest.resolve(summary); await pending; });
  expect(result.current.restSync).toBe("synced");
  expect(result.current.list.data?.page).toBe(1);
});
it("M01 queued refresh stays unsynchronized until the latest requested cohort succeeds", async () => {
  const { result } = renderHook(() => useOperations("", "", 0, null));
  await waitFor(() => expect(result.current.restSync).toBe("synced"));
  const old = deferred<Summary>();
  const latest = deferred<Summary>();
  vi.mocked(api.summary).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  let pending!: Promise<void>;
  act(() => { pending = result.current.refresh(); void result.current.refresh(); });
  await act(async () => { old.resolve(summary); });
  expect(result.current.restSync).toBe("refreshing");
  await act(async () => { latest.resolve(summary); await pending; });
  expect(result.current.restSync).toBe("synced");
});
it("M01 unmount aborts outstanding reads and late completion starts no further operations", async () => {
  const delayed = deferred<Summary>();
  vi.mocked(api.summary).mockReturnValue(delayed.promise);
  const clear = vi.spyOn(window, "clearInterval");
  const { unmount } = renderHook(() => useOperations("", "", 0, null));
  const signal = vi.mocked(api.summary).mock.calls.at(-1)![0]!;
  const calls = vi.mocked(api.summary).mock.calls.length;
  unmount();
  expect(signal.aborted).toBe(true);
  expect(clear).toHaveBeenCalled();
  await act(async () => { delayed.resolve(summary); });
  expect(api.summary).toHaveBeenCalledTimes(calls);
});
