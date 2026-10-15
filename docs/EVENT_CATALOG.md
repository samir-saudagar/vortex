# VORTEX Event Catalog

## Contract rules

Kafka carries asynchronous facts, simulator commands, and simulator reports. It does not replace PostgreSQL queries or direct typed Java calls between modules.

All records use a JSON envelope with these fields:

~~~json
{
  "eventId": "uuid",
  "eventType": "BagCheckedIn",
  "eventVersion": 1,
  "occurredAt": "2026-10-15T10:15:30Z",
  "producer": "bag-checkin",
  "aggregateType": "BAG",
  "aggregateId": "bag-uuid",
  "correlationId": "workflow-uuid",
  "causationId": "prior-event-or-command-uuid",
  "payload": {}
}
~~~

For a command, eventId is the stable commandId; for an observation, eventId is the stable observationId. Facts use a new UUID eventId. causationId may be null only for a root fact. occurredAt is UTC RFC 3339. Do not include passenger names or other personal information. IDs are UUIDs except configured business identifiers such as flightId, bagTag, nodeId, and equipmentId.

## Selected topics

| Topic | Message types | Producer | Consumer groups | Key and ordering |
|---|---|---|---|---|
| vortex.workflow-events | BagCheckedIn, FlightStatusVerified, FlightVerificationFailed, RoutePlanned, RoutingBlocked, BagStateChanged, EquipmentStatusChanged | Java outbox relay | vortex-workflow, vortex-ui | bagId for bag facts; equipmentId for equipment status. Outbox aggregate sequence preserves per-aggregate order. No cross-key ordering. |
| vortex.equipment-commands | EquipmentMovementCommand | Java Movement module through outbox | vortex-python-simulator | bagId. Commands for one bag are published in aggregate order. |
| vortex.simulator-reports | EquipmentCommandResult, ScannerObservation, EquipmentStatusReported | Python simulator | vortex-java-simulator-reports | bagId for command results and observations; equipmentId for status reports. Result/observation order for a bag follows producer send order in a partition. No ordering across keys/topics. |
| vortex.quarantine | Original failed envelope plus consumer, exception, source topic/partition/offset, attempts, failedAt | Java or Python Kafka consumer error handler | Operator inspection/redrive tooling; no automatic business consumer | Original key where available. Quarantine order is not a workflow guarantee. |

One workflow topic avoids a topic per domain method/table. Consumers filter by eventType. The workflow dispatcher group invokes the relevant typed Java module interface. The UI group independently receives the same committed facts and emits only BagStateChanged and EquipmentStatusChanged over STOMP. The Python process receives only command records. The Java report consumer receives simulator reports.

## Commands, events, and observations

- A command asks a receiver to act. EquipmentMovementCommand requests one edge movement attempt. It is not proof that a command was accepted or movement occurred.
- An event states that a backend workflow fact has been durably recorded. Examples: FlightStatusVerified, RoutePlanned, BagStateChanged.
- An observation reports a simulated external fact. ScannerObservation reports that a scanner saw a bag at a node; EquipmentStatusReported reports simulator equipment status. The backend validates and records observations before they can change authoritative state.
- EquipmentCommandResult reports simulator command acceptance/progress/completion/failure. SUCCEEDED does not itself confirm location.

## Payload contracts

Every payload below is inside the common envelope. Fields shown are required unless marked optional. Additional optional fields may be added compatibly.

### Workflow events

| eventType | Payload |
|---|---|
| BagCheckedIn | bagId, bagTag, flightId, checkInNodeId, checkedInAt |
| FlightStatusVerified | bagId, flightId, departureAirport, destinationAirport, flightStatus, checkedAt |
| FlightVerificationFailed | bagId, flightId, failureCode, safeReason, checkedAt |
| RoutePlanned | bagId, routeId, fromNodeId, destinationNodeId, networkVersion, algorithm, totalCost, stepCount |
| RoutingBlocked | bagId, fromNodeId, destinationNodeId, networkVersion, failureCode, safeReason |
| BagStateChanged | bagId, previousState (nullable for initial), currentState, confirmedNodeId (nullable), stateVersion, reasonCode, historyId |
| EquipmentStatusChanged | equipmentId, previousStatus, currentStatus, changedAt, sourceReportId |

Representative committed state fact:

