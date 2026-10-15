# VORTEX — Final Requirements for a New Architecture

**Project:** VORTEX — Vanderlande-Optimized Routing & Tracking Execution  
**Purpose of this document:** Define the requirements for designing VORTEX from a completely empty project folder. This document is the single source of truth for the initial architecture-design task.

**Project status:** Starting from zero in an empty IntelliJ project folder. Do not reuse previous project code, architecture, schemas, progress trackers, or implementation decisions.

**Product boundary:** VORTEX is an original educational/demo baggage-routing and tracking simulator inspired by the general baggage-handling problem domain. It must not claim to reproduce proprietary VIBES/Vanderlande internals, algorithms, protocols, or source code.

---

## 1. Instructions and scope

Use this document to design the architecture and plan implementation. The first task is architecture design only.

- Assume there is no existing implementation to preserve.
- Do not inspect, migrate, copy, or depend on previous architecture files or progress trackers.
- Do not generate application code during the architecture-design task.
- Produce the architecture documents listed in Section 16.
- Explain important decisions, trade-offs, assumptions, and open questions.
- Prefer a coherent, maintainable design that a solo developer can build incrementally.
- Do not add infrastructure or components without a concrete requirement.
- Do not claim a feature is implemented; this is a greenfield design.
- If a requirement is ambiguous, select a sensible simple default and document the assumption rather than blocking unnecessarily.
- Keep the initial design small but extensible.

---

## 2. Product goal

VORTEX is a local-development-friendly baggage routing and tracking simulator. It demonstrates an event-driven workflow in which a bag is registered, validated, routed through a simplified baggage network, moved by simulated equipment, observed by scanners, and tracked in a web UI.

The system should support:

1. Registering/checking in a bag and associating it with a flight.
2. Validating flight existence and eligibility.
3. Planning a route through a configurable, simplified baggage network.
4. Issuing movement commands to simulated equipment.
5. Simulating movement and generating scanner/equipment observations.
6. Updating authoritative bag state only from valid workflow results and observations.
7. Viewing the bag's current state, route, history, and relevant equipment status.
8. Showing live updates in the UI.
9. Recording failures and supporting safe retry/recovery.
10. Running the whole demo locally with documented setup instructions.

This is not a safety-certified industrial control system and does not connect to real PLCs or baggage hardware.

---

## 3. Core architecture decision: modular monolith plus Kafka

**The initial Java backend must be a modular monolith, not a collection of independently deployed Java microservices.**

The Java/Spring Boot backend is one deployable application with logically separated modules, clear interfaces, and explicit ownership. Do not split every domain area into its own process or container.

Recommended logical modules:

- **Bag & Check-in:** accepts check-in requests and owns initial bag registration.
- **Flight Validation:** checks flight validity and eligibility.
- **Route Planning:** calculates and records route decisions.
- **Movement Orchestration:** issues commands and tracks command outcomes.
- **Bag State & History:** owns authoritative bag-state transitions and history.
- **API & Live Updates:** exposes REST endpoints and WebSocket/STOMP updates.

These are logical modules, not separate Java microservices. The design may combine closely related modules where this reduces complexity, provided responsibilities and state ownership remain clear.

### Communication rules

- Use direct, typed internal Java interfaces/method calls for synchronous collaboration inside the modular monolith.
- Use Kafka for asynchronous workflows, business events, and integration with the separately running Python equipment simulator.
- Use REST for browser-to-backend commands and queries.
- Use WebSocket/STOMP for live UI updates.
- Do not send every internal method call through Kafka.
- Do not let the browser connect directly to Kafka or PostgreSQL.
- Do not use Kafka as a replacement for PostgreSQL or as the default mechanism for every query.

### Future microservice extraction

Design module interfaces and event contracts so a module can be extracted into a separately deployed service later if a concrete need arises. Do not implement microservice infrastructure now. Document possible future extraction candidates and the conditions that would justify extraction.

---

## 4. Preferred technology stack

Use the following stack for the initial design:

| Area | Technology | Requirements |
|---|---|---|
| Backend | Java + Spring Boot | One deployable modular monolith |
| Build | Gradle | Use a clear, reproducible build |
| Messaging | Apache Kafka in KRaft mode | No ZooKeeper |
| Database | PostgreSQL | Durable source of truth for business state |
| Frontend | React + TypeScript | Operations dashboard |
| Live updates | WebSocket + STOMP | Push state updates to connected UI clients |
| Simulator | Python | Separate process communicating asynchronously, preferably via Kafka |
| Local orchestration | Docker Compose | Reproducible local development |
| API documentation | OpenAPI/Swagger | Document public REST API |
| Database migrations | Flyway or an equivalent simple tool | Versioned, repeatable schema evolution |
| Testing | Appropriate Java, Python, and frontend test tools | Unit, integration, and end-to-end coverage |

