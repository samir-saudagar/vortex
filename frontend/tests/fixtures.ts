import type {
  Bag,
  BagPage,
  Journey,
  Movement,
  Route,
} from "../src/types/contracts";
export const bag: Bag = {
  bagId: "bag-1",
  bagTag: "BAG-001",
  flightId: "FL-204-2030-01-15",
  state: "IN_TRANSIT",
  confirmedNodeId: "checkin-main",
  routeId: "route-1",
  stateVersion: 5,
  failureCode: null,
  failureReason: null,
  updatedAt: "2026-11-05T10:00:00Z",
};
export const route: Route = {
  routeId: "route-1",
  status: "ACTIVE",
  networkVersion: "demo-v1",
  sourceNodeId: "checkin-main",
  destinationNodeId: "makeup-lhr",
  algorithm: "DIJKSTRA_COST_V1",
  totalCost: 2,
  decisionSummary: "{}",
  steps: [
    {
      stepNo: 1,
      fromNodeId: "checkin-main",
      toNodeId: "junction-west",
      equipmentId: "belt-west",
      expectedScannerId: "scanner-west",
      cost: 1,
    },
    {
      stepNo: 2,
      fromNodeId: "junction-west",
      toNodeId: "makeup-lhr",
      equipmentId: "belt-lhr",
      expectedScannerId: "scanner-lhr",
      cost: 1,
    },
  ],
};
export const movement: Movement = {
  bagId: bag.bagId,
  progress: [],
  observations: [],
  commands: [
    {
      command_id: "command-1",
      route_id: "route-1",
      route_step_no: 1,
      attempt: 1,
      equipment_id: "belt-west",
      from_node_id: "checkin-main",
      to_node_id: "junction-west",
      status: "SUCCEEDED",
      issued_at: bag.updatedAt,
      deadline_at: bag.updatedAt,
      started_at: bag.updatedAt,
      completed_at: bag.updatedAt,
      failure_code: null,
      failure_reason: null,
    },
  ],
};
export const journey: Journey = {
  bag,
  route,
  movement,
  history: [
    {
      historyId: "history-1",
      recordType: "STATE_TRANSITION",
      previousState: "MOVEMENT_PENDING",
      currentState: "IN_TRANSIT",
      reasonCode: "MOVEMENT_STARTED",
      nodeId: "checkin-main",
      occurredAt: bag.updatedAt,
      recordedAt: bag.updatedAt,
      details: null,
      stateVersion: 5,
    },
  ],
};
export const page: BagPage = {
  items: [
    { bag, destinationNodeId: "makeup-lhr", confirmedSteps: 0, totalSteps: 2 },
  ],
  total: 1,
  page: 0,
  size: 25,
};
export function event(version = 6, id = "event-1", bagId = "bag-1") {
  return {
    eventId: id,
    eventVersion: 1,
    eventType: "BagStateChanged",
    aggregateId: bagId,
    payload: { bagId, stateVersion: version },
  };
}
