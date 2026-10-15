# VORTEX

VORTEX is a local baggage-routing and tracking simulator. The approved design
uses one Spring Boot modular monolith, PostgreSQL as the durable source of truth,
Kafka for asynchronous workflow and simulator messages, a React/TypeScript UI,
and a separate Python equipment simulator. Phase 1 established local
infrastructure and persistence. Phase 2 provides the deterministic domain core.
Phase 3 adds the transactional outbox relay, Kafka consumer/deduplication boundary,
quarantine recovery procedures, and the Python Kafka process with its durable
command journal. Check-in and movement business workflows belong to Phase 4;
the operations dashboard and STOMP integration belong to Phase 5.

## Linux prerequisites

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
fresh Flyway initialization, seed consistency, repeated migrations, outbox atomicity,
aggregate ordering/leases/requeue, and consumer commit/replay behavior,
then removes only that temporary database. It does not reset `vortex` or remove
the named volume.

The Kafka broker smoke test is an opt-in diagnostic that requires the local
Kafka broker to be running. It uses the dedicated smoke-test topic to verify
the managed Java client's produce/consume path:

```bash
VORTEX_KAFKA_SMOKE_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew --no-daemon --rerun-tasks :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest
```

Run the Phase 3 PostgreSQL/Kafka suite with both Compose services running:

```bash
./scripts/test-database.sh --with-kafka
```

The script forces test execution and removes only its own temporary database.
Kafka tests create/reuse `vortex.phase3-test.workflow`, `.commands`, `.reports`,
and `.quarantine` as diagnostic topics (three partitions, except quarantine has
one; replication one; seven-day retention; 2 MiB source / 32 MiB quarantine
capacity). They never delete topics or send test workflows to the application topics. Outages and the
acknowledgement/DB-update crash are injected; tests keep the running dependencies
available. Java poison tests exercise the actual 1/5/15-second backoff.

Java listener containers remain disabled by default through
`VORTEX_KAFKA_CONSUMERS_ENABLED=false`. Phase 3 supplies dispatch interfaces and
transactional adapters; Phase 4 supplies the domain handlers. Enabling listeners
without all required handlers fails startup so valid workflow messages cannot be
consumed prematurely. RECORD acknowledgement and synchronous offset commits occur
after the PostgreSQL transaction returns. Semantic handler outcomes can commit
`REJECTED`; message failures get four attempts, then broker-acknowledged quarantine.
Changing exception types does not reset the four-attempt message retry budget.
PostgreSQL connection outages use a separate retry budget without quarantine,
with health checks and a delay capped at 30 seconds after the initial retries.

## Frontend foundation and simulator core

Install and run the frontend:

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run dev
```

The frontend development server binds to `127.0.0.1:5173`.

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
event contracts, persistence, and the phased implementation sequence. Phase 3 is
messaging and simulator infrastructure. Check-in REST, persisted flight/routing/
movement workflows, scanner acceptance, timeouts, and retry use cases remain Phase 4;
frontend and STOMP integration remain Phase 5. No Phase 4 business handler is installed.