Choose specific compatible versions during implementation planning and document them. Avoid unnecessary dependencies.

### Do not introduce by default

- Redis
- Kubernetes or cloud infrastructure
- Service discovery
- A separate workflow engine
- Schema Registry or Avro/Protobuf
- A data lake or analytics platform
- A large observability stack
- Multiple database technologies
- Complex authentication/authorization for the initial local demo
- Real PLC/hardware integrations
- Independently deployed Java microservices

If a future requirement would justify one of these, document it as a later option rather than including it in the initial architecture.

### Event serialization

Use JSON event payloads initially because they are easy to inspect. Define event versions and compatibility rules even with JSON.

---

## 5. Target architecture and component responsibilities

### 5.1 React operations UI

Responsibilities:
- Submit check-in requests using REST.
- Query bag state, routes, history, flights, and equipment status using REST.
- Subscribe to live updates using WebSocket/STOMP.
- Display a bag journey timeline, current state, planned route, equipment state, and failures.
- Recover from a WebSocket disconnect by refetching authoritative state from REST.

The UI is not a source of truth.

### 5.2 Java/Spring Boot modular monolith

Responsibilities:
- Expose REST APIs and WebSocket/STOMP endpoints.
- Coordinate the bag workflow through explicit modules.
- Persist and retrieve authoritative business state through PostgreSQL.
- Publish and consume Kafka messages where asynchronous boundaries are appropriate.
- Validate event-driven state transitions and record history.
- Deliver state updates to the UI after durable state changes.

Module boundaries must be documented. Avoid circular dependencies and direct cross-module database writes. Define which module owns each entity and state transition.

### 5.3 Python equipment simulator

Responsibilities:
- Run as a separate process/container.
- Consume movement commands through Kafka or a clearly documented asynchronous contract; Kafka is preferred.
- Simulate movement through the planned route and equipment nodes.
- Emit command results, scanner observations, and equipment status changes.
- Support configurable delays and deterministic success/failure scenarios.
- Include identifiers to correlate observations with the bag, route, command, and workflow.
- Never write directly to authoritative bag tables.
- Be restartable without becoming the source of truth for bag state.

### 5.4 Apache Kafka

Responsibilities:
- Carry asynchronous workflow events, simulator commands, simulator results, and observations.
- Decouple asynchronous workflow stages.
- Support consumer recovery and controlled retries.
- Preserve per-aggregate ordering where needed through partition keys.

Kafka is not the authoritative database and does not provide global ordering across topics/partitions.

### 5.5 PostgreSQL

Responsibilities:
- Persist bags, flights, network configuration, route plans, command records, state transitions, and event history as appropriate.
- Store the current authoritative bag state.
- Support REST queries and debugging.
- Persist outbox and processed-event records if the chosen consistency strategy requires them.

Use one PostgreSQL instance for the initial local environment. Define logical table/schema ownership even though the Java modules share one application and database.

---

## 6. End-to-end workflow

Design and document this workflow:

1. The UI submits a check-in request to a REST endpoint.
2. The Bag & Check-in module validates the request and persists the bag and initial state.
3. The backend reliably publishes a `BagCheckedIn` event.
4. Flight Validation evaluates whether the flight exists and is eligible.
5. The backend emits `FlightStatusVerified` or `FlightVerificationFailed`.
6. On success, Route Planning calculates a route and records the route decision.
7. The backend emits `RoutePlanned` or `RoutingBlocked`.
8. Movement Orchestration issues a movement command for the simulator.
9. The Python simulator simulates the requested movement and emits results/observations.
10. The backend validates the observations against the bag, route, command, and expected step.
11. Bag State & History applies permitted transitions and persists them.
12. Further commands and observations advance the bag through the route.
13. A valid final-destination scanner observation triggers the final arrival transition.
14. After the authoritative state change commits, the UI receives a WebSocket/STOMP update.
15. The UI can fetch the current state and complete history through REST.

### Critical semantic distinction

A route plan, a command being issued, a command being accepted, a simulator result, and a scanner observation are distinct concepts.

