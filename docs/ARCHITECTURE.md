# VORTEX Architecture

## Goals and non-goals

VORTEX is a local educational baggage-routing and tracking simulator. It demonstrates check-in, flight eligibility, deterministic route planning, simulated equipment movement, scanner observations, durable state/history, and live UI updates.

The first release is a single Java/Spring Boot deployment organized as a modular monolith. PostgreSQL is the durable source of truth. Kafka carries asynchronous workflow facts and the simulator boundary. The browser uses REST and WebSocket/STOMP. A separate Python process simulates equipment and scanners.

This system is not safety-certified, does not control real equipment, and does not reproduce proprietary VIBES/Vanderlande algorithms, protocols, source code, or internal designs. It does not promise high availability or exactly-once effects across PostgreSQL and Kafka.

## Architecture decisions

- Keep all Java business modules in one deployable Spring Boot application. Use packages and explicit internal interfaces to mark module boundaries; do not split Java modules into processes.
- Use direct typed Java calls for synchronous module collaboration. Use Kafka for the asynchronous workflow stages, simulator commands/reports, and durable UI notification fan-out.
- PostgreSQL owns all durable business state. Kafka is transport, not the authoritative bag history.
- Write each Java business change and the Kafka messages it requires in one PostgreSQL transaction using an outbox. A relay publishes outbox rows to Kafka.
- Persist each message consumer's deduplication record and business effects in one PostgreSQL transaction, then commit its Kafka offset.
- Route only through the configured directed graph, using deterministic Dijkstra shortest path over positive edge costs and currently available equipment.
- Treat a route, a command, command acceptance, movement result, and scanner observation as separate records and facts. Only an accepted scanner observation can change the bag's confirmed node. Only an accepted observation from the route's final makeup scanner can set AT_MAKEUP.
- In local development, run PostgreSQL and Kafka in Docker Compose; run the Java app, React UI, and Python simulator directly on the Linux host.

## Component architecture

~~~mermaid
flowchart LR
  UI[React + TypeScript operations UI]
  REST[Spring REST API and OpenAPI]
  WS[Spring WebSocket / STOMP]
  subgraph JAVA[One Spring Boot modular monolith]
    API[API and live updates]
    CHECK[Bag and check-in]
    FLIGHT[Flight validation]
    ROUTE[Route planning]
    MOVE[Movement orchestration]
    BAG[Bag state and history]
    MSG[Kafka adapters, outbox relay, deduplication]
  end
  PG[(PostgreSQL)]
  K[(Kafka in KRaft mode)]
  SIM[Python equipment simulator]
  UI -->|REST commands and queries| REST
  UI <-->|STOMP updates| WS
  REST --> API
  WS --> API
  API --> CHECK
  API --> BAG
  CHECK -->|typed Java interface| BAG
  MSG --> CHECK
  MSG --> FLIGHT
  MSG --> ROUTE
  MSG --> MOVE
  MSG --> BAG
  FLIGHT -->|typed Java interface| BAG
  ROUTE -->|typed Java interface| BAG
  MOVE -->|typed Java interfaces| ROUTE
  MOVE -->|typed Java interface| BAG
  CHECK --> PG
  FLIGHT --> PG
  ROUTE --> PG
  MOVE --> PG
  BAG --> PG
  MSG <--> PG
  MSG <--> K
  MSG -->|committed state/status events| WS
  SIM <-->|equipment commands / reports| K
  API --> WS
~~~

The arrows between Java modules represent in-process typed calls, not network requests. Kafka is used only at asynchronous workflow and simulator boundaries. The modules share one application lifecycle and PostgreSQL instance, but each module owns its tables and public internal interfaces.

## Module responsibilities, interfaces, dependencies, and ownership

