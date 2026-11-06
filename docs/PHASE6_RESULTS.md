# Phase 6 — Reliability and recovery evidence

> **Latest assessment — 2026-11-06 final audit: CONDITIONAL PASS.** Functional and reliability verification passes; the tagged demo milestone is **PENDING USER RELEASE ACTION**. The dated final audit at the end records the current 11-row matrix, fresh verification, fixture-safety correction and release instructions. Earlier runs and discrepancies below remain historical evidence.

Date: 2026-11-06. Baseline `cb9608d0744661e99ba2840f5b7a0490d672599e`, branch main, clean working tree at inspection. Remote publication is accepted from the user's verified baseline statement; no fetch, commit, push or tag is performed.

## Requirements audit and initial implementation inventory

The six authoritative documents and Phase 5 reports define the scope; the original Phase 5 audit/remediation history is preserved. Exact Phase 6 requirements are from IMPLEMENTATION_PLAN.md:184–202. Supporting safety/retry contracts are ARCHITECTURE.md:19,203,234–249,260–291; EVENT_CATALOG.md:168–222; DATA_MODEL.md:current-node, retry, progress, observations and outbox sections; DECISIONS_AND_OPEN_QUESTIONS.md:25,39–42,56; PROJECT_STRUCTURE.md:ownership/test/journal sections.

| ID | Exact documented Phase 6 requirement | Initial classification and implementation inventory | Planned/existing verification |
|---|---|---|---|
| P6-D01 | “One-command local developer guide, failure scenario guide, topic/outbox/quarantine inspection steps, and known limitations.” | Missing combined entry point; existing README/dev-start/inspection/requeue/redrive functionality already implemented | New verify-phase6.sh and PHASE6_GUIDE.md; existing procedures reused |
| P6-D02 | “Deterministic end-to-end test harness with simulator delay at zero or controlled clock; no real-time sleep dependency.” | Implemented but insufficiently tested: Phase4KafkaIntegrationTest gives real success/replay, workflow tests use controlled clock; stage-by-stage crash harness missing | New separate-JVM fixed-clock/zero-delay restart harness |
| P6-D03 | “Architecture-to-code boundary checks” | Already implemented and previously verified: ModuleBoundaryTest | Rerun existing boundary regression |
| P6-D04 | “an initial tagged demo milestone.” | Ambiguous/blocked by release workflow: reviewed Phase 6 commit/tag does not exist; user prohibits commit/push | Defer tag to approved post-review commit; do not tag Phase 5 as Phase 6 |
| P6-A01 | “Required happy path and failure cases run locally repeatably.” | Implemented but insufficiently tested: existing Java/Python fault, retry, timeout and Kafka tests; missing full restart/redrive combinations | Existing suites plus new edge/final-scan/redrive/crash tests |
| P6-A02 | “Final test asserts durable AT_MAKEUP, expected history, completed route, and duplicate no-op.” | Already implemented and previously verified in Phase 4 real Kafka/Python and offline workflow tests | Repeat after each new restart and final Kafka scan replay |
| P6-A03 | “App restart during each in-progress stage resumes from PostgreSQL and Kafka records without fabricating arrival.” | Missing executed per-stage backend process crash evidence | Eight separate-JVM checkpoints with durable PostgreSQL/outbox, fresh asynchronous Kafka/Python resume |
| P6-A04 | “No open high-severity consistency issue remains; unresolved noncritical limitations are documented.” | Ambiguous or blocked pending execution at inspection: H01/M01 resolved in Phase 5; current reliability evidence still required | Current regression results and explicit gaps, not a blanket reliability guarantee |
| P6-T01 | “End-to-end: valid flight -> route -> commands -> intermediate observations -> final observation -> durable AT_MAKEUP/history -> duplicate replay no extra effect.” | Already implemented and previously verified | Existing real workflow plus fresh-JVM resume/replay |
| P6-T02 | “Invalid flight, unavailable route, movement failure/retry, simulator offline/timeout, Kafka outage/restart, DB outage, outbox backlog, WebSocket disconnect, and app restart scenarios.” | Implemented but insufficiently tested across new combinations. Existing injected dependency failures are not shared-container power loss | Existing serial Kafka/DB/Python/Phase 5 suites; additive process crashes, final missing scanner and redrive |
| P6-T03 | “Manual smoke check of Compose startup/shutdown, persistent volume, topic inspection, and safe API errors.” | Implemented but insufficiently tested initially: prior phase evidence; no fresh shared-stack shutdown smoke at inspection | Read-only preservation/topic/health checks; manual maintenance-window procedure retained |

No architecture contradiction found. A tagged milestone cannot honestly represent uncommitted Phase 6 code; it remains a release gate. AT_INTERMEDIATE_NODE is atomically followed by next-command issuance, so the restart checkpoint is persisted MOVEMENT_PENDING at the confirmed junction rather than introducing a new pause/state. All other documented in-progress states plus waiting-for-observation and uncertain intent are explicitly exercised. Existing fault modes and retry API are reused; no new workflow state/topology/schema/production failure knob is required.

