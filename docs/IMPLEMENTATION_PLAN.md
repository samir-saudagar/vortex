# VORTEX Implementation Plan

## Recommended versions for the first implementation

These exact versions are the recommended pins checked against upstream compatibility/release information on 2026-10-15. The user approved the major/minor baselines; these patch versions make that baseline concrete. Keep Spring Boot-managed libraries on its dependency BOM and change pins only with updated compatibility evidence.

| Component | Proposed version | Notes |
|---|---|---|
| JDK | Eclipse Temurin 21.0.12.1+1 | Java 21 is within Spring Boot 3.5.16's supported Java 17-25 range; Gradle 8.14.x supports running on Java 21 |
| Spring Boot | 3.5.16 | Spring Boot 3.5 dependency BOM manages the Java libraries below |
| Gradle wrapper | 8.14.6 | Current 8.14 patch; supports running on Java 21, and Spring Boot 3.5 supports Gradle 8.4+ |
| Apache Kafka broker | 4.3.1, KRaft mode | Official image `apache/kafka:4.3.1`; Apache lists 4.3.1 as a supported release. User-approved change from 4.0.2 on 2026-10-15 to use a currently supported broker. |
| Spring Kafka / Kafka Java client | 3.3.16 / 3.9.2 | Managed by Spring Boot 3.5.16; use the BOM without overrides. Kafka's 4.3 compatibility material gives client-major guidance for 3.x clients, not an exact 3.9.2 + 4.3.1 pairing assertion. |
| PostgreSQL | 17.11 | Current supported PostgreSQL 17 minor release |
| PostgreSQL JDBC driver | 42.7.11 | Managed by Spring Boot 3.5.16 |
| Flyway core and PostgreSQL module | 11.7.2 | Both managed by Spring Boot 3.5.16; PostgreSQL 17 is listed as supported by Flyway's support table |
| React and React DOM | 19.3.0 | Same version for both packages |
| React and React DOM TypeScript declarations | @types/react and @types/react-dom 19.3.0 | Match the React 19.3 package line; compile the exact declarations with TypeScript in Phase 0 |
| Node.js | 22.23.3 LTS | Meets Vite 8 Node 22.12+ requirement |
| TypeScript | 5.9.3 | Pin with package-lock; use strict mode |
| Vite | 8.3.4 | Pin in package-lock; Node 22.23.3 meets Vite 8's Node 22.12+ requirement |
| Python | 3.13.16 | Pin runtime; use a virtual environment |
| Python Kafka client | kafka-python 3.0.11 | Its versioned compatibility docs list broker 4.3 through 0.8 and Python 3.8-3.14, covering Kafka 4.3.1 and Python 3.13; verify the actual command/report path in Phase 3 |

