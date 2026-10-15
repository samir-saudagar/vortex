# VORTEX Decisions and Open Questions

## Decisions fixed by the requirements

- VORTEX starts from an empty project and uses no previous implementation, database, schema, architecture document, or progress history.
- The Java backend is one Spring Boot modular monolith with clear logical modules and one initial deployment.
- Internal synchronous Java collaboration uses typed interfaces/method calls; Kafka is for justified asynchronous workflow and simulator integration.
- PostgreSQL is the durable source of truth; Kafka is transport and never the browser query store.
- Java state changes and outgoing messages use a transactional outbox. Consumers persist deduplication and business effects in one PostgreSQL transaction, commit offsets afterward, and tolerate duplicate delivery.
- Kafka messages are JSON with explicit type/version and stable correlation/causation identifiers. Consumers have bounded retries and a quarantine topic.
- The UI is React/TypeScript, uses REST for commands/queries and WebSocket/STOMP for committed live updates, and refetches after reconnect.
- The separate Python simulator consumes Kafka commands and emits results/observations/status. It cannot connect to PostgreSQL or write authoritative bag state.
- Flight verification, route choice, command issuance, simulator result, scanner observation, and final arrival are distinct facts.
- AT_MAKEUP is only entered after the backend accepts an observation from the expected final-destination scanner for the active bag/route/step/command.
- The graph is a simple directed versioned topology; Dijkstra by configured positive cost is deterministic. No proprietary routing, AI, or optimization is included.
- Local dependencies are PostgreSQL and Kafka KRaft in Docker Compose. The backend/UI/simulator run on the Linux host. There are no initial Java microservices, Redis, Kubernetes, cloud services, schema registry, authentication system, or real hardware connector.

## Selected design assumptions

1. **One local airport and network.** Each flight departs from one configured airport. A valid flight maps its destination airport code to exactly one makeup node in the active network version.
2. **Generated bag identity.** The backend generates a UUID bagId; bagTag is a separate unique value that scanners and operators recognize. A duplicate bagTag is a conflict.
3. **Check-in input.** The initial request contains bagTag and flightId. The active network supplies the check-in node; clients do not choose an arbitrary graph source.
4. **Eligibility policy.** A flight is eligible when it exists, departs from the configured airport, has status SCHEDULED or BOARDING, and its check-in window is open. When `check_in_opens_at` is configured, current time must be at or after it and before `check_in_closes_at`. This policy is local demo behavior, not an airline standard.
5. **Scanner checkpoints.** Every routed edge ends at a scanner-equipped node. Junctions/diverters may have a co-located scanner. Unscanned conveyor spans are modeled as a single edge between scan checkpoints. This keeps confirmed progress and re-planning unambiguous.
6. **Explicit recovery.** Listener retries are automatic and bounded. A failed bag journey is retried only through an idempotent API action after its cause has been corrected. MOVEMENT_FAILED recovery plans a new route from the last scanner-confirmed node using current equipment availability; uncertain movement is never automatically replayed and requires hardware-specific reconciliation in any future real-equipment system.
7. **Single simulator instance.** Local demo consumes one command stream. Deterministic IDs and backend deduplication make re-delivery safe; simulation remains non-authoritative.
8. **No sign-in.** The demo is local and has no authentication/authorization. It must not be exposed as an internet-facing service.
9. **Local setup.** PostgreSQL/Kafka run in Compose; app processes run on the host. This keeps IntelliJ debugging direct and avoids image-build work during early phases.
10. **Time behavior.** All services use UTC. Flight seed dates and simulator times are controlled for repeatable tests. Command-result and scanner-observation timeouts are each configurable local defaults of 30 seconds, not real equipment SLAs.

## User-approved decisions (2026-10-15)

The user approved the seven initial architecture decisions and the two Phase 2 implementation decisions below. The initial decisions settle the architecture; the Phase 2 decisions make the equal-cost route selection and JSON size bound explicit.