The final evidence below supersedes the initial classifications; prior phases' historical results alone are not counted as fresh passes.


## Implementation and fault infrastructure

No production defect was demonstrated; no production source fix or new runtime failure API was needed. The Phase 4/5 business logic, all six authoritative documents, Phase 5 reports, schema, graph/Compose configuration, dependencies and frontend remain byte-for-byte unchanged from the starting commit.

New test-only capabilities:

- `Phase6ReliabilityIntegrationTest.java` launches separate backend JVMs, with unique diagnostic topics/groups and a disposable database. `Phase6RestartWorker.java` prepares each durable checkpoint, then uses `Runtime.halt(0)` to skip Spring shutdown. A fresh JVM enables the real outbox and Kafka listeners and resumes through the real Python process.
- `simulator/tests/phase6_driver.py` uses the existing simulator service/journal with a fixed clock and zero movement delay. Its intent mode fsyncs an IN_PROGRESS command and exits via `os._exit(0)`; a successor simulator must report uncertainty, never rerun that command or invent a scan. These fixtures are outside the production boot JAR/package and require diagnostic resources.
- `phase6EquipmentFailureAtEitherEdgeRecoversFromLastScanner` adds conveyor/diverter failure and recovery cases without changing topology. `phase6MissingFinalScannerNeverFabricatesArrivalAndCanBeReplanned` exercises a missing final observation, controlled expiry, stale scan rejection after replanning and eventual valid arrival.
- `test_manual_quarantine_redrive_preserves_bytes_and_executes_movement_once` injects a handler failure before journal intent. The real Kafka adapter exhausts exactly four attempts, quarantines, then an explicit test redrive republishes the original topic/key/raw bytes after restoring the handler. A second deliberate redrive returns byte-identical journaled reports, with one movement execution. No automatic redrive or replacement identifiers are added.
- `scripts/verify-phase6.sh` provides the serial one-command verification profile and logs/XML/fingerprints. `scripts/phase6-preservation.py` performs read-only repeatable-read table fingerprints plus SHA-256 of existing local simulator files. `scripts/test-database.sh` adds `--phase6` and `--phase6-only` while retaining existing modes.
- `backend/build.gradle.kts` adds only the opt-in Phase 6 test input and subprocess test runtime classpath. No dependency or production startup change.

The existing check-in, retry, Kafka listeners, validators, typed module interfaces, outbox, timeout handlers, scanner guards, simulator modes and fsynced journal are reused. Retry is asynchronous; no new retry endpoint or frontend command replay exists. Scripts leave diagnostic Kafka records with seven-day retention and test journals/logs under `/tmp`; application topics are never used for synthetic journeys.

## Final requirements-to-tests assessment

| Requirement | Final status | Executed evidence / remaining condition |
|---|---|---|
| P6-D01 — guide, one command, inspection and limits | PASS | PHASE6_GUIDE.md, README link, verify-phase6.sh full invocation completed with exit 0; existing inspection/requeue/manual-redrive procedures reused |
| P6-D02 — deterministic end-to-end harness | PASS | Eight actual JVM crash/resume cases; fixed test clock, zero-delay Python and bounded state/offset waits, no arbitrary movement sleeps |
| P6-D03 — architecture boundaries | PASS | Existing ModuleBoundaryTest rerun; protected source hashes unchanged; test worker absent from boot JAR |
| P6-D04 — tagged demo milestone | BLOCKED | Phase 6 work is intentionally uncommitted under the user's restriction. No misleading tag points at the Phase 5 commit. A reviewed commit/release step is required. |
| P6-A01 — repeatable happy and failure paths | PASS | 71 enabled database/Kafka cases, including 11 new backend integration cases; 51 Python cases including one new redrive case; repeated crash/full-suite runs |
| P6-A02 — durable AT_MAKEUP/history/complete route/duplicate no-op | PASS | Existing real Kafka/Python journey and all eight fresh-JVM resumes assert persisted makeup, two accepted scans, completed route, retained original history and final Kafka scan replay with unchanged state version/history |
| P6-A03 — restart each in-progress stage | PASS | Actual process termination and fresh JVM/Kafka/Python resume at all checkpoints below; persisted checkpoint files separately checked against expected bag state/node |
| P6-A04 — no known high-severity consistency defect, document limits | PASS for tested scope | No new confirmed production defect; H01/M01 regressions remain green. The unattributed application retry/preservation failure is explicitly retained below, not erased or represented as unchanged data. Release and fault-window limits are recorded. |
| P6-T01 — complete success and duplicate replay | PASS | HTTP acceptance → Java planning → outbox → Kafka → Python → accepted scans → durable terminal state, then same-key check-in replay and duplicate final scan |
| P6-T02 — documented failures, outages, recovery and restart | PASS for executed scenarios | Existing invalid-flight/no-route/timeout/poison/dedup/outbox/dependency-failure tests; new edge/final-scan/redrive/crash tests; actual PostgreSQL/Kafka stop/start and reconnect smoke; Phase 5 disconnect/resync/heartbeat regressions. No claim of every combined outage/crash window or real network black-hole test. |
| P6-T03 — Compose/persistence/topics/safe errors smoke | PASS | Actual CLI-orchestrated stop/start of both existing containers, identical data/journal/summary/end offsets, safe 503 during downtime and 400/404 after restart, readiness UP and application consumer Stable |