| Module | Responsibilities | Main internal interfaces | Owns |
|---|---|---|---|
| API and Live Updates | REST controllers, request validation/mapping, OpenAPI, STOMP destinations, UI update fan-out, health endpoints | CheckInUseCase, BagQuery, FlightQuery, EquipmentQuery, RetryBagUseCase | No business tables; WebSocket session state is ephemeral |
| Bag and Check-in | Idempotent check-in request handling; create bag and initial state through Bag State | CheckInUseCase, BagLifecycle | check-in idempotency records |
| Flight Validation | Look up flight and check eligibility; emit verified/failed workflow result | FlightVerification, FlightQuery | flights and flight eligibility data |
| Route Planning | Own versioned graph, equipment catalog/status, deterministic path calculation, immutable route plans/steps, and no-route decisions | RoutePlanner, RouteQuery, RoutePlanLifecycle, EquipmentAvailability, EquipmentStatusUpdater | network versions, nodes, edges, equipment, route plans and route steps |
| Movement Orchestration | Create and track edge commands and route progress; validate simulator results and observations; handle command/observation deadlines and retry decisions | MovementStarter, MovementQuery; reads RouteQuery and EquipmentAvailability; calls BagLifecycle, RoutePlanLifecycle, and EquipmentStatusUpdater | route progress, movement commands, simulator observations |
| Bag State and History | Sole writer of bag state, current confirmed node, state version, and durable journey history; validate legal transitions | BagLifecycle, BagQuery | bags and bag history |
| Messaging and Workflow Dispatch | Kafka listeners/producers, event type dispatch, outbox relay, processed-event deduplication, retry/DLT adapter, idempotent retry action dispatch | Internal calls to module use cases; OutboxPublisher, RetryBagUseCase | outbox, processed-event, and workflow-retry idempotency records |

Allowed direction: API -> module interfaces; check-in -> bag lifecycle; workflow dispatch -> module use cases; flight/routing/movement -> bag lifecycle; movement -> route query, route lifecycle, and equipment status update; message adapters -> handlers. Route Planning owns equipment availability and does not depend on Movement, so dependencies remain acyclic. Domain modules do not call REST controllers or each other through Kafka. A Java module must not write another module's tables directly. A typed interface call can join the caller's PostgreSQL transaction.

Route Planning owns and reads the equipment catalog/status used for route eligibility. Movement obtains route steps and equipment availability through RouteQuery/EquipmentAvailability, and changes route lifecycle or equipment status only through RoutePlanLifecycle/EquipmentStatusUpdater. Bag State does not infer physical location from a command status.

## Technology choices

| Concern | Initial choice | Reason |
|---|---|---|
| Java backend | Java and Spring Boot, one executable | Familiar local development, clear modular boundaries, one deployment |
| Build | Gradle multi-project root with a single deployable backend project and separate frontend/simulator folders | Reproducible build while keeping the Java application as one runtime; do not turn each domain package into a Gradle project |
| Messaging | Apache Kafka in KRaft mode | Required asynchronous boundary and local event inspection without ZooKeeper |
| Database | PostgreSQL | Durable state, transactions, relational constraints, JSONB for limited event context |
| Migrations | Flyway | Small, ordered, versioned schema changes for new development |
| UI | React and TypeScript | Operations dashboard and typed API client |
| Live updates | Spring WebSocket with STOMP | Push after durable state changes; simple browser subscription model |
| Simulator | Separate Python process using a Kafka client | Isolated, restartable simulation with no direct database access |
| Local environment | Docker Compose for PostgreSQL and Kafka only | The application processes can run from IntelliJ or host terminals |
| API docs | OpenAPI generated from REST controllers | Inspectable local API contract |

Use the concrete baseline and compatibility notes in IMPLEMENTATION_PLAN.md: Eclipse Temurin JDK 21.0.12.1+1, Spring Boot 3.5.16, Gradle 8.14.6, Kafka 4.3.1, PostgreSQL 17.11, React/React DOM 19.3.0, Node.js 22.23.3, TypeScript 5.9.3, Vite 8.3.4, Python 3.13.16, and kafka-python 3.0.11. Spring Boot manages Spring Kafka 3.3.16, Kafka Java clients 3.9.2, PostgreSQL JDBC 42.7.11, and Flyway 11.7.2; no dependency override is planned. No Redis, orchestration platform, workflow engine, schema registry, cloud service, hardware adapter, or second database is needed for this scope.

## End-to-end workflow

