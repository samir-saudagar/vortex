import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BagDetails } from "../src/features/bags/BagDetails";
import { api } from "../src/api/client";
import { journey } from "./fixtures";

describe("journey details and safe recovery", () => {
  it("H01 keeps an unresolved recovery key across background version changes and rerenders", async () => {
    const retry = vi.spyOn(api, "retry").mockRejectedValueOnce(new Error("Response uncertain")).mockResolvedValue({ bagId: "bag-h01", state: "CHECKED_IN", correlationId: "c" });
    const user = userEvent.setup();
    const failed = { ...journey, bag: { ...journey.bag, bagId: "bag-h01", state: "FLIGHT_VERIFICATION_FAILED" as const } };
    const view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText("Response uncertain");
    view.rerender(<BagDetails journey={{ ...failed, bag: { ...failed.bag, stateVersion: 7 } }} onRefresh={() => {}} />);
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(retry).toHaveBeenCalledTimes(2));
    expect(retry.mock.calls[1][1]).toBe(retry.mock.calls[0][1]);
  });
  it("H01 retains per-bag keys across remounts and never mixes bags or resubmits on mount", async () => {
    const retry = vi.spyOn(api, "retry").mockRejectedValueOnce(new Error("Unknown A")).mockImplementation(async (id) => ({ bagId: id, state: "ROUTING_BLOCKED", correlationId: "c" }));
    const user = userEvent.setup();
    const failed = { ...journey, bag: { ...journey.bag, bagId: "bag-a-remount", state: "MOVEMENT_FAILED" as const } };
    let view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText("Unknown A"); view.unmount();
    view = render(<BagDetails journey={{ ...failed, bag: { ...failed.bag, bagId: "bag-b" } }} onRefresh={() => {}} />);
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull()); view.unmount();
    view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
    expect(retry).toHaveBeenCalledTimes(2);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    expect(retry).toHaveBeenCalledTimes(2);
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(retry).toHaveBeenCalledTimes(3));
    expect(retry.mock.calls[1][1]).not.toBe(retry.mock.calls[0][1]);
    expect(retry.mock.calls[2][1]).toBe(retry.mock.calls[0][1]);
    expect(retry.mock.calls[2][0]).toBe("bag-a-remount");
  });
  it("H01 a remounted confirmation shares an in-flight request and sends no duplicate command", async () => {
    let resolve!: (accepted: { bagId: string; state: "CHECKED_IN"; correlationId: string }) => void;
    const retry = vi.spyOn(api, "retry").mockReturnValue(new Promise(r => { resolve = r; }));
    const failed = { ...journey, bag: { ...journey.bag, bagId: "bag-in-flight", state: "FLIGHT_VERIFICATION_FAILED" as const } };
    const user = userEvent.setup(); let view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    expect(retry).toHaveBeenCalledTimes(1); view.unmount();
    view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    expect(retry).toHaveBeenCalledTimes(1);
    resolve({ bagId: failed.bag.bagId, state: "CHECKED_IN", correlationId: "c" });
    await screen.findByText(/Retry accepted/);
    expect(retry).toHaveBeenCalledWith("bag-in-flight", expect.any(String));
  });
  it("H01 a definitive guarded API rejection resolves that attempt but needs confirmation for a new one", async () => {
    const { ApiError } = await import("../src/api/client");
    const retry = vi.spyOn(api, "retry").mockRejectedValueOnce(new ApiError(409, "BAG_NOT_RETRYABLE", "Already pending"))
      .mockResolvedValue({ bagId: "bag-definitive", state: "CHECKED_IN", correlationId: "c" });
    const failed = { ...journey, bag: { ...journey.bag, bagId: "bag-definitive", state: "FLIGHT_VERIFICATION_FAILED" as const } };
    const user = userEvent.setup(); render(<BagDetails journey={failed} onRefresh={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText(/BAG_NOT_RETRYABLE/);
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(retry).toHaveBeenCalledTimes(2));
    expect(retry.mock.calls[1][1]).not.toBe(retry.mock.calls[0][1]);
  });
  it.each([400, 404, 409, 503])("H01 retains the key after an unverified HTTP %s proxy response", async (status) => {
    const { ApiError } = await import("../src/api/client");
    const bagId = `bag-unverified-http-${status}`;
    const retry = vi.spyOn(api, "retry")
      .mockRejectedValueOnce(new ApiError(status, "REQUEST_FAILED", "Proxy response uncertain"))
      .mockResolvedValue({ bagId, state: "CHECKED_IN", correlationId: "c" });
    const user = userEvent.setup();
    render(<BagDetails journey={{ ...journey, bag: { ...journey.bag, bagId, state: "FLIGHT_VERIFICATION_FAILED" } }} onRefresh={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText(/Proxy response uncertain/);
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(retry).toHaveBeenCalledTimes(2));
    expect(retry.mock.calls[1][1]).toBe(retry.mock.calls[0][1]);
  });
  it("shows a successful bag, assigned destination and durable timeline", () => {
    render(
      <BagDetails
        journey={{
          ...journey,
          bag: {
            ...journey.bag,
            state: "AT_MAKEUP",
            confirmedNodeId: "makeup-lhr",
          },
        }}
        onRefresh={() => {}}
      />,
    );
    expect(screen.getByRole("heading", { name: "BAG-001" })).toBeVisible();
    expect(screen.getByText("At makeup")).toBeVisible();
    expect(screen.getByText("MOVEMENT_STARTED")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Review recovery" }),
    ).toBeNull();
    expect(screen.getAllByText("makeup-lhr", { selector: "dd" })).toHaveLength(
      2,
    );
  });
  it.each([
    "FLIGHT_VERIFICATION_FAILED",
    "ROUTING_BLOCKED",
    "MOVEMENT_FAILED",
  ] as const)("displays meaningful %s failure details", (state) => {
    render(
      <BagDetails
        journey={{
          ...journey,
          bag: {
            ...journey.bag,
            state,
            failureCode: "OBSERVATION_TIMEOUT",
            failureReason:
              "Expected scanner did not report before the deadline",
          },
        }}
        onRefresh={() => {}}
      />,
    );
    expect(screen.getByText("OBSERVATION_TIMEOUT")).toBeVisible();
    expect(
      screen.getByText("Expected scanner did not report before the deadline"),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Review recovery" }),
    ).toBeEnabled();
  });
  it("allows a later explicitly confirmed recovery when a blocked state version is unchanged", async () => {
    const retry = vi
      .spyOn(api, "retry")
      .mockResolvedValue({
        bagId: "bag-1",
        state: "ROUTING_BLOCKED",
        correlationId: "c",
      });
    const user = userEvent.setup();
    render(
      <BagDetails
        journey={{
          ...journey,
          bag: { ...journey.bag, state: "ROUTING_BLOCKED" },
        }}
        onRefresh={() => {}}
      />,
    );
    for (let i = 0; i < 2; i++) {
      await user.click(screen.getByRole("button", { name: "Review recovery" }));
      await user.click(screen.getByRole("button", { name: "Confirm retry" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    }
    expect(retry.mock.calls[0][1]).not.toBe(retry.mock.calls[1][1]);
  });
  it("requires explicit confirmation, preserves the key on an uncertain retry, and reports acceptance", async () => {
    const user = userEvent.setup();
    const retry = vi
      .spyOn(api, "retry")
      .mockRejectedValueOnce(new Error("Connection lost"))
      .mockResolvedValue({
        bagId: "bag-1",
        state: "ROUTING_BLOCKED",
        correlationId: "c",
      });
    const refresh = vi.fn();
    render(
      <BagDetails
        journey={{
          ...journey,
          bag: { ...journey.bag, state: "MOVEMENT_FAILED" },
        }}
        onRefresh={refresh}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Review recovery" }));
    expect(retry).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await screen.findByText("Connection lost");
    await user.click(screen.getByRole("button", { name: "Confirm retry" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(retry.mock.calls[0][1]).toBe(retry.mock.calls[1][1]);
    expect(screen.getByRole("status")).toHaveTextContent("Retry accepted");
  });
});
