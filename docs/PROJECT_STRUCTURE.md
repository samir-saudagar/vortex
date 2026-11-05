# VORTEX Project Structure

This is the proposed clean layout for the new repository. There is one Gradle/Spring Boot deployment, one frontend application, and one separate Python simulator process. Domain areas are Java packages with typed interfaces, not separately deployed Java applications or Gradle projects.

~~~text
vortex/
├── README.md
├── req.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── .gitignore
├── .env.example
├── compose.yaml
├── backend/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/com/vortex/
│       │   │   ├── VortexApplication.java
│       │   │   ├── api/
│       │   │   │   ├── rest/
│       │   │   │   ├── websocket/
│       │   │   │   ├── openapi/
│       │   │   │   └── error/
│       │   │   ├── checkin/
│       │   │   │   ├── api/
│       │   │   │   ├── application/
│       │   │   │   ├── domain/
│       │   │   │   └── persistence/
│       │   │   ├── flight/
│       │   │   │   ├── api/
│       │   │   │   ├── application/
│       │   │   │   ├── domain/
│       │   │   │   └── persistence/
│       │   │   ├── routing/
│       │   │   │   ├── api/
│       │   │   │   ├── application/
│       │   │   │   ├── domain/
│       │   │   │   └── persistence/
│       │   │   ├── movement/
│       │   │   │   ├── api/
│       │   │   │   ├── application/
│       │   │   │   ├── domain/
│       │   │   │   └── persistence/
│       │   │   ├── bagstate/
│       │   │   │   ├── api/
│       │   │   │   ├── application/
│       │   │   │   ├── domain/
│       │   │   │   └── persistence/
│       │   │   ├── messaging/
│       │   │   │   ├── envelope/
│       │   │   │   ├── workflow/
│       │   │   │   ├── kafka/
│       │   │   │   ├── outbox/
│       │   │   │   └── deduplication/
│       │   │   └── config/
│       │   └── resources/
│       │       ├── application.yaml
│       │       └── db/migration/
│       └── test/
│           ├── java/com/vortex/
│           │   ├── checkin/
│           │   ├── flight/
│           │   ├── routing/
│           │   ├── movement/
│           │   ├── bagstate/
│           │   ├── messaging/
│           │   └── api/
│           └── resources/
├── frontend/
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   ├── src/
│   │   ├── app/
│   │   ├── api/
│   │   ├── features/
│   │   │   ├── checkin/
│   │   │   ├── bags/
│   │   │   ├── routes/
│   │   │   └── equipment/
│   │   ├── components/
│   │   ├── realtime/
│   │   ├── types/
│   │   └── styles/
│   └── tests/
├── simulator/
│   ├── pyproject.toml
│   ├── uv.lock
│   ├── README.md
│   ├── src/vortex_simulator/
│   │   ├── __init__.py
│   │   ├── config.py
│   │   ├── models.py
│   │   ├── movement.py
│   │   ├── command_journal.py
│   │   ├── scenarios.py
│   │   ├── kafka_adapter.py
│   │   └── main.py
│   └── tests/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── EVENT_CATALOG.md
│   ├── DATA_MODEL.md
│   ├── IMPLEMENTATION_PLAN.md
│   ├── PROJECT_STRUCTURE.md
│   └── DECISIONS_AND_OPEN_QUESTIONS.md
└── scripts/
    ├── dev-start.sh
    ├── dev-stop.sh
    ├── kafka-topics.sh
    ├── inspect-quarantine.sh
    └── reset-local-demo-data.sh
~~~

## Gradle and Spring Boot layout

The root Gradle build configures the backend project and common verification tasks. The backend project produces one executable Spring Boot application. Do not create separate Gradle projects for check-in, flights, routing, movement, or bag state. The application package is the composition root; module packages expose small public interfaces and keep implementation classes package-private where practical.

Use package groups by responsibility: api for transport, application for use cases, domain for rules/value objects, persistence for module-owned repository adapters. Messaging contains envelope types and Kafka/outbox adapters, not business domain rules. config wires interfaces and runtime settings. Avoid a generic shared/common package that becomes a dumping ground; place a type in the module that owns its meaning, and share only narrow transport primitives.

Repository-level boundaries should be checked with a lightweight architecture test or package visibility conventions. Do not add a custom module framework. Cross-module calls use typed APIs; no module imports another module's persistence repositories.

