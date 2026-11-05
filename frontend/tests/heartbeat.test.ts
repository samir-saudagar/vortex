import { expect, it, vi } from "vitest";
import type { IStompSocket } from "@stomp/stompjs";
import { createStompClient, LiveFeed } from "../src/realtime/feed";

// Exercise the actual STOMP parser/heartbeat/reconnect timers with an in-memory
// socket and deterministic time. This is not a browser or network black-hole test.
function socket() {
  const ws = {
    readyState: 0,
    binaryType: "arraybuffer",
    onopen: null as ((event: Event) => void) | null,
    onmessage: null as ((event: MessageEvent) => void) | null,
    onclose: null as ((event: CloseEvent) => void) | null,
    onerror: null as ((event: Event) => void) | null,
    send: vi.fn(),
    close: vi.fn(() => {
      if (ws.readyState === 3) return;
      ws.readyState = 3;
      ws.onclose?.(new CloseEvent("close"));
    }),
  };
  return {
    ws,
    connect() {
      ws.readyState = 1;
      ws.onopen?.(new Event("open"));
      ws.onmessage?.(new MessageEvent("message", {
        data: "CONNECTED\nversion:1.2\nheart-beat:10000,10000\n\n\0",
      }));
    },
  };
}
it("negotiated heartbeat loss closes the real STOMP client and reconnect waits for REST resync", async () => {
  vi.useFakeTimers();
  const sockets: ReturnType<typeof socket>[] = [];
  const client = createStompClient();
  client.webSocketFactory = () => {
    const next = socket();
    sockets.push(next);
    return next.ws as unknown as IStompSocket;
  };
  const status = vi.fn();
  let synced!: () => void;
  const refresh = vi.fn().mockResolvedValueOnce(undefined).mockImplementationOnce(
    () => new Promise<void>((finish) => { synced = finish; }),
  );
  const feed = new LiveFeed(client, refresh, status);
  try {
    feed.start();
    await vi.advanceTimersByTimeAsync(0);
    sockets[0].connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(status).toHaveBeenLastCalledWith("live");
    // The negotiated 10s interval and STOMP's default tolerance detect loss at 30s.
    await vi.advanceTimersByTimeAsync(30001);
    expect(sockets[0].ws.close).toHaveBeenCalledOnce();
    expect(status).toHaveBeenLastCalledWith("offline");
    await vi.advanceTimersByTimeAsync(3000);
    expect(sockets).toHaveLength(2);
    sockets[1].connect();
    expect(status).toHaveBeenLastCalledWith("syncing");
    expect(refresh).toHaveBeenCalledTimes(2);
    synced();
    await vi.advanceTimersByTimeAsync(0);
    expect(status).toHaveBeenLastCalledWith("live");
  } finally {
    const stopped = feed.stop();
    sockets.at(-1)?.ws.close();
    await stopped;
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  }
});
