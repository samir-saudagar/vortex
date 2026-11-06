# Blocked conveyor, scanner evidence and explicit recovery

> **User-reported manual acceptance: PASS.** The user confirmed blocked-belt
> exclusion/alternative routing and routing failure followed by availability
> restoration and explicitly confirmed recovery in `vortex-manual-test` at
> http://localhost:8088. The normal LHR journey also passed. See
> [the separate manual record](MANUAL_DOCKER_TEST.md#final-user-reported-manual-browser-acceptance).
> Earlier NOT VERIFIED statements below are historical; they do not describe
> these three newly confirmed scenarios. Automated evidence remains distinct.

The automated verification below uses the existing production planner, workflow,
Kafka adapters, simulator and retry API. It is **automated integration**, separate
from the later user-reported manual acceptance above. No real airport equipment
is involved.

## Actual seeded graph

Source: `backend/src/main/resources/db/migration/V2__seed_demo_catalog.sql`.
`belt-blocked` is **not** the ordinary LHR belt and is not on the LAX path. It
powers a cheaper parallel edge to the same west scanner checkpoint:

| Edge | From → to | Equipment / initial status | Cost |
|---|---|---|---:|
| edge-checkin-blocked | checkin-main → junction-west | belt-blocked / BLOCKED | 1 |
| edge-checkin-west | checkin-main → junction-west | belt-west / AVAILABLE | 4 |
| edge-west-lhr | junction-west → makeup-lhr | diverter-lhr / AVAILABLE | 8 |
| edge-checkin-east | checkin-main → junction-east | belt-east / AVAILABLE | 6 |
| edge-east-lax | junction-east → makeup-lax | diverter-lax / AVAILABLE | 9 |

The blocked candidate would cost 9 to LHR if available; it must be excluded. The
normal selected LHR route costs **12**, through `belt-west` and `diverter-lhr`.
The normal LAX route costs **15**, through `belt-east` and `diverter-lax`. These
LAX values are graph inspection here, not a separate executed Docker LAX journey.

Scanners: `junction-west → scanner-west`, `makeup-lhr → scanner-lhr`;
the east/LAX pair is `scanner-east`, `scanner-lax`. Route plans, command status,
accepted/rejected observations and canonical location remain distinct facts.

## Isolated, reproducible procedure

Run `python3 scripts/test-docker.py` (optional host Python 3, standard library only).
The harness builds the real images and creates a fresh
`vortex-docker-test-<random>` Compose project, free loopback port and three new
project-scoped volumes. It verifies container labels and volume prefixes before
SQL fixture changes. No development topology, equipment or records are changed.
It records exact generated bag IDs/tags, route steps, equipment/command failure
codes, observations and histories in a printed temporary
`results.json`, with Compose and service logs. It stops only its own services and
retains all test volumes/journals. It never deletes or resets them.

The existing flight `FL-204-2030-01-15` is used with unique `DOCKER-*` tags and
idempotency keys. The ordinary seed check-in window was already opened from 2020
by V3; no flight-eligibility bypass or demo clock is added to the deployed app.

| Scenario | Exact controlled input / action | Required observations and decision |
|---|---|---|
| Blocked candidate with available alternative | Fresh seed, belt-blocked BLOCKED, belt-west AVAILABLE; check in LHR bag | Persisted decision excludes edge-checkin-blocked with EQUIPMENT_NOT_AVAILABLE; route selects edge-checkin-west → edge-west-lhr, cost 12 |
| Safe no-route | Stop only test simulator; set test DB belt-west BLOCKED while belt-blocked remains BLOCKED; check in a new LHR bag | Durable 202, asynchronous ROUTING_BLOCKED, canonical checkin-main and **zero movement commands**; LAX paths do not reach LHR |
| Correct cause, explicit routing retry | Set only test DB belt-west AVAILABLE; POST existing `/api/bags/{id}/retry` with a new key, then start test simulator | New valid plan and accepted west/final scans; same key returns original accepted retry result |
| Failure after confirmed intermediate movement | Stop simulator; new LHR bag; run existing SimulatorService SUCCESS once for its real first command through Kafka and the same journal; wait for accepted scanner-west observation | Confirmed node becomes junction-west, next diverter command is queued; no final arrival yet |
| Active equipment movement failure | Start normal Kafka simulator with existing MOVEMENT_FAILURE mode | Completed first command replays saved reports; active diverter fails with SIMULATED_MOVEMENT_FAILURE, **no final scan**, canonical node stays junction-west |
| Explicit movement recovery | Recreate simulator in SUCCESS mode with the same journal; verify bag remains failed before retry; POST guarded keyed retry | New route starts at junction-west, one diverter step to makeup-lhr; original history/commands retained; correct scanner-lhr required for AT_MAKEUP; replayed retry key is stable |
| Uncertain movement after process death | Stop simulator; new bag/command; fsync IN_PROGRESS using existing CommandJournal, then one-off process `os._exit(0)`; restart normal simulator | SIMULATOR_EXECUTION_UNCERTAIN, checkin-main, zero observations, one old command, no automatic replacement/replay; only explicit retry creates a new route/command and reaches makeup via two accepted scans |
| Completed-command redelivery | Republish original command ID/key/payload through Kafka, wait until simulator group offset advances | Exact journal SHA-256 unchanged; no new intent/simulation record; bag version/projection unchanged; existing Python counter-based replay regressions additionally verify one execution |
| Whole-stack restart | Stop and start the same project's services with all named volumes intact | Same durable bag projections/history, same journal SHA-256, persisted Kafka groups/topics and safe readiness after restart |

The first-step one-off process is test infrastructure, not another recovery API:
the normal simulator is stopped, the real generated command is supplied, the
existing core and fsynced journal run unchanged, and reports travel over the real
isolated Kafka broker. Main-process consumption then sees the saved command and
cannot simulate it again. Movement is not advanced by writing bag state in SQL.
The only SQL fixture mutation is belt-west availability in the isolated DB.

Every successful assertion checks a COMPLETE persisted route, one AT_MAKEUP
transition, accepted final observation on **scanner-lhr**, active replacement
route/command correlation and SUCCEEDED matching command. All commands/results
alone remain insufficient location evidence. The existing controlled-clock
`phase6MissingFinalScannerNeverFabricatesArrivalAndCanBeReplanned` regression
separately verifies a missing final scan, 30-second observation expiry and stale
superseded scan rejection; it does not depend on real-time sleeping.

## Manual UI follow-up

The user has not reported manual blocked-belt acceptance. For a disposable full
stack only, observe the automated fixture records after starting its retained
project with the recorded port/configuration, or reproduce the controlled fixture
in another isolated project. Inspect the route exclusion, failure code, commands,
accepted scanner observations and history. Review recovery and explicitly confirm
it after correcting the cause. Never modify the real development topology merely
to force this case, and never replay an uncertain movement directly.

## Executed results — 2026-11-06

**PASS — automated Linux/amd64 integration.** Command:
`python3 scripts/test-docker.py`, exit 0. Project:
`vortex-docker-test-0e273feb9d7b`, test URL `http://127.0.0.1:57009`.
Complete JSON records and service/Compose logs:
`/tmp/vortex-docker-test-0e273feb9d7b-hgxt67b4`.

| Exact bag tag / ID | Observed failure before explicit action | Final persisted result |
|---|---|---|
| DOCKER-SUCCESS-f7a429e45e / a046ac20-92e5-4544-ad10-817096bab3ed | None; blocked cheaper edge excluded | AT_MAKEUP; source checkin-main, west/LHR route cost 12 |
| DOCKER-NO-ROUTE-2c95f046a2 / 2794850d-70d4-4dcf-8c5b-5feabfd287b4 | ROUTING_BLOCKED / NO_ROUTE at checkin-main, zero commands | After belt-west correction + keyed retry: AT_MAKEUP, source checkin-main, cost 12 |
| DOCKER-ACTIVE-FAILURE-4c47c21b83 / 9eb819a8-db20-4acd-8ea0-50d619f520f9 | MOVEMENT_FAILED / SIMULATED_MOVEMENT_FAILURE at junction-west | After mode correction + keyed retry: AT_MAKEUP, replacement source junction-west, one diverter step, cost 8 |
| DOCKER-UNCERTAIN-2e21fdab90 / 22414bd4-79f2-46f7-8982-24b011da9ac6 | MOVEMENT_FAILED / SIMULATOR_EXECUTION_UNCERTAIN at checkin-main; zero observations, one old command | Explicit retry only: AT_MAKEUP, new route from checkin-main, cost 12 |

All four journeys retained exactly two accepted observations overall:
**junction-west / scanner-west**, then **makeup-lhr / scanner-lhr**. Every final
observation matched the current route and a SUCCEEDED command. Each bag had one
AT_MAKEUP transition; no failed/uncertain result supplied scanner evidence.

The first route's actual persisted decision was:

```json
{"tieBreak":"LEXICOGRAPHIC_EDGE_ID_SEQUENCE","failureCode":null,"excludedEdges":[{"edgeId":"edge-checkin-blocked","reasonCode":"EQUIPMENT_NOT_AVAILABLE"}],"validationIssues":[]}
```

Duplicate command `ddc0c773-9ba1-4595-bcf3-9afbf0a589d1` was delivered through
the actual Kafka command topic and its consumer offset committed. Journal SHA-256
before/after was identically
`6ce5b2e19a32ea7933dd2bc5669607d9eb85fba764cbf01992011b6086696553`;
the bag projection/version stayed identical. Before/after whole-stack restart,
all four complete bag histories/projections matched and the 20-line journal
remained `be9ee2c974ac18db85c231d323e3f59de2d66269d8a655d6df63ad79b537d00a`.

Supplemental existing regressions were actually rerun: all **71** enabled
PostgreSQL/Kafka cases passed, including conveyor `belt-west` and diverter
`diverter-lhr` failure/recovery parameters, missing-final-scanner expiry/stale
rejection and all eight abrupt JVM checkpoints. All **58** Python cases passed,
including counter-based no-repeat movement assertions and real manual quarantine
redrive. The frontend's **37** tests retain mandatory confirmation/idempotent
recovery behavior. These are automated checks, not manual UI sign-off.

No development graph, equipment status, application rows or local simulator
journal was changed. Only the isolated project's belt-west status was toggled;
it ended AVAILABLE, while belt-blocked remained BLOCKED. Failed initial probe/
orchestration runs are retained in DOCKER_DEPLOYMENT.md rather than counted as
passes. In the delayed-resume draft, the existing 30-second deadline correctly
failed a bag at checkin-main; the fixture was corrected without weakening the
timeout or scanner guards.

Manual blocked-belt browser acceptance, Windows/macOS/ARM64 execution and real
equipment behavior remain **NOT VERIFIED**.

## Reverification using the single-file full profile — 2026-11-06

The same real workflow cases were rerun with `compose.yaml` and explicit
`--profile full`, after adding host-development listeners to the shared Kafka
service. **PASS, exit 0**, fresh project `vortex-docker-test-2db8e9ce06c0`, actual
frontend URL `http://127.0.0.1:8088`. Evidence:
`/tmp/vortex-docker-test-2db8e9ce06c0-uckxdx7p/{results.json,compose.log,services.log}`
and `/tmp/vortex-profile-full.log`. These results verify the merged configuration;
the earlier two-file records above remain historical.

| New bag tag / ID | Observed result |
|---|---|
| DOCKER-SUCCESS-653c0dda52 / 763e08dc-2bc9-4587-8783-466cd3f4b023 | Blocked cheaper edge excluded; west/LHR cost 12; AT_MAKEUP |
| DOCKER-NO-ROUTE-d8cb116268 / 773a4b1f-f25a-4e44-9168-6f93e074d0e5 | ROUTING_BLOCKED / NO_ROUTE at checkin-main, zero commands; cause correction + explicit keyed retry → cost-12 AT_MAKEUP |
| DOCKER-ACTIVE-FAILURE-cefff6bef3 / f447e530-2232-4c80-8ccd-e0622d260ea5 | MOVEMENT_FAILED / SIMULATED_MOVEMENT_FAILURE at accepted junction-west; corrected mode + explicit retry → route from junction-west, cost 8, AT_MAKEUP |
| DOCKER-UNCERTAIN-07db0e7592 / 5a3aef0e-e2b9-4099-ad12-793b2a25e76e | SIMULATOR_EXECUTION_UNCERTAIN at checkin-main, zero observations before explicit retry; replacement route cost 12 → AT_MAKEUP |

Every completed bag again had accepted scanner-west then scanner-lhr evidence,
one final transition, matching successful final command/current route and retained
history. Native STOMP live/final updates through Nginx passed. Duplicate command
`1e3e2e21-5fbf-41bc-8d7b-826f8a0223e3` left the journal exactly unchanged at
`3dd4f336d5527d315e045698a6316146f14510f23c447240d7d832d5f21447b0`.
After the complete stack stopped/restarted, all four bag projections/histories
and the 20-line journal remained identical (SHA-256
`c87846a4a2679aa25d7f0a56e05cd318fd263e67b54a7116f75a2b9e2a8cf9ca`).

Default-only mode was independently verified in another project; only PostgreSQL
and Kafka ran, host ports/protocols and the existing developer topic script worked,
and test SQL/Kafka data survived container replacement. The Java host Kafka smoke
also passed freshly. See DOCKER_DEPLOYMENT for artifacts and commands.

No existing development topology, application rows, Kafka state or host journal
was changed. Legacy storage/journal migration before switching an existing project
is explicitly documented; it was not performed. All Dockerfiles, scanner guards
and recovery logic remain unchanged. Manual UI acceptance remains NOT VERIFIED.
