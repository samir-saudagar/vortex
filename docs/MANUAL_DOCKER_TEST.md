# Your disposable full-Docker manual environment

> **Final manual acceptance: 3 PASS, user-reported.** The user confirmed the normal
> LHR journey, blocked-belt alternative routing, and routing failure followed by
> explicitly confirmed recovery. These are manual browser acceptance results in
> `vortex-manual-test` at **http://localhost:8088**, distinct from the agent's
> earlier automated protocol checks. The disposable environment was removed
> after explicit user approval. Its six containers, network and three named
> volumes are gone; development preservation checks passed.

Project: **vortex-manual-test**. Browser: **http://localhost:8088**.
Settings: ignored `.env.manual-test` in the repository root (private local
database password; do not commit or share it). The existing `compose.yaml` and
`full` profile are used; there is no additional Compose file.

Published loopback ports: frontend **8088**, PostgreSQL **15432**, Kafka **19092**.
The normal development ports 5432/9092 and their containers/data are independent.
The simulator uses SUCCESS mode with a two-second delay, making each normal edge
take approximately four seconds. Both command and observation deadlines remain
30 seconds. Keep only one simulator for this project's journal/command stream.

## Status and logs

Run every command from the repository root with the explicit project/settings:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full ps --all
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full logs -f --tail=100
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full logs -f backend simulator
```

Expected: PostgreSQL, Kafka, backend, frontend and simulator healthy; kafka-init
exited **0** (normal successful initialization). The named volumes are
`vortex-manual-test_postgres-data`, `vortex-manual-test_kafka-data` and
`vortex-manual-test_simulator-journal`; network `vortex-manual-test_default`.

## Flights available in the seeded catalog

| Flight ID | Destination/status | Expected check-in result |
|---|---|---|
| FL-204-2030-01-15 | LHR / SCHEDULED | Durable acceptance, then normal LHR journey |
| FL-205-2030-01-15 | LAX / BOARDING | Durable acceptance, then normal LAX journey |
| FL-999-2030-01-15 | LHR / CANCELLED | Durable acceptance, then FLIGHT_VERIFICATION_FAILED / FLIGHT_NOT_ELIGIBLE |

The eligible fixtures have their existing 2020-open/2030-close windows. No flight
validation or application clock was changed. Unknown flight IDs are accepted
durably and subsequently fail with UNKNOWN_FLIGHT.

## Manual browser checklist

The three confirmed acceptance results are recorded below. The checklist remains
as a repeatable procedure, not a claim that the agent performed browser UI
acceptance. Use a fresh bag tag each time; repeating
a tag with another check-in is correctly a conflict.

1. Open **http://localhost:8088**. Confirm flights, equipment, counters and baggage
   list load. An agent-created STARTUP-CHECK bag may already be present from the
   REST/STOMP readiness probe; use different tags for your own tests.
2. Check in **MANUAL-LHR-001** for **FL-204-2030-01-15** using **Check in bag**.
   The details should open. Expect durable CHECKED_IN followed asynchronously by
   verification, routing and movement. Follow
   `checkin-main → junction-west → makeup-lhr`.
3. Inspect commands, **Scanner observations** and history. STARTED or SUCCEEDED
   alone must not establish a new node. Location changes after accepted
   **scanner-west**, then **scanner-lhr**. Final state is **AT_MAKEUP**, route
   COMPLETE, with one final transition and two accepted checkpoint observations.
   With the configured delay the normal journey takes roughly eight seconds.
4. For another destination, check in **MANUAL-LAX-001** for **FL-205-2030-01-15**.
   Expect `checkin-main → junction-east → makeup-lax`, accepted **scanner-east**
   then **scanner-lax**, and final AT_MAKEUP at makeup-lax.
5. Check in **MANUAL-CANCELLED-001** for **FL-999-2030-01-15**. Check-in is
   accepted first, then fails asynchronous flight verification. It must stay at
   check-in with FLIGHT_NOT_ELIGIBLE, no movement and no false arrival.

## Blocked conveyor with an available alternative

The seed **belt-blocked** is a cost-1 parallel edge from checkin-main to
junction-west. It is already BLOCKED. It is not the usual LHR belt, and is not
on the LAX path.

1. Inspect equipment: belt-blocked should be BLOCKED and belt-west AVAILABLE.
2. Check in **MANUAL-BLOCKED-ALT-001** for FL-204-2030-01-15.
3. In route steps verify **belt-west → diverter-lhr**, total route cost **12**;
   the blocked candidate must not be selected. Accepted west/final scans should
   still be required for AT_MAKEUP.
4. For the complete planning explanation, copy the displayed bag ID and open
   `http://localhost:8088/api/bags/BAG_ID/route`. Its decisionSummary must exclude
   **edge-checkin-blocked / EQUIPMENT_NOT_AVAILABLE**. This is a read-only API.