~~~mermaid
sequenceDiagram
  actor Operator
  participant UI as React UI
  participant API as Spring API / Check-in
  participant DB as PostgreSQL
  participant Relay as Outbox relay
  participant K as Kafka
  participant WF as Workflow dispatcher
  participant FV as Flight validation
  participant RP as Route planning
  participant MO as Movement orchestration
  participant SIM as Python simulator
  participant BAG as Bag state/history
  participant WS as STOMP publisher

  Operator->>UI: Check in bag
  UI->>API: POST /api/bags/check-in + Idempotency-Key
  API->>DB: Persist bag CHECKED_IN + history + BagCheckedIn outbox
  API-->>UI: 202 bagId and correlationId
  Relay->>DB: Claim ordered outbox rows
  Relay->>K: Publish workflow event
  K->>WF: BagCheckedIn
  WF->>FV: Verify flight (typed call)
  FV->>DB: Persist result, state/history, outcome and BagStateChanged outbox
  Relay->>K: Publish FlightStatusVerified or FlightVerificationFailed
  K->>WF: FlightStatusVerified
  WF->>RP: Plan route (typed call)
  RP->>DB: Persist route and state/history/outbox
  Relay->>K: Publish RoutePlanned
  K->>WF: RoutePlanned
  WF->>MO: Start route (typed call)
  MO->>DB: Persist command, progress and MOVEMENT_PENDING + outbox
  Relay->>K: Publish EquipmentMovementCommand
  K->>SIM: Consume command
  SIM->>K: EquipmentCommandResult ACCEPTED
  SIM->>K: EquipmentCommandResult STARTED
  SIM->>K: EquipmentCommandResult SUCCEEDED
  SIM->>K: ScannerObservation at expected next node
  K->>MO: Consume reports, in bag-key order
  MO->>DB: Deduplicate, validate command/step/scanner, persist accepted observation
  MO->>BAG: Apply observation transition (typed call)
  BAG->>DB: Commit state/history and BagStateChanged outbox
  MO->>DB: Queue next command in the same transaction when a route step remains
  Relay->>K: Publish state update and next command
  K->>WS: UI notification consumer
  WS-->>UI: STOMP update after commit
  UI->>API: Refetch state/history after reconnect
~~~

Every destination node used as a route step in the initial demo has an associated scanner, including junction/diverter nodes. This keeps each movement command bounded by an observable checkpoint. A later topology can represent an unscanned conveyor segment as a single edge between scanner checkpoints. The final makeup node must always have its configured scanner.

### Journey stages

1. Check-in accepts a bag and flight identifier. It stores CHECKED_IN and a BagCheckedIn outbox event atomically. It does not wait for flight verification or movement.
2. The workflow dispatcher invokes Flight Validation when BagCheckedIn is consumed. A valid, eligible flight leads to FLIGHT_VERIFIED and FlightStatusVerified; an unknown, cancelled, departed, wrong-origin, or closed-check-in flight leads to FLIGHT_VERIFICATION_FAILED and FlightVerificationFailed.
3. On FlightStatusVerified, Route Planning calculates a path from the configured check-in node, or the last scanner-confirmed node during recovery, to the makeup node mapped to the flight destination. It stores the route and its decision inputs. No path leads to ROUTING_BLOCKED.
4. On RoutePlanned, Movement creates the first per-edge command and Bag State changes to MOVEMENT_PENDING. Command issuance is not evidence of motion.
5. The simulator emits ACCEPTED as a command acknowledgement, then STARTED, SUCCEEDED, and a scanner observation for that edge destination. The backend validates each report against the active route, route step, command, equipment, scanner, and correlation.
6. ACCEPTED updates command status only. STARTED may move MOVEMENT_PENDING to IN_TRANSIT. SUCCEEDED updates command/route progress only. None of these results establishes the bag's location.
7. A valid intermediate scanner observation changes the state to AT_INTERMEDIATE_NODE, sets the confirmed node, records history, and queues the next command. A final observation changes the state to AT_MAKEUP and completes the route.
8. The API reads the latest PostgreSQL state. WebSocket notifications are emitted from BagStateChanged/EquipmentStatusChanged events after those changes committed. On reconnect, the UI fetches REST state/history.