**A bag must not be marked as arrived at its final destination merely because a route was planned or a command was issued.** The final state `AT_MAKEUP` may be entered only after a valid final-destination observation is accepted by the backend's state-transition rules.

---

## 7. Bag state model

Use a finite state machine. Suggested states:

- `CHECKED_IN`
- `FLIGHT_VERIFIED`
- `FLIGHT_VERIFICATION_FAILED`
- `ROUTE_PLANNED`
- `ROUTING_BLOCKED`
- `MOVEMENT_PENDING`
- `IN_TRANSIT`
- `AT_INTERMEDIATE_NODE`
- `MOVEMENT_FAILED`
- `AT_MAKEUP`
- `CANCELLED` (only if cancellation is included in scope)

The design must:
- Provide a transition table or diagram.
- Name the event or command that triggers each transition.
- Define recovery from temporary failures.
- Reject or quarantine impossible transitions.
- Track timestamps and failure reasons.
- Distinguish bag state from route status, command status, and equipment status where appropriate.
- Prevent duplicate events from causing duplicate business effects.
- Validate duplicate or out-of-order observations against route, command, step, and current state as appropriate.
- Avoid meaningless states with no defined behavior.

---

## 8. Kafka topics and event contracts

Propose the smallest coherent set of topics. These names are examples and may be refined:

- `bag-checked-in`
- `flight-status-verified`
- `flight-verification-failed`
- `route-planned`
- `routing-blocked`
- `equipment-command-issued`
- `equipment-command-result`
- `scanner-observation`
- `equipment-status-changed`
- `bag-state-changed`

Do not create a topic for every internal method or database table. Clearly distinguish:
- **Commands:** requests for another component to do something.
- **Events:** facts about something that has happened.
- **Observations:** simulator/scanner reports about simulated equipment or bag location.

For each selected topic, document:
- Purpose and message type
- Producer and consumers
- Example JSON payload
- Kafka key/partition strategy
- Ordering assumptions
- Retry and dead-letter/quarantine handling
- Event version and compatibility approach
- Whether and how it appears in the business history

### Event envelope

Use a consistent envelope with fields equivalent to:

- `eventId`: unique identifier for deduplication
- `eventType`: stable message name
- `eventVersion`: schema version
- `occurredAt`: timestamp
- `producer`: source component
- `aggregateId`: bag ID, equipment ID, or relevant aggregate ID
- `correlationId`: end-to-end workflow ID
- `causationId`: preceding event/command ID where applicable
- `payload`: event-specific data

Use stable IDs and avoid unnecessary personal data.

### Ordering and reliability

- Use the bag ID as the key for bag workflow events where per-bag ordering is required.
- Use equipment ID for equipment-specific streams where appropriate.
- Do not promise ordering across different topics or partitions.
- Make consumers idempotent using event IDs and/or appropriate business keys.
- Define bounded retries and a dead-letter/quarantine approach for messages that repeatedly fail.
- Avoid infinite retry loops and poison-message blocking.
- Explain safe inspection and replay of failed messages.
- Use consumer groups so each independent logical consumer receives the events it needs.
- Keep local topic configuration simple.

---

## 9. PostgreSQL and Kafka consistency

The design must address the dual-write problem: writing business state to PostgreSQL and publishing an event to Kafka are not one automatic atomic transaction.

### Preferred approach: transactional outbox

For a state change that must publish an event:
1. Write the business state and an outbox record in the same PostgreSQL transaction.
2. A small relay publishes pending outbox records to Kafka.
3. The relay retries failures and safely handles restart/re-delivery.
4. Consumers are idempotent because duplicate publication may occur.
5. Document how pending/failed outbox records are inspected and retried.

The outbox relay can be a component within the Spring Boot application. Do not introduce Debezium unless there is a concrete reason; no previous infrastructure should be assumed.

For consumers updating PostgreSQL:
- Store the processed-event/deduplication record and apply the state change in one database transaction where practical.
- Explain offset handling and re-delivery behavior.
- Do not claim exactly-once business effects across Kafka and PostgreSQL.

Keep the implementation understandable for a solo developer.

---

## 10. REST and WebSocket requirements

Propose a small REST API, including operations equivalent to:

- `POST /api/bags/check-in`
- `GET /api/bags/{bagId}`
- `GET /api/bags/{bagId}/history`
- `GET /api/bags/{bagId}/route`
- `GET /api/flights` or a suitable flight lookup
- `GET /api/equipment` and/or `GET /api/equipment/{equipmentId}`