~~~json
{
  "eventId": "61fce2ca-1d1c-4ce6-9d06-0aa49f84344d",
  "eventType": "BagStateChanged",
  "eventVersion": 1,
  "occurredAt": "2026-10-15T10:16:20Z",
  "producer": "bag-state",
  "aggregateType": "BAG",
  "aggregateId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
  "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
  "causationId": "f03d4d5e-47ee-4903-a1e1-e2c4a9278ff1",
  "payload": {
    "bagId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
    "previousState": "IN_TRANSIT",
    "currentState": "AT_INTERMEDIATE_NODE",
    "confirmedNodeId": "junction-west",
    "stateVersion": 5,
    "reasonCode": "SCANNER_OBSERVATION_ACCEPTED",
    "historyId": "183d8d77-73b6-4fe4-bf29-c42e12a88ba2"
  }
}
~~~

### Equipment command

EquipmentMovementCommand payload: commandId (same as envelope eventId), bagId, routeId, routeStepNo, fromNodeId, toNodeId, equipmentId, attempt, issuedAt, deadlineAt. Its key is bagId. One command represents one route edge whose destination is an expected scanner checkpoint. deadlineAt is 30 seconds after issue; ACCEPTED and STARTED do not reset the command-result deadline. After the matching SUCCEEDED result, the backend starts a separate 30-second deadline for the expected scanner observation.

~~~json
{
  "eventId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
  "eventType": "EquipmentMovementCommand",
  "eventVersion": 1,
  "occurredAt": "2026-10-15T10:16:25Z",
  "producer": "movement-orchestration",
  "aggregateType": "BAG",
  "aggregateId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
  "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
  "causationId": "61fce2ca-1d1c-4ce6-9d06-0aa49f84344d",
  "payload": {
    "commandId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
    "bagId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
    "routeId": "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c",
    "routeStepNo": 1,
    "fromNodeId": "checkin-main",
    "toNodeId": "junction-west",
    "equipmentId": "belt-01",
    "attempt": 1,
    "issuedAt": "2026-10-15T10:16:25Z",
    "deadlineAt": "2026-10-15T10:16:55Z"
  }
}
~~~

### Simulator reports

EquipmentCommandResult payload: commandId, bagId, routeId, routeStepNo, equipmentId, status (ACCEPTED, STARTED, SUCCEEDED, REJECTED, FAILED), reportedAt, optional failureCode and safeReason. ACCEPTED is an acknowledgement that the simulator accepted the requested command; it is not evidence of movement. STARTED records simulated movement start. SUCCEEDED records completion of the requested edge but is not evidence of bag location. The simulator uses a local durable command-ID journal: completed duplicate deliveries republish previously recorded reports with stable IDs without simulating movement again; a command still marked in progress after restart produces FAILED with failureCode `SIMULATOR_EXECUTION_UNCERTAIN` and no ScannerObservation. The Java backend retains the last scanner-confirmed node and requires explicit retry/replanning.

ScannerObservation payload: observationId (same as envelope eventId), bagId, routeId, commandId, routeStepNo, equipmentId, nodeId, scannerId, observationType (BAG_DETECTED), observedAt. The final makeup scanner is mandatory. Observation IDs are deterministic for a command/checkpoint in the simulator.

EquipmentStatusReported payload: reportId (same as envelope eventId), equipmentId, status (AVAILABLE, BLOCKED, OFFLINE, MAINTENANCE), reportedAt, optional reason. The Java adapter validates the equipment and persists canonical EquipmentStatusChanged through its outbox.

~~~json
{
  "eventId": "6df23c8b-1b41-51cc-9b92-8c82696eae8b",
  "eventType": "ScannerObservation",
  "eventVersion": 1,
  "occurredAt": "2026-10-15T10:16:29Z",
  "producer": "python-equipment-simulator",
  "aggregateType": "BAG",
  "aggregateId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
  "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
  "causationId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
  "payload": {
    "observationId": "6df23c8b-1b41-51cc-9b92-8c82696eae8b",
    "bagId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
    "routeId": "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c",
    "commandId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
    "routeStepNo": 1,
    "equipmentId": "belt-01",
    "nodeId": "junction-west",
    "scannerId": "scanner-west",
    "observationType": "BAG_DETECTED",
    "observedAt": "2026-10-15T10:16:29Z"
  }
}
~~~

## Producer and consumer behavior