The simulator starts from environment/configuration values for Kafka address, consumer group, delay, deterministic mode, and named failure scenarios. It reports its configured equipment statuses on startup and holds no authoritative bag state. A local durable command journal fences duplicate command IDs: the simulator flushes an in-progress marker before simulating movement and flushes terminal report records before publishing them. Completed redeliveries republish the saved report IDs without simulating movement again; a command journaled as in-progress after a crash is reported as FAILED with `SIMULATOR_EXECUTION_UNCERTAIN` and no scanner observation. The backend leaves the bag at its last scanner-confirmed node and waits for the explicit retry endpoint to replan. The simulator commits a command offset only after its report records are acknowledged. Run its pure movement tests without Kafka; run its contract/restart tests against the local Compose broker.

## REST and WebSocket boundaries

The browser never connects to Kafka or PostgreSQL. Kafka contracts are in EVENT_CATALOG.md; persistence ownership is in DATA_MODEL.md.

| Endpoint | Purpose | Main result |
|---|---|---|
| POST /api/bags/check-in | Accept check-in; requires Idempotency-Key and JSON bagTag/flightId | 202 with bagId, correlationId, CHECKED_IN, status/history URLs; 400 malformed input; 409 duplicate bagTag or idempotency key reused with another body |
| GET /api/bags/{bagId} | Current authoritative bag projection | 200 current state and confirmed node; 404 unknown bag |
| GET /api/bags/{bagId}/history | Ordered state and observation decision timeline | 200 list; 404 unknown bag |
| GET /api/bags/{bagId}/route | Current/latest persisted route and steps | 200 route; 404 unknown bag or no route yet |
| GET /api/flights?date=...&origin=... | Local flight lookup for UI selection | 200 list; 400 invalid filters |
| GET /api/equipment | Current equipment status | 200 list |
| GET /api/equipment/{equipmentId} | One equipment status and last update time | 200; 404 unknown equipment |
| POST /api/bags/{bagId}/retry | Re-evaluate the failed workflow stage after its cause is corrected; requires Idempotency-Key | 202 retry accepted; 404 unknown bag; 409 state not retryable or retry key conflict |

Check-in uses a generated UUID bagId; bagTag is a separate unique human/scanner-facing identifier. Flight existence and eligibility are checked asynchronously, so a well-formed request with an unknown flight is accepted as CHECKED_IN and then transitions to FLIGHT_VERIFICATION_FAILED. Error responses use a small JSON body with stable code, safe message, and correlationId; do not expose stack traces or SQL/Kafka internals.

Request and response shapes:

- POST /api/bags/check-in request body: { "bagTag": "BAG-1001", "flightId": "FL-204-2026-10-16" }. Header Idempotency-Key is required. Response body: { "bagId": "uuid", "bagTag": "BAG-1001", "flightId": "FL-204-2026-10-16", "correlationId": "uuid", "state": "CHECKED_IN", "statusUrl": "/api/bags/uuid", "historyUrl": "/api/bags/uuid/history" }. Return 202 only after bag, initial history, idempotency row, and outbox records commit.
- GET /api/bags/{bagId} response: { "bagId": "uuid", "bagTag": "...", "flightId": "...", "state": "IN_TRANSIT", "confirmedNodeId": "junction-west", "routeId": "uuid-or-null", "failureCode": null, "failureReason": null, "updatedAt": "UTC timestamp", "stateVersion": 5 }.
- GET /api/bags/{bagId}/history response: { "bagId": "uuid", "items": [{ "historyId": "uuid", "recordType": "STATE_TRANSITION", "previousState": "IN_TRANSIT", "currentState": "AT_INTERMEDIATE_NODE", "reasonCode": "SCANNER_OBSERVATION_ACCEPTED", "nodeId": "junction-west", "occurredAt": "UTC timestamp", "recordedAt": "UTC timestamp" }] }. Return records oldest first with pagination after the initial demo grows.
- GET /api/bags/{bagId}/route response: { "routeId": "uuid", "status": "ACTIVE", "networkVersion": "demo-v1", "sourceNodeId": "checkin-main", "destinationNodeId": "makeup-east", "algorithm": "DIJKSTRA_COST_V1", "totalCost": 12, "steps": [{ "stepNo": 1, "fromNodeId": "checkin-main", "toNodeId": "junction-west", "equipmentId": "belt-01", "expectedScannerId": "scanner-west", "cost": 4 }] }.
- GET /api/flights accepts optional date and origin filters. Each item contains flightId, carrierCode, flightNumber, serviceDate, departureAirport, destinationAirport, status, and checkInClosesAt. It returns eligible and ineligible fixture flights so the UI can demonstrate both outcomes.
- GET /api/equipment returns { "items": [{ "equipmentId": "belt-01", "type": "CONVEYOR", "status": "AVAILABLE", "lastStatusAt": "UTC timestamp" }] }. GET /api/equipment/{equipmentId} returns one item with the same shape.
- POST /api/bags/{bagId}/retry accepts an empty JSON object and required Idempotency-Key. Response: 202 with bagId, correlationId, and state after retry acceptance. The retry is asynchronous.

