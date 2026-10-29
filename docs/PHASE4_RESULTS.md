# Phase 4 review record

Phase 4 implements the backend journey on the approved Phase 0–3 foundation.
The Java deployment, module ownership, PostgreSQL authority, event catalog,
Dijkstra tie-break, dependency baseline, and simulator journal policy are preserved.
React operations views and STOMP are not implemented; work stops for Phase 4 review.

## Implementation

- Transactional check-in with required idempotency key, canonical request hash,
  concurrent key/tag protection, stable response replay, initial history, and outbox facts.
- Asynchronous flight verification, immutable route/step persistence and explainable
  blocked decisions, followed by per-edge movement commands.
- Bag State remains the sole state/location/history writer. Its existing transition
  policy, row lock, and conditional state-version update protect transitions.
- Movement validates correlation/causation, active route/command/step, equipment,
  scanner/node, command success, and source timestamps. Only accepted observations
  advance the confirmed node; the final scanner also checks the verified destination mapping.
- Command/result/observation/progress queries, bag/history/route queries, flight and
  equipment lookup, and stage-specific idempotent retry are backed by PostgreSQL.
- Separate configurable 30-second command and observation deadlines. ACCEPTED/STARTED
  never extend the command deadline. A persistent sweep and report-time checks use
  the same bag lock. Recovery supersedes old execution and creates a replacement route.
- REST validation and safe 400/404/409/503 responses, local CORS, and generated
  OpenAPI/Swagger UI. The additional springdoc pin is 2.10.0; its
  [official release](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.10.0)
  targets the existing Spring Boot 3.5.16 baseline. Packaged Kafka/JDBC/Flyway
  versions remain 3.3.16 / 3.9.2 / 42.7.11 / 11.7.2 as approved.

V5 adds a pending-workflow event fence, the verified flight snapshot and original
verification time, and an identity sequence for unambiguous history order within
one transaction. V1–V4 are unchanged. Delayed facts cannot start an obsolete retry;
routing retries retain the original verified destination/time rather than inventing
another eligibility decision. No event type or version was added.

Java consumers now default to enabled because the complete handler set is present.
Tests disable scheduled/live adapters by default and enable them explicitly with
separate diagnostic topics. The outbox honors the same topic configuration as
listeners, with unchanged application defaults.

REST usage and timeout settings are documented in [README.md](../README.md#phase-4-rest-journey).
`GET /api/bags/{bagId}/movement` is an additional diagnostic query for command,
observation, and progress records. Unknown-reference observations are rejected in
bag history with their validated payload instead of violating observation foreign keys.
Reports for unknown bags receive a durable REJECTED processed-event outcome.

## Acceptance criteria

| Criterion | Result and evidence |
|---|---|
| Durable check-in returns 202 with stable IDs on replay | Concurrent check-in integration test, independent-connection visibility check, rollback injection, REST replay/conflict tests |
| Eligible flights reach routing; flight/no-route failures are visible | Valid journey plus unknown/cancelled/departed/wrong-origin/not-open/closed flight cases; persisted BLOCKED routes and exclusions |
| Commands/results cannot advance confirmed location | Assertions after issuance, ACCEPTED, STARTED, and SUCCEEDED; locations change only with accepted checkpoint observations |
| Only a valid final makeup observation completes arrival | Real HTTP → outbox → Kafka → Python → report consumer journey; verified destination/scanner negative case; durable COMPLETE route and AT_MAKEUP |
| Duplicate/out-of-order/wrong-route observations cannot duplicate transitions | Final replay through Kafka with committed-offset verification; early/wrong scanner/node/step/equipment/correlation/causation/timestamp/unknown-command cases |
| Failures, deadlines, and explicit recovery work | Rejected/uncertain commands, both 30-second boundaries, late-result check, failed retry, replacement from last confirmed node, and no-path movement recovery |
| Existing foundation and ownership remain intact | Existing Phase 0–3 regressions, package boundary check, fresh/repeated migration checks, V4→V5 data-preserving upgrade |

## Verification

| Check | Result |
|---|---|
| Backend packaged build with opt-in Java broker smoke | 57 passed, zero failures; 42 gated entries skipped here and exercised separately below |
| Isolated PostgreSQL/Kafka suite | 59 passed, zero failures/skips, including 33 Phase 4 workflow cases, the live Java/Python journey, and V4→V5 upgrade |
| Python simulator suite with live Kafka contract enabled | 50 passed, zero skips |
| Existing frontend | Typecheck and production build passed; no frontend test script exists in this foundation |
| Script syntax and whitespace checks | Passed |

The Kafka run used PostgreSQL 17.11, Kafka 4.3.1, Java 21.0.12.1, and the existing
Python 3.13.16 / kafka-python 3.0.11 environment. The live journey also verifies all
nine startup equipment reports through Movement's typed call to Route Planning.

Commands:

```bash
VORTEX_KAFKA_SMOKE_TEST=true ./gradlew :backend:build
./scripts/test-database.sh --with-kafka
VORTEX_KAFKA_SMOKE_TEST=true ./gradlew :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest
VORTEX_SIMULATOR_KAFKA_TEST=true PYTHONPATH=simulator/src simulator/.venv/bin/python -m pytest -q simulator/tests
npm --prefix frontend run typecheck
npm --prefix frontend run build
bash -n scripts/test-database.sh
git diff --check
```

Integration prerequisites are the healthy existing Compose services and the locked
Python environment described in README. The script creates/removes its own database;
the migration upgrade test also creates/removes a separate temporary database.
The live Java/Python journey starts and stops its own Python process and journal.
No existing application database migration or fixture was changed during verification.

## Defects closed during implementation

- Initial history incorrectly reused the check-in fact's unique history reference
  when verification consumed it. Initial acceptance now leaves its optional source
  event reference empty; verification records the actual consumed event.
- PostgreSQL rounds nanosecond timestamps to microseconds while Python truncates
  them. A real zero-delay command issued at a sub-microsecond boundary produced
  `REPORT_BEFORE_ISSUE` for valid reports and then timed out. Command timestamps are
  normalized before both persistence and publication; movement timestamp writes
  use the same precision. A regression covers the 999-nanosecond boundary.
- A cached live test context could claim subsequent offline tests' outbox rows.
  Live contexts now close after their class, offline tests use diagnostic topics,
  and each test removes only its fixtures. Before correction, 166 synthetic records
  for 21 bags reached the two application topics. Inspection proved those topics
  contained only this run's records; guarded cleanup removed exactly that traffic,
  preserving topic configuration and offset numbering. No application bag was created.
- The IDE's Gradle test debugger could not accept isolated environment/database
  launch overrides. Runtime diagnosis used the single-test configuration assertions
  and durable bag/history/outbox diagnostics. Temporary agent logpoints were removed;
  existing user breakpoints were preserved.

## Remaining scope and limitations

No known Phase 4 acceptance gap remains after final verification. The initial
network has nonempty routes. A zero-edge route is blocked with
`CONFIRMED_DESTINATION_SCAN_REQUIRED`; it cannot fabricate a makeup arrival.

Phase 5 UI/STOMP work is intentionally untouched. Full backend restart at every
journey stage, browser reconnect/resync, and the broader Phase 6 recovery harness
are outside this change. Existing messaging fault-injection and simulator restart
regressions are retained; hard power-loss behavior was not tested. Kafka remains
at-least-once transport and this remains an unauthenticated local educational demo.

Changes remain uncommitted for review.
