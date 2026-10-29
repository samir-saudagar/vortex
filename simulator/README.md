# VORTEX Python simulator

Phase 3 adds Kafka command consumption, report publication, and a durable local
command journal around the Phase 2 pure movement core. The process uses
`kafka-python==3.0.11`, has no database dependency, and holds no authoritative bag state.

From the repository root, create the Python 3.13 environment and run the CLI:

```bash
UV_CACHE_DIR=/tmp/vortex-uv-cache uv sync --project simulator --python 3.13 --locked --no-install-project
UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m vortex_simulator.main
```

Run the simulator tests in the same isolated environment:

```bash
UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m pytest -q simulator/tests
```

With Kafka running, run the opt-in contract/restart test:

```bash
VORTEX_SIMULATOR_KAFKA_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 UV_CACHE_DIR=/tmp/vortex-uv-cache PYTHONPATH=simulator/src uv run --project simulator --python 3.13 --locked --no-sync -- python -m pytest -q -s simulator/tests/test_kafka_contract.py
```

It uses `vortex.phase3-test.*` diagnostic topics, a unique group, and a temporary
journal, and never deletes topics. Unit tests inject waits; the production poison
policy waits 1, 5, and 15 seconds before its three retries.

| Variable | Default / purpose |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `VORTEX_SIMULATOR_GROUP` | `vortex-python-simulator` |
| `VORTEX_COMMAND_JOURNAL` | `simulator/.local/command-journal.jsonl`, resolved from the project |
| `VORTEX_SIMULATOR_DELAY_SECONDS` | `0`; delay before STARTED and before the terminal result |
| `VORTEX_FAILURE_MODE` | `SUCCESS`, `REJECT`, `BLOCKED_EQUIPMENT`, `MOVEMENT_FAILURE`, or `MISSING_OBSERVATION` |
| `VORTEX_EQUIPMENT_STATUS_JSON` | Optional JSON object overriding equipment ID/status pairs from the seeded demo catalog |
| `VORTEX_SCANNERS_JSON` | Optional JSON object overriding destination node/scanner ID pairs from the demo topology |

The defaults use the nine seeded equipment IDs and the four scanner-equipped
destinations in `demo-v1`. Topics default to the approved equipment-commands,
simulator-reports, and quarantine names. Test configuration uses dedicated topic
overrides; normal development uses the defaults.

The process logs READY after startup status and interrupted-command reports have
been acknowledged. SIGINT/SIGTERM close the consumer without auto-committing and
close the producer and journal. Run one simulator instance; an exclusive journal
lock rejects a second writer.

The journal fsyncs intent before movement, keeps commands IN_PROGRESS during
movement delays, and fsyncs the complete report set before terminal publication.
Completed duplicates replay saved bytes with the same report IDs. A restart with
an IN_PROGRESS command saves/publishes FAILED `SIMULATOR_EXECUTION_UNCERTAIN` and
never reruns movement or publishes an observation. A torn final journal append
is removed while retaining the preceding durable intent. Preserve this ignored
journal along with the consumer group's offsets.

A journal write/fsync failure closes the journal and prevents further execution.
Repair the storage problem and restart the process so journal recovery can inspect
the last durable intent safely; do not remove the journal to recover from an error.

Kafka send/offset failures retain the same pending record and commit no later
position. Dependency recovery polls while paused to maintain group membership,
with delay capped at 30 seconds. Malformed/unsupported commands get four attempts
before quarantine; its publication must be acknowledged before the source commit.
Expected movement failures are command results. See the root README for outbox
and manual quarantine inspection/redrive procedures. Java domain report handling
and bag/route/scanner workflows are implemented by the Phase 4 backend. Start the
backend and simulator together to run the complete REST-to-final-scanner journey;
the simulator still has no PostgreSQL access.