Common errors use { "code": "STABLE_ERROR_CODE", "message": "Safe user-facing text", "correlationId": "uuid" }. Return 400 for malformed JSON, invalid fields, or invalid filters; 404 for unknown bag/equipment; 409 for duplicate bagTag, idempotency-key/body conflict, or an operation invalid for the current state; 503 when PostgreSQL is unavailable. Flight ineligibility is an asynchronous workflow result, not a synchronous HTTP validation error. A missing route resource returns 404 until planning has produced one.

Retry behavior is state-dependent: FLIGHT_VERIFICATION_FAILED repeats flight verification; ROUTING_BLOCKED retries planning from the last confirmed node; MOVEMENT_FAILED replans from the last confirmed scanner node using current equipment availability. The prior route/commands remain in history. Reject retries when the bag is not in a failure state. A retry is idempotent by header key. There is no general catalog-editing API in the first version; a developer/operator corrects seeded local data or equipment configuration before retry.

STOMP endpoint: /ws. UI subscribes to /topic/bags/{bagId} for bag state and journey updates and /topic/equipment for equipment status. Payloads include eventId, eventType, eventVersion, occurredAt, correlationId, bagId/equipmentId, current state/status, confirmed node when applicable, and a history/event reference. Notifications are best-effort views of committed events, not durable history. Reconnect logic must refetch the bag, route, and history through REST before trusting the live stream.

## Bag state model

Bag state is separate from route status, command status, equipment status, and observation decision. Bag State is the only module allowed to update the state column and current confirmed node. Legal transitions are checked in code and protected by a state-version/optimistic update. Illegal transitions are recorded as rejected workflow outcomes and do not alter the bag.

| From | Trigger and guard | To |
|---|---|---|
| (new) | Check-in accepted and idempotency stored | CHECKED_IN |
| CHECKED_IN | Flight exists and is eligible for check-in | FLIGHT_VERIFIED |
| CHECKED_IN | Flight missing or not eligible | FLIGHT_VERIFICATION_FAILED |
| FLIGHT_VERIFIED | Reachable valid makeup route persisted | ROUTE_PLANNED |
| FLIGHT_VERIFIED | No available route | ROUTING_BLOCKED |
| FLIGHT_VERIFICATION_FAILED | Explicit retry after catalog correction | CHECKED_IN |
| ROUTING_BLOCKED | Explicit retry, route now reachable | ROUTE_PLANNED |
| ROUTING_BLOCKED | Explicit retry, still unreachable | ROUTING_BLOCKED; record failed retry, no fake transition |
| ROUTE_PLANNED | First movement command and outbox committed | MOVEMENT_PENDING |
| MOVEMENT_PENDING | Matching ACCEPTED report | MOVEMENT_PENDING; update command acknowledgment only |
| MOVEMENT_PENDING | Matching simulator STARTED result | IN_TRANSIT |
| MOVEMENT_PENDING or IN_TRANSIT | Matching terminal rejection/failure or command timeout | MOVEMENT_FAILED |
| IN_TRANSIT | Accepted non-final scanner observation for active step | AT_INTERMEDIATE_NODE |
| AT_INTERMEDIATE_NODE | Next command and outbox committed | MOVEMENT_PENDING |
| MOVEMENT_FAILED | Explicit retry and replacement route persisted | ROUTE_PLANNED |
| MOVEMENT_FAILED | Explicit retry, no available path from last confirmed node | ROUTING_BLOCKED |
| IN_TRANSIT | Accepted final makeup scanner observation meeting all final guards, including a SUCCEEDED active command | AT_MAKEUP |
| Any non-terminal state | Optional explicit cancellation, if cancellation is implemented later | CANCELLED |