| Decision | Approved choice | Consequence / implementation note |
|---|---|---|
| Check-in request and bag identity | Generate UUID `bagId`; enforce unique `bagTag`; accept `{bagTag, flightId}` with a required idempotency key; select the check-in node server-side; return HTTP 202 only after durable acceptance | Duplicate tag is HTTP 409. Bag, initial history, idempotency row, and outbox facts commit together before the response. |
| Flight eligibility | Accept check-in first, then asynchronously verify flight existence, configured departure airport, `SCHEDULED` or `BOARDING`, and an open check-in window | Unknown or ineligible flights remain durable journeys and transition to verification failure. If `check_in_opens_at` is configured, current time must have reached it; current time must still precede the close time. |
| Bag recovery | One idempotent retry endpoint with stage-specific recovery; movement failure replans from the last scanner-confirmed node | Never automatically replay uncertain movement. The Python simulator's local durable command journal re-emits saved reports for completed duplicates and reports an in-progress command as uncertain failure without a scanner observation. |
| Consumer retry, timeout, and quarantine | One initial attempt plus three retries, with 1-, 5-, and 15-second backoffs; quarantine poison messages after exhaustion; keep PostgreSQL outages separate and do not dead-letter solely for temporary DB unavailability; use 30-second command and observation timeouts; allow manual quarantine redrive preserving original identifiers | Message-specific processing gets four total attempts. Dependency outages pause/retry the unchanged offset after bounded initial handling. Quarantine redrive is explicit and never bypasses validation. |
| Scanner checkpoints and final confirmation | Every routed edge destination has a scanner; validate observations against active route, step, command, and scanner; only an accepted final makeup scanner observation transitions to `AT_MAKEUP` | Commands and success reports never prove location. A final observation must also match the verified destination's configured makeup node and active successful command. |
| Local development topology | Docker Compose runs PostgreSQL 17.11 and single-node Kafka in KRaft mode; Java, React, and Python run on the Linux host; local-only, without sign-in | Keeps IntelliJ debugging direct. Do not expose this unauthenticated setup outside the local workstation. |
| Version baseline | Java 21; Spring Boot 3.5.x; Gradle 8.14.x; PostgreSQL 17.11; React/TypeScript; Python 3.13; Kafka broker `apache/kafka:4.3.1` | Exact pins and compatibility status are recorded in IMPLEMENTATION_PLAN.md. The user approved Kafka 4.3.1 on 2026-10-15 because Apache lists it as a supported broker release. Keep Spring Boot 3.5.16 and its managed Spring Kafka 3.3.16 / Kafka Java client 3.9.2 unchanged; do not infer exact-version compatibility from the published client major-line matrix. Record the Java client smoke-test result there. |
| Equal-cost route tie-break | When route costs are equal, choose the lexicographically smallest sequence of edge IDs | Makes route selection deterministic for a fixed graph and equipment snapshot and gives the tie-break a stable, reviewable rule. |
| JSON envelope size limit | Limit each UTF-8 JSON event envelope to 1,048,576 encoded bytes, inclusive | Apply the byte limit consistently in Java and Python so non-ASCII payload size is measured by encoded bytes rather than character count. |

No architecture approval is pending. The exact version pins below are recommended implementation pins within the approved baseline. The only outstanding items are compatibility checks explicitly identified in IMPLEMENTATION_PLAN.md; those checks do not alter the approved decisions.

## Trade-offs

- The modular monolith keeps transactions and debugging simple but uses internal package boundaries rather than deployment isolation.
- Kafka adds local setup and asynchronous failure paths, but satisfies simulator integration and demonstrates controlled event processing.
- One workflow-events topic keeps the topic set small; eventType filtering and consumer groups provide distinct workflows and UI fan-out.
- JSON is easy to inspect but requires explicit payload validation and disciplined compatibility.
- The outbox avoids PostgreSQL/Kafka dual-write loss but allows duplicate publication; deduplication is mandatory.
- The last confirmed scanner node is the recovery origin. If movement outcome is uncertain, the simulator does not assert a new location or replay movement; an explicit retry creates a replacement plan. A real BHS would need hardware-specific reconciliation before recovery.
- No UI editor for topology/flights is included. Seed/config changes are developer operations in the first version.
- A single local broker/database and no auth are appropriate for a local demo, not production availability/security.

## Deferred ideas

- Real PLC/scanner/hardware integration, industrial safety, and production-grade control.
- Proprietary VIBES/Vanderlande behavior or algorithms.
- Independently deployed Java services, service discovery, distributed transactions, or workflow engine.
- Kubernetes, cloud/multi-region HA, Redis, schema registry, Avro/Protobuf, analytics/data lake, or large observability stack.
- AI routing and advanced dynamic optimization.
- User authentication, roles, multi-tenant baggage handling, and public deployment.
- Network/flight administration UI, topology hot reload, alternate retry strategies, and bag cancellation.
- Advanced event sourcing; PostgreSQL remains authoritative and the history is an append-only timeline, not a replay-only system.
