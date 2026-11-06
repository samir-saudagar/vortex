# Phase 6 local reliability verification

From the Phase 5 baseline, install the locked Java 21 / frontend / Python environments described in README. Docker and Compose, `python3`, `flock`, and local PostgreSQL/Kafka access are required. Do not run another simulator against the same application command journal. Allow existing journeys to settle and keep the dashboard and other application write clients idle for the entire verification window. A concurrent retry or other live change will correctly fail the preservation guard; never reset or roll back valid user records to make it pass.

## One-command verification

```bash
./scripts/verify-phase6.sh
```

This safely starts/reuses the existing Compose dependencies, fingerprints all application tables and files under `simulator/.local`, runs frontend checks, a fresh backend build, the isolated Phase 1–6 database/Kafka suite, Java broker smoke, then Python tests. It runs Kafka contracts serially because the existing Java/Python Phase 3 tests share diagnostic topics. It compares the fingerprints even after a test fails. It never resets the application database/volume or removes the application journal. Each invocation prints its `/tmp/vortex-phase6-verification-*` evidence directory with logs, copied JUnit XML and before/after hashes. Review skips separately from passes.

To run narrower suites:

```bash
./scripts/test-database.sh --phase6-only  # eight abrupt JVM restart/recovery checkpoints
./scripts/test-database.sh --phase6       # existing DB/Kafka regressions plus Phase 6
```

Both modes create and remove only their own `vortex_test_*` database. Restart tests create uniquely named `vortex.phase6-test.<uuid>.*` diagnostic topics and consumer groups; they retain diagnostic Kafka records with seven-day retention and test journals/logs under `/tmp`, not `simulator/.local`. No source topic or event contract changes. Production domain code is reused unchanged.

## Fault and recovery scenarios

| Scenario | Deterministic exercise | Required result |
|---|---|---|
| Unknown/cancelled/wrong-origin/closed flight | Existing workflow integration fixtures in isolated database | Durable check-in, then asynchronous failure, no fabricated movement |
| Unavailable equipment / no route | Existing equipment snapshot fixtures | ROUTING_BLOCKED; only explicit retry after correction replans |
| Conveyor or diverter failure | `phase6EquipmentFailureAtEitherEdgeRecoversFromLastScanner` | MOVEMENT_FAILED at last accepted scanner; new route/command on explicit keyed retry |
| Missing final scan | `phase6MissingFinalScannerNeverFabricatesArrivalAndCanBeReplanned` | SUCCEEDED alone does not arrive; controlled 30-second observation deadline fails; delayed old scan cannot complete replacement route |
| Command / observation deadlines | Existing mutable-clock workflow tests | Separate 30-second deadlines; ACCEPTED/STARTED do not reset command deadline |
| Duplicate/wrong/late/out-of-order reports | Existing workflow tests and final scan replay after each new restart | Persist rejection or deduplicate; no false AT_MAKEUP or repeated accepted movement |
| Poison / alternating errors | Existing Java/Python contracts | Four attempts with 1/5/15-second backoffs; quarantine acknowledged before source commit |
| Database / Kafka dependency outage | Existing injected JDBC/publish/offset failures | No poison classification solely for DB outage; no lost outbox or advanced offset; restart/replay remains idempotent |
| Manual quarantine redrive | New real-Kafka Python test, after restoring an injected handler bug | Original topic, raw key and bytes/identifiers unchanged; a second deliberate redrive re-emits saved reports without movement |
| Abrupt backend death | New separate-JVM worker at eight checkpoints | Durable state/history survive; fresh JVM/outbox/Kafka/Python resumes safely |
| Simulator durable-intent death | Test-only Python driver uses fsynced intent then `os._exit` | Fresh simulator reports SIMULATOR_EXECUTION_UNCERTAIN without scanner; no automatic movement replay; explicit recovery replaces command |
| WebSocket loss/refetch | Existing Phase 5 component and actual STOMP-client heartbeat tests | Disconnect/status and authoritative REST resync; no stale notification patches canonical location |

