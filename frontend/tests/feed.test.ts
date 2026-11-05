import { describe, it, expect, vi } from "vitest";
import type { IFrame, IMessage } from "@stomp/stompjs";
import { EventGate, LiveFeed } from "../src/realtime/feed";
import type { StompTransport } from "../src/realtime/feed";
import { event } from "./fixtures";

export function transport() {
  const callbacks = new Map<string, (m: IMessage) => void>();
  const unsubscribe = vi.fn();
  const client: StompTransport = {
    connected: true,
    onConnect: () => {},
    onWebSocketClose: () => {},
    onStompError: () => {},
    activate: vi.fn(),
    deactivate: vi.fn(async () => {}),
    subscribe: vi.fn((destination, callback) => {
      callbacks.set(destination, callback);
      return { id: destination, unsubscribe };
    }),
  };
  return { client, callbacks, unsubscribe };
}
describe("committed STOMP notifications", () => {
  it("rejects duplicate, stale, malformed, unsupported and mismatched events independently per bag", () => {
    const gate = new EventGate();
    expect(gate.accept(event())).toBe(true);
    expect(gate.accept(event())).toBe(false);
    expect(gate.accept(event(5, "older"))).toBe(false);
    expect(gate.accept(event(6, "same-version"))).toBe(false);
    expect(gate.accept(event(2, "other", "bag-2"))).toBe(true);
    expect(gate.accept({ ...event(7, "bad"), aggregateId: "wrong" })).toBe(
      false,
    );
    expect(gate.accept({ ...event(7, "v2"), eventVersion: 2 })).toBe(false);
    expect(gate.accept({})).toBe(false);
  });
  it("subscribes to visible/selected bags and equipment, resyncs before live, and cleans up", async () => {
    const { client, callbacks, unsubscribe } = transport();
    let finish!: () => void;
    const refresh = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const status = vi.fn();
    const feed = new LiveFeed(client, refresh, status);
    client.connected = false;
    feed.setBags(["bag-1", "bag-2"]);
    feed.start();
    client.connected = true;
    client.onConnect({} as IFrame);
    expect([...callbacks.keys()]).toEqual([
      "/topic/bags/bag-1",
      "/topic/bags/bag-2",
      "/topic/equipment",
    ]);
    expect(status).toHaveBeenLastCalledWith("syncing");
    finish();
    await Promise.resolve();
    expect(status).toHaveBeenLastCalledWith("live");
    callbacks.get("/topic/bags/bag-2")!({
      body: JSON.stringify(event(2, "e2", "bag-2")),
    } as IMessage);
    expect(refresh).toHaveBeenCalledTimes(2);
    callbacks.get("/topic/bags/bag-2")!({
      body: JSON.stringify(event(2, "e2", "bag-2")),
    } as IMessage);
    expect(refresh).toHaveBeenCalledTimes(2);
    client.onWebSocketClose({} as CloseEvent);
    expect(status).toHaveBeenLastCalledWith("offline");
    client.onConnect({} as IFrame);
    expect(status).toHaveBeenLastCalledWith("syncing");
    finish();
    await Promise.resolve();
    expect(status).toHaveBeenLastCalledWith("live");
    await feed.stop();
    expect(unsubscribe).toHaveBeenCalledTimes(3);
    expect(client.deactivate).toHaveBeenCalledOnce();
  });
  it("retries a failed reconnect resync and stops its retry timer on unmount", async () => {
    vi.useFakeTimers();
    try {
      const { client, unsubscribe } = transport();
      const refresh = vi
        .fn()
        .mockRejectedValueOnce(new Error("DB down"))
        .mockResolvedValue(undefined);
      const status = vi.fn();
      const feed = new LiveFeed(client, refresh, status);
      client.connected = false;
      feed.setBags(["bag-1"]);
      client.connected = true;
      client.onConnect({} as IFrame);
      await Promise.resolve();
      expect(status).toHaveBeenLastCalledWith("syncing");
      await vi.advanceTimersByTimeAsync(3000);
      expect(status).toHaveBeenLastCalledWith("live");
      expect(unsubscribe).toHaveBeenCalledTimes(2);
      await feed.stop();
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });
  it("does not mark a closed or unmounted feed live when a late REST resync completes", async () => {
    const { client } = transport();
    let finish!: () => void;
    const status = vi.fn();
    const feed = new LiveFeed(
      client,
      () =>
        new Promise<void>((r) => {
          finish = r;
        }),
      status,
    );
    client.onConnect({} as IFrame);
    client.onWebSocketClose({} as CloseEvent);
    finish();
    await Promise.resolve();
    expect(status).toHaveBeenLastCalledWith("offline");
    await feed.stop();
  });
});