| Message | Produced when | Consumer action |
|---|---|---|
| BagCheckedIn | Initial bag, history, and idempotency record commit | Workflow group calls Flight Validation; result is persisted with its follow-up fact/outbox |
| FlightStatusVerified / FlightVerificationFailed | Eligibility decision and bag transition commit | Verified invokes Route Planning; failed waits for explicit retry |
| RoutePlanned / RoutingBlocked | Route decision and bag transition commit | Planned invokes Movement; blocked waits for explicit retry |
| EquipmentMovementCommand | Command row, route progress, bag state/history commit | Simulator emits started/result/observation reports |
| EquipmentCommandResult | Simulator acknowledges, starts, completes, rejects, or fails command | Backend records ACCEPTED without changing bag location; STARTED may move bag state to IN_TRANSIT; failure/timeout changes bag state; SUCCEEDED waits up to 30 seconds for the matching scanner observation |
| ScannerObservation | Simulator's configured scanner sees the bag at an expected node | Backend validates and persists decision; only accepted observation updates confirmed node/state |
| EquipmentStatusReported | Simulator equipment state changes | Movement validates the report and calls Route Planning's EquipmentStatusUpdater; Route Planning persists status and emits EquipmentStatusChanged from outbox |
| BagStateChanged / EquipmentStatusChanged | State/status and outbox commit | UI consumer publishes STOMP message; workflow consumer ignores UI-only facts |

## Keys, ordering, and event versions

Bag workflow events, commands, command results, and scanner observations use bagId as Kafka key. Equipment status uses equipmentId. Per-key order is limited to a single topic partition. There is no ordering guarantee across topics or keys; handlers also validate state version, route, command, and step. Java outbox records include aggregateType, aggregateId, and a monotonically increasing aggregateSequence. The relay publishes the lowest unpublished sequence for an aggregate before later rows, even if it parallelizes different aggregates. A FAILED outbox row blocks later rows for that aggregate until repaired. The simulator flushes a command's in-progress marker before simulating movement and its terminal report set before publishing; it acknowledges the input command only after reports are broker-acknowledged. On restart, a completed command can only re-emit saved report IDs, while an in-progress command becomes an uncertain FAILED result without a scanner observation. The simulator sends STARTED, SUCCEEDED, and its observation in that order and waits for broker acknowledgement between records.

eventVersion starts at 1. JSON is UTF-8. Consumers reject missing required fields, invalid enum values, invalid identifiers, or unsupported versions into quarantine. Optional fields may be added without changing existing meaning. Breaking changes require a new version and a deployment sequence in which consumers support old and new versions before producers switch. Do not silently reinterpret a field. No Schema Registry is used initially.

## Idempotency and transaction boundaries

- Check-in uses a required HTTP Idempotency-Key plus request hash. Repeating the same key/body returns the same bag and correlation IDs; reusing it with a different body returns 409. bagTag is independently unique.
- The simulator's commandId is stable and deterministic report IDs prevent duplicate simulator work from creating duplicate backend effects.
- Each Java consumer writes (consumerName, eventId) to processed_events with a uniqueness constraint in the same database transaction as its business effects and outbox writes. A second delivery is a successful no-op.
- Observation ID and command ID have unique business constraints in their owning tables. A new envelope ID carrying an already-recorded observationId is recorded/rejected as duplicate without a bag transition.
- State-version compare/update and active route/command checks reject stale results. They are additional guards, not substitutes for event-ID deduplication.
- Commit PostgreSQL first; only then acknowledge the Kafka input offset. Redelivery after a commit is deduplicated.
- The UI broadcaster has no business database effect: it publishes after receiving the event and commits its group offset after the publish attempt. Duplicate STOMP updates carry the same eventId/stateVersion and are applied idempotently; REST resync remains the recovery path.
- The simulator disables auto-commit and commits a command offset only after its report records are acknowledged by Kafka. A crash may replay the command; deterministic report IDs and backend deduplication suppress repeated business effects.

## Retries, quarantine, and replay

Message-specific consumer failures in both Java and Python get one initial attempt plus three retries, with 1-, 5-, and 15-second backoffs before retries 1, 2, and 3 (four total processing attempts). After the fourth failed attempt, publish the original message plus source topic/partition/offset, consumer group, total attempt count, failure class, safe reason, and failedAt to vortex.quarantine. Acknowledge the source record only after quarantine publication succeeds. If publishing to quarantine fails, do not acknowledge the source record; the consumer framework resumes/retries it. Shared dependency outages such as PostgreSQL unavailability use the same bounded initial handling, then pause the affected partition and retry the unchanged offset with a capped 30-second health-aware delay; do not quarantine a valid event for a temporary database outage. The Python simulator applies the same policy to malformed/unsupported commands; expected simulated failures are terminal EquipmentCommandResult reports, not consumer exceptions. Monitor quarantine count and consumer lag locally.

