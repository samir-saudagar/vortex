import { Client } from "@stomp/stompjs";
import type { IFrame, IMessage, StompSubscription } from "@stomp/stompjs";
import type { LiveEvent } from "../types/contracts";

export type Connection = "connecting" | "syncing" | "live" | "offline";
export class EventGate {
  private versions = new Map<string, number>();
  private seen = new Set<string>();
  accept(raw: unknown): raw is LiveEvent {
    if (!raw || typeof raw !== "object") return false;
    const e = raw as LiveEvent;
    if (
      e.eventVersion !== 1 ||
      typeof e.eventId !== "string" ||
      !e.payload ||
      this.seen.has(e.eventId)
    )
      return false;
    if (e.eventType === "BagStateChanged") {
      if (
        e.aggregateId !== e.payload.bagId ||
        !Number.isSafeInteger(e.payload.stateVersion)
      )
        return false;
      if (e.payload.stateVersion <= (this.versions.get(e.payload.bagId) ?? -1))
        return false;
      this.versions.set(e.payload.bagId, e.payload.stateVersion);
    } else if (
      e.eventType !== "EquipmentStatusChanged" ||
      e.aggregateId !== e.payload.equipmentId
    )
      return false;
    this.seen.add(e.eventId);
    if (this.seen.size > 2000)
      this.seen.delete(this.seen.values().next().value!);
    // Bound memory; old bag events still cannot patch state because every update refetches REST.
    if (this.versions.size > 1000)
      this.versions.delete(this.versions.keys().next().value!);
    return true;
  }
}
export interface StompTransport {
  connected: boolean;
  onConnect: (frame: IFrame) => void;
  onWebSocketClose: (event: CloseEvent) => void;
  onStompError: (frame: IFrame) => void;
  subscribe: (
    destination: string,
    callback: (message: IMessage) => void,
  ) => StompSubscription;
  activate: () => void;
  deactivate: () => Promise<void>;
}
export class LiveFeed {
  private ids: string[] = [];
  private subscriptions: StompSubscription[] = [];
  private gate = new EventGate();
  private generation = 0;
  private stopped = false;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  constructor(
    private client: StompTransport,
    private refresh: () => Promise<void>,
    private status: (value: Connection) => void,
  ) {
    client.onConnect = () => {
      void this.resync();
    };
    client.onWebSocketClose = () => {
      this.generation++;
      this.subscriptions = [];
      if (!this.stopped) status("offline");
    };
    client.onStompError = () => {
      this.generation++;
      if (!this.stopped) status("offline");
    };
  }
  start() {
    this.status("connecting");
    this.client.activate();
  }
  setBags(ids: string[]) {
    if (JSON.stringify(this.ids) === JSON.stringify(ids)) return;
    this.ids = ids;
    if (this.client.connected) {
      this.subscribe();
      // Close the gap between the REST list response and newly installed subscriptions.
      void this.refresh().catch(() => {});
    }
  }
  private subscribe() {
    this.subscriptions.forEach((s) => s.unsubscribe());
    this.subscriptions = [...new Set(this.ids)]
      .map((id) => `/topic/bags/${id}`)
      .concat("/topic/equipment")
      .map((destination) =>
        this.client.subscribe(destination, (message) => {
          try {
            if (this.gate.accept(JSON.parse(message.body)))
              void this.refresh().catch(() => {});
          } catch {
            /* malformed best-effort notification; REST polling repairs the view */
          }
        }),
      );
  }
  private async resync() {
    if (this.stopped) return;
    clearTimeout(this.retryTimer);
    const generation = ++this.generation;
    this.subscribe();
    this.status("syncing");
    try {
      await this.refresh();
      if (
        !this.stopped &&
        generation === this.generation &&
        this.client.connected
      )
        this.status("live");
    } catch {
      if (!this.stopped && generation === this.generation) {
        this.status(this.client.connected ? "syncing" : "offline");
        this.retryTimer = setTimeout(() => {
          if (this.client.connected) void this.resync();
        }, 3000);
      }
    }
  }
  async stop() {
    this.stopped = true;
    this.generation++;
    clearTimeout(this.retryTimer);
    if (this.client.connected)
      this.subscriptions.forEach((s) => s.unsubscribe());
    this.subscriptions = [];
    await this.client.deactivate();
  }
}
export function createStompClient(): Client {
  const url = new URL("/ws", window.location.href);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return new Client({
    brokerURL: url.toString(),
    reconnectDelay: 3000,
    connectionTimeout: 8000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });
}