Backend checkpoints: CHECKED_IN, FLIGHT_VERIFIED, ROUTE_PLANNED, MOVEMENT_PENDING, IN_TRANSIT, WAITING_FOR_OBSERVATION, INTERMEDIATE_CONFIRMED, and UNCERTAIN_COMMAND. WAITING_FOR_OBSERVATION is route-progress status, not an invented bag state. An intermediate accepted scan atomically queues the next command, so INTERMEDIATE_CONFIRMED persists MOVEMENT_PENDING at the junction; AT_INTERMEDIATE_NODE remains in durable history. The first JVM commits each checkpoint and halts without Spring shutdown; its successor uses real asynchronous listeners and outbox publication. A fixed test clock and zero-delay Python driver avoid arbitrary sleep-based movement assertions.

The test-only driver/worker are not a runtime admin API or a production failure-injection feature. Do not launch them against the application database/topics. The JVM worker requires a disposable database and diagnostic topic prefix. The Python driver rejects application command/report/quarantine topics and consumer groups, requires a temporary journal outside `simulator/.local`, and confines command/report files to that temporary journal directory before opening Kafka or a journal. Explicit exceptions keep these guards active under Python optimization. Five negative regression cases verify rejection before I/O; actual restart tests verify the valid configuration.

## Local manual failure demonstration

Use **new bag tags** and the existing local check-in/retry APIs only. Start one backend, one frontend and one simulator as in README. Existing `VORTEX_FAILURE_MODE` values are SUCCESS, REJECT, BLOCKED_EQUIPMENT, MOVEMENT_FAILURE and MISSING_OBSERVATION; they are already implemented. A zero-delay simulator can finish before the UI renders intermediate state; use the existing delay option only for manual viewing. Preserve the same application journal between simulator restarts. After changing the cause, use Review recovery → Confirm retry with a stable idempotency key if the HTTP outcome is uncertain. Never replay an uncertain equipment command manually.

Inspect `/api/bags/{id}`, `/history`, `/route`, `/movement`, `/api/equipment` and `/actuator/health/readiness`. Distinguish command success, received/rejected/accepted scan, canonical node and terminal AT_MAKEUP. REST reads remain authoritative while STOMP reconnects.

## Topic/outbox/quarantine inspection

Use README's existing topic inspection commands, `scripts/inspect-quarantine.sh` and `scripts/requeue-outbox.sh`. The latter changes only an explicitly selected FAILED outbox row after its cause is fixed; it preserves its ID, envelope/key and sequence. Use the reviewed manual redrive procedure in README to republish an operator-selected quarantine wrapper's original bytes and key. Do not rewrite event/correlation/causation/command/observation IDs, consume quarantine automatically, bypass validation, or delete source/quarantine records. New tests exercise this existing procedure against diagnostic topics only.

## Remaining manual and release checks

A real shared PostgreSQL/Kafka container outage is not performed by the automated harness: it uses injected dependency failures and fresh application/simulator JVM/processes, preserving other local users. To manually verify Compose persistence in a maintenance window, wait for live journeys to settle, fingerprint using `python3 scripts/phase6-preservation.py /tmp/before.json`, run `docker compose stop` then `./scripts/dev-start.sh`, fingerprint to `/tmp/after.json`, and compare with `cmp`. Do not run `down -v` or the reset script. Inspect readiness, topics and safe API errors afterward. The results report distinguishes this manual gate from automated checks; do not claim it passed unless actually executed.

The documented tagged demo milestone is **PENDING USER RELEASE ACTION**. Only the user will stage, commit, tag and push. No tag is created for an uncommitted Phase 6 tree or attached to Phase 5. Exact Node 22.23.3 checks and agent-executed browser/device/accessibility/network-black-hole coverage remain separate limitations; existing Phase 5 user-reported acceptance is not new Phase 6 browser evidence.