## Safe no-route failure and operator recovery

These developer fixture changes affect **only vortex-manual-test**. Wait for your
earlier journeys to finish first. Do not change project names or omit the explicit
settings/project arguments. No topology or bag state is edited.

1. Block only this project's remaining west conveyor. The simulator can remain
   running; the backend planner will exclude both blocked west candidates.

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full exec -T postgres psql -U vortex -d vortex -v ON_ERROR_STOP=1 -c "UPDATE vortex.equipment SET status='BLOCKED', status_reason='MANUAL_TEST_BLOCKED' WHERE equipment_id='belt-west';"
```

2. In the browser check in **MANUAL-RECOVERY-001** for FL-204-2030-01-15. Expect
   **ROUTING_BLOCKED / NO_ROUTE**, confirmed node **checkin-main**, zero movement
   commands, and no accepted arrival observation. Allow the normal background
   refresh to update equipment status.
3. Correct the cause in this project only:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full exec -T postgres psql -U vortex -d vortex -v ON_ERROR_STOP=1 -c "UPDATE vortex.equipment SET status='AVAILABLE', status_reason=NULL WHERE equipment_id='belt-west';"
```

4. Select MANUAL-RECOVERY-001 and click **Review recovery**, then **Confirm
   retry**. Recovery must require this explicit confirmation. Expect a new route
   from the confirmed check-in node, accepted west/final observations and
   **AT_MAKEUP**. The NO_ROUTE result and retry remain in history.
5. Do not replay commands directly. If a retry response is uncertain, use the
   existing confirmation/retry flow within the same loaded page, which retains
   the unresolved key. Do not create a different logical retry to force movement.

## Optional movement-failure recovery

After all current journeys finish, edit **only** VORTEX_FULL_FAILURE_MODE in
`.env.manual-test` from SUCCESS to MOVEMENT_FAILURE. Apply only the simulator:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full up -d --no-deps simulator
```

Wait until the simulator is healthy. Check in a fresh LHR bag. Expect
MOVEMENT_FAILED / SIMULATED_MOVEMENT_FAILURE and no false scanner-confirmed
advance. Restore the file's mode to SUCCESS, repeat the same simulator command,
wait for health, then use Review recovery → Confirm retry. The replacement route
starts at the last confirmed node; old command/history records remain.
This optional exercise is **not pre-run** for you. The environment is initially
left running in SUCCESS mode.

## Stop, resume and delete later — user actions only

Nothing in this setup automatically stops or deletes your manual environment.
To stop it without deleting data:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full stop
```

Resume with the same settings/project:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full up -d --wait --wait-timeout 600
```

When you are completely finished, this command **permanently deletes only this
project's containers/network and its three named data volumes**, including all
manual-test baggage, Kafka data and its journal. It does not target the existing
`vortex` development project. Inspect the project first:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full ps --all
docker volume ls --filter label=com.docker.compose.project=vortex-manual-test
```

Bash confirmation:

```bash
read -r -p 'Type DELETE VORTEX MANUAL TEST: ' confirmation
if [ "$confirmation" = 'DELETE VORTEX MANUAL TEST' ]; then
  docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full down --volumes
fi
```

PowerShell confirmation:

```powershell
$confirmation = Read-Host 'Type DELETE VORTEX MANUAL TEST'
if ($confirmation -ceq 'DELETE VORTEX MANUAL TEST') {
  docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full down --volumes
}
```