Totals: **10 PASS, 1 BLOCKED, 0 confirmed production FAIL** across the 11 extracted clauses. BLOCKED is a release deliverable, not a permission to claim Phase 6 fully complete.

## Checkpoint, recovery and scanner evidence

| Crash checkpoint | Persisted bag state / confirmed node | Outcome after fresh JVM |
|---|---|---|
| CHECKED_IN | CHECKED_IN / checkin-main | AT_MAKEUP / makeup-lhr |
| FLIGHT_VERIFIED | FLIGHT_VERIFIED / checkin-main | AT_MAKEUP / makeup-lhr |
| ROUTE_PLANNED | ROUTE_PLANNED / checkin-main | AT_MAKEUP / makeup-lhr |
| MOVEMENT_PENDING | MOVEMENT_PENDING / checkin-main | AT_MAKEUP / makeup-lhr |
| IN_TRANSIT | IN_TRANSIT / checkin-main | AT_MAKEUP / makeup-lhr |
| WAITING_FOR_OBSERVATION | IN_TRANSIT / checkin-main; command SUCCEEDED | Accepted scanner required before progress; AT_MAKEUP |
| INTERMEDIATE_CONFIRMED | MOVEMENT_PENDING / junction-west | No loss of intermediate scan/history; final valid scan → AT_MAKEUP |
| UNCERTAIN_COMMAND | MOVEMENT_PENDING / checkin-main; fsynced simulator intent | First MOVEMENT_FAILED / SIMULATOR_EXECUTION_UNCERTAIN, zero accepted scans; explicit keyed retry → replacement route and two scans → AT_MAKEUP |

AT_INTERMEDIATE_NODE and the next command are committed atomically; the checkpoint uses the real durable MOVEMENT_PENDING at the junction. WAITING_FOR_OBSERVATION is route progress, not a new bag state. All eight cases preserve prepared history IDs, check final duplicate scan through actual Kafka until its consumer offset commits, and assert no additional state version/history. They replay the check-in key without changing bag identity and reject a new retry key after completion with HTTP 409. The uncertain case also asserts one durable RETRY_REQUEST for repeated same-key recovery after arrival, an extra superseded old command retained, and no scan on uncertain execution.

The prepare JVM advances only to the requested durable checkpoint using real transactional listeners; it does not publish pending outbox work yet. The successor uses normal asynchronous outbox/listener publication/consumption and a separate Python Kafka process. This explicitly tests restart with committed state and unpublished work; it is not a claim of process death at every instruction inside a transaction or publish acknowledgement.

## Retry, timeout, outbox and quarantine contracts

| Contract | Verification |
|---|---|
| Consumer initial attempt + 3 retries, 1/5/15 s | KafkaBoundaryTest verifies exact pause calls and budget across alternating exception types; Phase3KafkaIntegrationTest executes actual broker retries/quarantine/offsets; Python adapter tests and new redrive test verify the exact sequence with an injected wait function |
| Dependency failures separated from poison | Existing injected JDBC outage continues beyond four deliveries without quarantine or offset loss; Kafka publish/offset failures retain pending work; actual shared PostgreSQL stop yields safe 503 and recovers. Dependency backoff is capped at 30 s. |
| Two separate 30 s deadlines | Mutable-clock workflow tests verify command issue deadline is not reset by ACCEPTED/STARTED; observation deadline starts at accepted SUCCEEDED. New missing-final-scan test expires it, rejects a delayed superseded scan, and recovers from the junction. |
| Outbox ack/lease/order/requeue | Existing persistence/Kafka tests verify transaction rollback, same-event lease replay, crash-after-ack duplication, failed-row blocking and explicit requeue without identifier replacement. Outbox policy is separately **initial + up to 20 retries**, capped 1–60 s; it is not the consumer's four-attempt policy. |
| Manual redrive | New real-Kafka Python test preserves original bytes, key, command/route/correlation identifiers; retries go through normal validation and journal; repeated explicit redrive does not execute movement twice. Invalid schema is not silently repaired or accepted. |
| No false location / AT_MAKEUP | Scanner-only transition tests, wrong/late/out-of-order/final-guard tests, missing-final-scan test and all eight fresh-JVM journeys pass. A command success, route plan, rejected observation or uncertain intent is not location evidence. |

## Executed results and failures