A reset/demo endpoint is optional and must be clearly scoped.

For each endpoint, document purpose, request/response shapes, validation errors, and HTTP status codes.

Check-in should return after the request and initial state have been durably accepted; it should not wait for the entire asynchronous journey. Return a bag ID and correlation ID where appropriate.

WebSocket/STOMP:
- Define the UI subscription destination(s).
- Define the update payload.
- Send updates after authoritative state changes commit.
- On reconnect, the UI should refetch current state through REST.
- WebSocket delivery is not the durable history.

---

## 11. Routing model

Implement the concept of a simplified configurable baggage network; do not imitate a proprietary algorithm.

Requirements:
- Represent the network as nodes and directed edges.
- Nodes can represent check-in points, conveyor junctions, scanners, diverters, and makeup destinations.
- Edges can include cost/travel time and availability.
- Define a simple deterministic pathfinding algorithm, such as shortest path by configured cost.
- Explain how the network configuration is stored and loaded.
- Define behavior when no valid route exists or a node/edge is unavailable.
- Persist the selected route and enough information to explain the decision.
- Keep the algorithm unit-testable.
- Do not add AI or advanced dynamic optimization without a specific need.
- A route decision must never be treated as proof of physical movement.

---

## 12. Python simulator requirements

The simulator must:
- Be a separate runnable Python process/container.
- Consume movement commands using Kafka where practical.
- Emit correlated command results, scanner observations, and equipment status changes.
- Simulate movement through route steps.
- Support configurable delays and deterministic automated-test mode.
- Support deliberate failure scenarios such as blocked equipment, rejected commands, missing observations, and movement failure.
- Include bag, route, command, and correlation identifiers where applicable.
- Never modify the authoritative bag database directly.
- Document configuration, startup, restart behavior, and local test usage.

No real hardware or PLC integration is required.

---

## 13. Failure handling

Document detection, state behavior, retry policy, user-visible result, and recovery for:
- Unknown or ineligible flight
- No route available
- Command rejected
- Simulator unavailable
- Movement failure
- Duplicate observation
- Out-of-order observation
- Kafka consumer outage
- Poison message / repeated processing failure
- PostgreSQL outage
- Outbox relay outage or backlog
- WebSocket disconnect
- Application restart during an in-progress journey

Do not promise high availability or production hardening beyond what the architecture actually provides.

---

## 14. Local development and security basics

Target a Linux workstation and IntelliJ-based development.

Requirements:
- Docker Compose for Kafka in KRaft mode and PostgreSQL.
- Document whether the frontend and application services run in containers or directly on the host; choose the simplest reliable developer workflow.
- Clear environment-variable and configuration conventions.
- Health checks and startup dependency handling.
- Persistent database volume and documented reset procedure.
- Versioned database migrations.
- Seed/demo flights and a small network topology.
- Commands to start, stop, test, inspect Kafka topics, and view logs.
- No committed secrets.
- Basic validation and safe error responses.
- Simple local CORS configuration.
- Document ports and service names.

Do not introduce Kubernetes or cloud deployment in the initial version.

---

## 15. Testing strategy

Specify a test pyramid and practical implementation order.

### Unit tests
- Flight validation
- Route calculation, including no-route cases
- State transition rules
- Event envelope and payload validation
- Idempotency/deduplication
- Simulator movement and failure scenarios

### Integration tests
- Kafka producer/consumer contracts
- PostgreSQL persistence and migrations
- Outbox publishing and retries
- Consumer deduplication
- WebSocket update mapping where practical

### End-to-end workflow
A deterministic test must:
1. Check in a bag with a valid flight.
2. Verify the flight.
3. Plan a route.
4. Issue simulated movement commands.
5. Process intermediate observations.
6. Process the final destination observation.
7. Verify the durable state is `AT_MAKEUP`.
8. Verify the history records the expected transitions.
9. Replay a duplicate event and confirm there is no duplicate business effect.

Also test invalid flight, unavailable route, and movement failure. Automated tests must not depend on real hardware or unpredictable sleeps.

---

## 16. Required architecture-design deliverables

Create these Markdown documents from scratch. If a filesystem/repository is available, create them in a clear `docs/` directory or the project root. Otherwise, provide their complete contents with these filenames.