## Database migrations and seeds

Place versioned Flyway SQL files under backend/src/main/resources/db/migration, for example an initial schema migration and a separate deterministic demo seed migration. The first implementation starts from an empty database. Keep seed records small: a configured airport, one check-in node, a few scanner-equipped junction/diverter nodes, two makeup destinations, available/blocked equipment variants, and valid/ineligible flights. Do not put production credentials or user-specific data in migrations.

## Frontend structure

- app: router, application shell, state providers, error boundary.
- api: typed REST functions and response/error models.
- features: focused bag check-in, bag tracking/timeline, route display, and equipment views.
- realtime: STOMP connection, subscriptions, reconnect/resync behavior.
- types: shared frontend-only API/event types; generated OpenAPI client is optional, not a separate service.
- tests: component and browser-level tests.

The frontend owns presentation state only. It refetches REST after reconnect and never treats STOMP as durable history.

## Python simulator structure

Manage simulator dependencies with `simulator/pyproject.toml` and the checked-in
`simulator/uv.lock`. Create or synchronize the Python 3.13 environment with
`uv sync --project simulator --python 3.13 --locked --no-install-project`.

- config: Kafka addresses, consumer group, delay settings, deterministic mode, and failure scenario configuration.
- models: validated command and report envelope payloads.
- movement: pure simulation of one edge command, stable result/observation ID generation, and scanner behavior.
- command_journal: append-only local durable journal keyed by commandId. Flush intent before simulated movement and terminal report payloads before publication; replay of a completed command republishes its stored report IDs, while an interrupted command returns an uncertain failure without another simulated movement.
- scenarios: deterministic blocked, rejected, failed, missing-observation, and equipment-status scenarios.
- kafka_adapter: command consume and report publish with manual offset handling.
- main: process startup, logging, graceful shutdown, and health/readiness output.

Keep core simulation callable directly in unit tests. Simulator code has no PostgreSQL dependency and no database configuration. Store the command journal under ignored `simulator/.local/`; it is local simulator recovery state, not an authoritative bag store. Do not remove it while retaining the simulator's Kafka consumer offsets unless intentionally resetting the local demo.

## Docker Compose and configuration

compose.yaml contains only PostgreSQL and one Kafka KRaft broker/controller for local use. Include named PostgreSQL storage, health checks, and Kafka internal plus host listeners. Java, frontend, and simulator run from host terminals/IntelliJ. .env.example lists names and safe local defaults; .env is ignored. Keep secrets out of tracked files. The reset script should make its destructive scope explicit and operate only on the local Compose volume/database.

## Tests and docs

Backend tests mirror domain packages and include unit, persistence, Kafka contract/integration, and REST/WebSocket tests. Simulator tests exercise movement without Kafka and contract tests with Kafka. Frontend tests cover views, errors, connection loss, and resync. End-to-end tests use deterministic simulator settings and controlled time rather than real sleeps.

The docs folder contains the six architecture documents created before implementation. README.md later documents prerequisites, version pins, service ports, environment setup, IntelliJ run configuration, Compose commands, tests, topic inspection, log viewing, data reset, and known limitations.

## Keep the tree proportional

Do not create modules for every table or event. Do not add an admin frontend, generated clients, SDK, shared contracts repository, Kubernetes manifests, or production deployment folders until a concrete need exists. The simulator is a separate process because that separation is explicitly part of the product; the Java domains remain one deployable application.

## Phase 5 implementation locations

`frontend/src/api/client.ts` centralizes REST; `types/contracts.ts` mirrors the
existing contracts, including snake_case movement diagnostics. `app/useOperations.ts`
coordinates bounded REST reads and lifecycle cleanup. `realtime/feed.ts` owns STOMP,
deduplication and reconnect/resync; `features/bags` owns details and route progress;
`features/checkin` owns check-in; `components/Status.tsx` shares status/time/error
presentation. Vitest/Testing Library tests live in `frontend/tests`.
`backend/.../api/websocket` supplies the documented STOMP configuration and committed
Kafka-to-STOMP adapter. Existing API/application/repository classes add only read
projections. The existing Phase 4 Kafka integration journey now also tests a real
STOMP subscription against a committed final-state envelope.