Final complete command: `./scripts/verify-phase6.sh`, evidence `/tmp/vortex-phase6-verification-NQplBP`, **exit 0**. The final runner performed all steps serially, copied XML before later Gradle invocations and compared application/journal snapshots. Skips are not passes and overlapping executions are not summed as unique test coverage.

| Executed check | Passed | Failed | Skipped | Evidence |
|---|---:|---:|---:|---|
| Standalone initial `./scripts/test-database.sh --phase6-only` | 8 | 0 | 0 | `/tmp/vortex-phase6-restarts-first.log` |
| Final frontend typecheck | check passed | 0 | 0 | `frontend-typecheck.log` |
| Final frontend tests | 37 | 0 | 0 | Seven files, unchanged Phase 5 regressions; `frontend-tests.log` |
| Final frontend production build | build passed | 0 | 0 | `frontend-build.log` |
| Final fresh backend build/test | 70 | 0 | **47** | `backend-build.log`, `backend-default-xml`; default gate skips include the new integration methods |
| Final enabled Phase 1–6 PostgreSQL/Kafka suite | **71** | 0 | 0 | `integration.log`, `integration-xml` |
| Final separately enabled Java broker smoke | 1 | 0 | 0 | `kafka-smoke.log`, `smoke-xml` |
| Final Python suite with Kafka contracts enabled | **51** | 0 | 0 | `simulator.log` |
| Final whitespace / shell syntax / protected hashes | check passed | 0 | 0 | `whitespace.log`; `bash -n`; source hash comparison |
| Actual Compose stop/start / safe errors / preservation / consumer rejoin | smoke passed | 0 | 0 | `/tmp/vortex-phase6-compose-smoke-18ir8D` and `/tmp/vortex-phase6-compose-smoke.log` |
| Tagged milestone | — | — | — | BLOCKED by uncommitted work and explicit no-commit/push instruction; not counted as a test pass |

All final-run relative paths above are under `/tmp/vortex-phase6-verification-NQplBP`.

Earlier failed runs remain evidence:

1. `/tmp/vortex-phase6-verification-w28X0X`: **69 integration passes / 2 failures** in the initial new equipment-recovery tests. Those draft assertions inspected the replacement route before asynchronous planning was drained. A test edit overlapped compilation; bytecode inspection confirmed the compiled run still had that assertion ordering. Only the tests were corrected to follow the existing async contract. No production fix or weakened recovery guard. This run's preservation snapshots were equal.
2. `/tmp/vortex-phase6-verification-PNhkgd`: all behavioral checks passed (**71 / 1 / 51** for integration/Java smoke/Python; frontend 37; backend 70/47), but the **overall runner exited 1** because application fingerprints changed. It is not labelled a passing preservation run. The independent final run above has matching fingerprints and exit 0.

Enabled integration breakdown: PersistenceFoundation 5, Phase3Persistence 6, Phase3Kafka 12, KafkaTopicPolicy 1, Phase4Migration 1, Phase4Workflow **37** (previous 34 + 3 new cases), Phase4Kafka 1, Phase6Reliability **8** = **71**. Default gated methods expand parameterized cases differently, so 47 skipped entries are not arithmetically converted into 71 passes. The default smoke skip was executed separately. Existing Phase 4 API/state/scanner/retry tests and Phase 5 origin/STOMP/API/frontend tests were rerun; no actual browser execution is claimed.

## Data/journal preservation and concurrent activity

Initial read-only snapshot: **17 application tables / 984 rows**, one existing local simulator journal file. During the second run, the application database gained exactly one explicit retry on the existing **BAG-17**, accepted at **2026-11-06 04:07:47 UTC (09:37:47 local)**. Deltas: bag_history +3, outbox +4, processed_events +4, workflow_retry_requests +1; bag count remained 19. The bag's state returned to FLIGHT_VERIFICATION_FAILED / FLIGHT_NOT_ELIGIBLE. Its history was **not deleted, reset or rolled back**.

This label/time is outside generated `P6-*` fixtures and the fixed 2030 worker clock. All test DBs assert `vortex_test_*`; child processes target their allocated loopback ports and diagnostic topics; no P6 bag was found in the application DB. These facts support concurrent application-client activity rather than synthetic test leakage, but the precise client origin is **not independently established**. A clarification was requested; no user confirmation is assumed. The unexpected change and failed guard remain recorded. The task-wide 984-row starting snapshot therefore cannot be described as identical at completion.

After leaving that valid retry intact and requesting a read-only application window, the final full run recorded **17 tables / 996 rows** before and after with **identical per-table counts/content hashes**; the existing journal SHA-256 was identical. The subsequent real Compose restart smoke again preserved those fingerprints, the summary and all **10 application-topic partition end offsets**; identical container IDs confirm stop/start rather than volume/container replacement. Both services are healthy, readiness is UP, and the existing `vortex-workflow` consumer group rejoined **Stable / 1 member**. No `vortex_test_*` databases remain. Diagnostic topics/test journals are separate and retained for inspection.