### A. `ARCHITECTURE.md`
Include:
- Goals and non-goals
- Modular monolith decision and rationale
- System/component diagram (Mermaid)
- Module responsibilities, interfaces, dependencies, and state ownership
- Technology stack and rationale
- End-to-end sequence diagram (Mermaid)
- REST, Kafka, database, simulator, and WebSocket boundaries
- Bag state model and transition table/diagram
- Consistency, reliability, and failure handling
- Local deployment diagram
- Trade-offs, assumptions, and future microservice extraction criteria

### B. `EVENT_CATALOG.md`
Include:
- Chosen topics and their purpose
- Command/event/observation distinction
- Producers and consumers
- Envelope and payload examples
- Partition keys and ordering assumptions
- Versioning and compatibility
- Idempotency and deduplication
- Retry and dead-letter/quarantine policy
- Transactional outbox design
- Example end-to-end event trace

### C. `DATA_MODEL.md`
Include:
- Entities and relationships
- Key fields and state semantics
- Primary/foreign keys, uniqueness constraints, indexes
- Bag, route, command, observation/history, and equipment modeling
- Processed-event/deduplication data
- Outbox table and ownership
- Data ownership by module
- Migration strategy for the future implementation (not a migration from any old project)

### D. `IMPLEMENTATION_PLAN.md`
Include:
- Phases in dependency order
- Deliverables and acceptance criteria per phase
- Tests required per phase
- Clear definition of done
- Recommended implementation sequence for a solo developer
- Explicit checkpoint before large-scale code generation
- No task marked complete before it is implemented and verified

### E. `PROJECT_STRUCTURE.md`
Include:
- Proposed clean directory tree for the entire new project
- Gradle and Spring Boot structure
- Java module/package boundaries
- React/TypeScript frontend structure
- Python simulator structure
- Docker Compose and configuration files
- Database migrations, seed data, tests, and docs
- Brief explanation of important folders
- Avoid generating empty abstractions or excessive modules

### F. `DECISIONS_AND_OPEN_QUESTIONS.md`
Include:
- Architecture decisions already made in this brief
- Assumptions selected by Claude
- Meaningful trade-offs
- Only questions that truly require user approval
- Recommended default for each open question
- Future ideas explicitly deferred

Do not create a repository gap report or migration from previous code because this is a greenfield project.

---

## 17. Non-goals for the first version

Defer:
- Reproducing proprietary VIBES/Vanderlande internals
- Production-grade industrial controls
- Real PLC/hardware integration
- Kubernetes/cloud deployment
- Multi-region/high-availability infrastructure
- Redis
- Complex authentication/authorization
- Schema Registry and Avro/Protobuf
- Large observability platforms
- AI-based routing
- Advanced optimization
- Excessive microservice splitting
- Exactly-once claims across Kafka and PostgreSQL
- Full event sourcing where Kafka alone is the source of truth

The initial goal is a correct, observable, local demo with clear module boundaries and reliable event handling.

---

## 18. Architecture acceptance criteria

The architecture task is complete only when:
- The Java backend is one modular monolith with clear internal boundaries.
- Kafka is used for justified asynchronous communication and simulator integration, not every internal call.
- PostgreSQL is the durable source of truth.
- REST and WebSocket responsibilities are clear.
- The Python simulator is separate and does not write authoritative bag state.
- The database-to-Kafka dual-write problem has a concrete solution.
- Event contracts include IDs, correlation/causation, versioning, and payload definitions.
- Partitioning, per-bag ordering, idempotency, retry, and dead-letter behavior are defined.
- Route plans, commands, command results, and scanner observations are distinct.
- `AT_MAKEUP` requires an accepted final-destination observation.
- The state model rejects invalid transitions and defines recovery.
- The REST API, UI subscriptions, and reconnect behavior are specified.
- The local Linux/Docker Compose setup is documented.
- Tests cover the critical workflow and failure paths.
- The clean project directory structure is defined.
- Future microservice extraction is described as an option with explicit triggers, not part of the initial implementation.
- No application code is generated during the architecture-design phase.

---

## 19. Final instruction

Design VORTEX from a completely empty IntelliJ project folder using only this document as the requirements source. Produce all six deliverables in Section 16. Keep the design simple, internally consistent, and implementable.

Do not use, request, or rely on previous architecture files, progress trackers, code, schemas, or project history. Do not generate application code yet. Finish with a concise recommended architecture, the proposed implementation sequence, and any decisions requiring user approval before code generation.