The stop/delete commands above remain repeatable instructions. After the user
explicitly approved the exact cleanup scope, the agent executed the scoped
`down --volumes` command; see **Cleanup finalization** below. The earlier startup
record is retained as historical evidence, not the current running status.

## Agent startup verification — 2026-11-06

The environment was started successfully and **left running**. All five main
services were healthy and kafka-init exited 0. Resource inspection confirmed the
manual project owns its three named volumes and private network; development
containers/volumes were not reused. Ports 15432/19092 were selected because the
development stack already occupied 5432/9092; 8088 was available.

Actual protocol verification through Nginx passed: localhost HTML, readiness,
flights/equipment/REST reads, durable 202 check-in, native STOMP handshake and
**nine committed bag-state updates**, including final arrival. The probe created
`STARTUP-CHECK-20261106-132020`, bag ID
`ca079b59-aae1-4076-86f5-ae3b0e4cee1b`, for FL-204-2030-01-15. It reached
AT_MAKEUP through accepted scanner-west and scanner-lhr observations, two
successful commands and a COMPLETE cost-12 route. An idempotent check-in replay
returned the same response. At verification the new dashboard had one total/
completed bag, zero active and zero failed bags.

These were agent protocol checks, **not manual browser acceptance**. Your manual
scenarios above have not been pre-run; the initial mode remains SUCCESS, belt-west
AVAILABLE and the seeded belt-blocked BLOCKED. Evidence:
`/tmp/vortex-manual-startup.log`, `/tmp/vortex-manual-verification.log`,
`/tmp/vortex-manual-verification.json`.

Original development preservation checks passed: **17 tables / 1,005 rows** and
the existing host journal have identical content hashes; all ten application-topic
partition end offsets match. Original PostgreSQL/Kafka container IDs, start times
and mounts are unchanged. No migration, stop, recreation, reset, production logic
change or Git mutation was performed against the existing development environment.

## Final user-reported manual browser acceptance

Environment: **vortex-manual-test**. Verified browser URL:
**http://localhost:8088**. Evidence source: the user's explicit confirmation in
the finalization request. The exact test execution times, browser version,
screenshots, HAR/frame traces and manual bag identifiers were not supplied and
are not inferred from the agent's automated records.

| Scenario | Expected outcome | Observed outcome reported by the user | Result |
|---|---|---|---|
| Normal LHR journey | Successful check-in; scanner-confirmed movement; final AT_MAKEUP | Check-in succeeded, movement was scanner-confirmed, and the bag reached AT_MAKEUP | **PASS — user-reported manual acceptance** |
| Blocked-belt alternative routing | Planner excludes belt-blocked and completes the journey through an available alternative | The planner excluded belt-blocked and the bag successfully followed the alternative route | **PASS — user-reported manual acceptance** |
| Routing failure and explicit recovery | Blocking the remaining available belt prevents routing; restoring it and explicitly confirming recovery permits completion | Routing was prevented while blocked; restoring availability and explicitly confirming recovery allowed the journey to complete | **PASS — user-reported manual acceptance** |

These three results close the previously unverified manual browser checks for
this deployment/environment. They are **not agent-executed browser tests**.
The startup REST/STOMP probe, isolated profile/blocked-belt/restart suites and
their actual artifacts remain separate automated evidence in this document and
DOCKER_DEPLOYMENT/BLOCKED_BELT_TEST.

Remaining limits: no additional manual result is claimed for LAX, cancelled or
unknown flights, the optional movement-failure scenario, uncertain HTTP/network
responses, reconnect/heartbeat loss, other browsers/devices, accessibility, or
Windows/macOS/ARM64 execution. Existing automated evidence for some of these
cases is retained; it does not establish new manual acceptance. Existing
development Kafka storage/journal migration remains a separate, unperformed
operation and is not needed to delete this isolated test project.

## Cleanup finalization

Ownership inspection confirmed the six manual-project containers and its network
`vortex-manual-test_default` are separate from development. The only attached
data volumes are `vortex-manual-test_postgres-data`,
`vortex-manual-test_kafka-data`, and `vortex-manual-test_simulator-journal`.
All are labelled for this project and are attached to no other project's
containers. The shared topic-init source is a read-only configuration bind, not
a shared data volume. `.env.manual-test` remains ignored and is not selected for
deletion or publication.