Snapshots/logs: final runner `before.json` / `after.json`; smoke `/tmp/vortex-phase6-compose-smoke-18ir8D/{before.json,after.json,offsets-before.txt,offsets-after.txt,containers-before.txt,containers-after.txt,unavailable.json,readiness.json}`. The smoke used only read-only REST probes against the application: database downtime returned safe DATABASE_UNAVAILABLE/503, then malformed ID 400 and unknown ID 404 after restart. It refused to start unless active bag count and unpublished outbox count were both zero and restored services on failure. No application API command was submitted by that smoke.

## Files changed

- `backend/build.gradle.kts` — gated test classpath/input only.
- `backend/src/test/java/com/vortex/workflow/Phase4WorkflowIntegrationTest.java` — conveyor/diverter and missing-final-scanner cases.
- `backend/src/test/java/com/vortex/workflow/Phase6ReliabilityIntegrationTest.java` — eight isolated abrupt-process restart scenarios.
- `backend/src/test/java/com/vortex/workflow/Phase6RestartWorker.java` — transactional checkpoint and fresh JVM/Kafka/Python fixture.
- `simulator/tests/phase6_driver.py` — test-only controlled clock, intent death and Kafka service driver.
- `simulator/tests/test_kafka_contract.py` — actual manual-redrive/duplicate movement check.
- `scripts/test-database.sh` — additive Phase 6 selectors/gate.
- `scripts/verify-phase6.sh` — serial verification and preservation guard.
- `scripts/phase6-preservation.py` — read-only fingerprints, no record contents.
- `docs/PHASE6_GUIDE.md`, `docs/PHASE6_RESULTS.md`, `README.md` — exact commands, scenarios, evidence and limits.

No changes to any backend production Java/resource, simulator production source, frontend, schema/migrations, topology/Compose, dependency lockfile, authoritative document or Phase 5 report. Protected file hashes matched the initial source snapshot; test fixtures are absent from the production JAR. No commit, tag or push, and no further phase was started.

## Remaining conditions and final verdict

**CONDITIONAL PASS.** The Phase 6 implementation, documented fault/recovery checks, repeated JVM restarts, real dependency restart smoke, Phase 4/5 regressions and final preservation-qualified command all pass. No new production defect or known open high-severity consistency issue was demonstrated. The initial tagged demo milestone remains a required release step after review/commit; it cannot truthfully be created for an uncommitted Phase 6 working tree under this task's restrictions.

Limits: the existing BAG-17 client request is unattributed, and no task-wide unchanged-data claim is made across it. Hard power loss, arbitrary in-transaction crash instructions, every combination of live outages, and real network black-hole heartbeat timing are not certified. Restart fixtures hold time fixed; timeout expiry is verified separately with a mutable clock rather than sleeping through long downtime. No new browser/device/accessibility run or exact Node 22.23.3 check was performed (frontend checks used Node 24.19.0; Python 3.13.16; Java 21 / existing PostgreSQL 17.11 and Kafka 4.3.1). Retained diagnostic topics use seven-day retention; `/tmp` logs/journals can be removed manually after review, never the application journal. Existing Phase 5 user-reported browser acceptance remains historical, not newly agent-executed Phase 6 evidence.

## Final audit and release readiness — 2026-11-06

This finalization reviewed all six authoritative documents, the Phase 5 audit/results, Phase 6 guide/results, every modified/untracked Phase 6 path, the JVM/Python restart fixtures, redrive contracts, scripts and preservation guard. The exact Phase 6 scope remains IMPLEMENTATION_PLAN.md:184–200. No new phase or runtime feature was added. Read-only Git inspection found `main` at `cb9608d0744661e99ba2840f5b7a0490d672599e`, upstream `origin/main`, configured remote `https://github.com/samir-saudagar/vortex.git`, no staged changes, and the intended Phase 6 working-tree changes. No Git mutation, fetch, commit, tag or push was executed. The index SHA-256 and HEAD were checked unchanged after verification.

### Confirmed fixture defect and minimal correction

**P6-F01 — test resource isolation, corrected.** The Python failure driver checked only its command topic with an `assert`. A misconfigured direct invocation could inherit an application report/quarantine topic, consumer group or `simulator/.local` journal. This was a defect in the new test fixture, not a production movement/recovery defect.

Before the fix, `simulator/tests/test_phase6_driver.py::test_phase6_driver_rejects_application_resources_before_io` reproduced **5 failures**: the existing command-topic assertion raised the wrong exception, and four unsafe configurations reached stubbed journal/Kafka I/O. I/O was replaced by failure sentinels; the reproduction did not open the application journal or publish application records. Evidence: `/tmp/vortex-phase6-final-guard-red.log`.

