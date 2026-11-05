import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { RouteProgress, routeNodes } from "../src/features/bags/RouteProgress";
import { bag, route, movement } from "./fixtures";

describe("scanner-confirmed route", () => {
  it("does not advance location after a successful equipment command", () => {
    render(<RouteProgress bag={bag} route={route} movement={movement} />);
    expect(
      screen.getByText("junction-west", { selector: "strong" }).closest("li"),
    ).toHaveClass("pending");
    expect(
      screen.getByText("checkin-main", { selector: "strong" }).closest("li"),
    ).toHaveClass("current");
  });
  it("uses only accepted observations on the current route", () => {
    const accepted = {
      observation_id: "o",
      route_id: "route-1",
      command_id: "command-1",
      route_step_no: 1,
      node_id: "junction-west",
      scanner_id: "scanner-west",
      decision: "ACCEPTED" as const,
      rejection_code: null,
      observed_at: bag.updatedAt,
      received_at: bag.updatedAt,
    };
    const confirmed = { ...bag, confirmedNodeId: "junction-west" };
    expect(
      routeNodes(confirmed, route, {
        ...movement,
        observations: [accepted],
      }).map((n) => n.status),
    ).toEqual(["completed", "current", "pending"]);
    expect(
      routeNodes(bag, route, {
        ...movement,
        observations: [
          { ...accepted, decision: "REJECTED" },
          { ...accepted, route_id: "old-route" },
        ],
      })[1].status,
    ).toBe("pending");
  });
  it("distinguishes the failed pending step from the confirmed location", () => {
    expect(
      routeNodes({ ...bag, state: "MOVEMENT_FAILED" }, route, movement).map(
        (n) => n.status,
      ),
    ).toEqual(["current", "blocked", "pending"]);
  });
});
