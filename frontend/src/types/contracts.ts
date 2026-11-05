export const bagStates = [
  "CHECKED_IN",
  "FLIGHT_VERIFIED",
  "FLIGHT_VERIFICATION_FAILED",
  "ROUTE_PLANNED",
  "ROUTING_BLOCKED",
  "MOVEMENT_PENDING",
  "IN_TRANSIT",
  "AT_INTERMEDIATE_NODE",
  "MOVEMENT_FAILED",
  "AT_MAKEUP",
] as const;
export type BagState = (typeof bagStates)[number];
export interface Bag {
  bagId: string;
  bagTag: string;
  flightId: string;
  state: BagState;
  confirmedNodeId: string;
  routeId: string | null;
  failureCode: string | null;
  failureReason: string | null;
  updatedAt: string;
  stateVersion: number;
}
export interface BagRow {
  bag: Bag;
  destinationNodeId: string | null;
  confirmedSteps: number;
  totalSteps: number;
}
export interface BagPage {
  items: BagRow[];
  total: number;
  page: number;
  size: number;
}
export interface Summary {
  total: number;
  active: number;
  completed: number;
  failed: number;
}
export interface RouteStep {
  stepNo: number;
  fromNodeId: string;
  toNodeId: string;
  equipmentId: string;
  expectedScannerId: string;
  cost: number;
}
export interface Route {
  routeId: string;
  status: string;
  networkVersion: string;
  sourceNodeId: string;
  destinationNodeId: string;
  algorithm: string;
  totalCost: number;
  steps: RouteStep[];
  decisionSummary: string;
}
export interface HistoryItem {
  historyId: string;
  recordType: string;
  previousState: BagState | null;
  currentState: BagState | null;
  reasonCode: string;
  nodeId: string | null;
  occurredAt: string;
  recordedAt: string;
  details: string | null;
  stateVersion: number | null;
}
// Movement diagnostics deliberately retain the Phase 4 API's snake_case keys.
export interface Command {
  command_id: string;
  route_id: string;
  route_step_no: number;
  attempt: number;
  equipment_id: string;
  from_node_id: string;
  to_node_id: string;
  status: string;
  issued_at: string;
  deadline_at: string;
  started_at: string | null;
  completed_at: string | null;
  failure_code: string | null;
  failure_reason: string | null;
}
export interface Observation {
  observation_id: string;
  route_id: string;
  command_id: string;
  route_step_no: number;
  node_id: string;
  scanner_id: string;
  decision: "ACCEPTED" | "REJECTED";
  rejection_code: string | null;
  observed_at: string;
  received_at: string;
}
export interface Progress {
  route_id: string;
  next_step_no: number;
  last_confirmed_node_id: string;
  active_command_id: string | null;
  status: string;
  observation_deadline_at: string | null;
  updated_at: string;
  row_version: number;
}
export interface Movement {
  bagId: string;
  commands: Command[];
  observations: Observation[];
  progress: Progress[];
}
export interface Journey {
  bag: Bag;
  route: Route | null;
  history: HistoryItem[];
  movement: Movement;
}
export interface Flight {
  flightId: string;
  carrierCode: string;
  flightNumber: string;
  serviceDate: string;
  departureAirport: string;
  destinationAirport: string;
  status: string;
  checkInClosesAt: string;
}
export interface Equipment {
  equipmentId: string;
  type: string;
  status: string;
  lastStatusAt: string;
  statusReason?: string | null;
}
export interface Accepted {
  bagId: string;
  correlationId: string;
  state: BagState;
}
export interface BagEvent {
  eventId: string;
  eventType: "BagStateChanged";
  eventVersion: 1;
  aggregateId: string;
  occurredAt: string;
  correlationId: string;
  payload: {
    bagId: string;
    stateVersion: number;
    currentState: BagState;
    confirmedNodeId: string;
    historyId: string;
  };
}
export interface EquipmentEvent {
  eventId: string;
  eventType: "EquipmentStatusChanged";
  eventVersion: 1;
  aggregateId: string;
  occurredAt: string;
  correlationId: string;
  payload: { equipmentId: string; currentStatus: string; changedAt: string };
}
export type LiveEvent = BagEvent | EquipmentEvent;