The minimal fix in `simulator/tests/phase6_driver.py` checks **all three topics and the consumer group**, requires a resolved temporary journal outside application `.local`, validates the mode, and confines command/report files to the temporary journal directory. It raises explicit exceptions before I/O, so optimization cannot disable the checks. The five regressions then passed (`/tmp/vortex-phase6-final-guard-green.log`) and passed again within the full 56-test Python suite. All eight real JVM/Python restart journeys also passed with the tightened guard, verifying accepted fixture configuration. No production source or API changed.

Only five paths were edited/created during this finalization: the driver, its new safety regression file, README scope text, PHASE6_GUIDE and this report. The README's obsolete “Phase 6 deferred” statement was corrected. The guide now includes the exact 13-file staging manifest and user-managed release/remote-reference checks. All earlier Phase 6 implementation paths were reviewed and retained.

### Final 11-row requirements matrix

References below are to IMPLEMENTATION_PLAN.md; the initial table above retains exact requirement wording and supporting authoritative-document contracts. Test evidence is from the fresh full run identified below unless explicitly labelled historical. PASS is limited to the stated exercised behavior.

| ID | Document reference / requirement | Implementation evidence | Verification evidence | Status | Remaining action |
|---|---|---|---|---|---|
| P6-D01 | Phase 6 deliverables, line 187: guide, one command, scenarios and inspection | `scripts/verify-phase6.sh`, `scripts/phase6-preservation.py`, `docs/PHASE6_GUIDE.md`; existing README/quarantine/outbox procedures | Full command exit 0; log/XML/snapshot artifacts; shell syntax and documented procedure review | PASS | None |
| P6-D02 | Deliverables, line 188: deterministic end-to-end harness | `Phase6ReliabilityIntegrationTest`, `Phase6RestartWorker.Time`, `simulator/tests/phase6_driver.py`; zero delay and fixed clock | All 8 separate-JVM cases passed; bounded state/offset waits, mutable-clock deadline tests, no arbitrary movement sleep | PASS | Arbitrary crash instructions are outside verified scope |
| P6-D03 | Deliverables, line 189: architecture boundaries | `backend/src/test/java/com/vortex/workflow/ModuleBoundaryTest.java`; existing typed APIs/module ownership | Boundary test PASS; 161 protected files unchanged against HEAD and review hashes; production JAR excludes fixtures/test libraries | PASS | None |
| P6-D04 | Deliverables, line 189: initial tagged demo milestone | User-managed checklist in PHASE6_GUIDE; no required tag name/version specified in plan | No release commit/tag was created or verified by the agent | **BLOCKED — PENDING USER RELEASE ACTION** | User reviews, stages, commits, creates annotated tag on new Phase 6 commit, pushes and verifies remote refs/clean tree |
| P6-A01 | Acceptance, line 192: repeatable happy/failure paths | Existing Phase 1–5 tests plus new edge, missing-scan, restart and redrive tests | 71 enabled Java integration cases and 56 Python cases passed; prior repeated runs retained above | PASS | No claim of all fault combinations |
| P6-A02 | Acceptance, line 193: durable arrival/history/complete route/duplicate no-op | `Phase6RestartWorker` resume assertions; `Phase4KafkaIntegrationTest.realRestOutboxKafkaPythonJourneyAndFinalReplay` | All 8 resumes: same bag, AT_MAKEUP, expected node/history, COMPLETE route, 2 accepted scans, replay with unchanged version/history; existing live E2E passed | PASS | None |
| P6-A03 | Acceptance, line 194: each in-progress stage survives restart | Eight named checkpoints in `Phase6ReliabilityIntegrationTest`; abrupt `Runtime.halt`, fresh JVM/outbox/Kafka/Python | 8/8 passed; all saved `checkpoint.json` states/nodes and `verified.json` files independently checked after the run | PASS | No claim of hard power loss or every transaction instruction |
| P6-A04 | Acceptance, line 195: no known high-severity consistency defect, document limits | Existing guards unchanged; P6-F01 fixed only in fixture; Phase 5 H01/M01 retained | Current safety/recovery regressions pass; no confirmed open production consistency defect found in reviewed/tested scope | PASS | Retain explicit noncritical limits and original BAG-17 discrepancy |
| P6-T01 | Tests, line 198: complete real workflow and duplicate replay | Existing real HTTP/outbox/Kafka/Python/scanner integration and fresh-JVM resume fixture | Live E2E plus 8 resumed journeys passed; duplicate check-in/final scan and recovery-key replay assertions passed | PASS | None |
| P6-T02 | Tests, line 199: failures, timeouts, outages, backlog, WebSocket and restarts | `Phase4WorkflowIntegrationTest`, `Phase3KafkaIntegrationTest`, `Phase3PersistenceIntegrationTest`, Python adapter/service/journal tests, frontend feed/heartbeat tests | Current enabled suites PASS, including poison/dependency failures, timeouts, recovery and restart; prior actual Compose outage/reconnect evidence retained above | PASS | Actual network black-hole and unexecuted combined outage windows remain unverified |
| P6-T03 | Tests, line 200: manual Compose/persistence/topics/safe errors | Existing dev-start/stop, topic inspection, health and safe REST errors | **Prior executed CLI smoke** documented above: stop/start, identical data/journal/end offsets, safe 503/400/404 and consumer rejoin; current topic-policy test, healthy services/readiness and preservation pass | PASS | No new Compose shutdown or browser smoke is claimed in this finalization |