**Cleanup status: PASS — completed after explicit user approval.** Immediately
before execution, the agent rechecked container identities and project labels,
volume ownership and attachments, network membership, and the effective Compose
configuration. Exactly the approved six containers, one network and three named
volumes were selected, with no foreign volume attachments or writable host binds.
`compose.yaml` and `.env.manual-test` still matched the reviewed hashes.

The exact approved command was executed and returned **exit 0**:

```bash
docker compose --env-file .env.manual-test -p vortex-manual-test -f compose.yaml --profile full down --volumes
```

Post-cleanup inspection confirmed all six approved containers,
`vortex-manual-test_default`, and the three named volumes above were removed.
No manual-project containers, networks or volumes remained. Manual-test baggage,
Kafka state and the disposable journal were deleted as authorized; the browser
URL is a historical acceptance address and is no longer served by this project.

Development preservation checks passed against fresh pre-cleanup fingerprints:

- All **17 application tables / 1,005 rows** and host simulator files, including
  the existing command journal, have identical content fingerprints.
- All **ten application Kafka partition end offsets** are unchanged.
- Both original development PostgreSQL/Kafka containers remain healthy with
  unchanged IDs, start times and mounts; neither was stopped or recreated.
- Unrelated container identities/start times/mounts and network/volume
  inventories are unchanged.
- `.env.manual-test` is retained, byte-for-byte unchanged, ignored, untracked
  and mode 0600. The Git index, HEAD and original Phase 6 tag are unchanged.

No development Kafka storage migration, journal reset or unrelated cleanup was
performed. Fresh local comparison artifacts are
`/tmp/vortex-approved-cleanup-{before,after}.json`, with command output in
`/tmp/vortex-approved-cleanup.log`; they are not publication inputs.

## Final publication review

The finalization checks below were executed separately from the earlier full
integration suites and the user's browser acceptance:

| Check | Finalization result |
|---|---|
| Compose validation | PASS for default, full and private manual-test settings |
| Default service selection | PASS: PostgreSQL and Kafka only |
| Full service selection | PASS: PostgreSQL, Kafka, kafka-init, backend, frontend and simulator |
| Packaging review | PASS: one Compose YAML; all three Dockerfiles, Nginx REST/STOMP proxy, health checks and topic-init configuration reviewed |
| Documentation/script syntax | PASS: all 39 Bash/sh documentation examples parse; shell scripts and new Python files parse |
| Git whitespace/index | PASS: diff --check clean; index unchanged |
| Publication hygiene | PASS: .env.manual-test is ignored, untracked and mode 0600; its private password and recognized credential markers were absent from tracked/intended source files |
| Phase 6 preservation | PASS: HEAD and v0.6.0-phase6-demo still resolve to c08cbc2ddb549d94c75a9a956cd5c0173e33e832; existing production business source, schema and topology unchanged |
| Development preservation | PASS: 17 tables / 1,005 rows, host simulator files, all ten application Kafka partition end offsets, and both development container IDs/start times/mounts match the finalization baseline |
| Disposable cleanup | PASS: exact approved command exited 0; six containers, one network and three named volumes removed; development/unrelated-resource preservation checks passed |

Five existing README placeholder URLs were quoted after shell syntax checking
showed that unquoted `<bagId>` was parsed as shell redirection. This was a
documentation correction only. No application behavior was changed.

The expensive integration suites were not repeated for documentation-only
finalization. Their executed default/full-profile, blocked-belt, STOMP,
restart-persistence and Phase 6 regression evidence remains in
[DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md) and
[BLOCKED_BELT_TEST.md](BLOCKED_BELT_TEST.md). Default backend tests' gated skips
remain skips, distinct from the separately enabled integration runs.

Local finalization artifacts: `/tmp/vortex-finalization-review.json`,
`/tmp/vortex-finalization-dev-{before,after}.json` and
`/tmp/vortex-finalization-offsets-{before,after}.json`. These temporary files are
not publication inputs. No Git staging, commit, tag, push, branch change or
development Kafka migration was performed. Publishing this new deployment
does not authorize recreating the existing legacy-storage development broker;
read the deployment migration warning before any later recreation.