AT_MAKEUP, FLIGHT_VERIFICATION_FAILED, ROUTING_BLOCKED, and MOVEMENT_FAILED have no automatic exit. The retry transitions above are explicit operator actions. CANCELLED is deferred and is not implemented in the initial scope. A route can be PLANNED, ACTIVE, COMPLETE, BLOCKED, or SUPERSEDED. A command can be ISSUED, ACCEPTED, STARTED, SUCCEEDED, REJECTED, FAILED, TIMED_OUT, or SUPERSEDED. A command is SUPERSEDED when an explicit recovery activates a replacement route; later reports for it cannot affect the bag. Equipment can be AVAILABLE, BLOCKED, OFFLINE, or MAINTENANCE. An observation is ACCEPTED or REJECTED. These statuses do not substitute for bag state.

Final-arrival guard: the observation must be structurally valid and new; match the bag's active route and correlation; refer to its active command and expected route step; arrive after that command was issued; name the configured scanner on the route's final makeup node; match the makeup node mapped to the verified flight destination; and be accepted while that final step is active. A command result, route plan, simulator success result, or client request alone cannot set AT_MAKEUP.

## Routing model

The graph is a versioned set of directed nodes and edges in PostgreSQL. Nodes have a stable ID, type, airport/destination mapping where relevant, enabled flag, and optional scanner/equipment reference. Edges have source, target, positive cost/travel-time, equipment ID, and enabled flag. The route planner filters disabled edges and edges whose equipment is not AVAILABLE, then runs deterministic Dijkstra with stable ID tie-breaks. Cost is a configured positive integer. It stores algorithm/version, active network version, selected nodes/edges, total cost, and evaluated exclusions with the route plan so a decision is explainable.

Seed data is loaded by versioned migrations or seed SQL in the new project. Network edits are a later operator feature. Each route step snapshots from/to node, edge, equipment, expected scanner, cost, and step number so later topology changes do not rewrite history. A route is never proof that a bag moved.

## PostgreSQL/Kafka consistency, idempotency, and errors

Java-side state changes and required outgoing messages are committed together as business rows plus outbox rows. The in-process relay publishes to Kafka after commit, preserving per-aggregate outbox sequence and waiting for broker acknowledgement before marking a row published. A crash after broker acknowledgement but before marking the row causes a duplicate; all consumers deduplicate. Failed relay rows remain inspectable and block later messages for that aggregate until retried or explicitly repaired.

Each PostgreSQL-mutating Java consumer wraps processed-event insertion and business effects in one PostgreSQL transaction. It commits the Kafka offset only after PostgreSQL commits. A crash before database commit causes safe redelivery; a crash after database commit but before offset commit causes a duplicate that processed-event uniqueness suppresses. The UI broadcaster commits its Kafka offset only after attempting STOMP publication; a crash may repeat that notification, so the browser treats eventId/stateVersion idempotently and can always resync through REST. External simulator reports do not use the Java outbox because the Python simulator has no authoritative database. No cross-system exactly-once claim is made.

For message-specific processing failures, make one initial attempt plus three retries, waiting 1, 5, and 15 seconds before retries 1, 2, and 3. After the fourth failed attempt, publish the original envelope and failure metadata to vortex.quarantine and commit the source offset only after quarantine publish acknowledgement. Shared dependency outages such as PostgreSQL unavailability are classified separately: after the same bounded initial handling, pause the affected partition and retry the unchanged offset with a capped 30-second health-aware delay; do not quarantine valid messages merely because the database is down. A semantic rejection such as a validly formed but stale/wrong-step observation is persisted as REJECTED with a reason and consumed; it is not retried. Malformed payloads, incompatible versions, and repeated unexpected message-specific failures go to quarantine. Redrive is a deliberate operator/developer action after fixing the cause; preserve original event, correlation, causation, aggregate, command, and observation identifiers and rely on deduplication. Never silently discard quarantine records.