**Totals: 10 PASS, 1 BLOCKED, 0 PARTIAL, 0 FAIL, 0 NOT VERIFIED across the 11 mandatory clauses.** The BLOCKED clause is specifically **PENDING USER RELEASE ACTION**. Technical limits within tested requirements remain explicit; this total does not imply every optional runtime/browser/fault combination was tested.

### Fresh serial verification

Executed `./scripts/verify-phase6.sh` to completion, **exit 0**. Evidence directory: `/tmp/vortex-phase6-verification-6YskBP`; outer log: `/tmp/vortex-phase6-finalization.log`. Kafka suites ran serially. JUnit XML was copied before subsequent Gradle executions; counts below were calculated from XML, not inferred from build success.

| Check | Passed | Failed/errors | Skipped | Evidence under final directory |
|---|---:|---:|---:|---|
| Frontend TypeScript | PASS | 0 | 0 | `frontend-typecheck.log` |
| Frontend tests | **37** | 0 | 0 | `frontend-tests.log`, 7 files |
| Frontend production build | PASS | 0 | 0 | `frontend-build.log` |
| Fresh backend build/default tests | **70** | 0 | **47 gated entries** | `backend-build.log`, `backend-default-xml` (117 entries total) |
| Enabled PostgreSQL/Kafka Phase 1–6 integration | **71** | 0 | 0 | `integration.log`, `integration-xml` |
| Separately enabled Java Kafka broker smoke | **1** | 0 | 0 | `kafka-smoke.log`, `smoke-xml` |
| Python, real Kafka contracts enabled | **56** | 0 | 0 | `simulator.log`; includes 5 new safety regressions and manual-redrive test |
| Preservation comparison | PASS | 0 | 0 | `before.json`, `after.json` |
| Whitespace, shell/Python syntax, source/artifact/index review | PASS | 0 | 0 | `whitespace.log`; additional checks recorded below |

No required automated suite was environment-blocked. The 47 default gated entries are **not passes**; enabled integration and smoke executions are reported separately, with no summing of overlapping tests as unique coverage. The enabled integration breakdown remains 5 foundation + 6 persistence + 12 Kafka + 1 topic policy + 1 migration + 37 workflow + 1 real E2E/STOMP + 8 restart = 71. The Python increase from 51 to 56 is exactly the five new fixture-isolation cases.

The focused pre-fix five failures are intentional defect reproduction, not hidden successful tests. The original two draft recovery-test failures, the earlier preservation-guard failure, and the BAG-17 activity remain unchanged in the historical sections. No later pass erases them.

### Reliability and recovery guarantees reviewed

All test names below refer to the existing production contracts. No state, endpoint, recovery bypass or automatic redrive was added.

| Guarantee | Executed evidence / result |
|---|---|
| Accepted scanner evidence is the only movement confirmation | `happyJourneyRequiresEachScannerAndFinalReplayIsANoop`, `wrongAndOutOfOrderObservationsAreDurablyRejected`, `finalGuardUsesVerifiedDestinationAndConfiguredMakeupScanner`: PASS |
| Command success alone cannot produce AT_MAKEUP | Happy-journey guards, WAITING_FOR_OBSERVATION restart, new missing-final-scan case: PASS |
| Missing final scan cannot fabricate arrival | `phase6MissingFinalScannerNeverFabricatesArrivalAndCanBeReplanned`: no arrival, separate 30-second deadline, retained junction, superseded scan rejection, valid replacement scan required: PASS |
| Interrupted intent fails closed after simulator restart | UNCERTAIN_COMMAND JVM/Python checkpoint and `test_in_progress_restart_reports_uncertain_without_observation_or_movement`: uncertain failure, no scan/no old movement execution: PASS |
| Uncertain movement is never automatically replayed | Same Python no-execution assertion; Java retains failure until explicit retry; old command remains in history: PASS |
| Explicit recovery is idempotent | `explicitFlightRetryIsFencedAndRetryKeyIncludesBagPath`, movement recovery tests, fresh-JVM uncertain same-key replay, frontend H01 confirmation/no-auto-send tests: PASS |
| Permitted movement recovery starts at last confirmed node | Both equipment-edge cases, rejected/uncertain movement, missing final scanner: source is check-in or accepted junction as applicable: PASS |
| Duplicate/out-of-order reports cannot advance incorrectly | Wrong/out-of-order/result/deadline guards, final replay after all 8 restarts, journal duplicate tests: PASS |
| Poison has four total attempts and 1/5/15-second delays | KafkaBoundaryTest exact pause calls, real Java poison/quarantine tests, Python adapter and real redrive delays: PASS. DB dependency outages have a separate non-exhausting policy. |
| Manual redrive preserves original bytes and identifiers | `test_manual_quarantine_redrive_preserves_bytes_and_executes_movement_once`: exact raw value/key, unchanged route/correlation, byte-identical saved reports on second explicit redrive and one movement execution: PASS |
| DB/Kafka outages do not create invalid transitions | Injected publish failure/durable pending outbox, DB outage beyond four deliveries without quarantine/offset loss, lease/ack crash/replay tests: PASS. Prior real shared-service stop/start safe-error/preservation smoke retained as historical evidence. |
| Durable backend/simulator state survives restart | All eight abrupt JVM checkpoints PASS; existing completed/uncertain/torn-journal/restart contracts PASS. Initial history IDs survive, 2 accepted scans, completed route, one final transition and no duplicate effect. |

