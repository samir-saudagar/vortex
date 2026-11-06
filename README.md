# VORTEX

VORTEX is a local baggage-routing and tracking simulator. The approved design
uses one Spring Boot modular monolith, PostgreSQL as the durable source of truth,
Kafka for asynchronous workflow and simulator messages, a React/TypeScript UI,
and a separate Python equipment simulator. Phase 1 established local
infrastructure and persistence. Phase 2 provides the deterministic domain core.
Phase 3 adds the transactional outbox relay, Kafka consumer/deduplication boundary,
quarantine recovery procedures, and the Python Kafka process with its durable
command journal. Phase 4 adds check-in REST, durable flight/routing/movement
workflows, scanner validation, timeouts, PostgreSQL queries, and explicit retry.
Phase 5 adds the React operations dashboard and committed STOMP notifications.
See [the Phase 5 implementation report](docs/PHASE5_RESULTS.md).
See [the Phase 4 review record](docs/PHASE4_RESULTS.md).

For the Docker-only workflow on Linux, Windows or macOS, skip to
[Run VORTEX with Docker](#run-vortex-with-docker).

## Linux host-development prerequisites

- Eclipse Temurin JDK 21.0.12.1+1 (or another compatible Java 21 JDK)
- Gradle 8.14.6 through the checked-in wrapper
- Docker Engine and Docker Compose plugin
- Node.js 22.23.3 and npm for the frontend foundation
- Python 3.13.16 for the simulator foundation
- uv for the simulator's locked Python environment
- IntelliJ IDEA is optional

Compose runs PostgreSQL 17.11 and `apache/kafka:4.3.1`. Kafka uses combined
KRaft broker/controller mode with no ZooKeeper. PostgreSQL stores data in the
named `postgres-data` volume. Spring Boot 3.5.16 manages Spring Kafka 3.3.16,
Kafka client 3.9.2, and the PostgreSQL JDBC driver; Flyway core and its
PostgreSQL module are managed at 11.7.2. VORTEX does not override these managed
versions.

## Run VORTEX with Docker

The single `compose.yaml` builds and runs Java, React/Nginx and Python alongside
PostgreSQL and Kafka when the `full` profile is enabled. It requires Docker with
Compose and Linux containers; Java, Gradle, Node, npm, Python and uv are not
required on the host.

**Existing installations:** read the
[Kafka storage migration note](docs/DOCKER_DEPLOYMENT.md#existing-development-data-before-first-recreation)
before the first `up` that recreates the old broker. A new clone with empty
volumes needs no migration.

```bash
git clone https://github.com/samir-saudagar/vortex.git
cd vortex
docker compose --profile full up --build -d
```

Open **http://localhost:8088** when the services are healthy. Initial downloads,
image builds and topic initialization can take several minutes.

```bash
docker compose --profile full ps --all
docker compose --profile full logs -f --tail=100
docker compose --profile full stop
```

Without a profile, `docker compose up -d` starts **only PostgreSQL and Kafka**.
Their host-development ports remain `127.0.0.1:5432` and `127.0.0.1:9092`;
full mode also publishes the frontend on `127.0.0.1:8088`. Profiles share the
chosen project's data. Use another project name and unused ports for isolation.
The IntelliJ/Vite/host-simulator workflow below remains supported.

Do not run host and container simulators together or replace an existing command
journal with an empty one when switching modes. The migration guide covers
preserving existing state before adopting containerized storage.
See [Docker deployment](docs/DOCKER_DEPLOYMENT.md) for platform/architecture limits,
configuration, restart/rebuild, confirmed reset and automated acceptance evidence;
see [blocked-belt verification](docs/BLOCKED_BELT_TEST.md) for the real graph and
isolated failure/recovery scenarios. This remains an educational local demo.

## Local configuration

Copy `.env.example` to `.env` if you want to adjust the safe local defaults.
Compose reads `.env` automatically. To give those values to a host process,
export them in that terminal:

```bash
set -a
source .env
set +a
```

The example password is only a local development default. Do not use it outside
the local workstation or commit a real secret to `.env.example`.

## Start and inspect dependencies

Start PostgreSQL and Kafka, wait for their health checks, then initialize and
verify the four approved topics:

```bash
./scripts/dev-start.sh
docker compose ps
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

`scripts/dev-start.sh` is safe to rerun. Topic initialization creates missing
topics, reapplies the approved topic retention, and fails if an existing topic
has a conflicting partition count.

| Topic | Partitions | Replicas | Retention |
|---|---:|---:|---:|
| `vortex.workflow-events` | 3 | 1 | 7 days |
| `vortex.equipment-commands` | 3 | 1 | 7 days |
| `vortex.simulator-reports` | 3 | 1 | 7 days |
| `vortex.quarantine` | 1 | 1 | 7 days |

The three source topics retain `max.message.bytes=2097152` (2 MiB).
Quarantine uses `max.message.bytes=33554432` (32 MiB), with the Java and Python
producer request limits set to the same capacity. This conservative bound covers
Base64 preservation and worst-case JSON escaping of malformed keys as well as
payloads from a 2 MiB source record. No diagnostic bytes are truncated; every
wrapper retains `originalRawBase64`, with a parsed `originalEnvelope` when safe.
The Java and Python validators still limit each event envelope to
**1,048,576 UTF-8 bytes**, inclusive. The larger transport setting accepts no
larger application event envelopes.

View configuration and records from the Kafka container:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe
docker compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:29092 --entity-type topics --entity-name vortex.workflow-events --describe
docker compose logs -f postgres kafka
```

Host applications use PostgreSQL at `localhost:5432` and Kafka at
`localhost:9092`. Compose containers use `postgres:5432` and `kafka:29092`.

## Backend and persistence

The backend binds to `127.0.0.1:8080`. Database URL, username, and password
come from `DATABASE_URL`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`. Flyway
creates the `vortex` schema, applies the ordered SQL migrations, and loads the
fixed 2030 demo flight fixtures (with an open local check-in window) and JFK
routing/equipment catalog at startup.
Repeated application startup validates the applied migrations and does not
duplicate seed data.

Run the backend:

```bash
./gradlew :backend:bootRun
```

Health and database readiness endpoints are:

```bash
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Readiness includes PostgreSQL connectivity and outbox health. A FAILED outbox
row makes the outbox indicator DOWN; its details contain pending/in-flight/failed
counts. This does not prove Kafka connectivity. Health responses do not expose
credentials or internal component details.

Run the backend tests/build and the PostgreSQL integration test against a fresh
isolated database created in the running Compose PostgreSQL server:

```bash
./gradlew :backend:test
./scripts/test-database.sh
./gradlew :backend:build
```

The database test script creates a uniquely named temporary database, verifies
fresh Flyway initialization, seed consistency, repeated migrations, V4-to-V5 upgrade,
outbox atomicity, aggregate ordering/leases/requeue, consumer commit/replay behavior,
and Phase 4 REST/workflow/failure/recovery cases,
then removes only that temporary database. It does not reset `vortex` or remove
the named volume.

The Kafka broker smoke test is an opt-in diagnostic that requires the local
Kafka broker to be running. It uses the dedicated smoke-test topic to verify
the managed Java client's produce/consume path:

```bash
VORTEX_KAFKA_SMOKE_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew --no-daemon --rerun-tasks :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest
```

Run the complete Phase 3–4 PostgreSQL/Kafka suite with both Compose services
running, the documented Python environment installed, and frontend dependencies
installed with `npm --prefix frontend ci` (the live regression starts a temporary
Vite proxy against its isolated Spring server):

```bash
./scripts/test-database.sh --with-kafka
```

The script forces test execution and removes only its own temporary database.
Kafka tests create/reuse `vortex.phase3-test.workflow`, `.commands`, `.reports`,
and `.quarantine` as diagnostic topics (three partitions, except quarantine has
one; replication one; seven-day retention; 2 MiB source / 32 MiB quarantine
capacity). Phase 4's HTTP/outbox/Kafka/Python journey uses unique
`vortex.phase4-test.<uuid>.*` topics and a temporary command journal. Offline
workflow tests also assign diagnostic topics, and live test contexts close before
other test classes run. The suite never deletes topics or sends test workflows
to the application topics. Outages and the
acknowledgement/DB-update crash are injected; tests keep the running dependencies
available. Java poison tests exercise the actual 1/5/15-second backoff.

Java listener containers are enabled by default through
`VORTEX_KAFKA_CONSUMERS_ENABLED=true`; all required Phase 4 domain handlers are
installed. If reusing a Phase 3 `.env`, change its previous `false` value to `true`.
Set this flag to false only for offline inspection. Startup still
checks that the full handler set exists. RECORD acknowledgement and synchronous offset commits occur
after the PostgreSQL transaction returns. Semantic handler outcomes can commit
`REJECTED`; message failures get four attempts, then broker-acknowledged quarantine.
Changing exception types does not reset the four-attempt message retry budget.
PostgreSQL connection outages use a separate retry budget without quarantine,
with health checks and a delay capped at 30 seconds after the initial retries.

## Phase 4 REST journey

Run the backend and Python simulator in separate terminals using the commands
above/below. An accepted check-in starts asynchronous verification and routing:

```bash
curl --fail-with-body -X POST http://127.0.0.1:8080/api/bags/check-in \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-phase4-001' \
  -d '{"bagTag":"DEMO-PHASE4-001","flightId":"FL-204-2030-01-15"}'
```

Repeat the same key/body to replay the original 202 response, including its IDs.
Use a new bag tag and key for a separate journey. Unknown/ineligible flights are
accepted first, then become `FLIGHT_VERIFICATION_FAILED`. Identifiers contain
1–128 ASCII letters/digits/dots/underscores/hyphens, starting with a letter/digit;
idempotency keys contain 1–128 visible ASCII characters.

Replace `<bagId>` with the accepted UUID:

```bash
curl --fail 'http://127.0.0.1:8080/api/bags/<bagId>'
curl --fail 'http://127.0.0.1:8080/api/bags/<bagId>/history'
curl --fail 'http://127.0.0.1:8080/api/bags/<bagId>/route'
curl --fail 'http://127.0.0.1:8080/api/bags/<bagId>/movement'
curl --fail 'http://127.0.0.1:8080/api/flights?date=2030-01-15&origin=JFK'
curl --fail http://127.0.0.1:8080/api/equipment
curl --fail http://127.0.0.1:8080/api/equipment/belt-west
```

The movement endpoint exposes durable command, observation, and route-progress
records. Rejected observations with unknown references are retained in history,
where their diagnostic payload can be read without violating business foreign keys.
All queries read PostgreSQL. A route is 404 before planning; a failed route
decision is a queryable `BLOCKED` route with its exclusions and failure summary.
History uses a durable sequence to order records created in the same transaction.

After correcting a failed stage's cause in the local catalog/equipment configuration:

```bash
curl --fail-with-body -X POST 'http://127.0.0.1:8080/api/bags/<bagId>/retry' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-retry-001' -d '{}'
```

Retry is asynchronous and idempotent. Flight failure repeats verification; routing
failure repeats planning; movement failure supersedes the old execution and plans
from the last scanner-confirmed node. A second pending retry is 409. Completed or
healthy journeys are not retryable. No uncertain command is automatically replayed.

`VORTEX_COMMAND_TIMEOUT` and `VORTEX_OBSERVATION_TIMEOUT` each default to `30s`.
The first deadline starts at command issue and never resets on ACCEPTED/STARTED.
The second starts when SUCCEEDED is accepted. A persistent deadline sweep runs
every `VORTEX_TIMEOUT_SWEEP_INTERVAL_MS` (default 1000); handlers also check deadlines
under the same bag lock, so a late report cannot beat the next sweep. Restarting
the backend resumes deadline checks from PostgreSQL.

OpenAPI JSON is at `/v3/api-docs`, with Swagger UI at `/swagger-ui.html`.
The added `springdoc-openapi` pin is 2.10.0, whose
[official release](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.10.0)
targets Spring Boot 3.5.16. The approved Boot-managed Kafka/JDBC/Flyway versions
are unchanged. REST CORS and the native `/ws` STOMP endpoint both allow the exact
local origins `http://localhost:5173` and `http://127.0.0.1:5173`. Existing
`VORTEX_CORS_ORIGIN` and `VORTEX_STOMP_ORIGIN` settings can add comma-separated
exact origins; they do not remove the two required local origins. Wildcards are
rejected. Methods remain GET/POST, with Content-Type and Idempotency-Key headers.

## Operations dashboard and simulator

Install and run the frontend:

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run dev
```

Open **http://localhost:5173** or **http://127.0.0.1:5173** (the server binds
to `127.0.0.1:5173`). Vite proxies `/api` and `/ws` to the backend at
`127.0.0.1:8080`, rewriting Host while preserving the browser's Origin. Do not
strip Origin or enable WebSocket Origin rewriting to bypass Spring's allowlists.
If using another explicit local origin, add it to both `VORTEX_CORS_ORIGIN` and
`VORTEX_STOMP_ORIGIN` when starting the backend. Restart Spring Boot and Vite
after changing configuration; already-running processes retain the old settings. If changing its port, update
`frontend/vite.config.ts`'s proxy target too.

The dashboard reads real summary counts, paginated/searchable baggage, routes,
commands, scanner observations, history, flights and equipment. Select a bag tag
for details. Check-in uses the existing idempotent API; **Review recovery** opens
an explicit confirmation before calling the existing retry endpoint.

STOMP subscribes to `/topic/bags/{bagId}` for visible/selected bags and
`/topic/equipment`. Notifications invalidate REST projections; they never invent
location. Reconnect subscribes and refetches authoritative state before reporting
live status. REST also refreshes every 10 seconds to discover new/off-page bags
and diagnostics that do not produce a state event. All timestamps display UTC.

```bash
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
curl --fail 'http://127.0.0.1:8080/api/bags?page=0&size=25'
curl --fail http://127.0.0.1:8080/api/bags/summary
```

For manual checks using existing records and exact startup commands, see
[PHASE5_RESULTS.md](docs/PHASE5_RESULTS.md).

Set up the Python 3.13 project environment and run the Kafka simulator:

```bash
UV_CACHE_DIR=/tmp/vortex-uv-cache uv sync --project simulator --python 3.13 --locked --no-install-project
UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m vortex_simulator.main
```

Run the Python simulator tests in that isolated project environment:

```bash
UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m pytest -q simulator/tests
```

The movement core remains callable without Kafka. The simulator process reports
its configured equipment status at startup, consumes commands, and waits for
report acknowledgements before committing each command's offset. Its journal is
ignored under `simulator/.local/command-journal.jsonl` and allows one writer.
Completed duplicates replay the exact saved report bytes; interrupted commands
produce `SIMULATOR_EXECUTION_UNCERTAIN` with no observation. Preserve the journal
alongside the simulator consumer group's offsets. See [simulator/README.md](simulator/README.md)
for configuration and recovery details.

Run the opt-in Python contract/restart test against the same broker:

```bash
VORTEX_SIMULATOR_KAFKA_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m pytest -q -s simulator/tests/test_kafka_contract.py
```

It uses the dedicated diagnostic topics, a temporary journal, and a unique group.
It covers real produce/consume, report correlation, completed restart/replay,
uncertain in-progress recovery, and quarantine at the 1 MiB envelope boundary.

## Outbox and quarantine operations

Inspect durable outbox state without changing it:

```bash
docker compose exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT outbox_id, event_id, aggregate_id, aggregate_sequence, status, attempts,
       next_attempt_at, claimed_until, last_error
FROM vortex.outbox WHERE status <> 'PUBLISHED' ORDER BY outbox_id;
SQL
./scripts/inspect-quarantine.sh 20
```

The inspection consumer commits no offsets. Its five-second idle timeout exits
nonzero when there are fewer records than requested; this includes an empty topic.
Identify quarantine duplicates by source topic/partition/offset.

Outbox publication gets one initial attempt and up to 20 retries, using capped
1–60-second exponential backoff. A FAILED row blocks later sequences for its
aggregate. After repairing its cause, explicitly requeue its existing numeric ID:

```bash
./scripts/requeue-outbox.sh 42
```

Replace `42` with the inspected FAILED row ID. The script preserves its event ID,
payload, key, aggregate, and sequence; it resets attempts and retry time only for
FAILED rows. Other statuses produce `UPDATE 0`. It never deletes records.

Quarantine redrive is manual. Repair the cause first and save one selected wrapper
JSON value as `/tmp/vortex-quarantine-entry.json` (without console key/offset prefixes).
Review its source coordinates and original identifiers. Republish that original
envelope to its original topic with this command:

```bash
UV_CACHE_DIR=/tmp/vortex-uv-cache uv run --project simulator --python 3.13 --locked --no-sync -- python - /tmp/vortex-quarantine-entry.json <<'PY'
import base64, json, os, sys
from kafka import KafkaProducer
with open(sys.argv[1], encoding="utf-8") as source:
    entry = json.load(source)
topic = entry["sourceTopic"]
if topic not in {"vortex.workflow-events", "vortex.equipment-commands", "vortex.simulator-reports"}:
    raise ValueError("unexpected redrive source topic")
value = (base64.b64decode(entry["originalRawBase64"], validate=True) if "originalRawBase64" in entry
         else json.dumps(entry["originalEnvelope"], separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
key = (base64.b64decode(entry["originalKeyBase64"], validate=True) if "originalKeyBase64" in entry
       else entry.get("originalKey", "").encode("utf-8") if entry.get("originalKey") is not None else None)
producer = KafkaProducer(bootstrap_servers=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                         acks="all", enable_idempotence=True, max_request_size=2097152)
try:
    print(producer.send(topic, key=key, value=value).get(timeout=10))
finally:
    producer.close(timeout=10)
PY
```

This creates no replacement event, correlation, causation, command, or observation
identifiers. It sends through the normal consumer validation and deduplication path;
it does not mark a message processed or advance a bag. There is no automatic redrive loop.

## Stop and reset local data

Stop dependencies without removing containers or the PostgreSQL volume:

```bash
./scripts/dev-stop.sh
```

Start them again with `./scripts/dev-start.sh`; the database and Kafka topic
data remain in their existing storage.

To clear only the local `vortex` database schema while preserving the named
volume, run the guarded reset script and type its confirmation phrase when
prompted:

```bash
./scripts/reset-local-demo-data.sh
```

The script removes all schema objects/data in `vortex`; the next backend start
reapplies the Flyway schema and seed migrations. Do not run this command when
you need to preserve local demo records.

## Scope

The six approved architecture documents in `docs/` define module ownership,
event contracts, persistence, and the phased implementation sequence. Phases 4–6
are implemented; the completed Phase 6 milestone is tagged `v0.6.0-phase6-demo`
at `c08cbc2ddb549d94c75a9a956cd5c0173e33e832`. The separate full-stack Docker
deployment is a post-milestone enhancement. Future integrations remain deferred.
No authentication, catalog editor, topology editor or physical hardware integration
has been added.

## Phase 6 reliability verification

Run `./scripts/verify-phase6.sh` for serial frontend/backend/PostgreSQL/Kafka/Python
verification with application-data/journal fingerprints. It uses disposable test
databases, diagnostic topics and temporary journals. See [the failure/recovery
guide](docs/PHASE6_GUIDE.md) and [Phase 6 evidence](docs/PHASE6_RESULTS.md).