Failures are visible through bag state/history, command/observation records, equipment status, outbox/consumer health, and safe API responses.

| Failure | Detection and durable behavior | User-visible result and recovery |
|---|---|---|
| Unknown or ineligible flight | Flight lookup/eligibility result; transition CHECKED_IN to FLIGHT_VERIFICATION_FAILED and record reason | Bag page shows failed verification. Correct local catalog/fixture, then use retry endpoint to recheck. |
| No route available | Dijkstra returns no path using active graph/available equipment; transition to ROUTING_BLOCKED and persist decision | Bag page shows routing blocked and reason. Correct network/equipment configuration, then explicitly retry planning. |
| Command rejected | Matching REJECTED result for active command; update command, transition to MOVEMENT_FAILED | Show equipment/failure reason. Correct equipment or configuration, then retry; recovery plans from last confirmed scanner node. |
| Simulator unavailable or restarted during movement | No terminal command result before deadline, or command journal shows execution was in progress at restart; mark command TIMED_OUT/FAILED and bag MOVEMENT_FAILED without emitting a scanner observation for uncertain movement | Show movement failure while retaining the last confirmed node. Restore simulator health, then explicitly retry; recovery replans and never replays the uncertain command. |
| Missing scanner observation | Command reports SUCCEEDED but no matching observation arrives before the separate observation deadline; route progress fails and bag transitions to MOVEMENT_FAILED | Show observation timeout while retaining the last confirmed node. Restart/fix the simulator or scanner scenario, then explicitly replan from that node. |
| Movement failure | Matching FAILED result for active command; transition to MOVEMENT_FAILED; confirmed node remains the last accepted scanner node | Show failure and route/step. Explicit retry creates a replacement route from the last confirmed node. |
| Duplicate observation | processed_events/eventId or observationId uniqueness detects replay | No second transition or command. Record/measure duplicate and leave current view unchanged. |
| Out-of-order or wrong-step observation | Route, active command, step, scanner, state, and timestamp guards fail; persist REJECTED decision | Show rejected observation in history/diagnostics; bag state and confirmed node do not change. Correct simulator/contract before a deliberate replay. |
| Kafka consumer outage | Consumer lag/health check; Kafka retains records and PostgreSQL is unchanged for unprocessed records | Journey pauses at last committed state. Restore consumer; it resumes from committed offset. |
| Poison message or unsupported schema | One initial attempt plus three retries (1, 5, and 15 second backoffs), then publish to vortex.quarantine; acknowledge source only after quarantine acknowledgement | Business journey does not advance on that record. Inspect original payload/failure metadata, fix code/data, then manually redrive with original IDs. |
| PostgreSQL outage | Health check and transaction failure; REST returns 503; consumers pause partitions after bounded initial retries and leave offsets uncommitted; outbox relay pauses | New commands are rejected safely. Restore DB and allow requests/consumers/relay to retry the same durable work. |
| Outbox relay outage/backlog | Pending age/count and relay health; rows remain durable; failed row blocks later aggregate sequence | Workflow may pause while message is pending. Restore Kafka/relay; retry failed rows after fixing cause. |
| WebSocket disconnect | Client connection close/reconnect state | UI marks updates stale, reconnects, then refetches bag/route/history via REST before showing live updates. |
| Application restart mid-journey | Process health/startup logs; bag, route progress, commands, outbox, dedup state, and simulator command journal are durable | Spring resumes from PostgreSQL/Kafka offsets. Completed simulator commands can republish saved report IDs; an interrupted command is marked uncertain and fails without a location observation or automatic movement replay. |

Use a configurable 30-second deadline from command issue for its terminal result (ACCEPTED/STARTED do not reset it), and a separate configurable 30-second observation deadline starting when the matching SUCCEEDED result is accepted. These are local demonstration timeouts, not equipment SLAs. Message-specific consumer handling has four total attempts (initial plus three retries) with 1-, 5-, and 15-second backoffs before the retries; PostgreSQL outages pause and retry the unchanged offset after the bounded initial handling and are never dead-lettered solely for temporary database unavailability. Command movement is never automatically replayed after an uncertain outcome; recovery is the single idempotent retry operation.