Compatibility basis: [Temurin 21.0.12.1+1 release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1), [Spring Boot 3.5.16 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Spring Boot 3.5.16 managed dependency coordinates](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [Gradle 8.14.6 release notes](https://docs.gradle.org/8.14.6/release-notes.html), [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Kafka 4.3 client compatibility](https://kafka.apache.org/43/getting-started/compatibility/), [Kafka 4.3 Docker image instructions](https://kafka.apache.org/43/getting-started/docker/), [Apache Kafka supported releases](https://kafka.apache.org/community/downloads/), [PostgreSQL version policy](https://www.postgresql.org/support/versioning/), [Flyway 10.20 PostgreSQL 17 support and release notes](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine), [Flyway supported database versions](https://documentation.red-gate.com/fd/supported-database-versions-143754067.html), [React versions](https://react.dev/versions), [Node.js 22.23.3 release archive](https://nodejs.org/download/archive/v22.23.3), [Vite 8 Node requirements](https://vite.dev/blog/announcing-vite8.html), [Vite package versions](https://www.npmjs.com/package/vite?activeTab=versions), [TypeScript 5.9.3 release](https://github.com/microsoft/TypeScript/releases/tag/v5.9.3), [Python 3.13.16 release](https://www.python.org/downloads/release/python-31316/), and [kafka-python 3.0.11 compatibility](https://kafka-python.readthedocs.io/en/3.0.11/compatibility.html). Apache lists Kafka 4.3.1 and its official Docker image as supported. Spring Boot 3.5.16 manages Spring Kafka 3.3.16 and Kafka Java client 3.9.2. The published Kafka client matrix is major-line guidance, not an exact-version pairing claim. The exact client/broker smoke result is recorded below. Published Flyway documentation establishes PostgreSQL 17 support before the Boot-managed 11.7.2 release; Phase 1 still applies migrations to PostgreSQL 17.11. No dependency override is currently justified.

### Compatibility verification status

| Combination | Status | What is established |
|---|---|---|
| Temurin 21.0.12.1+1 + Spring Boot 3.5.16 + Gradle 8.14.6 | Verified by official support ranges | Boot 3.5.16 supports Java 17-25 and Gradle 8.4+; Gradle 8.14.x supports running on Java 21. |
| Kafka broker 4.3.1 + Spring Kafka 3.3.16 / Kafka Java client 3.9.2 | Tested operations verified; not a blanket protocol/workload guarantee | Spring Boot 3.5.16 resolves Spring Kafka 3.3.16 and Kafka Java client 3.9.2 without overrides. The Phase 0 host smoke test successfully created/described a topic, produced, and consumed a matching payload against `apache/kafka:4.3.1`. In Phase 1, the host-JVM topic-policy integration test also listed/described all four approved topics and verified partitions, replication, and retention using client 3.9.2. Apache's compatibility page gives 3.x client-major guidance rather than naming this exact patch pair. |
| Kafka broker 4.3.1 + kafka-python 3.0.11 + Python 3.13.16 | Command/report and restart contract verified at runtime | The Phase 3 contract test ran against the existing `apache/kafka:4.3.1` broker with kafka-python 3.0.11 under Python 3.13.16. Actual produce/consume, source offset commits, report correlation, completed-command replay after restart, uncertain in-progress recovery without an observation, and quarantine of a 1 MiB unsupported-version envelope passed. This establishes the tested operations, not every protocol feature or workload. |
| PostgreSQL 17.11 + Boot-managed pgjdbc 42.7.11 + Flyway 11.7.2 core/PostgreSQL module | Runtime migration compatibility verified | Gradle resolved pgjdbc 42.7.11 and both Flyway artifacts at 11.7.2 from the Spring Boot 3.5.16 BOM. On the running PostgreSQL 17.11 Compose service, Flyway applied all four versioned migrations to a fresh isolated database, the persistence integration checks passed, a repeated migrate call reported the schema up to date, and a second backend startup against the local `vortex` database also validated it without rerunning migrations. The isolated test database was removed afterward; the existing database volume was preserved. |
| React/React DOM 19.3.0 + `@types` 19.3.0 + TypeScript 5.9.3 + Node 22.23.3 + Vite 8.3.4 | Version availability and Node/Vite constraint verified; exact frontend type/build compatibility is an implementation check | Vite 8 requires Node 20.19+ or 22.12+; Node 22.23.3 satisfies the latter. The exact locked package set must pass the Phase 0 install, typecheck, and build. |

The broker change from Kafka 4.0.2 to 4.3.1 was approved by the user on 2026-10-15 because Apache lists 4.3.1 among supported releases and documents the matching official image. Spring Boot 3.5.16 and its managed client versions remain unchanged. The reported smoke and Phase 1 topic-policy checks establish the tested admin, producer, consumer, and topic-configuration operations for these exact versions; they do not establish every Kafka protocol feature or production workload combination.

## Dependency-ordered phases

No phase is complete until its acceptance criteria and tests pass. Keep each phase reviewable and do not generate the entire application in a single code-generation pass.

### Phase 0 — Architecture approval and project skeleton

Deliverables:
- Use the seven decisions recorded as approved in DECISIONS_AND_OPEN_QUESTIONS.md.
- Create the clean Gradle/React/Python/docs directory structure from PROJECT_STRUCTURE.md.
- Pin the approved runtime/build versions, document environment variables, and create local ignore rules and an example environment file.

Acceptance criteria:
- IntelliJ imports the Gradle project and recognizes the single backend application.
- React and Python environments install from their lock/dependency files.
- No domain implementation or schema exists yet; project boundaries match the architecture.

Tests/checks:
- Gradle wrapper launches and compiles a minimal application shell.
- Frontend typecheck/build and Python test discovery run.
- Review ignore rules to confirm local secrets and build artifacts are excluded.

### Phase 1 — Local infrastructure and persistence foundation

Deliverables:
- Docker Compose for PostgreSQL 17.11 and Kafka 4.3.1 in combined KRaft broker/controller mode with internal/host listeners, no ZooKeeper, and health checks.
- Spring Boot configuration, health endpoints, Flyway baseline schema, initial seed data, and documented start/stop/reset/topic/log commands.
- Kafka topic creation policy for the four selected topics; small local partition counts and explicit retention.

Acceptance criteria:
- Host processes can reach both Compose dependencies at documented ports.
- Fresh PostgreSQL 17.11 startup applies Spring Boot-managed Flyway 11.7.2 core/PostgreSQL module migrations and seed flights/network/equipment. Record migration compatibility evidence for this exact pair.
- Kafka starts without ZooKeeper and the host can list/create the selected topics.
- Named database volume persists across stop/start; documented reset clears only local demo data.

Tests:
- Migration test on an empty database and on an already migrated database.
- Seed consistency checks for unique makeup mapping, scanner-equipped route destinations, and equipment references.
- Dependency health/readiness check; no app assumes Compose startup alone means ready.

Phase 1 verification record (2026-10-15): Compose validation passed with the existing PostgreSQL 17.11 and Kafka 4.3.1 containers healthy. Flyway 11.7.2 applied V1-V4 to a fresh temporary database; five PostgreSQL integration checks passed, including a repeated migration call and catalog consistency. The backend applied the migrations to the local `vortex` database, restarted with no pending migration, and returned UP for health, readiness, and liveness. The four approved Kafka topics were initialized twice; host-JVM verification with Kafka client 3.9.2 passed for names, partition counts, replication factor, and explicit seven-day retention. Stopping and starting the same Compose containers preserved migration history, seed rows, and topics. `:backend:build` passed. The opt-in Kafka produce/consume smoke test is separate from the topic-policy integration test and was recorded as passed in the Phase 0 host verification.

### Phase 2 — Domain core and deterministic rules

Deliverables:
- Java module package skeleton and explicit internal interfaces.
- Flight eligibility service, graph validator and Dijkstra planner, Bag State transition policy, route/command domain records, JSON envelope validation.
- Python simulator movement core separated from Kafka I/O, with deterministic result/observation IDs and configurable failure modes.

Acceptance criteria:
- Each domain module owns only its defined data/API boundary.
- Planner produces deterministic route decisions for a fixed graph and equipment snapshot.
- No invalid transition or command result can set a confirmed node.
- Final arrival guard requires the verified destination's configured makeup scanner.

Tests:
- Unit tests for valid/ineligible/unknown flight.
- Route tests for shortest cost, deterministic tie-break, disabled edge/equipment, and no route.
- State transition table tests, including all failure/retry transitions and illegal transitions.
- Envelope/payload required field, version, enum, size, and ID validation tests.
- Simulator tests for success, rejection, blocked equipment, movement failure, missing observation, delay configuration, and stable IDs.

### Phase 3 — Outbox, topics, and simulator boundary

Deliverables:
- Outbox table/repository/relay with per-aggregate sequence, lease/retry status, broker acknowledgement, health/log visibility, and explicit failed-row recovery procedure.
- Kafka envelope serializers, four topics, dispatcher/report listeners, processed_events table and post-commit offset acknowledgement behavior.
- Python Kafka process consumes commands, uses an ignored local command-ID journal to prevent replaying movement, and publishes STARTED/SUCCEEDED-or-failure/observation records and equipment status reports.

Acceptance criteria:
- Java business records and outgoing messages are atomic in PostgreSQL.
- Relay restart, broker outage, and crash-after-publish produce durable retry or harmless duplicate, never silent event loss.
- Consumers commit PostgreSQL effects before offsets and suppress duplicates.
- Simulator has no PostgreSQL connection and can restart; completed redelivered command IDs only republish saved report IDs, while an in-progress command on restart reports uncertain failure without rerunning movement or emitting an observation.

Tests:
- PostgreSQL/Kafka integration test for outbox publication, retry, duplicate after publish-ack crash simulation, and aggregate ordering.
- Consumer dedup test for crash/replay before and after DB commit.
- Invalid version/poison messages in both Java and Python consumers reach quarantine after bounded attempts; semantic stale report is stored as rejected and does not DLT-loop.
- Simulator contract test against Kafka 4.3.1, including restart, duplicate command delivery, uncertain in-progress recovery, and report correlation.

Phase 3 verification record (2026-10-15): the isolated PostgreSQL/Kafka suite passed 20 checks with no failures or skips; this includes six persistence checks, eight Kafka checks, the five existing migration/seed checks, and the application topic-policy check. The backend build with the opt-in Java smoke test passed 43 checks; 19 gated integration entries were skipped in that build and were executed separately in the enabled suite (the disabled parameterized poison method represents two enabled cases). The final Python 3.13.16 suite passed all 46 checks, including the live kafka-python 3.0.11 contract test. Lease expiry, failure/retry, publish-ack crash duplication, aggregate ordering, database commit before offset commit, deduplication, bounded poison quarantine, and continued retry during an injected PostgreSQL outage were verified. Faults were injected without stopping the healthy Compose services; hard power-loss testing was not performed. The initial transport limits were 2 MiB; the defect-closure record below gives the corrected capacities. The approved JSON envelope limit remains 1,048,576 UTF-8 bytes. Test Kafka traffic uses dedicated `vortex.phase3-test.*` topics; database tests create and remove only their own temporary database. Java listeners remain disabled until Phase 4 handlers are present; enabling them without those handlers fails startup. Test-only handlers exercise the transactional processing/rejection boundary without implementing Phase 4 workflows. The built backend returned UP for health/readiness/liveness, and the Python CLI reached READY and shut down cleanly on SIGTERM. Existing database fixture counts and V1–V4 migration history were preserved.


Phase 3 defect-closure verification (2026-10-15): alternating Java message exception types now share one four-attempt retry budget, with 1/5/15-second delays. PostgreSQL outages use a separate non-exhausting budget and neither exhaust nor reset message retries. The three source topics remain at 2 MiB; quarantine and both producer request limits are 32 MiB to retain Base64 payloads and worst-case JSON escaping of malformed keys without truncation. Both publishers retain the exact original value in `originalRawBase64`; a parsed `originalEnvelope` is included when safe. This corrects transport capacity without changing the approved 1,048,576-byte event-envelope limit or business contracts. The final backend build passed 49 tests with 22 gated entries skipped; the separately enabled isolated PostgreSQL/Kafka suite passed all 24 checks, including actual broker quarantine and source offset commits for alternating exceptions, 1,600,000-byte and 2,096,128-byte malformed payloads, and a near-2-MiB malformed key. Python 3.13.16 passed all 50 tests, including its live Kafka contract and the same oversized payload/key cases. The standalone Java smoke test was not rerun. Source topic partitions/retention, dependency versions, existing application database fixture counts, and V1–V4 migrations were preserved. No Phase 4 workflow was added.

### Phase 4 — Check-in, verification, route planning, and movement workflow

Deliverables:
- Check-in REST request with Idempotency-Key; bag creation and initial history/outbox in a transaction.
- Event workflow dispatch from BagCheckedIn through Flight Validation and Route Planning to Movement.
- Bag, route, command, observation, history, equipment, and retry queries backed by PostgreSQL.
- Per-edge command flow and final observation acceptance logic.

Acceptance criteria:
- POST check-in returns 202 after durable initial acceptance and returns stable IDs on an idempotent retry.
- Flight success reaches route planning; flight/no-route failures become visible states.
- Commands and results do not advance confirmed location. Valid checkpoint observations do.
- Only a valid final makeup observation reaches AT_MAKEUP and completes the route.
- Duplicate/out-of-order/wrong-route observations cannot produce duplicate or illegal state changes.

Tests:
- REST/controller validation and status-code tests.
- Integration test through valid flight, route, command, intermediate scan, next command, and final scan.
- Failure tests for unknown/ineligible flight, no route, rejected/uncertain command, 30-second command and observation timeouts, and explicit retry/replan without movement replay.
- Replayed final observation test verifies one AT_MAKEUP transition/history row.

Phase 4 verification record (2026-10-29): implemented the transactional check-in REST API, persisted flight/routing/movement workflow, PostgreSQL queries, guarded scanner acceptance, configurable 30-second command/observation deadlines, and explicit idempotent retry/replanning. The packaged backend build with the opt-in Java broker smoke test passed 57 checks; 42 gated entries were skipped there and exercised separately in the enabled integration suite. The final isolated PostgreSQL/Kafka suite passed all 59 checks with no failures or skips, including 33 Phase 4 workflow cases, the live HTTP/outbox/Kafka/Python journey and final replay, all nine startup equipment reports, V4-to-V5 upgrade, and the existing Phase 1–3 regressions. Python 3.13.16 passed all 50 simulator checks with its Kafka contract enabled. The existing frontend typecheck/build passed. Fixed a cross-language timestamp precision defect and test-context isolation; the guarded cleanup of this run's synthetic application-topic traffic is documented in [PHASE4_RESULTS.md](PHASE4_RESULTS.md), together with acceptance evidence and remaining limitations. Existing application database rows and V1–V4 migrations were preserved. No Phase 5 UI/STOMP work was implemented; changes stop for review.

### Phase 5 — React operations UI and STOMP

Deliverables:
- Dashboard for check-in, flight selection, bag search, state/timeline/route, equipment status, failures, and retry action.
- Typed REST client and STOMP client subscriptions.
- Reconnect/resync path that refetches bag, route, and history before resuming live display.

Acceptance criteria:
- Browser only calls REST and STOMP, never PostgreSQL/Kafka.
- API errors render safe actionable messages; bag route/history are queryable independently of WebSocket.
- STOMP notifications are produced only from committed event records; reconnect restores latest state by REST.

Tests:
- Component tests for state/timeline, loading/error/retry rendering, and API mapping.
- WebSocket update mapping integration test where practical.
- Browser smoke test for check-in, live progress, disconnect/refetch, and final state display.

### Phase 6 — Deterministic end-to-end and recovery review

Deliverables:
- One-command local developer guide, failure scenario guide, topic/outbox/quarantine inspection steps, and known limitations.
- Deterministic end-to-end test harness with simulator delay at zero or controlled clock; no real-time sleep dependency.
- Architecture-to-code boundary checks and an initial tagged demo milestone.

Acceptance criteria:
- Required happy path and failure cases run locally repeatably.
- Final test asserts durable AT_MAKEUP, expected history, completed route, and duplicate no-op.
- App restart during each in-progress stage resumes from PostgreSQL and Kafka records without fabricating arrival.
- No open high-severity consistency issue remains; unresolved noncritical limitations are documented.

Tests:
- End-to-end: valid flight -> route -> commands -> intermediate observations -> final observation -> durable AT_MAKEUP/history -> duplicate replay no extra effect.
- Invalid flight, unavailable route, movement failure/retry, simulator offline/timeout, Kafka outage/restart, DB outage, outbox backlog, WebSocket disconnect, and app restart scenarios.
- Manual smoke check of Compose startup/shutdown, persistent volume, topic inspection, and safe API errors.

## Test pyramid and implementation sequence

1. Fast pure unit tests for flight rules, route algorithm, state transition policy, envelope validation, and simulator movement.
2. PostgreSQL tests for constraints, migrations, state/history ownership, idempotency, processed-event deduplication, and outbox atomicity.
3. Kafka integration tests for producer/consumer contracts, ordering by key, retry/quarantine, and simulator restart.
4. API/WebSocket tests for statuses, payload mapping, committed update fan-out, and resync.
5. Deterministic end-to-end tests for complete success and failure/recovery paths.

Use isolated test data and injected clock/delay controls. Automated tests must not depend on real baggage hardware, uncontrolled sleeps, or manually started external services without a documented test profile.

## Definition of done

A feature is done only when code, migrations if required, tests, API/event documentation, and local instructions agree; relevant tests pass; failure handling and idempotency are demonstrated; and the implementation has been reviewed against module ownership. Do not label planned or generated tasks complete before they are implemented and verified.

## Explicit code-generation checkpoint

Before large-scale code generation, stop after Phase 0/architecture approval and review the concrete skeleton, dependency pins, module ownership, event schemas, and first migration outline. Confirm that no approval item changed. Then generate and implement one phase at a time, reviewing and verifying each phase before moving on. This checkpoint is required; do not generate the complete backend, frontend, simulator, and migrations in one pass.