The agent executed the non-destructive Compose stop/start and safe-error smoke in this Phase 6 run; its exact preservation/offset/health evidence is in PHASE6_RESULTS. The automated one-command runner itself intentionally does not stop shared services.

## User-managed release checklist — 2026-11-06

IMPLEMENTATION_PLAN.md:189 requires an initial tagged demo milestone but specifies **no tag naming convention or version**. Choose and confirm a new tag name before executing the release commands below. Do not reuse an existing tag. These commands are instructions for the user; the agent has not executed any Git mutation. Current reviewed branch is `main`, remote `origin` is `https://github.com/samir-saudagar/vortex.git`, and the parent is `cb9608d0744661e99ba2840f5b7a0490d672599e`.

1. Review the final result, all 13 intended paths, and any newly introduced local changes. Stop if a path outside this manifest is present or verification is no longer current.

```bash
git status --short
git branch --show-current
git remote -v
git diff --check
git diff
git ls-files --others --exclude-standard
```

2. Stage only the reviewed Phase 6 implementation, fixtures, scripts and reports; review the staged diff before committing. Do not add `.env`, journals, logs, databases, caches or build outputs.

```bash
git add -- README.md backend/build.gradle.kts \
  backend/src/test/java/com/vortex/workflow/Phase4WorkflowIntegrationTest.java \
  backend/src/test/java/com/vortex/workflow/Phase6ReliabilityIntegrationTest.java \
  backend/src/test/java/com/vortex/workflow/Phase6RestartWorker.java \
  simulator/tests/phase6_driver.py simulator/tests/test_phase6_driver.py \
  simulator/tests/test_kafka_contract.py scripts/test-database.sh \
  scripts/verify-phase6.sh scripts/phase6-preservation.py \
  docs/PHASE6_GUIDE.md docs/PHASE6_RESULTS.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "test: complete Phase 6 reliability and recovery verification"
```

3. Choose the tag, inspect existing local and remote tags, then create an annotated milestone on the **new Phase 6 commit**. The commit message above is a suggestion, not a prescribed project convention.

```bash
read -r -p 'Chosen new Phase 6 milestone tag: ' PHASE6_TAG
git check-ref-format "refs/tags/$PHASE6_TAG"
git show-ref --tags
git ls-remote --tags origin
# Continue only after confirming the chosen tag does not already exist.
PHASE6_COMMIT=$(git rev-parse HEAD)
git show --stat --oneline "$PHASE6_COMMIT"
git tag -a "$PHASE6_TAG" -m "VORTEX Phase 6 demo milestone" "$PHASE6_COMMIT"
git cat-file -t "refs/tags/$PHASE6_TAG"  # must print tag
test "$(git rev-parse "$PHASE6_TAG^{commit}")" = "$PHASE6_COMMIT"
```

4. Push the current commit and tag without force, fetch, and compare the remote branch, annotated tag object and peeled commit. If a normal push is rejected, investigate without rewriting history.

```bash
git push origin main
git push origin "refs/tags/$PHASE6_TAG"
git fetch origin
git ls-remote --exit-code origin refs/heads/main "refs/tags/$PHASE6_TAG" "refs/tags/$PHASE6_TAG^{}"
test "$(git rev-parse origin/main)" = "$PHASE6_COMMIT"
test "$(git ls-remote origin refs/heads/main | cut -f1)" = "$PHASE6_COMMIT"
test "$(git ls-remote origin "refs/tags/$PHASE6_TAG" | cut -f1)" = "$(git rev-parse "refs/tags/$PHASE6_TAG")"
test "$(git ls-remote origin "refs/tags/$PHASE6_TAG^{}" | cut -f1)" = "$PHASE6_COMMIT"
git status --short  # must be empty
test -z "$(git status --porcelain)"
```

Record the actual commit, annotated tag, remote verification and clean-tree result after completing these steps. Until that evidence exists, the verdict remains **CONDITIONAL PASS**, even when functional verification passes. Do not claim a tag exists from these instructions alone.