## Local deployment

~~~mermaid
flowchart TB
  subgraph HOST[Linux workstation: IntelliJ and host processes]
    APP[Spring Boot backend :8080]
    WEB[React dev server :5173]
    PY[Python simulator]
  end
  subgraph COMPOSE[Docker Compose]
    PG[(PostgreSQL :5432, named volume)]
    K[(Kafka 4.3.1 KRaft :9092 host listener)]
  end
  WEB -->|HTTP and WebSocket| APP
  APP --> PG
  APP <-->|Kafka| K
  PY <-->|Kafka| K
~~~

Compose contains only PostgreSQL 17.11 and single-node Kafka 4.3.1 in combined broker/controller KRaft mode, with no ZooKeeper. Configure Kafka internal and host listeners so containers and host processes can connect correctly. Backend/frontend/simulator run directly on the Linux host during development. Use environment variables for ports, database URL/credentials, Kafka bootstrap address, simulator delay/failure mode, CORS origin, and STOMP origin. Supply local defaults via ignored .env and document required keys in .env.example; never commit secrets. PostgreSQL uses a named volume; a documented destructive reset command removes only local demo data. Use health checks and application retry/backoff for dependencies instead of assuming startup order means readiness. Seed flights, network nodes/edges, and equipment. OpenAPI is available locally from the backend. Kafka has three partitions and replication factor one for workflow, command, and simulator-report topics; quarantine has one partition. Use seven-day local retention and do not present these settings as production availability.

| Process/service | Host port | Container/internal address |
|---|---:|---|
| PostgreSQL | 5432 | postgres:5432 |
| Kafka | 9092 | Host: localhost:9092; Compose clients: kafka:29092 |
| Spring Boot REST, OpenAPI, STOMP | 8080 | Host process |
| React dev server | 5173 | Host process; CORS allowlist is http://localhost:5173 |
| Python simulator | none required | Host process |

Proposed developer commands after implementation: start dependencies with docker compose up -d postgres kafka; run Java with ./gradlew :backend:bootRun; run UI with npm --prefix frontend run dev; run simulator in its virtual environment with python -m vortex_simulator.main from simulator; run tests with ./gradlew :backend:test, npm --prefix frontend test, and python -m pytest simulator/tests. Inspect dependencies with docker compose ps and docker compose logs -f postgres kafka. Inspect topics with docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list and inspect records with the matching kafka-console-consumer.sh command. Stop dependencies with docker compose stop. A destructive docker compose down -v or reset script is explicitly for local demo data only.

No authentication is included in the local demo. Bind development ports to localhost where possible, validate request sizes and identifiers, use parameterized database access, restrict local CORS to the UI origin, and return safe errors. This is not an internet-facing deployment design.

## Trade-offs, assumptions, and future extraction

The single deployable is easier for one developer to debug and transact across module boundaries. Kafka still demonstrates asynchronous workflow and process integration, but increases local setup and event-contract work. The outbox and processed-event table add tables and relay behavior in exchange for avoiding a lost-event dual write. JSON is readable and low-friction, but consumers must enforce compatibility themselves. One simulator instance and a single local Kafka broker are sufficient for deterministic demos, not availability.

Assumptions: one simulated airport/network; generated UUID bag IDs plus unique bag tags; flights are loaded into a local catalog; flight eligibility means the flight exists, departs from the configured airport, is not cancelled/departed, and check-in is still open; each routed edge ends at a scanner checkpoint; a valid final scanner report is the only terminal arrival evidence; retry is explicit after correcting the cause; no authentication or catalog editing is needed for first demo.

Potential extraction candidates are Flight Validation, Route Planning, or Movement/Simulator Adapter. Consider extraction only when independent team ownership, deployment cadence, scaling/isolation needs, or operational failure boundaries justify network contracts and separate runtime costs. Before extraction, stabilize message schemas, module APIs, ownership, and transaction boundaries. Do not add service discovery or distributed tracing now. No Java service is independently deployed in the initial version.