Timeout policy remains separate **30-second command and observation deadlines**, verified with mutable clocks. The outbox's initial attempt plus up to 20 retries/capped 1–60-second backoff is distinct from consumer poison's four-attempt budget. Repeated manual redrive never bypasses validation, creates a replacement ID, or directly replays an uncertain command.

### Preservation and release-content review

Before the fresh run: **0 active bags, 0 unpublished outbox rows, 0 active other application DB sessions**; dashboard summary was 19 total / 13 completed / 6 failed. Application write clients were requested to remain idle; no user confirmation of activity attribution is assumed. The runner then recorded **17 application tables / 1,005 rows** both before and after, with exactly equal per-table counts/content hashes and equal simulator-file SHA-256. The existing journal hash was `92dfe94cceba06033371b45cbdf1a285859bc39da3e1a39a0b9c4b6d5a7be806`. No application records/journal were reset, deleted, rolled back or written by the finalization.

The current 1,005-row baseline is the observed start of this final verification, not a claim that earlier 984- or 996-row snapshots remained identical across sessions. The historical BAG-17 retry and its unattributed origin remain recorded above. The final guard detected **no unexpected changes during this run**. Afterward: 0 active bags, 0 unpublished outbox rows, 0 remaining `vortex_test_*` databases, 0 synthetic `P6-*` bags in the application database; PostgreSQL/Kafka healthy and REST readiness UP. Diagnostic Kafka records and temporary test journals are retained outside application resources.

Compared with both HEAD and the start-of-review hashes, **all 161 protected files** were unchanged: backend production Java/resources/migrations, simulator production source, frontend/locks, root build/settings, Compose topology, the six authoritative documents and both Phase 5 reports. The generated `backend/build/libs/backend.jar` contains neither Phase6RestartWorker nor Phase6ReliabilityIntegrationTest, JUnit or Spring Boot test libraries. Python fixtures live outside `simulator/src` and are not the simulator runtime package.

Every one of the **13 intended Phase 6 paths** was inspected and scanned for private-key/token/credential-URL markers; no matches were found. Ignore checks exclude `.env`, simulator `.local`/virtualenv, node_modules/dist, Gradle caches and build output. No secret, journal, database, log, cache, generated artifact, schema or topology path appears in the intended release manifest. The original 12-path manifest above gains only `simulator/tests/test_phase6_driver.py`; the full explicit staging list is in [the guide's user-managed release checklist](PHASE6_GUIDE.md#user-managed-release-checklist--2026-11-06).

### Verdict, limits and pending user release

**CONDITIONAL PASS — functional/reliability work verified; P6-D04 is PENDING USER RELEASE ACTION.** No mandatory functional/safety regression remains demonstrated. Phase 6 is ready for the user's final diff/staging review and release actions. It is not a released/tagged milestone yet.

IMPLEMENTATION_PLAN.md:189 prescribes a tagged demo milestone but **no exact tag name/version**. The user must select a new name, review and stage the 13 intended files, create a Phase 6 commit, create an annotated tag on that commit, push branch and tag without force, fetch and verify branch/tag-object/peeled-commit remote equality, and confirm a clean working tree. Exact commands are in PHASE6_GUIDE. No tag existence or remote equality is claimed in advance. Only after that evidence exists does the milestone become eligible for PASS.

Nonblocking limits remain unchanged: exact recommended Node 22.23.3 was not executed (current checks use Node 24.19.0); no new browser/device/accessibility run; Phase 5 A–G is historical **user-reported** acceptance. Real network black-hole heartbeat timing, arbitrary hard power loss, every in-transaction crash instruction and all combined live-fault windows remain unverified. Prior manual Compose stop/start smoke remains documented historical evidence; it was not rerun in this finalization and its temporary artifact paths are historical references. No production availability/exactly-once guarantee is asserted. Phase 7 has not started, and no Git staging, commit, tag, push, branch, configuration, credential or remote change was performed by the agent.