Semantic failures (wrong route, stale attempt, wrong scanner, repeated observation, impossible step) are not transient: persist a REJECTED observation/command decision and reason, acknowledge it, and leave bag state unchanged. Invalid JSON/schema, unsupported event version, or unexpected repeated exception is quarantined. The implementation plan includes a small documented inspection/redrive command or operator procedure. Redrive is manual after correcting the cause, preserves the original event, correlation, causation, aggregate, command, and observation identifiers, and must not bypass validation. Do not create an infinite retry topic cycle.

Quarantine publication itself is at-least-once. A crash after quarantine broker acknowledgement but before source offset commit may create a duplicate quarantine record; identify quarantine entries by source topic, partition, and offset when inspecting or redriving.

Outbox failures are distinct from consumer quarantine. The relay records attempts, last error, next retry time, lease, and status. Retry up to 20 times with capped exponential backoff (1 second to 60 seconds); after the threshold mark FAILED and expose it in logs/health/admin inspection. Repair the cause and explicitly requeue the same row. It stays durable and blocks subsequent per-aggregate events until repaired.

Example quarantine record (the wrapper metadata surrounds the original unchanged envelope):

~~~json
{
  "sourceTopic": "vortex.simulator-reports",
  "sourcePartition": 1,
  "sourceOffset": 42,
  "consumerGroup": "vortex-java-simulator-reports",
  "attempts": 4,
  "failedAt": "2026-10-15T10:17:00Z",
  "failureCode": "UNSUPPORTED_EVENT_VERSION",
  "safeReason": "Consumer does not support event version 9",
  "originalKey": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
  "originalEnvelope": {
    "eventId": "6df23c8b-1b41-51cc-9b92-8c82696eae8b",
    "eventType": "ScannerObservation",
    "eventVersion": 9,
    "occurredAt": "2026-10-15T10:16:29Z",
    "producer": "python-equipment-simulator",
    "aggregateType": "BAG",
    "aggregateId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
    "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
    "causationId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
    "payload": {}
  }
}
~~~

## Transactional outbox

For every Java transaction that must publish a Kafka record, insert the business updates and one outbox row per message before commit. A scheduled relay claims due rows using PostgreSQL row locks/leases, publishes and waits for Kafka acknowledgement, then marks each row published. Multiple relay workers may publish different aggregates, but aggregate sequence prevents same-aggregate reordering. Crash after Kafka acknowledgement and before the published update may duplicate the record; consumer idempotency handles it. Do not use Debezium or a distributed transaction for the initial version.

For Kafka-to-PostgreSQL consumers, processed-event insertion, domain updates, history, and any new outbox rows share one PostgreSQL transaction. The Kafka offset is acknowledged after commit. This provides at-least-once transport with idempotent business effects; it is not an exactly-once transaction spanning Kafka and PostgreSQL.

## Example event trace

1. REST check-in creates bag B in CHECKED_IN; outbox sequence 1 BagCheckedIn, then BagStateChanged.
2. Workflow handler verifies flight F. Valid: state FLIGHT_VERIFIED, then FlightStatusVerified and BagStateChanged are stored in ordered outbox sequence.
3. Workflow handler calculates route R. It stores route and steps, state ROUTE_PLANNED, then RoutePlanned and BagStateChanged.
4. Movement creates command C1 for route step 1, state MOVEMENT_PENDING, and EquipmentMovementCommand.
5. Simulator emits ACCEPTED(C1), STARTED(C1), SUCCEEDED(C1), ScannerObservation(O1). The Java consumer deduplicates and validates each in report-topic order.
6. O1 matches route R, step 1, C1, equipment, expected node, scanner, and active bag. It is accepted; history records AT_INTERMEDIATE_NODE and Movement queues command C2 in the same transaction.
7. Repeat for each checkpoint. The final observation must match R's last step, destination-mapped makeup node, and configured final scanner. Its transaction changes state to AT_MAKEUP, marks route complete, writes history, and inserts BagStateChanged into the outbox.
8. The UI consumer publishes the committed state notification. A duplicate O1 or final observation is a no-op and cannot add a second transition.
