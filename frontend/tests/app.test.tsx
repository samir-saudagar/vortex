import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { IFrame, IMessage } from "@stomp/stompjs";
import { App } from "../src/app/App";
import { api } from "../src/api/client";
import { createStompClient } from "../src/realtime/feed";
import { journey, page, event } from "./fixtures";

vi.mock("../src/api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/api/client")>()),
  api: {
    bags: vi.fn(),
    summary: vi.fn(),
    equipment: vi.fn(),
    journey: vi.fn(),
    flights: vi.fn(),
    checkin: vi.fn(),
    retry: vi.fn(),
  },
}));
vi.mock("../src/realtime/feed", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/realtime/feed")>()),
  createStompClient: vi.fn(),
}));
function newClient() {
  const callbacks = new Map<string, (m: IMessage) => void>();
  const client = {
    connected: false,
    onConnect: (_: IFrame) => {},
    onWebSocketClose: (_: CloseEvent) => {},
    onStompError: (_: IFrame) => {},
    activate: vi.fn(),
    deactivate: vi.fn(async () => {}),
    subscribe: vi.fn(
      (destination: string, callback: (message: IMessage) => void) => {
        callbacks.set(destination, callback);
        return { id: destination, unsubscribe: vi.fn() };
      },
    ),
  };
  return { client, callbacks };
}
beforeEach(() => {
  vi.mocked(api.bags).mockResolvedValue(page);
  vi.mocked(api.summary).mockResolvedValue({
    total: 1,
    active: 1,
    completed: 0,
    failed: 0,
  });
  vi.mocked(api.equipment).mockResolvedValue({ items: [] });
  vi.mocked(api.journey).mockResolvedValue(journey);
  vi.mocked(api.flights).mockResolvedValue({
    items: [
      {
        flightId: journey.bag.flightId,
        carrierCode: "VX",
        flightNumber: "204",
        serviceDate: "2030-01-15",
        departureAirport: "JFK",
        destinationAirport: "LHR",
        status: "SCHEDULED",
        checkInClosesAt: "2030-01-15T12:00:00Z",
      },
    ],
  });
  vi.mocked(api.checkin).mockResolvedValue({
    bagId: journey.bag.bagId,
    state: "CHECKED_IN",
    correlationId: "c",
  });
});
describe("operations dashboard", () => {
  it("M01 downgrades REST freshness on failure, retains data and restores freshness independently of the socket", async () => {
    const { client } = newClient(); vi.mocked(createStompClient).mockReturnValue(client as never);
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole("button", { name: "BAG-001" });
    await act(async () => { client.connected = true; client.onConnect({} as IFrame); });
    await screen.findByText("Live feed · REST synced");
    vi.mocked(api.summary).mockRejectedValue(new Error("Database unavailable"));
    await user.click(screen.getByRole("button", { name: "Refresh data" }));
    await screen.findByText("Database unavailable");
    expect(screen.queryByText("Live feed · REST synced")).toBeNull();
    expect(screen.getByRole("button", { name: "BAG-001" })).toBeVisible();
    expect(screen.getByRole("status").textContent).toContain("REST refresh failed");
    expect(screen.getByRole("status").textContent).not.toContain("offline");
    vi.mocked(api.summary).mockResolvedValue({ total: 1, active: 1, completed: 0, failed: 0 });
    await user.click(screen.getByRole("button", { name: "Refresh data" }));
    await screen.findByText("Live feed · REST synced");
    await act(async () => { client.connected = false; client.onWebSocketClose({} as CloseEvent); });
    expect(screen.getByRole("status").textContent).toContain("Live feed offline");
    expect(screen.getByRole("status").textContent).toContain("REST synced");
  });
  it("H01 STOMP invalidation and REST refresh preserve an uncertain recovery key without auto retry", async () => {
    const { client, callbacks } = newClient(); vi.mocked(createStompClient).mockReturnValue(client as never);
    const failed = { ...journey, bag: { ...journey.bag, state: "FLIGHT_VERIFICATION_FAILED" as const } };
    vi.mocked(api.journey).mockResolvedValue(failed);
    vi.mocked(api.retry).mockRejectedValueOnce(new Error("Uncertain recovery")).mockResolvedValue({ bagId: "bag-1", state: "CHECKED_IN", correlationId: "c" });
    const user = userEvent.setup(); render(<App />);
    await user.click(await screen.findByRole("button", { name: "BAG-001" }));
    await screen.findByRole("heading", { name: "BAG-001" });
    await act(async () => { client.connected = true; client.onConnect({} as IFrame); });
    await screen.findByText("Live feed · REST synced");
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText("Uncertain recovery");
    vi.mocked(api.journey).mockResolvedValue({ ...failed, bag: { ...failed.bag, stateVersion: 7 } });
    await act(async () => { callbacks.get("/topic/bags/bag-1")!({ body: JSON.stringify(event(7, "recovery-update")) } as IMessage); });
    await waitFor(() => expect(screen.getByText("State version").nextSibling).toHaveTextContent("7"));
    expect(api.retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(api.retry).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.retry).mock.calls[1][1]).toBe(vi.mocked(api.retry).mock.calls[0][1]);
  });
  it("loads next and previous backend pages", async () => {
    vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
    const next = { ...page.items[0], bag: { ...page.items[0].bag, bagId: "bag-26", bagTag: "BAG-026" } };
    vi.mocked(api.bags).mockImplementation(async (_search, _state, index) => ({ ...page, page: index, total: 26, items: index === 0 ? page.items : [next] }));
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole("button", { name: "BAG-001" });
    await user.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByRole("button", { name: "BAG-026" });
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(api.bags).toHaveBeenCalledWith("", "", 1, expect.any(AbortSignal));
    await user.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByRole("button", { name: "BAG-001" });
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
  });
  it("keeps the selected page when the initial unchanged search debounce expires", async () => {
    vi.useFakeTimers();
    vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
    const next = { ...page.items[0], bag: { ...page.items[0].bag, bagId: "bag-26", bagTag: "BAG-026" } };
    vi.mocked(api.bags).mockImplementation(async (_search, _state, index) => ({
      ...page, page: index, total: 26, items: index === 0 ? page.items : [next],
    }));
    const view = render(<App />);
    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Next" })); });
      expect(screen.getByRole("button", { name: "BAG-026" })).toBeVisible();
      await act(async () => { await vi.advanceTimersByTimeAsync(250); });
      expect(screen.getByRole("button", { name: "BAG-026" })).toBeVisible();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(api.bags).toHaveBeenLastCalledWith("", "", 1, expect.any(AbortSignal));
    } finally {
      view.unmount();
      vi.useRealTimers();
    }
  });
  it("discovers new bags on the existing ten-second REST refresh without any STOMP event", async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
      const view = render(<App />);
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      expect(screen.getByRole("button", { name: "BAG-001" })).toBeVisible();
      const next = { ...page.items[0], bag: { ...page.items[0].bag, bagId: "bag-new", bagTag: "NEW-BAG" } };
      vi.mocked(api.bags).mockResolvedValue({ ...page, total: 2, items: [...page.items, next] });
      await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
      expect(screen.getByRole("button", { name: "NEW-BAG" })).toBeVisible();
      const calls = vi.mocked(api.bags).mock.calls.length; view.unmount();
      await vi.advanceTimersByTimeAsync(10000);
      expect(api.bags).toHaveBeenCalledTimes(calls);
    } finally { vi.useRealTimers(); }
  });
  it("loads initial data under StrictMode, searches, filters, selects a journey and handles live/reconnect REST refresh", async () => {
    const { client, callbacks } = newClient();
    vi.mocked(createStompClient).mockReturnValue(client as never);
    const user = userEvent.setup();
    render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
    await screen.findByRole("button", { name: "BAG-001" });
    expect(screen.getByText("1 matching bags")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "BAG-001" }));
    await screen.findByRole("heading", { name: "BAG-001" });
    await act(async () => {
      client.connected = true;
      client.onConnect({} as IFrame);
    });
    await screen.findByText("Live feed · REST synced");
    vi.mocked(api.journey).mockResolvedValue({
      ...journey,
      bag: {
        ...journey.bag,
        state: "AT_MAKEUP",
        confirmedNodeId: "makeup-lhr",
        stateVersion: 6,
      },
    });
    await act(async () => {
      callbacks.get("/topic/bags/bag-1")!({
        body: JSON.stringify(event()),
      } as IMessage);
    });
    await waitFor(() =>
      expect(screen.getAllByText("At makeup").length).toBeGreaterThan(0),
    );
    const calls = vi.mocked(api.journey).mock.calls.length;
    await act(async () => {
      callbacks.get("/topic/bags/bag-1")!({
        body: JSON.stringify(event(4, "stale")),
      } as IMessage);
    });
    expect(api.journey).toHaveBeenCalledTimes(calls);
    await act(async () => {
      client.connected = false;
      client.onWebSocketClose({} as CloseEvent);
    });
    expect(
      screen.getByText("Live feed offline · REST synced"),
    ).toBeVisible();
    await act(async () => {
      client.connected = true;
      client.onConnect({} as IFrame);
    });
    await screen.findByText("Live feed · REST synced");
    expect(vi.mocked(api.journey).mock.calls.length).toBeGreaterThan(calls);
    await user.type(screen.getByLabelText("Search bags"), "BAG");
    await waitFor(() =>
      expect(api.bags).toHaveBeenCalledWith(
        "BAG",
        "",
        0,
        expect.any(AbortSignal),
      ),
    );
    await user.selectOptions(
      screen.getByLabelText("Workflow state"),
      "MOVEMENT_FAILED",
    );
    await waitFor(() =>
      expect(api.bags).toHaveBeenCalledWith(
        "BAG",
        "MOVEMENT_FAILED",
        0,
        expect.any(AbortSignal),
      ),
    );
  });
  it("discards a slow old journey response after the operator selects a different bag", async () => {
    vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
    const other = { ...journey.bag, bagId: "bag-2", bagTag: "BAG-002" };
    vi.mocked(api.bags).mockResolvedValue({
      ...page,
      total: 2,
      items: [...page.items, { ...page.items[0], bag: other }],
    });
    let release!: (value: typeof journey) => void;
    const slow = new Promise<typeof journey>((resolve) => {
      release = resolve;
    });
    vi.mocked(api.journey).mockImplementation((id) =>
      id === "bag-1" ? slow : Promise.resolve({ ...journey, bag: other }),
    );
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "BAG-001" }));
    await waitFor(() =>
      expect(api.journey).toHaveBeenCalledWith(
        "bag-1",
        expect.any(AbortSignal),
      ),
    );
    await user.click(screen.getByRole("button", { name: "BAG-002" }));
    await act(async () => {
      release(journey);
    });
    await screen.findByRole("heading", { name: "BAG-002" });
    expect(screen.queryByRole("heading", { name: "BAG-001" })).toBeNull();
  });
  it("shows loading, API errors, retry controls and empty results", async () => {
    vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
    vi.mocked(api.bags).mockRejectedValue(new Error("Backend unavailable"));
    const user = userEvent.setup();
    render(<App />);
    expect(screen.getByText("Loading baggage…")).toBeVisible();
    await screen.findByText("Backend unavailable");
    vi.mocked(api.bags).mockResolvedValue({ ...page, items: [], total: 0 });
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText(
      "No baggage matches these filters. Adjust the search or check in a bag.",
    );
  });
  it("checks in against real API contract and opens the accepted journey", async () => {
    vi.mocked(createStompClient).mockReturnValue(newClient().client as never);
    const user = userEvent.setup();
    render(<App />);
    await user.click(
      screen.getByRole("button", { name: "+ Check in baggage" }),
    );
    await user.type(screen.getByLabelText("Bag tag"), "NEW-001");
    await waitFor(() => expect(screen.getByLabelText("Flight")).toBeEnabled());
    await user.selectOptions(
      screen.getByLabelText("Flight"),
      journey.bag.flightId,
    );
    await user.click(screen.getByRole("button", { name: /^Check in bag$/ }));
    await screen.findByRole("heading", { name: "BAG-001" });
    expect(api.checkin).toHaveBeenCalledWith(
      "NEW-001",
      journey.bag.flightId,
      expect.any(String),
    );
  });
});
