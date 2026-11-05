# VORTEX Phase 5 Compliance Audit

> **Final assessment (2026-11-05): PASS.** All mandatory Phase 5 requirements are satisfied. Browser scenarios A–G are PASS based on **user-reported manual acceptance**, not agent browser testing. See [§16](#16-final-phase-5-sign-off--2026-11-05) for final checks, the 62-row matrix and nonblocking limitations. Earlier audit/remediation verdicts remain historical. GitHub publication/remote equality is a separate verification step.

Audit date: 2026-11-05. Scope: current working tree, including the uncommitted Phase 5 implementation and CORS correction. Audit-only: no production source, permanent tests, or authoritative documents changed. No commit or push.

## 1. Executive summary

**Verdict: FAIL — Phase 5 is substantially implemented, but not fully compliant.** One confirmed High finding violates the explicit requirement to retain a recovery request's idempotency key across uncertain HTTP failures. A background bag-version update causes the unresolved key to be replaced. One additional Medium presentation defect leaves “REST synced” visible after REST refresh failure. Neither finding establishes fabricated scanner arrival, an unsafe replay of an old equipment command, or loss of PostgreSQL integrity.

The existing suites passed when rerun: backend 70 passed / 44 gated skips in the default test run, full isolated PostgreSQL/Kafka integration 60 passed / zero skips, simulator 50 passed including its Kafka contract, frontend 20 passed, TypeScript/build passed, and a separately enabled Java broker smoke passed. The build reused the just-tested outputs; it is not counted as another 70 executions. Two **additional temporary audit probes** failed and establish the new findings. Passing historical test counts do not negate those failures.

Actual browser automation was attempted but the browser access host returned “The browser access host is unavailable” with no browser surfaces. Browser scenarios A–F are **NOT VERIFIED**, even where HTTP/STOMP/component evidence exists. This audit does not convert jsdom or Java WebSocket tests into browser evidence.

### Method and requirement authority

All six authoritative documents were read completely, along with README, PHASE5_RESULTS, relevant source, and the frontend/API/workflow/Kafka tests. PHASE5_RESULTS and previous completion messages were treated as claims to investigate, not evidence. Quotes in the matrix are exact document excerpts (line wrapping normalized); overlapping requirements are cross-referenced rather than inventing extra functionality.

- **Mandatory**: Phase 5 deliverables/acceptance/tests and preserved architecture/business contracts.
- **Documented Phase 5 contract**: concrete list/summary/live-feed behavior now specified in the authoritative architecture/data model.
- **Conditional/recommended**: “where practical,” proposed structure, optional generated client, and recommended exact runtime pin are distinguished from feature mandates.
- Responsive layout/accessibility/data-freshness checks requested by this audit are assessed separately where no primary document makes them a distinct Phase 5 deliverable.
- Phase 0–4 requirements are dependencies checked for preservation, not newly required Phase 5 features. Phase 6 restart/outage harness work, catalog/topology editing, AODB/DCS/PLC/physical scanners, authentication, cancellation, public hosting, and new failure injection remain excluded.

Evidence convention: paths are repository-relative and `:line`/`:start–end` give the inspected locations. `backend/src/main/java/com/vortex/` is the Java composition/module root; no classes are inferred from the implementation report. Test identifiers below map to exact test names in §10.

## 2. Phase 5 requirements traceability matrix

| ID | Requirement class | Exact documented requirement/excerpt | Source document and section | Actual implementation | Evidence paths / relevant lines | Test coverage and audit result | Status | Gap |
|---|---|---|---|---|---|---|---|---|
| P5-A01 | Mandatory dependency | “Keep all Java business modules in one deployable Spring Boot application.” | `ARCHITECTURE.md:13` · Architecture decisions | Spring Boot composition; typed module APIs | backend/src/main/java/com/vortex/VortexApplication.java; settings.gradle.kts; backend/build.gradle.kts:16–27 | B-boundary PASS; backend compile PASS | PASS | — |
| P5-A02 | Mandatory | “Browser only calls REST and STOMP, never PostgreSQL/Kafka.” | `IMPLEMENTATION_PLAN.md:155` · Phase 5 — React operations UI and STOMP | api.request; createStompClient | frontend/src/api/client.ts:24–91; frontend/src/realtime/feed.ts:141–150 | F-api, F-app PASS; dependency/source inspection | PASS | — |
| P5-A03 | Mandatory dependency | “PostgreSQL owns all durable business state. Kafka is transport, not the authoritative bag history.” | `ARCHITECTURE.md:15` · Architecture decisions | JdbcBagRepository/JdbcRouteRepository/JdbcMovementRepository | backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:21–58; backend/src/main/java/com/vortex/movement/persistence/JdbcMovementRepository.java:12–19 | B-reads, B-rest, B-live PASS | PASS | — |
| P5-A04 | Mandatory | “The frontend owns presentation state only. It refetches REST after reconnect and never treats STOMP as durable history.” | `PROJECT_STRUCTURE.md:145` · Frontend structure | useOperations; LiveFeed.resync | frontend/src/app/useOperations.ts:34–105; frontend/src/realtime/feed.ts:92–129 | F-feed-2/3/4, F-app-1 PASS | PASS | — |
| P5-A05 | Mandatory dependency | “A Java module must not write another module's tables directly.” | `ARCHITECTURE.md:83` · Module responsibilities, interfaces, dependencies, and ownership | Owner services and API boundaries | backend/src/main/java/com/vortex/bagstate/application/PersistentBagLifecycle.java:41–117; movement/application/MovementService.java:25–31; routing/application/PersistentRoutePlanning.java:61–76 | B-boundary PASS; ownership inspection | PASS | — |
| P5-A06 | Mandatory dependency | “The separate Python simulator consumes Kafka commands and emits results/observations/status. It cannot connect to PostgreSQL or write authoritative bag state.” | `DECISIONS_AND_OPEN_QUESTIONS.md:12` · Decisions fixed by the requirements | Python simulator service/Kafka adapter; Java report handlers | simulator/src/vortex_simulator/service.py; kafka_adapter.py; movement.py:25–98; backend/src/main/java/com/vortex/movement/application/MovementService.java:90–173 | B-live PASS (actual Python process); P-contract PASS; source/dependency inspection | PASS | — |
| P5-A07 | Mandatory dependency | “Route only through the configured directed graph, using deterministic Dijkstra shortest path over positive edge costs and currently available equipment.” | `ARCHITECTURE.md:18` · Architecture decisions | PersistentRoutePlanning; DeterministicRoutePlanner | backend/src/main/java/com/vortex/routing/application/PersistentRoutePlanning.java:25–38; routing/domain/DijkstraPathPlanner.java | B-routing unit suite, B-no-route PASS | PASS | — |
| P5-A08 | Mandatory scope | “The demo is local and has no authentication/authorization. It must not be exposed as an internet-facing service.” | `DECISIONS_AND_OPEN_QUESTIONS.md:27` · Selected design assumptions | Loopback services and bounded origin settings | backend/src/main/resources/application.yaml:70–72; compose.yaml:8–9,22–23; frontend/package.json scripts.dev | Compose health/port inspection; CORS tests PASS. No public hosting/auth added | PASS | — |
| P5-F01 | Mandatory deliverable | “Dashboard for check-in, flight selection, bag search, state/timeline/route, equipment status, failures, and retry action.” | `IMPLEMENTATION_PLAN.md:150` · Phase 5 — React operations UI and STOMP | App; CheckIn; BagDetails; RouteProgress | frontend/src/app/App.tsx:103–316; features/checkin/CheckIn.tsx:32–108; features/bags/BagDetails.tsx:26–268 | F-app, F-details, F-route PASS for baseline coverage; recovery exception tracked P5-R02 | PASS | — |
| P5-F02 | Mandatory deliverable excerpt | “check-in, flight selection” | `IMPLEMENTATION_PLAN.md:150` · Phase 5 — React operations UI and STOMP | CheckIn form loads catalog, submits selected flight/tag | frontend/src/features/checkin/CheckIn.tsx:15–31,41–105 | F-app-4 PASS; B-live cancelled/eligible HTTP paths PASS | PASS | — |
| P5-F03 | Mandatory deliverable excerpt | “bag search” | `IMPLEMENTATION_PLAN.md:150` · Phase 5 — React operations UI and STOMP | App debounced search; api.bags; SQL literal search | frontend/src/app/App.tsx:15–21,123–150; api/client.ts:46–50; backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:39–48 | F-app-1, F-api-2, B-reads PASS | PASS | — |
| P5-F04 | Mandatory deliverable excerpt | “state/timeline/route” | `IMPLEMENTATION_PLAN.md:150` · Phase 5 — React operations UI and STOMP | Canonical state, full timeline, route sequence/steps | frontend/src/features/bags/BagDetails.tsx:28–59,133–265; features/bags/RouteProgress.tsx:35–110 | F-details-1, F-route, B-rest PASS | PASS | — |
| P5-F05 | Mandatory deliverable excerpt | “equipment status, failures, and retry action” | `IMPLEMENTATION_PLAN.md:150` · Phase 5 — React operations UI and STOMP | Equipment cards, failure banner, confirmed retry | frontend/src/app/App.tsx:292–315; components/Status.tsx:2–5,19–25; features/bags/BagDetails.tsx:61–131 | F-details failures/confirmation PASS; B-equipment/B-live PASS; R02 exception | PASS | — |
| P5-F06 | Documented Phase 5 contract | “Summary is `{total, active, completed, failed}` over all bags;” | `ARCHITECTURE.md:222` · REST and WebSocket boundaries | App metrics populated from api.summary | frontend/src/app/App.tsx:26–31,83–101; backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:51–58 | B-reads verifies count deltas/completion; F-app initial metrics PASS | PASS | — |
| P5-F07 | Documented Phase 5 contract | “`confirmedSteps` uses the canonical confirmed node within the current route, never command status.” | `ARCHITECTURE.md:221` · REST and WebSocket boundaries | JourneyController.list; routeNodes | backend/src/main/java/com/vortex/api/rest/JourneyController.java:50–54; frontend/src/features/bags/RouteProgress.tsx:4–33 | B-reads; F-route-1/2/3 PASS | PASS | — |
| P5-F08 | Mandatory dependency | “a route, a command, command acceptance, movement result, and scanner observation as separate records and facts.” | `ARCHITECTURE.md:19` · Architecture decisions | Separate route, commands, observations, progress and bag DTOs; explanatory text | frontend/src/features/bags/BagDetails.tsx:133–232; types/contracts.ts:44–124; backend/src/main/java/com/vortex/movement/persistence/JdbcMovementRepository.java:12–19 | B-happy; F-route-1/2 PASS. Command status is not arrival | PASS | — |
| P5-F09 | Mandatory dependency | “Unknown or ineligible flight” | `ARCHITECTURE.md:274` · PostgreSQL/Kafka consistency, idempotency, and errors | Failure state/code/reason shown from BagView; unknown flight accepted by backend | frontend/src/features/bags/BagDetails.tsx:61–67; app/App.tsx:217–225; backend/src/main/java/com/vortex/flight/application/PersistentFlightVerification.java:47–54 | B-unknown/cancelled, B-reads UNKNOWN_FLIGHT assertion; F-details failure states PASS | PASS | — |
| P5-F10 | Mandatory dependency | “Out-of-order or wrong-step observation” | `ARCHITECTURE.md:281` · PostgreSQL/Kafka consistency, idempotency, and errors | Rejected scanner decision and history diagnostics are visible | frontend/src/features/bags/BagDetails.tsx:198–265; backend/src/main/java/com/vortex/movement/application/MovementService.java:132–156 | B-wrong-scan (10 cases), F-route rejects wrong-route/rejected evidence PASS | PASS | — |
| P5-F11 | Mandatory | “API errors render safe actionable messages; bag route/history are queryable independently of WebSocket.” | `IMPLEMENTATION_PLAN.md:156` · Phase 5 — React operations UI and STOMP | ApiError/errorMessage/ErrorNotice; REST load works without socket | frontend/src/api/client.ts:34–41,93–99; components/Status.tsx:28–39; app/useOperations.ts:79–97,112–115 | F-app-3, F-api-1, B-errors, B-rest PASS. Freshness label defect is separate M01 | PASS | — |
| P5-F12 | Mandatory test criterion | “Component tests for state/timeline, loading/error/retry rendering, and API mapping.” | `IMPLEMENTATION_PLAN.md:160` · Phase 5 — React operations UI and STOMP | Five Vitest/Testing Library test files | frontend/tests/{app.test.tsx,details.test.tsx,route.test.tsx,feed.test.ts,api.test.ts}; frontend/vitest.config.ts | 20 existing tests PASS; two additional audit probes FAIL (P5-R02 and quality finding M01) | PASS | — |
| P5-REST01 | Mandatory contract | “Return 202 only after bag, initial history, idempotency row, and outbox records commit.” | `ARCHITECTURE.md:193` · REST and WebSocket boundaries | CheckInService transactional acceptance | backend/src/main/java/com/vortex/checkin/application/CheckInService.java:29–54; api/rest/JourneyController.java:33–40; bagstate/application/PersistentBagLifecycle.java:41–55 | B-atomic, B-rest, B-live PASS | PASS | — |
| P5-REST02 | Mandatory contract | “Repeating the same key/body returns the same bag and correlation IDs; reusing it with a different body returns 409.” | `EVENT_CATALOG.md:170` · Idempotency and transaction boundaries | CheckInService key/hash replay; frontend keyed POST | backend/src/main/java/com/vortex/checkin/application/CheckInService.java:35–46; frontend/src/features/checkin/CheckIn.tsx:46–56; api/client.ts:80–85 | B-atomic/B-rest conflict and duplicate-tag assertions PASS; frontend key generation/source verified | PASS | — |
| P5-REST03 | Mandatory contract | “bagTag is independently unique.” | `EVENT_CATALOG.md:170` · Idempotency and transaction boundaries | Unique database bag tag and locked duplicate check | backend/src/main/java/com/vortex/checkin/application/CheckInService.java:44–46; backend/src/main/resources/db/migration/V1__create_vortex_schema.sql | B-atomic/B-rest DUPLICATE_BAG_TAG PASS | PASS | — |
| P5-REST04 | Mandatory contract | “GET /api/bags/{bagId}” | `ARCHITECTURE.md:180` · REST and WebSocket boundaries | BagView current state/node/failure/version DTO | backend/src/main/java/com/vortex/api/rest/JourneyController.java:61–65,101–104; bagstate/persistence/JdbcBagRepository.java:21–23,61–70 | B-rest/B-live, F-api/F-details PASS | PASS | — |
| P5-REST05 | Mandatory contract | “Return records oldest first with pagination after the initial demo grows.” | `ARCHITECTURE.md:195` · REST and WebSocket boundaries | History query ordered by durable history_sequence | backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:26–32; api/rest/JourneyController.java:63–65; frontend/src/features/bags/BagDetails.tsx:234–265 | B-rest/B-happy; F-details-1 PASS; history pagination explicitly deferred | PASS | — |
| P5-REST06 | Mandatory contract | “GET /api/bags/{bagId}/route” | `ARCHITECTURE.md:182` · REST and WebSocket boundaries | RouteView/StepView; only ROUTE_NOT_FOUND becomes absent route in client | backend/src/main/java/com/vortex/api/rest/JourneyController.java:67–72,106–108; frontend/src/api/client.ts:60–68 | B-rest, F-api-1/3, F-route PASS | PASS | — |
| P5-REST07 | Mandatory contract | “It returns eligible and ineligible fixture flights so the UI can demonstrate both outcomes.” | `ARCHITECTURE.md:197` · REST and WebSocket boundaries | Flights catalog API and CheckIn dropdown (no eligibility filter in UI) | backend/src/main/java/com/vortex/api/rest/JourneyController.java:89–93; frontend/src/features/checkin/CheckIn.tsx:78–95 | B-rest (3 catalog flights), B-live cancelled flight, F-app-4 PASS | PASS | — |
| P5-REST08 | Mandatory contract | “GET /api/equipment returns” | `ARCHITECTURE.md:198` · REST and WebSocket boundaries | EquipmentQuery API; cards preserve backend ID/type/status/time | backend/src/main/java/com/vortex/api/rest/JourneyController.java:95–99; frontend/src/app/App.tsx:292–315 | B-rest/B-equipment; broadcaster equipment branch unit PASS | PASS | — |
| P5-REST09 | Documented Phase 5 contract | “Page is zero-based; size defaults to 25 and must be 1–100.” | `ARCHITECTURE.md:219` · REST and WebSocket boundaries | Controller bounds and SQL LIMIT/OFFSET; UI previous/next | backend/src/main/java/com/vortex/api/rest/JourneyController.java:42–54; bagstate/persistence/JdbcBagRepository.java:39–48; frontend/src/app/App.tsx:240–263 | B-filters, B-reads pagination, F-api-2 PASS. Explicit UI Next-click test desirable | PASS | — |
| P5-REST10 | Documented Phase 5 contract | “`search` is a case-insensitive literal substring of bag tag, flight identifier or bag UUID (max 128 characters).” | `ARCHITECTURE.md:217` · REST and WebSocket boundaries | Parameterized strpos/lower filters; 128-character validation | backend/src/main/java/com/vortex/api/rest/JourneyController.java:47–49; bagstate/persistence/JdbcBagRepository.java:39–48 | B-reads tests UUID/tag lower-case and literal %; B-filters PASS. Flight predicate verified in SQL; dedicated substring case advisable | PASS | — |
| P5-REST11 | Documented Phase 5 contract | “`state` is one implemented bag state; omitted means all.” | `ARCHITECTURE.md:219` · REST and WebSocket boundaries | BagState.valueOf; optional SQL state predicate | backend/src/main/java/com/vortex/api/rest/JourneyController.java:44–49; bagstate/persistence/JdbcBagRepository.java:43; frontend/src/types/contracts.ts:1–12 | B-filters rejects CANCELLED/invalid; B-reads tests AT_MAKEUP/flight failure PASS | PASS | — |
| P5-REST12 | Documented Phase 5 contract | “Ordering is updatedAt descending, then bag UUID.” | `ARCHITECTURE.md:220` · REST and WebSocket boundaries | Deterministic paginated SQL ordering | backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:46–48 | B-reads nonoverlapping pages PASS; SQL source directly confirms tie-break | PASS | — |
| P5-REST13 | Documented Phase 5 contract | “completed means AT_MAKEUP; failed means FLIGHT_VERIFICATION_FAILED, ROUTING_BLOCKED or MOVEMENT_FAILED; active is the remainder.” | `ARCHITECTURE.md:223` · REST and WebSocket boundaries | Single aggregate count query; list filters do not enter summary query | backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java:51–58; frontend/src/api/client.ts:51 | B-reads actual count deltas/completion PASS; SQL verifies all three failure categories | PASS | — |
| P5-REST14 | Documented Phase 5 contract | “Page count and rows use a read-only repeatable-read transaction;” | `DATA_MODEL.md:221` · Phase 5 read projections | PersistentBagLifecycle.list transaction; RouteQuery composition at API | backend/src/main/java/com/vortex/bagstate/application/PersistentBagLifecycle.java:36–39; api/rest/JourneyController.java:50–54 | B-reads and B-boundary PASS; transaction annotation inspection | PASS | — |
| P5-REST15 | Mandatory contract | “Return 400 for malformed JSON, invalid fields, or invalid filters; 404 for unknown bag/equipment; 409 for duplicate bagTag, idempotency-key/body conflict, or an operation invalid for the current state; 503 when PostgreSQL is unavailable.” | `ARCHITECTURE.md:201` · REST and WebSocket boundaries | ApiExceptionHandler; DTO/body/filter validation | backend/src/main/java/com/vortex/api/error/ApiExceptionHandler.java:16–35; api/rest/JourneyController.java:47–48,85–87; frontend/src/api/client.ts:34–41,93–99 | B-errors/B-filters/B-rest PASS; errors preserve safe code/message/correlation ID | PASS | — |
| P5-W01 | Mandatory | “STOMP endpoint: /ws. UI subscribes to /topic/bags/{bagId} for bag state and journey updates and /topic/equipment for equipment status.” | `ARCHITECTURE.md:205` · REST and WebSocket boundaries | WebSocketConfiguration; LiveFeed.subscribe | backend/src/main/java/com/vortex/api/websocket/WebSocketConfiguration.java:15–20; frontend/src/realtime/feed.ts:92–106 | F-feed-2, B-broadcast, B-live actual bag subscriptions PASS | PASS | — |
| P5-W02 | Mandatory | “The UI group independently receives the same committed facts and emits only BagStateChanged and EquipmentStatusChanged over STOMP.” | `EVENT_CATALOG.md:35` · Selected topics | CommittedUpdateListener independent Kafka group and two-event dispatch | backend/src/main/java/com/vortex/api/websocket/CommittedUpdateListener.java:18–26; backend/src/main/resources/application.yaml:55–64 | B-broadcast both mappings/ignore-root; B-live independent UI group PASS | PASS | — |
| P5-W03 | Mandatory | “STOMP notifications are produced only from committed event records;” | `IMPLEMENTATION_PLAN.md:157` · Phase 5 — React operations UI and STOMP | Transactional state/outbox writes; committed relay; validated broadcaster | backend/src/main/java/com/vortex/bagstate/application/PersistentBagLifecycle.java:41–55,74–83,113–117; messaging/outbox/OutboxWriter.java:32–49; messaging/outbox/OutboxRelay.java:48–68 | B-atomic rollback; messaging persistence rollback; B-live actual committed final envelope PASS | PASS | — |
| P5-W04 | Mandatory contract | “forwards its unchanged JSON wire envelope” | `EVENT_CATALOG.md:238` · Phase 5 browser adapter | EventEnvelopeJsonCodec serialization plus string STOMP message | backend/src/main/java/com/vortex/api/websocket/CommittedUpdateListener.java:20–26; messaging/envelope/EventEnvelopeJsonCodec.java:19–38 | B-broadcast/B-codec; B-live verifies eventType/bagId/node/version PASS | PASS | — |
| P5-W05 | Mandatory | “Reconnect logic must refetch the bag, route, and history through REST before trusting the live stream.” | `ARCHITECTURE.md:205` · REST and WebSocket boundaries | LiveFeed.resync awaits useOperations.refresh/api.journey; generation guard | frontend/src/realtime/feed.ts:108–129; app/useOperations.ts:34–105; api/client.ts:56–78 | F-feed-2/3/4, F-app-1 PASS with fake transport; real-browser reconnect NOT VERIFIED (T03) | PASS | — |
| P5-W06 | Mandatory contract | “The browser rejects duplicate IDs and non-increasing bag state versions, then refetches REST instead of applying partial event payloads to bag projections.” | `EVENT_CATALOG.md:244` · Phase 5 browser adapter | EventGate per-bag watermarks/event IDs; invalidation-only callbacks | frontend/src/realtime/feed.ts:6–39,98–104; app/useOperations.ts:34–105 | F-feed-1/2, F-app-1/2 PASS; equipment stale facts only cause current REST reads, never patch status | PASS | — |
| P5-W07 | Documented Phase 5 contract | “serialized refreshes, subscription cleanup and REST resync on reconnect.” | `ARCHITECTURE.md:212` · REST and WebSocket boundaries | Refresh queue/abort; unsubscribe/deactivate/timer cleanup | frontend/src/app/useOperations.ts:34–105,116–132; realtime/feed.ts:69–72,92–94,131–138 | F-app-2 and F-feed-2/3/4 PASS; cleanup source inspection | PASS | — |
| P5-W08 | Documented Phase 5 contract | “Heartbeats detect lost connections.” | `ARCHITECTURE.md:210` · REST and WebSocket boundaries | Negotiated heartbeat configuration and reconnect delay | backend/src/main/java/com/vortex/api/websocket/WebSocketConfiguration.java:19; frontend/src/realtime/feed.ts:144–149 | B-live connects with real broker; F-feed close handling PASS; black-hole/heartbeat timing NOT VERIFIED | PARTIAL | No actual browser/black-hole heartbeat loss scenario executed |
| P5-W09 | Mandatory contract | “Clients cannot publish to broker topics.” | `ARCHITECTURE.md:210` · REST and WebSocket boundaries | Client inbound interceptor rejects all STOMP SEND frames | backend/src/main/java/com/vortex/api/websocket/WebSocketConfiguration.java:22–33 | Implementation compiled/inspected; existing live test does not send forbidden SEND frames | PARTIAL | Add wire-level SEND rejection regression; no observed bypass |
| P5-W10 | Documented Phase 5 contract | “A 10-second REST refresh discovers new/off-page bags and command/observation-only diagnostics without inventing a global topic or new workflow event.” | `ARCHITECTURE.md:212` · REST and WebSocket boundaries | useOperations interval; subscriptions limited to visible/selected IDs | frontend/src/app/useOperations.ts:112–115,133–141; api/client.ts:56–74 | F-app initial/query mapping PASS; interval source verified; comprehensive fake-timer discovery test desirable | PASS | — |
| P5-R01 | Mandatory | “Retry requires operator confirmation” | `DECISIONS_AND_OPEN_QUESTIONS.md:78` · Phase 5 implementation notes | Inline nonmodal confirmation; no API call until Confirm retry | frontend/src/features/bags/BagDetails.tsx:68–125 | F-details confirmation test PASS; backend accepts only guarded API | PASS | — |
| P5-R02 | Mandatory | “retains its idempotency key across uncertain HTTP failures;” | `DECISIONS_AND_OPEN_QUESTIONS.md:79` · Phase 5 implementation notes | BagDetails retryKey is coupled to mutable bag.stateVersion | frontend/src/features/bags/BagDetails.tsx:25,102–116; backend/src/main/java/com/vortex/messaging/workflow/RetryBagService.java:34–43 | Existing fixed-version uncertainty test PASS; additional AUD-01 FAIL | FAIL | H01: polling/STOMP version advance replaces unresolved request key, allowing a new recovery attempt instead of replay |
| P5-R03 | Mandatory contract | “a positively acknowledged request ends that attempt, allowing a later explicitly confirmed retry even if a blocked state's version remains unchanged.” | `DECISIONS_AND_OPEN_QUESTIONS.md:80` · Phase 5 implementation notes | retryKey cleared only on resolved acceptance; new explicit confirmation | frontend/src/features/bags/BagDetails.tsx:108–114 | F-details later explicitly confirmed blocked recovery test PASS | PASS | — |
| P5-R04 | Mandatory dependency | “uncertain movement is never automatically replayed” | `DECISIONS_AND_OPEN_QUESTIONS.md:25` · Selected design assumptions | No frontend equipment-command API; backend supersede/replan; simulator journal | frontend/src/api/client.ts:80–91; backend/src/main/java/com/vortex/messaging/workflow/RetryBagService.java:41–58; movement/application/MovementService.java:230–234; simulator/src/vortex_simulator/service.py | B-movement-recovery (both failures), B-no-path-retry; P-contract PASS. H01 does not demonstrate replay of an old command ID | PASS | — |
| P5-R05 | Mandatory contract | “Reject retries when the bag is not in a failure state. A retry is idempotent by header key.” | `ARCHITECTURE.md:203` · REST and WebSocket boundaries | RetryBagService state/pending-workflow fence and replay | backend/src/main/java/com/vortex/messaging/workflow/RetryBagService.java:29–61; api/rest/JourneyController.java:85–87 | B-flight-retry/B-movement-recovery; B-live completed bag 409 and cancelled failure retry 202 PASS (backend contract) | PASS | — |
| P5-S01 | Mandatory dependency | “Only an accepted scanner observation can change the bag's confirmed node.” | `ARCHITECTURE.md:19` · Architecture decisions | MovementService match/observation acceptance; BagState policy sole writer | backend/src/main/java/com/vortex/movement/application/MovementService.java:90–173; bagstate/domain/BagStateTransitionPolicy.java:34–43,54–74 | B-happy verifies ACCEPTED/STARTED/SUCCEEDED cannot move node; F-route-1/2; wrong-scan cases PASS | PASS | — |
| P5-S02 | Mandatory dependency | “Only an accepted observation from the route's final makeup scanner can set AT_MAKEUP.” | `ARCHITECTURE.md:19` · Architecture decisions | Active route/step/command, SUCCEEDED, node/scanner/time/destination guards | backend/src/main/java/com/vortex/movement/application/MovementService.java:141–169,176–185; routing/persistence/JdbcRouteRepository.java:22–28 | B-final-guard, B-wrong-scan, B-happy/final replay; B-live PASS | PASS | — |
| P5-S03 | Mandatory dependency | “Flight existence and eligibility are checked asynchronously” | `ARCHITECTURE.md:189` · REST and WebSocket boundaries | Durable check-in followed by Kafka flight-verification handler | backend/src/main/java/com/vortex/checkin/application/CheckInService.java:29–54; flight/application/PersistentFlightVerification.java:26–55 | B-unknown/cancelled; B-live 202 CHECKED_IN then FLIGHT_NOT_ELIGIBLE PASS | PASS | — |
| P5-C01 | Documented local contract | “REST/STOMP allow http://localhost:5173 and http://127.0.0.1:5173” | `ARCHITECTURE.md:317` · Local deployment | Shared exact local origins; legacy settings additive, wildcard rejected | backend/src/main/java/com/vortex/api/LocalDevelopmentOrigins.java:10–16; api/rest/RestConfiguration.java:11–16; api/websocket/WebSocketConfiguration.java:14–16 | B-origins/B-cors localOrigin tests and B-live both origins PASS | PASS | — |
| P5-C02 | Mandatory scope | “restrict local CORS to the UI origin” | `ARCHITECTURE.md:322` · Local deployment | Allowed methods GET/POST and two request headers; no wildcard | backend/src/main/java/com/vortex/api/rest/RestConfiguration.java:14–16; api/LocalDevelopmentOrigins.java:13–16; frontend/vite.config.ts:8–10 | B-cors unrelated origins/method/header rejection; B-origins wildcard validation PASS | PASS | — |
| P5-C03 | Mandatory transport dependency | “The browser uses REST and WebSocket/STOMP.” | `ARCHITECTURE.md:7` · Goals and non-goals | Origin-preserving Vite proxy with explicit upstream Host rewrite | frontend/vite.config.ts:6–11; backend/src/main/java/com/vortex/api/websocket/WebSocketConfiguration.java:15–20 | B-live eligible/cancelled POST/read/retry through actual Vite; direct and proxied STOMP for both origins; foreign origins rejected PASS | PASS | — |
| P5-T01 | Mandatory | “Typed REST client and STOMP client subscriptions.” | `IMPLEMENTATION_PLAN.md:151` · Phase 5 — React operations UI and STOMP | Strict TypeScript contracts/API/feed/components | frontend/tsconfig.json:2–34; frontend/src/types/contracts.ts; api/client.ts; realtime/feed.ts | Fresh typecheck and production build PASS; 20 component/client tests PASS | PASS | — |
| P5-T02 | Conditional documented test | “WebSocket update mapping integration test where practical.” | `IMPLEMENTATION_PLAN.md:161` · Phase 5 — React operations UI and STOMP | Actual Kafka/UI listener/simple broker/STOMP client integration | backend/src/test/java/com/vortex/workflow/Phase4KafkaIntegrationTest.java:168–202; backend/src/test/java/com/vortex/api/websocket/CommittedUpdateListenerTest.java | B-live and B-broadcast PASS. Final committed event is replayed, not observed continuously during movement | PASS | — |
| P5-T03 | Mandatory verification criterion | “Browser smoke test for check-in, live progress, disconnect/refetch, and final state display.” | `IMPLEMENTATION_PLAN.md:162` · Phase 5 — React operations UI and STOMP | No real-browser test executed or browser E2E test found; jsdom and Java protocol clients are distinct | frontend/tests/app.test.tsx:77–242; backend/src/test/java/com/vortex/workflow/Phase4KafkaIntegrationTest.java:168–202 | Actual browser inventory attempted: browser host unavailable | NOT VERIFIED | V01: scenarios A–F and actual browser reconnect/rendering remain unverified |
| P5-T04 | Recommended runtime pin | “Node.js \| 22.23.3 LTS” | `IMPLEMENTATION_PLAN.md:19` · Recommended versions for the first implementation | Runtime baseline versus available Node 24.19.0 | frontend/package.json engines >=22.12.0; audit node --version v24.19.0 | Typecheck/tests/build PASS on 24.19.0 only | NOT VERIFIED | V02: exact recommended Node 22.23.3 pair not rerun; not treated as a missing feature |
| P5-T05 | Structure recommendation | “app: router, application shell, state providers, error boundary.” | `PROJECT_STRUCTURE.md:138` · Frontend structure | Single-page App shell; local hook state; no router/provider/error-boundary component found | frontend/src/app/App.tsx; app/useOperations.ts; main.tsx | Static source inspection | PARTIAL | L01: proposed structure mentions error boundary, absent. Separate router/provider not needed by present single-screen behavior; not a new mandatory feature |
| P5-T06 | Mandatory completion gate | “A feature is done only when code, migrations if required, tests, API/event documentation, and local instructions agree; relevant tests pass; failure handling and idempotency are demonstrated;” | `IMPLEMENTATION_PLAN.md:214` · Definition of done | Cross-document/source/test reconciliation | This report H01/M01/V01; frontend/src/features/bags/BagDetails.tsx:102–116 | Existing suites PASS but new uncertainty probe FAIL and browser check NOT VERIFIED | FAIL | Mandatory recovery claim is incorrect in an executed edge case; unconditional completion cannot be certified |

Matrix totals: **PASS: 55**, **PARTIAL: 3**, **FAIL: 2**, **NOT VERIFIED: 2** (62 rows). Rows overlap in scope; these are not distinct test counts. T06 is the overall gate and repeats the consequence of R02, not a second implementation defect.

## 3. Architecture compliance

**Compliant at the reviewed module/integration boundaries.** The root Gradle settings retain one deployable backend. `VortexApplication` is the Spring composition root; routing, flight, movement, bag state and messaging collaborate through their typed APIs. `ModuleBoundaryTest.crossModuleImportsRespectOwnershipAndBusinessModulesDoNotUseKafkaClients` passed. The added API read projections stay in the owning bag module; route data is obtained through `RouteQuery`, not through an API-layer import of another module's repository.

PostgreSQL remains the query/state source. `CheckInService`, `PersistentBagLifecycle`, `MovementService` and `RetryBagService` retain transactional durable writes, locks/fences and outbox writes. `OutboxRelay` only publishes claimed committed rows; Kafka group offsets are acknowledged after listener return. The UI consumer performs no business write or processed-event insert. It uses a separate configured UI group, validates event envelopes, and forwards only the two documented state/status fact types.

Java still plans deterministic routes; Python still simulates edge commands and reports observations without a PostgreSQL client/configuration dependency. The enabled live integration launched the real Python process, traversed HTTP → PostgreSQL/outbox → Kafka → Python → report handler, and reached final scanner-confirmed arrival. The simulator's existing uncertain-command journal/replay contract also passed. No new migrations, graph, catalog management service, auth system, direct browser database/Kafka access or Phase 6 harness was found in the Phase 5 change.

The `git diff` for `JourneyController` adds list/summary methods and records; the existing Phase 4 endpoint implementations/DTOs remain intact. No Flyway migration is changed by Phase 5. This is contract/regression evidence, not a claim that every possible Phase 4 workload was retested.

## 4. Frontend compliance

| Audit item | Observed implementation and verification | Assessment |
|---|---|---|
| Real metrics and bag records | `App.tsx:26–31,86–101,175–230` renders API values. `useOperations.ts:79–97` fetches actual REST resources. SQL summary is unfiltered. Production `frontend/src` contains no sample bag/flight records or fake response provider. Fixtures/mocks are confined to tests. | PASS |
| Search/state filtering/pagination | Debounced tag/flight/UUID search, exact state selector, Previous/Next controls and API page parameters; database filtering and LIMIT/OFFSET. Existing search/filter/API/persistence tests passed. | PASS, with extra UI page-navigation cases recommended |
| Details | Bag UUID/tag, flight identifier, state, confirmed node, destination, route steps, equipment/scanner IDs, commands from old/current routes, accepted/rejected observations, progress/deadlines and ordered history are rendered. Flight selection displays carrier/number/origin/destination/date/status. The primary documents do not mandate an additional detailed flight-management screen or free-text unknown-flight field. | PASS within documented fields |
| Distinct evidence concepts | Command, observation and route tables are separate. “A successful command awaits scanner confirmation” is explicit. `routeNodes` ignores command statuses and checks accepted observations on the current route. Check-in is initial known location; current location comes from BagView. | PASS |
| Failure visibility | Three implemented failed/blocked bag states, actual failure code/reason, command failures and rejected scan codes are rendered. There is no invented CANCELLED baggage state. | PASS |
| Check-in and recovery | Keyed check-in, catalog selection, operator confirmation, pending/error/acceptance feedback exist. Recovery's unresolved key handling is incorrect after a background version update. | PARTIAL; H01 |
| Loading/empty/errors | Baggage/equipment/details/catalog loaders, table/catalog/equipment/history/diagnostic empty states, safe API messages and “Try again” paths exist. Existing loading/error/empty component tests passed. A later REST failure does not downgrade the “REST synced” header. | PARTIAL; M01 |
| Responsive behavior | `styles.css:503–566` has 1000/600px layout breakpoints; `styles.css` uses horizontal table/route overflow. | Static support present; visual/mobile behavior NOT VERIFIED |
| Accessible controls | Labels, semantic tables/headings, button controls, status/alert roles, focus-visible styles, and a labelled confirmation dialog exist. It is explicitly nonmodal (`aria-modal=false`). | Static/component evidence only; actual keyboard, screen reader and viewport interaction NOT VERIFIED |
| TypeScript/reuse | Strict compiler settings include source/tests/config. Shared typed client/contracts/status/time/error components and a lifecycle hook are used; no added global state framework. | PASS typecheck/build |
| Proposed app structure | App shell exists; no router/provider is needed for current single-screen behavior. No React error boundary was found. | Low recommendation gap L01, not an invented mandatory screen/framework |

Neither responsiveness nor a particular global state framework is promoted to a new mandatory Phase 5 deliverable merely because PHASE5_RESULTS described it. Browser rendering, continuous visible movement and reconnect appearance are not established by the jsdom tests.

## 5. REST API compliance

| Operation | Verified behavior |
|---|---|
| POST `/api/bags/check-in` | Required Idempotency-Key, validated `{bagTag,flightId}`, server-generated bag UUID and configured source node. Returns durable 202/CHECKED_IN before asynchronous verification. Concurrent same-key replay returns stable IDs; changed-body key and duplicate tag return safe 409. Tests also inject rollback before commit. |
| GET `/api/bags` | Default page 0/size 25; size 1–100, nonnegative page, max 128-character search, implemented BagState only. Literal case-insensitive predicates cover tag/flight/UUID; order is updated_at descending and UUID. Rows/count share repeatable-read; route summaries use RouteQuery and omit a changed/mismatched route until refresh. |
| GET `/api/bags/summary` | Single PostgreSQL aggregate: completed AT_MAKEUP; failed the three supported failure states; active total minus completed/failed. No search/state filters affect global counts. Integration asserts count deltas, failure category and final completion. |
| GET bag/history/route/movement | Existing DTO/resource conventions preserved. History uses history_sequence. Route is 404/ROUTE_NOT_FOUND until persisted. Movement diagnostics retain the existing snake_case keys and all-attempt records. Client handles only ROUTE_NOT_FOUND as a missing route, rather than hiding other 404/503 errors. |
| GET flights/equipment | Existing filters/catalog items and equipment shapes retained. Eligible and ineligible flights are returned; the UI does not filter away cancelled flights. |
| POST retry | Empty-object body and Idempotency-Key required. Backend validates failed state, pending workflow fence, bag path/key hash; known accepted keys replay their stored response. Completed/nonretryable bags return 409. Backend behavior passed; the frontend's selection of an existing/new key has H01. |
| Errors | Existing code/message/correlationId envelope; safe 400/404/409/503 and generic unexpected errors. `ApiError` and `ErrorNotice` display backend messages/codes/correlation IDs rather than SQL/stack details. CORS rejection occurs before the controller and remains an intentional plain-text Spring 403 for unrelated origins. |

New APIs are additive. No replacement check-in/recovery endpoint or changed Phase 4 payload was introduced. The list's per-row route queries and full unpaginated history/movement reads are local-demo scaling limits, not a documented Phase 5 missing feature: the data model explicitly defers history pagination until growth. Separate summary/list/detail requests need not share one transaction; the docs specify eventual refresh for their different commit instants. The journey client checks bag version/route before and after assembling independent projections and retries changes up to three times.

## 6. WebSocket/STOMP compliance

- Native `/ws`, `/topic/bags/{bagId}` and `/topic/equipment` are implemented. The simple broker is ephemeral, not a durable history store.
- `CommittedUpdateListener` validates Kafka key/envelope and uses the existing wire codec. Event IDs/types/versions/correlation/history references are preserved. The UI group is independent of business workflow handling. Unit mapping covers both bag and equipment events and ignores other facts.
- Actual committed bag-state delivery was verified by the real Kafka/UI consumer/Spring broker/Java STOMP client test. The test subscribes after arrival and replays the already committed final outbox envelope until subscription is installed. It verifies the received event type, bag ID, confirmed node and state version. **It does not prove the React dashboard continuously received every intermediate transition during movement.**
- The frontend subscribes to visible and selected bag IDs plus equipment, deduplicates event IDs and rejects non-increasing per-bag versions. Events invalidate REST rather than directly modifying location/status/history. Old equipment notifications only trigger current REST reads, so lack of an equipment version watermark cannot directly roll status backward.
- REST refreshes are serialized/coalesced; old query/selection results are discarded. `api.journey` reloads bag/history/route/movement and checks version/route consistency. Duplicate events do not append UI history or cause writes.
- On close, the feed becomes offline, and on connect it subscribes and awaits authoritative refresh before becoming live. Failed reconnect resync retries; generation/unmount guards prevent a late reconnect read marking a closed feed live. These paths are verified with fake transports/component tests; a real browser reconnect remains NOT VERIFIED.
- Stop/unmount cleans the STOMP client, current subscriptions, retry/refresh timers and query abort controller. The bounded event caches are intentional: evicted very old duplicates may cause an extra REST refresh but cannot patch stale state.
- Heartbeats and reconnection delays are configured at both ends. Actual network black-hole/heartbeat timeout detection was not exercised; a wire-level forbidden SEND regression is also missing. The inbound interceptor rejects SEND by inspection; no bypass was observed.
- The 10-second REST refresh is documented and necessary for new/off-page bags and command-only/rejected-observation changes that do not emit BagStateChanged. It is not a hidden global event destination or a Kafka query projection.

The “REST synced” claim after a subsequent failed refresh is M01. It does not invalidate the successful initial/reconnect resync tests, but the operator-facing freshness signal is inaccurate on that later path.

## 7. State and route correctness

The following distinctions were checked separately:

| Fact | Backend effect | Dashboard representation / verification |
|---|---|---|
| Command issued | Stores durable request; MOVEMENT_PENDING | Command status is visible; initial location/checkpoint count remains unchanged. |
| Command accepted | Updates acknowledgement; no confirmed-node change | Command table shows ACCEPTED; no scanner progress inferred. |
| Command started | May enter IN_TRANSIT | State may change but confirmed location remains last accepted node. |
| Command succeeded | Marks command SUCCEEDED and WAITING_FOR_OBSERVATION; starts separate observation deadline | Command success is shown separately; route progress does not advance from this alone. |
| Scanner observation received | Validated against bag/correlation/active route/command/step/equipment/scanner/node/time | Observation decision is visible; rejected observations/history cannot establish arrival. |
| Scanner observation accepted | Atomic observation, bag transition, route progress and next command | Current node comes from persisted BagView; accepted observations establish completed route nodes. |
| Final accepted makeup observation | Requires active succeeded command and final node/scanner/destination guards; AT_MAKEUP and COMPLETE route | Final bag state/destination displayed from backend; completed route evidence is available. |

`MovementService.java:132–173,176–202` and `JdbcRouteRepository.finalMakeup` enforce these guards. `BagStateTransitionPolicy` is the sole transition policy, and `PersistentBagLifecycle` is the canonical bag writer. Tests cover accepted results without location movement; wrong scanner/node/route/step/correlation/cause/equipment/time/unknown command; late results; command and observation deadlines; final guard mismatch; final observation replay producing a single AT_MAKEUP transition. The real Kafka/Python journey also asserts two accepted observations, two commands, destination makeup and durable COMPLETE/AT_MAKEUP.

No Critical scanner/location-integrity defect was established. The audit is not a safety certification or an exhaustive Phase 6 restart/recovery review. The existing success-rendering fixture does not include a full final-observation history and therefore should not be mistaken for a golden end-to-end final-route visualization test; that additional test is recommended.

## 8. Recovery and failure visibility

The dashboard displays FLIGHT_VERIFICATION_FAILED, ROUTING_BLOCKED and MOVEMENT_FAILED without inventing a baggage cancellation state. It displays the backend flight/routing/equipment/command/observation failure code/reason, route decision details and rejected scanner diagnostics. The backend tests cover UNKNOWN_FLIGHT, cancelled/ineligible flights, unavailable route/equipment, rejected/uncertain commands, COMMAND_TIMEOUT and OBSERVATION_TIMEOUT. All three bag-failure categories are rendered by component tests, although those tests reuse one example code/reason rather than verifying every semantic failure combination.

Recovery requires Review recovery → Confirm retry. The UI explicitly warns that the cause must first be corrected; a confirmation calls only POST retry with `{}` and Idempotency-Key. It never sends equipment movement commands. The backend stage-specific recovery fences, supersession and replan-from-last-confirmed-node rules remain intact and tested. Known accepted keys replay their original response even after bag state changes, which is exactly why H01 must preserve an uncertain key instead of deriving a new one from the current bag version.

A successful acknowledgement clears the attempt key, allowing a later deliberate recovery when routing remains blocked at the same version; the existing regression for that behavior passes. A failed/uncertain response at an unchanged version also reuses the key; that existing test passes. **The untested combination of uncertain response plus a background version advance fails**, as documented in H01 below. This is an operator/API idempotency defect; it is not evidence that the backend automatically executes an old uncertain command again.

## 9. CORS regression verification

| Path / rule | Evidence and audit result |
|---|---|
| `http://localhost:5173` and `http://127.0.0.1:5173` | Both explicit origins are always present in LocalDevelopmentOrigins; existing single-origin settings add rather than remove them. Two-origin controller regressions passed. |
| REST POST through Vite | Actual Vite config loaded by the isolated live integration; eligible check-in returns 202. Cancelled check-in returns 202/CHECKED_IN, then asynchronously becomes FLIGHT_VERIFICATION_FAILED / FLIGHT_NOT_ELIGIBLE. |
| Recovery/reads through Vite | Both origins can read final bag state; completed-bag retry reaches the backend and returns BAG_NOT_RETRYABLE/409. Failed cancelled-flight retry is accepted and leaves one durable RETRY_REQUEST. |
| Direct and proxied STOMP | Each local Origin connects on both paths and receives an actual committed final-state envelope. |
| Unsupported Origin | Controller GET/POST/preflight rejects unrelated/other-port/https/opaque `null` origins; live foreign-Origin POST is 403. Direct and proxied foreign-Origin WebSocket handshakes both return 403. |
| Unsupported method/header | Direct Spring preflight DELETE and X-Unapproved header regression returns 403. Vite may itself answer browser preflights before forwarding; the Spring tests separately verify the backend restriction. |
| Wildcards / security assumptions | Shared origin helper rejects `*` and wildcard patterns. No Origin stripping, rewriteWsOrigin, disabled CORS, or new anonymous public deployment is used. GET/POST, approved headers and read-only STOMP interceptor remain unchanged. |

The earlier Spring rejection described in PHASE5_RESULTS was **not** rerun against reverted production source during this audit. Current behavior was independently verified using current tests/source. The inspected Vite implementation installs CORS before its proxy; that explains why a Vite allow-origin header could coexist with an upstream Spring 403. Existing Vite REST and WebSocket targets now explicitly rewrite Host while preserving Origin.

## 10. Test execution results

All results below were produced during this audit, not copied from PHASE5_RESULTS. Logs/artifacts are local `/tmp/vortex-audit-*`; their permanence is limited to the current environment. Existing suites and the failing audit probes are reported separately.

| Executed command | Passed | Failed | Skipped | Result / limits |
|---|---:|---:|---:|---|
| `./gradlew --rerun-tasks :backend:test` | 70 | 0 | 44 | Fresh execution. Default gating skips DB/Kafka integration entries and opt-in broker smoke. `/tmp/vortex-audit-backend-test.log`; XML copied to `/tmp/vortex-audit-unit-results`. |
| `./gradlew :backend:build` | — | 0 | — | BUILD SUCCESSFUL, tasks UP-TO-DATE after the fresh test run. Reuses the preceding 70/44 results; **not** counted as another test execution. `/tmp/vortex-audit-backend-build.log`. |
| `./scripts/test-database.sh --with-kafka` | 60 | 0 | 0 | Separate isolated PostgreSQL/Kafka suite. `/tmp/vortex-audit-integration.log`; XML `/tmp/vortex-audit-integration-results`. |
| `VORTEX_KAFKA_SMOKE_TEST=true ./gradlew :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest` | 1 | 0 | 0 | Separately executes the opt-in smoke skipped in default run. `/tmp/vortex-audit-smoke.log`. |
| `npm --prefix frontend run typecheck` | check passed | 0 | 0 | Strict checks include tests/config as well as source. |
| `npm --prefix frontend run test` | 20 | 0 | 0 | Five existing Vitest files. |
| `npm --prefix frontend run build` | build passed | 0 | 0 | TypeScript and Vite production bundle passed. |
| `VORTEX_SIMULATOR_KAFKA_TEST=true PYTHONPATH=simulator/src simulator/.venv/bin/python -m pytest -q simulator/tests` | 50 | 0 | 0 | Includes real Python Kafka/restart/quarantine contract. `/tmp/vortex-audit-simulator.log`. |
| `npm --prefix frontend run test -- tests/audit-temporary.test.tsx` | 0 | **2** | 0 | Temporary extra audit probes; H01 and M01 reproduced. `/tmp/vortex-audit-edge-tests.log`. Test artifact moved out of the repository to `/tmp/vortex-audit-edge-tests.test.tsx` afterward. |
| Browser scenarios A–F | 0 verified | — | — | **Blocked / NOT VERIFIED**: actual browser host unavailable. No browser pass claimed. |
| Exact recommended Node 22.23.3 | — | — | — | NOT VERIFIED; installed Node is 24.19.0. |
| `git diff --check` | check passed | 0 | 0 | Whitespace check, not a behavioral test. |

The 44 default gated entries are **not** counted as passes. The enabled integration suite expands some parameterized entries differently, so its 60 cases are not a one-for-one arithmetic conversion of the 43 default integration skips. The remaining opt-in smoke was run separately. Do not sum overlapping invocation totals as unique requirements covered.

Environment recorded: OpenJDK 21.0.12.1, Node 24.19.0/npm 11.17.0, Python 3.13.16, healthy existing PostgreSQL 17.11 and Kafka 4.3.1 Compose services. No dependency upgrade was performed. Java/Spring-managed pins and locked frontend/simulator versions were inspected. Tests use `vortex_test_*` temporary databases, diagnostic topics and temporary simulator journals/Vite processes. No application records were deliberately created or altered by this audit.

### Exact test references used in the matrix

| ID | Test file and exact test name(s) | Result in this audit |
|---|---|---|
| F-app-1 | `frontend/tests/app.test.tsx` — `loads initial data under StrictMode, searches, filters, selects a journey and handles live/reconnect REST refresh` | PASS |
| F-app-2 | Same file — `discards a slow old journey response after the operator selects a different bag` | PASS |
| F-app-3 | Same file — `shows loading, API errors, retry controls and empty results` | PASS |
| F-app-4 | Same file — `checks in against real API contract and opens the accepted journey` | PASS with mocked API, not actual browser HTTP |
| F-details-1 | `frontend/tests/details.test.tsx` — `shows a successful bag, assigned destination and durable timeline` | PASS |
| F-details failures | Same file — `displays meaningful %s failure details` (three existing states) | 3 PASS |
| F-details confirmation | Same file — `requires explicit confirmation, preserves the key on an uncertain retry, and reports acceptance` | PASS only for unchanged bag version |
| F-details later recovery | Same file — `allows a later explicitly confirmed recovery when a blocked state version is unchanged` | PASS |
| F-route-1/2/3 | `frontend/tests/route.test.tsx` — `does not advance location after a successful equipment command`; `uses only accepted observations on the current route`; `distinguishes the failed pending step from the confirmed location` | 3 PASS |
| F-api-1/2/3 | `frontend/tests/api.test.ts` — `retains movement snake_case and treats only ROUTE_NOT_FOUND as an absent route`; `encodes search/filter pagination and preserves the retry key after uncertain responses`; `retries a read straddling state versions` | 3 PASS; API key test verifies the passed key, not version-change UI behavior |
| F-feed-1 | `frontend/tests/feed.test.ts` — `rejects duplicate, stale, malformed, unsupported and mismatched events independently per bag` | PASS |
| F-feed-2 | Same file — `subscribes to visible/selected bags and equipment, resyncs before live, and cleans up` | PASS |
| F-feed-3 | Same file — `retries a failed reconnect resync and stops its retry timer on unmount` | PASS |
| F-feed-4 | Same file — `does not mark a closed or unmounted feed live when a late REST resync completes` | PASS |
| B-boundary | `backend/src/test/java/com/vortex/workflow/ModuleBoundaryTest.java` — `crossModuleImportsRespectOwnershipAndBusinessModulesDoNotUseKafkaClients` | PASS |
| B-routing | `backend/src/test/java/com/vortex/routing/DeterministicRoutePlannerTest.java` — deterministic planner unit suite | PASS |
| B-reads | `backend/src/test/java/com/vortex/workflow/Phase4WorkflowIntegrationTest.java` — `operationsReadsArePaginatedSearchableAndScannerConfirmed` | PASS |
| B-atomic | Same class — `durableAcceptanceAndConcurrentIdempotencyAreAtomic` | PASS |
| B-happy | Same class — `happyJourneyRequiresEachScannerAndFinalReplayIsANoop` | PASS |
| B-unknown/cancelled | Same class — `unknownAndCancelledFlightsFailAsynchronously` (UNKNOWN and existing cancelled flight) | 2 PASS |
| B-no-route | Same class — `noRouteIsVisibleAndOnlyExplicitRetryPlansAfterCorrection` | PASS |
| B-movement-recovery | Same class — `rejectedOrUncertainMovementRequiresReplacementFromLastConfirmedNode` (REJECTED/FAILED) | 2 PASS |
| B-no-path-retry | Same class — `movementRetryWithUnavailableRouteBecomesRoutingBlockedWithoutReplayingCommand` | PASS |
| B-wrong-scan | Same class — `wrongAndOutOfOrderObservationsAreDurablyRejected` (ten mismatch cases) | 10 PASS |
| B-final-guard | Same class — `finalGuardUsesVerifiedDestinationAndConfiguredMakeupScanner` | PASS |
| B-flight-retry | Same class — `explicitFlightRetryIsFencedAndRetryKeyIncludesBagPath` | PASS |
| B-equipment | Same class — `equipmentReportsAreCanonicalOrderedAndUnknownEquipmentIsRejected` | PASS |
| B-rest | Same class — `restQueriesAndResponsesMatchTheDurableJourney` | PASS |
| B-errors | `backend/src/test/java/com/vortex/api/JourneyControllerTest.java` — `missingHeaderMalformedJsonAndInvalidPathReturnSafe400`, `retryRequiresEmptyObjectAndIdempotencyHeader`, `missingResourcesReturn404`, `idempotencyConflictReturnsSafe409`, `databaseOutageReturns503WithoutDatabaseDetails`, `unexpectedFailureDoesNotExposeSqlOrStack` | PASS |
| B-filters | Same controller test — `listValidatesFiltersAndPagination` | PASS |
| B-cors | Same controller test — `localBrowserOriginReachesCheckIn`, `localOriginsCanPreflightReadAndReachTheGuardedRecoveryHandler`, `unrelatedOriginsCannotReadWriteOrPreflight`, `localOriginsStillCannotPreflightUnsupportedMethodsOrHeaders` | PASS (parameterized origins included) |
| B-origins | `backend/src/test/java/com/vortex/api/LocalDevelopmentOriginsTest.java` — `bothRequiredOriginsRemainWhenExistingSingleOriginSettingsArePresent`; `unrestrictedOrPatternOriginsAreNotAccepted` | 2 PASS |
| B-broadcast | `backend/src/test/java/com/vortex/api/websocket/CommittedUpdateListenerTest.java` — `onlyCommittedStateAndEquipmentFactsAreForwardedWithTheUnchangedWireEnvelope`; `invalidMessageKeysCannotReachTheBrowser` | 2 PASS |
| B-codec | `backend/src/test/java/com/vortex/messaging/EventEnvelopeJsonCodecTest.java` | PASS |
| B-live | `backend/src/test/java/com/vortex/workflow/Phase4KafkaIntegrationTest.java` — `realRestOutboxKafkaPythonJourneyAndFinalReplay` plus its direct/proxy Origin, cancelled/retry, STOMP and final replay helpers | PASS, real protocol clients/Vite/Kafka/Python |
| P-contract | `simulator/tests/test_kafka_contract.py` — `test_exact_broker_command_reports_restart_duplicate_uncertainty_and_quarantine` | PASS |
| AUD-01 / AUD-02 | Temporary test artifact, exact names begin `AUD-01 retains the uncertain recovery key after a background REST stateVersion advance` and `AUD-02 does not claim REST synced after a connected feed refresh fails` | **Both FAIL**; see §12 |

Enabled integration breakdown: Phase3Persistence 6, PersistenceFoundation 5, Phase3Kafka 12, Phase4Workflow 34, Phase4Kafka 1, KafkaTopicPolicy 1 and Phase4Migration 1 = 60 passed. This confirms both new reads/origins and existing workflow/persistence/messaging regressions; it is not a continuous browser test.

## 11. Manual acceptance results and exact remaining steps

**Actual browser result for every scenario below: NOT VERIFIED.** Browser discovery was attempted during this audit and returned no browsers plus the host-unavailable error. No existing verified bag was reused for a mutating browser action. The protocol/component evidence in this table is explicitly separate.

Preparation for a later browser run, from the repository root, in separate terminals:

```bash
./scripts/dev-start.sh
./gradlew :backend:bootRun
```

```bash
npm --prefix frontend run dev
```

```bash
VORTEX_SIMULATOR_DELAY_SECONDS=2 PYTHONPATH=simulator/src simulator/.venv/bin/python -m vortex_simulator.main
```

Use the installed locked simulator environment as documented in README; do not remove its journal or run a second simulator writer. Open `http://127.0.0.1:5173`; repeat the Origin checks at `http://localhost:5173`. Start DevTools Network/WS recording. Use a fresh tag such as `AUDIT-A-<UTC timestamp>-<random suffix>` for every new bag. The UI generates fresh idempotency keys; inspect that header rather than reusing a prior record's key.

| Scenario | Exact browser acceptance procedure | Nonbrowser evidence already obtained | Browser status |
|---|---|---|---|
| A — successful journey | Open Check in baggage; enter a unique tag; select existing `FL-204-2030-01-15`; submit once. Record Network POST 202/CHECKED_IN and returned bagId. Select that tag. During movement inspect `/ws` frames and the detail route/history; finish at AT_MAKEUP / makeup-lhr, COMPLETE route and accepted final scanner. Compare GET bag/history/route/movement responses. | B-live actual full journey; B-happy/F-route/ F-details | NOT VERIFIED |
| B — cancelled flight | New unique tag; select existing `FL-999-2030-01-15`; submit. Record initial HTTP 202/CHECKED_IN, then visible FLIGHT_VERIFICATION_FAILED and backend code FLIGHT_NOT_ELIGIBLE. Check that no route/movement is fabricated. | B-live cancelled path and B-unknown/cancelled | NOT VERIFIED |
| C — unknown flight | The documented dropdown selects catalog flights; free-text unknown-flight entry is not a mandated UI feature. In the **local page's** DevTools console, issue the fetch below, record 202/bagId, then search the unique tag in the dashboard. Confirm FLIGHT_VERIFICATION_FAILED / UNKNOWN_FLIGHT and no movement. | B-unknown/cancelled and B-reads UNKNOWN_FLIGHT | NOT VERIFIED |
| D — live consistency | With the existing two-second simulator delay, select the new A journey while moving. Observe BagStateChanged frames for the correct bag, subsequent REST reloads, command SUCCEEDED versus accepted scanner observations, and checkpoint progression. No node may advance solely on command success. Compare final history and route to REST. Equipment status cards must equal GET equipment; existing startup reports may already be settled. | F-app mock live invalidation; F-route; B-live final committed notification | NOT VERIFIED |
| E — recovery safety | On a newly failed audit journey, click Review recovery. Cancel first and confirm no POST retry occurred. Only after correcting its cause through the already supported local developer configuration, confirm once and inspect keyed POST retry `{}` and asynchronous feedback. On the completed new A bag there is no recovery control; an explicitly keyed API retry must return BAG_NOT_RETRYABLE/409. To reproduce H01, lose/delay the HTTP response of an accepted retry while allowing subsequent authoritative version refresh, then inspect that re-confirmation currently sends a different key (expected same unresolved key). Do not alter existing verified journeys. | Existing confirmation/guard/replan tests PASS; AUD-01 FAIL | NOT VERIFIED |
| F — reconnect | While the new A journey is in flight, set DevTools Network Offline to close the socket, observe offline status, and restore Online while backend/simulator continue running. Confirm automatic STOMP reconnect, “Synchronizing” status, REST bag/route/history reloads before “Live,” and final state matching REST. Separately test socket-only blocking/re-enable `/ws` to verify REST refresh remains available. | F-feed/F-app simulated lifecycle PASS; no real-browser disconnect/refetch | NOT VERIFIED |

Local console request for C (unique values; no existing data overwritten):

```javascript
const suffix = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`;
const tag = `AUDIT-UNKNOWN-${suffix}`;
const response = await fetch('/api/bags/check-in', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `audit-${crypto.randomUUID()}` },
  body: JSON.stringify({ bagTag: tag, flightId: `NO-SUCH-FLIGHT-${suffix}` })
});
console.log(response.status, await response.json(), tag);
```

Also perform narrow/wide viewport checks, keyboard focus/confirmation traversal and screen-reader status checks. Static CSS/ARIA inspection is not a substitute. The delay option and failure behavior referenced here already exist; the audit did not add a fault mechanism or Phase 6 harness.

## 12. Missing/partial requirements and findings

Severity definitions follow the audit request. Findings distinguish executed defects, missing coverage and recommendations; source-backed concerns without reproduction are not promoted to confirmed defects.

### H01 — High — unresolved recovery attempt changes idempotency key after background version advance (confirmed)

1. **Requirement:** P5-R02; `DECISIONS_AND_OPEN_QUESTIONS.md:78–81`, Phase 5 implementation notes: retry requires confirmation and “retains its idempotency key across uncertain HTTP failures.” Related REST/data-model rules require a same-key replay to return the original accepted request.
2. **Observed:** AUD-01 rendered a failed bag at version 5, made Confirm retry reject as “HTTP response lost,” rerendered the same selected bag at version 7 (representing accepted retry + another failed verification observed through REST), then re-confirmed. The second call used a different UUID key. The documented same-key expectation failed. `/tmp/vortex-audit-edge-tests.log` records both actual UUIDs.
3. **Expected:** A lost/uncertain response must leave that attempt's key intact regardless of refreshed bag version, so the next transmission reconciles/replays the original operation. A new attempt is allowed after a known acknowledgement and another explicit confirmation.
4. **Established root cause:** `BagDetails.tsx:102–106` regenerates the key whenever `retryKey.current.version !== bag.stateVersion`. State version is mutable backend state, not request identity. `useOperations` updates the still-mounted, same-keyed BagDetails during ordinary polling/STOMP REST refresh (`App.tsx:275–280`), so this is a realistic path, not an artificial remount requirement.
5. **Impact:** If the first request committed and failed again before the next confirmation, the new key can be accepted as a second recovery workflow rather than replaying the original response. It can add another verification/replan/route attempt and confuse operator feedback. Backend state/route fences still apply; this test does **not** demonstrate unsafe old-command replay or false scanner arrival.
6. **Minimal recommended correction:** Keep one unresolved attempt identity independent of bag.stateVersion. Generate only when no attempt exists; clear after a positive acknowledgement or explicit authoritative reconciliation. Retain the already-correct ability to request a later new attempt after acknowledgement when a blocked state's version is unchanged. Do not weaken backend retry fences.
7. **Required regression:** Reject/lose first HTTP response, advance selected bag's stateVersion while preserving component instance, retry, assert identical Idempotency-Key and a single backend RETRY_REQUEST/route attempt for that key. Also retain the existing unchanged-version uncertainty test and acknowledged-blocked-new-attempt test. Consider pending dialog/state changes and navigation separately; the audit did not prove every remount path.

### M01 — Medium — “REST synced” remains after later REST refresh failure (confirmed presentation defect)

1. **Reference:** P5-F11 and the documented meaningful failure/REST-resync behavior; the exact “REST synced” freshness claim is an implemented UI assertion, not a separately invented feature requirement.
2. **Observed:** AUD-02 connected/resynced the feed, then made a subsequent summary refresh fail with a database-unavailable error. The error notice rendered, but the header remained `Live feed · REST synced`. The expectation that this claim be removed failed.
3. **Expected:** Socket connection state and successful authoritative REST synchronization must be distinguished; retained data after failed refresh should not be labelled successfully synced at the current refresh.
4. **Root cause:** `App.tsx:47–48` derives both claims solely from the STOMP connection enum. `useOperations.ts:62–76,98–101` records per-resource errors but does not update a REST freshness/health signal; ordinary refresh failures are swallowed by call sites while the connection enum remains live.
5. **Impact:** Old/partial operational data can be accompanied by an inaccurate positive freshness label. The API error is visible and no state overwrite/data integrity failure was shown.
6. **Minimal correction:** Separate connected/live socket status from current REST health/freshness. Gate “REST synced” on successful required resource refresh and show retained snapshot time/staleness when any required refresh fails; restore after success.
7. **Regression:** Start connected/synced; fail selected/all/summary REST refreshes and verify both error notice and stale/not-synced label; then succeed and verify label recovery.

### V01 — Medium — required actual browser smoke not verified (environment/coverage gap)

- **Requirement:** P5-T03; implementation plan Phase 5 browser smoke and project structure's browser-level tests.
- **Observed:** Current browser discovery failed with no accessible browser. No real-browser E2E test was found among the five frontend Vitest files. Java live tests prove transport/workflow and component tests prove mocked lifecycle behavior, but neither demonstrates A–F in an actual browser.
- **Impact:** Continuous rendered progress, real browser reconnection, Origin behavior in browser networking, responsive and keyboard/screen-reader acceptance cannot be certified.
- **Correction:** Run §11 on an available browser, record evidence, then retain a small browser smoke covering check-in, committed arrival and disconnect/refetch. This is Phase 5 acceptance work, not a new Phase 6 recovery framework.
- **Required test:** Actual browser with real local backend/simulator, unique tags/keys and isolated test data; no mocked STOMP transport for the acceptance pass.

### V02 — Low — exact recommended Node baseline not rerun (verification limit)

The authoritative implementation plan recommends Node 22.23.3, while the available audit runtime is 24.19.0 and satisfies the package engine floor. Current typecheck/tests/build pass on 24.19.0. Re-run the locked install/checks on the recommended baseline before claiming that exact pairing; do not misclassify this as a missing dashboard feature or silently upgrade pins.

### V03 — Medium — specific live-edge security/loss tests absent (coverage gap; no confirmed bypass)

`WebSocketConfiguration.java:22–33` rejects inbound SEND and both ends configure heartbeats, but the existing live test does not send a forbidden broker SEND or simulate heartbeat network black-hole loss. F-feed tests cover callback-driven disconnect, resync and cleanup. Add a narrowly scoped wire-level SEND rejection test and real disconnect/heartbeat/reconnect acceptance evidence; no need to add hardware/fault-injection mechanisms. Matrix W08/W09 remain PARTIAL until then.

### L01 — Low — proposed error boundary absent (recommendation, not verdict driver)

`PROJECT_STRUCTURE.md:138` lists an error boundary among proposed app responsibilities. `main.tsx` renders StrictMode/App without one. A minimal app-level error boundary would improve unexpected render-failure containment, but a new router, provider framework, generated API client or additional screen is not required by the current Phase 5 behavior. No such framework should be added merely to match folder labels.

### Coverage improvements that are not established feature defects

Add a complete successful route fixture with both accepted scans and final makeup, UI Next/Previous behavior over multiple pages, flight-ID search and all failure-count categories, backend 409 code/correlation rendering at the form, real equipment-topic delivery through the frontend, bag route/history querying while STOMP is unavailable, and read/reconnect races during route replacement. Existing source/tests support these behaviors to varying degrees; the audit does not assert they are broken just because every combination lacks a dedicated test.

## 13. Recommended corrections and regression work

Priority order:

1. Correct H01's unresolved recovery identity handling and add the version-advance uncertainty regression. This is the mandatory defect that prevents certification.
2. Correct M01's freshness feedback and add refresh-failure/recovery assertions.
3. Execute and retain the Phase 5 browser smoke; cover W08/W09's live-edge gaps without introducing Phase 6 features.
4. Re-run the recommended runtime pairing and consider the optional app error boundary and focused coverage improvements.

No correction is implemented in this audit. The temporary test file was removed from the working tree after execution and retained at `/tmp/vortex-audit-edge-tests.test.tsx` for inspection. To reproduce the two probes in this environment, copy it to the previously absent `frontend/tests/audit-temporary.test.tsx`, run `npm --prefix frontend run test -- tests/audit-temporary.test.tsx`, then remove only that newly created temporary file. It imports the existing app/components and fixtures and mocks only API/transport boundaries; it does not alter production behavior or send business commands to existing application records.

Core reproduction for H01, suitable for a permanent regression added in a later authorized fix:

```tsx
const retry = vi.spyOn(api, 'retry')
  .mockRejectedValueOnce(new Error('HTTP response lost'))
  .mockResolvedValue({ bagId: 'bag-1', state: 'CHECKED_IN', correlationId: 'c' });
const failed = { ...journey, bag: { ...journey.bag, state: 'FLIGHT_VERIFICATION_FAILED' as const } };
const view = render(<BagDetails journey={failed} onRefresh={() => {}} />);
await user.click(screen.getByRole('button', { name: 'Review recovery' }));
await user.click(screen.getByRole('button', { name: 'Confirm retry' }));
await screen.findByText('HTTP response lost');
view.rerender(<BagDetails journey={{ ...failed,
  bag: { ...failed.bag, stateVersion: failed.bag.stateVersion + 2 }
}} onRefresh={() => {}} />);
await user.click(screen.getByRole('button', { name: 'Confirm retry' }));
await waitFor(() => expect(retry).toHaveBeenCalledTimes(2));
expect(retry.mock.calls[1][1]).toBe(retry.mock.calls[0][1]); // currently FAILS
```

As in the executed probe, import Testing Library/Vitest helpers and the existing `frontend/tests/fixtures.ts`, and initialize `user = userEvent.setup()`. The freshness probe connects the fake transport, fails a later API refresh and asserts the “REST synced” label is absent; its exact executed source/log is retained with the temporary artifact.

## 14. Final Phase 5 completion verdict

**FAIL.** Most Phase 5 functionality is present and the existing regression suites pass, including the actual local-Origin fix, committed STOMP delivery and Phase 4 invariants. However, the documented mandatory recovery-idempotency behavior fails an executed audit probe. The additional freshness defect and unverified browser acceptance must also be addressed or explicitly resolved before an unconditional completion claim.

A CONDITIONAL PASS would be appropriate only if mandatory behavior were correct and remaining items were limited to clearly bounded minor/verification gaps. H01 is a confirmed implementation error against an explicit authoritative requirement, so that verdict would overstate compliance here.

Audit integrity check: the pre-audit baseline contains 211 existing versioned/untracked nonignored files. Final hash and file-list verification after writing this report found no changes to those 211 files and exactly one repository addition, `docs/PHASE5_AUDIT.md`. The post-test database inspection found no remaining `vortex_test_*` databases. Generated build/cache outputs and temporary test artifacts are excluded from source changes. The existing uncommitted Phase 5/CORS work is left intact, not reset, committed or pushed.

## 15. Remediation and final verification — 2026-11-05

This section supersedes the current-status conclusions in the original audit. Sections 1–14, their original 62-row matrix, findings, failed probes and historical FAIL verdict remain intact as the audit trail. The six authoritative documents have not been changed during remediation. No new endpoint, event, migration, production backend change, dependency, topology change or Phase 6 feature was introduced.

### 15.1 Reproduce first, then fix

Before changing production code, permanent regressions were added for H01 (version advance/rerender and navigation/remount) and M01 (a connected feed followed by failed REST refresh). `npm --prefix frontend test -- tests/details.test.tsx tests/app.test.tsx` failed **3 tests with 10 passing** against the original implementation. H01 assertions observed different recovery UUIDs; M01 observed the unchanged `Live feed · REST synced` label. Log: `/tmp/vortex-remediation-before.log`. These were expected defect reproductions, not successful verification.

A final conservative-outcome regression also reproduced key clearing after opaque proxy responses: the focused details suite had **3 expected failures / 11 passing** for generic HTTP 400/404/409 responses (the 503 case already retained its key). Log: `/tmp/vortex-remediation-proxy-before.log`. Key clearing was then restricted to the backend's documented status/code pairs.

After the corrections, all 36 frontend tests pass, including the reproduced failures. The regression result, rather than the existence of a patch, supports closing H01/M01.

### 15.2 Findings, root causes and corrections

| Original finding | Root cause and exact correction | Regression evidence | Current status |
|---|---|---|---|
| H01 / P5-R02 — unresolved recovery key replaced on refreshed version | A component ref coupled identity to mutable `bag.stateVersion` and was lost on unmount. New `frontend/src/features/bags/recoveryRequests.ts:10` owns a page-session map keyed by bag ID; `requestRecovery:13` allocates once, retains uncertain outcomes, shares an existing in-flight promise and clears after matching acceptance or a documented API status/code rejection (400/INVALID_REQUEST, 404/BAG_NOT_FOUND, or 409/BAG_NOT_RETRYABLE/IDEMPOTENCY_KEY_CONFLICT). `BagDetails.tsx:103` invokes it only inside the existing explicit Confirm handler. No version, polling, STOMP, rerender or mount callback generates/submits a recovery. | `details.test.tsx:9,23,46,63` and `app.test.tsx:96`; existing unchanged-version uncertainty and acknowledged-blocked-new-attempt tests also pass. Backend `explicitFlightRetryIsFencedAndRetryKeyIncludesBagPath` and live completed-bag 409 pass. | **RESOLVED / PASS** for the documented operation identity and safeguards. |
| M01 — stale REST freshness claim | The header derived REST freshness from STOMP state. `useOperations.ts:26,41–105` now tracks REST loading/refreshing/synced/stale independently. Only a successful current refresh cohort sets synced and the successful-refresh timestamp; failure retains loaded data and marks stale. Abort, query/selection and mount-epoch guards discard obsolete responses; queued refreshes stay refreshing. `App.tsx:46–86` renders both signals and the last successful refresh time. `feed.ts:124` keeps a connected-but-resyncing socket separate from a disconnected socket. | `app.test.tsx:76` covers success → failure → restored success, preserved baggage and independent disconnect. `operations.test.tsx:34,56,69` covers obsolete/aborted responses, queued refreshes and unmount cleanup. Feed failed-resync test passes with syncing rather than falsely offline. | **RESOLVED / PASS**. |
| V03 / P5-W09 — client SEND rejection only inspected | No production correction was needed. `Phase4KafkaIntegrationTest.java:130–131,265–297` now connects an allowed-Origin raw STOMP client directly and through actual Vite, sends a valid SEND to `/topic/equipment`, and requires ERROR plus session close. Removing the inbound rejection would fail this check. | The real Kafka/Python/Vite integration test passed, including both new wire-level checks. | **CLOSED** for forbidden SEND verification. |
| V03 / P5-W08 — heartbeat-loss coverage | `heartbeat.test.ts:33` exercises the actual installed STOMP Client, CONNECTED frame parser, negotiated heartbeat timeout, socket close, reconnect delay, deferred REST resync and shutdown cleanup with deterministic time and an in-memory socket. Existing feed/App tests continue to verify stale/late reconnect completion and cleanup. | Passing heartbeat and reconnect tests; real direct/proxy handshake tests also pass. | **PASS** for heartbeat detection/reconnect implementation; actual browser/network black-hole acceptance remains NOT VERIFIED under V01/T03. |
| Pagination and background discovery coverage recommendation | `app.test.tsx:117` exercises Next/Previous backend page indices and boundary buttons; `app.test.tsx:131` discovers a new bag after the ten-second REST interval without any STOMP event and verifies polling ends on unmount. | Both new tests pass. | Coverage gap closed for these two cases; no new dashboard feature added. |
| V01 / P5-T03 — actual browser smoke | Browser discovery again returned no available browsers and “The browser access host is unavailable.” No real browser scenario was executed. | Protocol and jsdom coverage is recorded separately below. | **NOT VERIFIED / environment blocked**. |
| V02 / P5-T04 — exact recommended Node baseline | Available Node is 24.19.0, meeting package engine requirements; the recommended 22.23.3 baseline was not executed. No runtime/dependency pins changed. | Typecheck, 36 tests and production build pass on Node 24.19.0. | **NOT VERIFIED** for the exact recommended pairing. |
| L01 / P5-T05 — proposed error boundary absent | Original structure recommendation remains. This targeted remediation did not add an optional error boundary/router/provider. | Source inspection unchanged. | **PARTIAL**, recommendation only. |

Recovery identity is retained for the lifetime of the loaded JavaScript page, including component/App remounts and bag selection changes. A complete page reload, closed tab or browser crash is outside this in-memory lifetime; cross-reload persistence is not claimed. No unresolved request is silently expired or replaced by a bag version change. Network, timeout and server/proxy errors keep the same key; an unverified acceptance for another bag cannot acknowledge this bag's attempt. A known acknowledgement permits a later explicitly confirmed new attempt even when the blocked state version is unchanged. Existing API paths, keyed request body `{}`, backend state validation, path-bound hashes and uncertain-movement fences remain unchanged.

### 15.3 Regression coverage against the requested H01/M01 scenarios

| Scenario | Executed evidence | Result |
|---|---|---|
| H01 uncertain failure, unchanged version | Existing `requires explicit confirmation, preserves the key on an uncertain retry, and reports acceptance` | PASS |
| H01 uncertain failure, advanced version and React rerender | `H01 keeps an unresolved recovery key across background version changes and rerenders` | PASS |
| H01 uncertain failure, STOMP invalidation then REST refresh | `H01 STOMP invalidation and REST refresh preserve an uncertain recovery key without auto retry` | PASS |
| H01 selection/remount and different bags | `H01 retains per-bag keys across remounts and never mixes bags or resubmits on mount` | PASS |
| H01 remount while HTTP request is still pending | `H01 a remounted confirmation shares an in-flight request and sends no duplicate command` | PASS |
| H01 acknowledged attempt followed by separately confirmed attempt at the same blocked version | Existing `allows a later explicitly confirmed recovery when a blocked state version is unchanged` | PASS |
| H01 no automatic recovery and mandatory confirmation | Above H01 tests assert call counts before confirmation, after refresh and after mount; unchanged confirmation dialog | PASS |
| H01 definitive guarded error/new explicit attempt | `H01 a definitive guarded API rejection resolves that attempt but needs confirmation for a new one`; real backend invalid-state test and live 409 check | PASS |
| H01 opaque HTTP/proxy errors remain unresolved | Parameterized `H01 retains the key after an unverified HTTP %s proxy response` (400/404/409/503), `details.test.tsx:77` | 4 PASS |
| H01 equipment never replayed directly by frontend | The only recovery submission remains `api.retry` → POST `/api/bags/{id}/retry`, `{}`, keyed header (`api.test.ts`); no equipment-command client/API was added. Existing backend replacement-plan and Python uncertain-command tests rerun. | PASS at API/source/workflow boundaries; browser exercise pending |
| M01 initial success, later failure, no synced label, retained data, later restored success, socket independence | `M01 downgrades REST freshness on failure, retains data and restores freshness independently of the socket` | PASS |
| M01 stale/aborted/older queued reads cannot restore freshness; unmount cleanup | Three `operations.test.tsx` tests and existing late-resync feed test | PASS |

There are 16 new frontend tests (20 → 36) and additional assertions inside the existing live backend integration test; the latter does not increase the Java test-method count. No test requires changes to Phase 4 production behavior.

### 15.4 Executed verification and failures

| Executed command/check | Passed | Failed | Skipped | Result / evidence |
|---|---:|---:|---:|---|
| Before-fix focused regressions: `npm --prefix frontend test -- tests/details.test.tsx tests/app.test.tsx` | 10 | **3** | 0 | Expected H01/M01 reproductions; `/tmp/vortex-remediation-before.log`. |
| Before-outcome-hardening details regressions: `npm --prefix frontend test -- tests/details.test.tsx` | 11 | **3** | 0 | Expected uncertain proxy-response reproductions; `/tmp/vortex-remediation-proxy-before.log`. |
| `npm --prefix frontend run typecheck` | check passed | 0 | 0 | `/tmp/vortex-remediation-typecheck.log`. |
| `npm --prefix frontend test` | **36** | 0 | 0 | Seven files; `/tmp/vortex-remediation-frontend-test.log`. |
| `npm --prefix frontend run build` | build passed | 0 | 0 | TypeScript + Vite bundle; `/tmp/vortex-remediation-frontend-build.log`. |
| `./gradlew --rerun-tasks :backend:test` | **70** | 0 | **44** | Default gated run; `/tmp/vortex-remediation-backend-test.log`, XML `/tmp/vortex-remediation-unit-results`. |
| `./gradlew :backend:build` | build passed | 0 | — | BUILD SUCCESSFUL; reused preceding tested outputs, not another test execution; `/tmp/vortex-remediation-backend-build.log`. |
| First `./scripts/test-database.sh --with-kafka` | 59 | **1** | 0 | Shared-topic interference described below; `/tmp/vortex-remediation-integration.log`, XML `/tmp/vortex-remediation-integration-first-results`. |
| Final serial `./scripts/test-database.sh --with-kafka` | **60** | **0** | **0** | All seven integration classes; `/tmp/vortex-remediation-integration-final.log`, XML `/tmp/vortex-remediation-integration-results`. |
| `VORTEX_KAFKA_SMOKE_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew --no-daemon --rerun-tasks :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest` | **1** | 0 | 0 | Admin/create/produce/consume smoke; `/tmp/vortex-remediation-smoke.log`, XML `/tmp/vortex-remediation-smoke-results`. |
| `VORTEX_SIMULATOR_KAFKA_TEST=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 PYTHONPATH=simulator/src simulator/.venv/bin/python -m pytest -q simulator/tests` | **50** | 0 | 0 | Includes actual Kafka contract/restart/duplicate/uncertainty/quarantine; `/tmp/vortex-remediation-simulator.log`. |
| Browser A–G | 0 verified | — | — | Environment blocked / NOT VERIFIED; browser host unavailable. |
| Exact Node 22.23.3 test/build | 0 verified | — | — | NOT VERIFIED on that runtime; available runtime is 24.19.0. |
| `git diff --check` | check passed | 0 | 0 | Working-tree whitespace check. |

The first integration run overlapped the Python Kafka contract suite. `Phase3KafkaIntegrationTest.injectedDatabaseOutageBeyondFourAttemptsRetainsOffsetAndDoesNotQuarantine` expected five handler calls but saw ten; the other 59 integration tests passed, including the new forbidden-SEND check. Source inspection confirmed both suites publish/consume the same fixed `vortex.phase3-test.reports` diagnostic topic. The outage callback counts every eligible report rather than filtering this test's event. The full integration suite was therefore rerun serially after Python finished. Its result is reported separately, with no backend production/test workaround to hide the failure. This is shared-topic verification contamination, not evidence of a Phase 4 workflow regression. Run these two suites sequentially when reproducing the results.

Default skips are not passes: the default backend run has 44 environment-gated entries, while the separately enabled integration run executes their expanded parameterized cases and the Java smoke is separately enabled. The backend build reused the fresh default test output and is not another 70 test executions. Heartbeat timing is a deterministic protocol/client test, not a real network outage or browser run.

### 15.5 Files and preservation evidence

Production frontend corrections:

- `frontend/src/features/bags/recoveryRequests.ts` — new operation identity store.
- `frontend/src/features/bags/BagDetails.tsx` — existing confirmed retry uses that store.
- `frontend/src/app/useOperations.ts` — independent freshness plus obsolete/aborted-request guards.
- `frontend/src/app/App.tsx` — accurate independent REST/socket feedback.
- `frontend/src/realtime/feed.ts` — connected REST resync failure remains syncing.

Tests:

- `frontend/tests/details.test.tsx`, `app.test.tsx`, `feed.test.ts` — extended recovery/freshness/discovery/page/reconnect assertions.
- `frontend/tests/operations.test.tsx`, `heartbeat.test.ts` — new request-race/cleanup and actual STOMP-client heartbeat tests.
- `backend/src/test/java/com/vortex/workflow/Phase4KafkaIntegrationTest.java` — direct/proxied forbidden SEND verification only.

Reports: `docs/PHASE5_AUDIT.md`, `docs/PHASE5_RESULTS.md`. No other preexisting source/configuration file was changed during remediation; the broader uncommitted Phase 5 working tree predates this task.

A pre-remediation SHA-256 baseline recorded 212 existing nonignored files (`/tmp/vortex-remediation-baseline.json`). Hash comparison confirms all six authoritative documents, all backend production sources/migrations/configuration, simulator sources, dependencies/lockfiles and topology remain unchanged. Only the files listed above differ from that baseline, plus the three new files (operation store and two test files). The prior Phase 5/CORS changes were preserved rather than reset.

A read-only repeatable-read snapshot before/after verification recorded all **17 application tables / 687 rows** in the existing `vortex` database. Every per-table count and content hash matches. The existing `simulator/.local/command-journal.jsonl` SHA-256 also matches exactly. Snapshots: `/tmp/vortex-remediation-data-before.json` and `...-after.json`; values/records were not dumped into this report. The database script created/dropped only its own temporary databases, and a final `pg_database` query found no remaining `vortex_test_*` database. Tests used diagnostic topics and temporary simulator journals; no application reset or journal deletion was performed. No commit or push was made.

Java remains the deterministic planner, Python the equipment simulator, PostgreSQL the authoritative state and Kafka the outbox transport. The passing full workflow suite includes scanner-only location advancement, final makeup guards, asynchronous unknown/cancelled-flight failure, guarded retry, route supersession and no uncertain-command replay. The local origin allowlist, methods/headers, WebSocket inbound guard, schema, network topology and all backend production sources remain unchanged.

### 15.6 Reassessment of all 62 traceability rows

The IDs, requirement wording, classification and document sources remain those in §2; no new requirement has been invented. The table below is the **current** assessment. For unchanged implementations, original file evidence remains applicable and the cited §10 test families were rerun in §15.4. Updated frontend line references and new regression names are supplied in §15.2–15.3. Source-hash preservation plus the fresh relevant suites supports retained PASS rows; historical outcomes were not silently overwritten.

| Requirement ID | Original status | Current status | Reassessment evidence / remaining gap |
|---|---|---|---|
| P5-A01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-boundary PASS; backend compile PASS |
| P5-A02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-api, F-app PASS; dependency/source inspection |
| P5-A03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads, B-rest, B-live PASS |
| P5-A04 | PASS | PASS | useOperations.ts + LiveFeed; frontend reconnect, race/abort/unmount tests PASS; REST remains authoritative. |
| P5-A05 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-boundary PASS; ownership inspection |
| P5-A06 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-live PASS (actual Python process); P-contract PASS; source/dependency inspection |
| P5-A07 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-routing unit suite, B-no-route PASS |
| P5-A08 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: Compose health/port inspection; CORS tests PASS. No public hosting/auth added |
| P5-F01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-app, F-details, F-route PASS for baseline coverage; recovery exception tracked P5-R02 |
| P5-F02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-app-4 PASS; B-live cancelled/eligible HTTP paths PASS |
| P5-F03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-app-1, F-api-2, B-reads PASS |
| P5-F04 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-details-1, F-route, B-rest PASS |
| P5-F05 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-details failures/confirmation PASS; B-equipment/B-live PASS; R02 exception |
| P5-F06 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads verifies count deltas/completion; F-app initial metrics PASS |
| P5-F07 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads; F-route-1/2/3 PASS |
| P5-F08 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-happy; F-route-1/2 PASS. Command status is not arrival |
| P5-F09 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-unknown/cancelled, B-reads UNKNOWN_FLIGHT assertion; F-details failure states PASS |
| P5-F10 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-wrong-scan (10 cases), F-route rejects wrong-route/rejected evidence PASS |
| P5-F11 | PASS | PASS | App.tsx:46–86 / useOperations.ts:26–105; M01 failure/recovery and operations race tests PASS; backend safe errors PASS. |
| P5-F12 | PASS | PASS | Seven frontend test files, 36 passing tests (including 16 additions); typecheck and build PASS. |
| P5-REST01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic, B-rest, B-live PASS |
| P5-REST02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic/B-rest conflict and duplicate-tag assertions PASS; frontend key generation/source verified |
| P5-REST03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic/B-rest DUPLICATE_BAG_TAG PASS |
| P5-REST04 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-live, F-api/F-details PASS |
| P5-REST05 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-happy; F-details-1 PASS; history pagination explicitly deferred |
| P5-REST06 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest, F-api-1/3, F-route PASS |
| P5-REST07 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest (3 catalog flights), B-live cancelled flight, F-app-4 PASS |
| P5-REST08 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-equipment; broadcaster equipment branch unit PASS |
| P5-REST09 | PASS | PASS | B-filters/B-reads and F-api rerun PASS; new App Next/Previous page-navigation test PASS. |
| P5-REST10 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads tests UUID/tag lower-case and literal %; B-filters PASS. Flight predicate verified in SQL; dedicated substring case advisable |
| P5-REST11 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-filters rejects CANCELLED/invalid; B-reads tests AT_MAKEUP/flight failure PASS |
| P5-REST12 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads nonoverlapping pages PASS; SQL source directly confirms tie-break |
| P5-REST13 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads actual count deltas/completion PASS; SQL verifies all three failure categories |
| P5-REST14 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads and B-boundary PASS; transaction annotation inspection |
| P5-REST15 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-errors/B-filters/B-rest PASS; errors preserve safe code/message/correlation ID |
| P5-W01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-feed-2, B-broadcast, B-live actual bag subscriptions PASS |
| P5-W02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-broadcast both mappings/ignore-root; B-live independent UI group PASS |
| P5-W03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic rollback; messaging persistence rollback; B-live actual committed final envelope PASS |
| P5-W04 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-broadcast/B-codec; B-live verifies eventType/bagId/node/version PASS |
| P5-W05 | PASS | PASS | Feed and App reconnect/resync tests plus actual STOMP-client heartbeat reconnect PASS; actual browser remains T03 NOT VERIFIED. |
| P5-W06 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: F-feed-1/2, F-app-1/2 PASS; equipment stale facts only cause current REST reads, never patch status |
| P5-W07 | PASS | PASS | useOperations.ts:52–105,121–127 + feed cleanup; request cohort/abort/late-response/unmount and heartbeat cleanup tests PASS. |
| P5-W08 | PARTIAL | PASS | WebSocketConfiguration.java:19 / feed.ts:149–162; heartbeat.test.ts:33 actual STOMP parser/timer loss/reconnect/resync PASS. Real browser/network black-hole remains NOT VERIFIED under T03. |
| P5-W09 | PARTIAL | PASS | WebSocketConfiguration.java:22–33 unchanged; Phase4KafkaIntegrationTest.java:265 direct/proxied valid client SEND → ERROR and close PASS. |
| P5-W10 | PASS | PASS | useOperations.ts:118–121; app.test.tsx:131 fake-timer new-bag discovery without STOMP and unmount polling cleanup PASS. |
| P5-R01 | PASS | PASS | BagDetails.tsx:75–127 confirmation remains the only invocation; H01 confirmation/no-auto-submission tests PASS. |
| P5-R02 | FAIL | PASS | recoveryRequests.ts:10–43; details.test.tsx:9,23,46,63 + app.test.tsx:96 PASS across versions, rerenders, STOMP, remounts and bags. Backend same-key replay/guard tests PASS. |
| P5-R03 | PASS | PASS | Matching accepted response removes the attempt; existing later explicitly confirmed blocked-at-same-version test PASS. |
| P5-R04 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-movement-recovery (both failures), B-no-path-retry; P-contract PASS. H01 does not demonstrate replay of an old command ID |
| P5-R05 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-flight-retry/B-movement-recovery; B-live completed bag 409 and cancelled failure retry 202 PASS (backend contract) |
| P5-S01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-happy verifies ACCEPTED/STARTED/SUCCEEDED cannot move node; F-route-1/2; wrong-scan cases PASS |
| P5-S02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-final-guard, B-wrong-scan, B-happy/final replay; B-live PASS |
| P5-S03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-unknown/cancelled; B-live 202 CHECKED_IN then FLIGHT_NOT_ELIGIBLE PASS |
| P5-C01 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-origins/B-cors localOrigin tests and B-live both origins PASS |
| P5-C02 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-cors unrelated origins/method/header rejection; B-origins wildcard validation PASS |
| P5-C03 | PASS | PASS | Unchanged implementation/file evidence in §2; §10 tests rerun: B-live eligible/cancelled POST/read/retry through actual Vite; direct and proxied STOMP for both origins; foreign origins rejected PASS |
| P5-T01 | PASS | PASS | Strict TypeScript checks, typed API/STOMP contracts and 36 tests PASS; production build PASS on Node 24.19.0. |
| P5-T02 | PASS | PASS | Committed broadcaster unit tests and actual Kafka/Python/Vite/STOMP mapping integration PASS, now also client SEND rejection. |
| P5-T03 | NOT VERIFIED | NOT VERIFIED | Browser access host unavailable; no interactive browser executed. Exact A–G procedures in §15.7. V01 remains open. |
| P5-T04 | NOT VERIFIED | NOT VERIFIED | Exact recommended Node 22.23.3 not executed; typecheck/test/build PASS on available 24.19.0. Recommendation only. |
| P5-T05 | PARTIAL | PARTIAL | Proposed app error boundary remains absent; source inspected unchanged. Optional structure recommendation L01. |
| P5-T06 | FAIL | PARTIAL | H01/M01 regressions and final automated suites PASS; actual browser acceptance is still required. Current verdict CONDITIONAL PASS. |

Current totals: **58 PASS, 2 PARTIAL, 0 FAIL, 2 NOT VERIFIED** (62 rows). P5-T06 is the overall completion gate; it does not introduce another implementation defect. P5-T05 is a recommendation, and P5-T04 is a recommended exact runtime pairing. P5-T03 is the outstanding mandatory browser verification.

### 15.7 Exact final browser acceptance checklist — all NOT VERIFIED

Interactive browser discovery was unavailable. Preparation in separate terminals, after checking that a backend or simulator is not already running:

```bash
./scripts/dev-start.sh
./gradlew :backend:bootRun
```

```bash
npm --prefix frontend run dev
```

```bash
VORTEX_SIMULATOR_DELAY_SECONDS=2 PYTHONPATH=simulator/src simulator/.venv/bin/python -m vortex_simulator.main
```

Use the installed locked environments. Run only one simulator writer against the existing journal; do not remove its file or reset the database. Open `http://127.0.0.1:5173`, repeat the origin-sensitive checks at `http://localhost:5173`, and enable DevTools Preserve log / Network / WS frames. Use a distinct `P5-ACCEPT-<scenario>-<Date.now()>-<random>` bag tag for each check-in; the form creates new idempotency keys. Record returned bag IDs, request headers/statuses, frames and final REST projections.

1. **A — successful check-in (NOT VERIFIED):** Submit a fresh bag with `FL-204-2030-01-15`. Record initial 202/CHECKED_IN. Select its tag immediately and observe live progress to AT_MAKEUP, `makeup-lhr`, COMPLETE route and accepted final scanner observation. Compare `/api/bags/{id}`, `/history`, `/route`, `/movement`. Command success alone must not change the confirmed node.
2. **B — cancelled flight (NOT VERIFIED):** Submit a distinct tag with `FL-999-2030-01-15`. Record 202/CHECKED_IN followed by FLIGHT_VERIFICATION_FAILED / FLIGHT_NOT_ELIGIBLE. Confirm no fabricated route arrival.
3. **C — unknown flight (NOT VERIFIED):** In the local page console execute the fetch below, then search its tag. Expect 202 first, followed by FLIGHT_VERIFICATION_FAILED / UNKNOWN_FLIGHT with no movement. A free-text flight entry is not a new UI requirement.
4. **D — real-time consistency (NOT VERIFIED):** During A, inspect `/ws` BagStateChanged frames for this bag and subsequent REST loads. Confirm completed/current/pending route nodes agree with accepted observations/current BagView, that rejected observations do not advance location, and that final detail/list agree with REST. Inspect equipment values against GET `/api/equipment`. Test narrow and wide layouts, keyboard operation and confirmation focus while here.
5. **E — recovery identity/safety (NOT VERIFIED):** On the newly failed B or C bag choose Review recovery → Cancel and confirm no POST. Install the one-shot console probe below, then Review recovery → Confirm retry. The Network panel will show the real backend response, while the UI sees uncertainty. Leave STOMP/polling running until the asynchronous failure advances `stateVersion`; close details, select another bag and reopen the original. There must be no automatic POST. Explicitly confirm again: its Idempotency-Key must exactly equal the first request's key, and the stored accepted request must replay without a second RETRY_REQUEST history entry for that operation. After this positive acknowledgement, a later Review recovery → Confirm retry must use a fresh key even at the same blocked version. Do not change fixture/catalog data. Completed A must have no recovery control; an explicitly keyed POST to A's retry API with `{}` must return 409/BAG_NOT_RETRYABLE. No request may publish an equipment command directly.
6. **F — reconnect/resync (NOT VERIFIED):** Start another distinct successful bag and select it. Set DevTools Network Offline long enough to close `/ws`, observe live-feed offline, then restore Online while backend/simulator continue. Allow reconnect (3-second delay, REST timeout up to 15 seconds). Confirm subscriptions reinstall, REST bag/route/history reload and `REST synchronizing…` before `REST synced`; final state must match backend even if movement finished offline. Separately block only `/ws`, checking that REST reads still work, then unblock and verify resync again. A socket loss and a REST refresh failure must be distinguishable.
7. **G — REST freshness failure/recovery (NOT VERIFIED):** First obtain `Live feed · REST synced`. Block only `*/api/bags/summary` with DevTools Request blocking, keeping `/ws` enabled; click Refresh data. Expect an API error and `REST refresh failed · showing last successful data`, with previously loaded bags retained and last-success timestamp unchanged. `REST synced` must be absent. Unblock and Refresh data; expect synced and a new last-success timestamp. Repeat with socket-only blocking to verify independent status.

Console request for C:

```javascript
const suffix = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`;
const tag = `P5-ACCEPT-UNKNOWN-${suffix}`;
const response = await fetch('/api/bags/check-in', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify({ bagTag: tag, flightId: `NO-SUCH-FLIGHT-${suffix}` })
});
console.log(response.status, await response.json(), tag);
```

One-shot console instrumentation for E (temporary browser-only verification; no server feature/code/data rewrite):

```javascript
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const response = await originalFetch(input, init);
  if (init?.method === 'POST' && /\/api\/bags\/[^/]+\/retry$/.test(String(input))) {
    window.fetch = originalFetch;
    throw new TypeError('Acceptance probe: retry response hidden after server replied');
  }
  return response;
};
// Restore manually if the probe was not used: window.fetch = originalFetch;
```

This intentionally hides a response only in the operator's new test page; Network evidence still establishes whether the backend returned 202 or a definitive rejection. It is not a production fault-injection mechanism. Do not reload the entire page during the unresolved attempt: the tested guarantee covers React remounts, not process/tab persistence. Use the dashboard history and a same-key 202 replay to verify one logical operation; no direct database edits are required.

### 15.8 Current Phase 5 verdict

**CONDITIONAL PASS — H01 and M01 are corrected and verified; ready for final manual browser acceptance.** No confirmed mandatory implementation defect remains in this targeted remediation. All required automated suites pass in their final, noninterfering runs. Full Phase 5 completion / unconditional PASS remains gated by actual browser acceptance (P5-T03 and P5-T06). Exact recommended Node verification remains open, and the proposed error boundary remains an optional recommendation. The original FAIL verdict is preserved above as historical evidence, not the current verdict.

## 16. Final Phase 5 sign-off — 2026-11-05

**Final Phase 5 verdict: PASS.** All mandatory Phase 5 implementation and completion requirements are satisfied by the automated evidence below and the user's explicit confirmation of successful manual browser acceptance. This verdict covers Phase 5 functionality/compliance; publishing and independently verifying the GitHub branch is a separate Git finalization step, whose actual outcome must be reported without assuming success.

Sections 1–14 preserve the original audit findings/FAIL; §15 preserves the remediation work/CONDITIONAL PASS and its then-unverified browser results. This section supersedes their current-status conclusions. The six authoritative documents were reread and their existing Phase 5 changes reviewed, not rewritten during sign-off. Their earlier dated implementation record describes the original verification point; the final acceptance record is here and in PHASE5_RESULTS.

### 16.1 User-reported manual browser acceptance

Evidence/provenance: the user's “Phase 5 Final Sign-off, Commit and Push” request in this conversation explicitly states that all seven manual browser acceptance scenarios passed. The following PASS results are **user-reported manual acceptance**, not browser testing performed or observed by the agent. No browser screenshots, HAR/STOMP traces, browser version, device matrix, bag tags/IDs, idempotency keys or exact execution timestamps were supplied with that confirmation. Those technical details remain independently unverified; none is invented below.

| Scenario | Accepted behavior | Status | Evidence source |
|---|---|---|---|
| A — successful check-in | Durable acceptance, successful journey to scanner-confirmed AT_MAKEUP and correct route display | PASS | User-reported manual acceptance |
| B — cancelled flight | HTTP 202 first, then asynchronous verification failure | PASS | User-reported manual acceptance |
| C — unknown flight | HTTP 202 first, then asynchronous UNKNOWN_FLIGHT failure | PASS | User-reported manual acceptance |
| D — real-time tracking | Dashboard state/route progress consistent with backend and scanner evidence | PASS | User-reported manual acceptance |
| E — recovery safety | Explicit confirmation and original recovery key retained across uncertain responses | PASS | User-reported manual acceptance |
| F — reconnection | WebSocket reconnection followed by authoritative REST resynchronization | PASS | User-reported manual acceptance |
| G — REST freshness failure/recovery | Failed refresh downgrades synchronization status and later success restores it | PASS | User-reported manual acceptance |

V01/P5-T03 is closed on this explicit user acceptance. The agent's browser access limitation and absence of an agent-executed browser run remain true. Scenario F confirmation does not establish a specifically timed real-network heartbeat black-hole test; the deterministic STOMP-client heartbeat regression is the independently executed evidence for that implementation.

### 16.2 Final checks and pagination regression

The first final frontend run exposed a real page-selection race: the initial 250 ms search debounce reset page to zero even though the search value was unchanged. The existing Next/Previous test failed (35 passed / 1 failed). A deterministic fake-clock test, `keeps the selected page when the initial unchanged search debounce expires` (`frontend/tests/app.test.tsx:131`), then reproduced the lost page after advancing 250 ms. The focused before-fix run had 1 failure / 8 deselected skips. An initial fake-clock/user-event harness timed out; replacing only the simulated click with Testing Library fireEvent made the regression fail at the actual page-loss assertion, without changing its required behavior.

Minimal fix: `frontend/src/app/App.tsx:15–23` does not schedule a debounce when `searchInput.trim() === search`, and the effect depends on both values. A genuine search change still resets pagination after the debounce. The deterministic regression and existing search/filter/pagination cases all pass. This changes only presentation-state scheduling; no endpoint, DTO/event, scanner rule, backend workflow or Phase 6 feature changed. User-reported A–G acceptance is retained as supplied; no subsequent agent browser retest of this small pagination correction is claimed.

| Final verification command | Result | Provenance / limits |
|---|---|---|
| `npm --prefix frontend run typecheck` | PASS | Fresh final execution; `/tmp/vortex-signoff-typecheck.log` |
| `npm --prefix frontend test` | **37 passed, 0 failed, 0 skipped** | Seven files, including the pagination regression; `/tmp/vortex-signoff-frontend-test.log` |
| `npm --prefix frontend run build` | PASS | Fresh TypeScript/Vite production build; `/tmp/vortex-signoff-frontend-build.log` |
| `./gradlew :backend:build` | PASS | `/tmp/vortex-signoff-backend-build.log`; backend test task FROM-CACHE, with 70 passed / 44 gated skips in its XML. This is not a fresh execution of those 70 tests. |
| `git diff --check` | PASS | Working-tree whitespace verification; staged check is also required before commit |

Earlier applicable results remain documented in §15.4 and were checked against retained XML/logs: isolated PostgreSQL/Kafka **60 passed / 0 failed / 0 skipped**, standalone Java broker smoke **1 passed / 0 failed / 0 skipped**, Python simulator suite including Kafka contract **50 passed / 0 failed / 0 skipped**. The prior first integration run's 59/1 shared-topic failure and successful serial rerun remain recorded. No skip is counted as a pass.

Those three external-infrastructure suites were **not rerun in this sign-off**. Since their passing remediation run, this sign-off changes only the two Phase 5 reports and the frontend unchanged-search debounce plus its component regression; backend production/test sources, CORS/STOMP/proxy configuration, dependency pins/lockfiles, migrations and simulator sources are unchanged. The inspected final change cannot alter their Kafka/PostgreSQL/Python contracts, so the prior executed results remain applicable. The user’s manual tests may have created their own accepted test baggage; this sign-off performs no application API writes, database reset/update or simulator/journal operation.

### 16.3 Final reassessment of all 62 requirements

Requirement wording, classification and authoritative source references are unchanged from §2. Implementation evidence and original/remediation outcomes remain in §2/§15. The final table below updates only evidence/status warranted by this sign-off and retains technical limitations explicitly.

| Requirement ID | Remediation status | Final status | Final evidence and limits |
|---|---|---|---|
| P5-A01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-boundary PASS; backend compile PASS |
| P5-A02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-api, F-app PASS; dependency/source inspection |
| P5-A03 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads, B-rest, B-live PASS |
| P5-A04 | PASS | PASS | Implementation and verification retained from §15.6. useOperations.ts + LiveFeed; frontend reconnect, race/abort/unmount tests PASS; REST remains authoritative. |
| P5-A05 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-boundary PASS; ownership inspection |
| P5-A06 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-live PASS (actual Python process); P-contract PASS; source/dependency inspection |
| P5-A07 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-routing unit suite, B-no-route PASS |
| P5-A08 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: Compose health/port inspection; CORS tests PASS. No public hosting/auth added |
| P5-F01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-app, F-details, F-route PASS for baseline coverage; H01 resolution verified in P5-R02 |
| P5-F02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-app-4 PASS; B-live cancelled/eligible HTTP paths PASS |
| P5-F03 | PASS | PASS | Debounced real API search remains verified; new deterministic unchanged-search/page-preservation regression PASS (App.tsx:15–23; app.test.tsx:131). |
| P5-F04 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-details-1, F-route, B-rest PASS |
| P5-F05 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-details failures/confirmation PASS; B-equipment/B-live PASS; H01 resolved |
| P5-F06 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads verifies count deltas/completion; F-app initial metrics PASS |
| P5-F07 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads; F-route-1/2/3 PASS |
| P5-F08 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-happy; F-route-1/2 PASS. Command status is not arrival |
| P5-F09 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-unknown/cancelled, B-reads UNKNOWN_FLIGHT assertion; F-details failure states PASS |
| P5-F10 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-wrong-scan (10 cases), F-route rejects wrong-route/rejected evidence PASS |
| P5-F11 | PASS | PASS | Implementation and verification retained from §15.6. App.tsx:46–86 / useOperations.ts:26–105; M01 failure/recovery and operations race tests PASS; backend safe errors PASS. |
| P5-F12 | PASS | PASS | 37 frontend tests in seven files PASS; includes prior H01/M01 regressions and one new pagination race regression. Typecheck and production build PASS. |
| P5-REST01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic, B-rest, B-live PASS |
| P5-REST02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic/B-rest conflict and duplicate-tag assertions PASS; frontend key generation/source verified |
| P5-REST03 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic/B-rest DUPLICATE_BAG_TAG PASS |
| P5-REST04 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-live, F-api/F-details PASS |
| P5-REST05 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-happy; F-details-1 PASS; history pagination explicitly deferred |
| P5-REST06 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest, F-api-1/3, F-route PASS |
| P5-REST07 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest (3 catalog flights), B-live cancelled flight, F-app-4 PASS |
| P5-REST08 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-rest/B-equipment; broadcaster equipment branch unit PASS |
| P5-REST09 | PASS | PASS | Existing backend pagination/validation evidence retained; Next/Previous and unchanged-search debounce page-preservation component regressions PASS. |
| P5-REST10 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads tests UUID/tag lower-case and literal %; B-filters PASS. Flight predicate verified in SQL; dedicated substring case advisable |
| P5-REST11 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-filters rejects CANCELLED/invalid; B-reads tests AT_MAKEUP/flight failure PASS |
| P5-REST12 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads nonoverlapping pages PASS; SQL source directly confirms tie-break |
| P5-REST13 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads actual count deltas/completion PASS; SQL verifies all three failure categories |
| P5-REST14 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-reads and B-boundary PASS; transaction annotation inspection |
| P5-REST15 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-errors/B-filters/B-rest PASS; errors preserve safe code/message/correlation ID |
| P5-W01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-feed-2, B-broadcast, B-live actual bag subscriptions PASS |
| P5-W02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-broadcast both mappings/ignore-root; B-live independent UI group PASS |
| P5-W03 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-atomic rollback; messaging persistence rollback; B-live actual committed final envelope PASS |
| P5-W04 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-broadcast/B-codec; B-live verifies eventType/bagId/node/version PASS |
| P5-W05 | PASS | PASS | Automated reconnect/resync and late-response guards PASS; scenario F additionally PASS by user-reported manual acceptance. No agent browser run. |
| P5-W06 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: F-feed-1/2, F-app-1/2 PASS; equipment stale facts only cause current REST reads, never patch status |
| P5-W07 | PASS | PASS | Implementation and verification retained from §15.6. useOperations.ts:52–105,121–127 + feed cleanup; request cohort/abort/late-response/unmount and heartbeat cleanup tests PASS. |
| P5-W08 | PASS | PASS | Actual STOMP-client heartbeat loss/reconnect regression PASS (deterministic clock/in-memory socket); scenario F user-reported PASS. A real network black-hole/heartbeat trace is still NOT VERIFIED. |
| P5-W09 | PASS | PASS | Implementation and verification retained from §15.6. WebSocketConfiguration.java:22–33 unchanged; Phase4KafkaIntegrationTest.java:265 direct/proxied valid client SEND → ERROR and close PASS. |
| P5-W10 | PASS | PASS | Implementation and verification retained from §15.6. useOperations.ts:118–121; app.test.tsx:131 fake-timer new-bag discovery without STOMP and unmount polling cleanup PASS. |
| P5-R01 | PASS | PASS | Implementation and verification retained from §15.6. BagDetails.tsx:75–127 confirmation remains the only invocation; H01 confirmation/no-auto-submission tests PASS. |
| P5-R02 | PASS | PASS | Implementation and verification retained from §15.6. recoveryRequests.ts:10–43; details.test.tsx:9,23,46,63 + app.test.tsx:96 PASS across versions, rerenders, STOMP, remounts and bags. Backend same-key replay/guard tests PASS. |
| P5-R03 | PASS | PASS | Implementation and verification retained from §15.6. Matching accepted response removes the attempt; existing later explicitly confirmed blocked-at-same-version test PASS. |
| P5-R04 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-movement-recovery (both failures), B-no-path-retry; P-contract PASS. H01 does not demonstrate replay of an old command ID |
| P5-R05 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-flight-retry/B-movement-recovery; B-live completed bag 409 and cancelled failure retry 202 PASS (backend contract) |
| P5-S01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-happy verifies ACCEPTED/STARTED/SUCCEEDED cannot move node; F-route-1/2; wrong-scan cases PASS |
| P5-S02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-final-guard, B-wrong-scan, B-happy/final replay; B-live PASS |
| P5-S03 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-unknown/cancelled; B-live 202 CHECKED_IN then FLIGHT_NOT_ELIGIBLE PASS |
| P5-C01 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-origins/B-cors localOrigin tests and B-live both origins PASS |
| P5-C02 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-cors unrelated origins/method/header rejection; B-origins wildcard validation PASS |
| P5-C03 | PASS | PASS | Implementation and verification retained from §15.6. Unchanged implementation/file evidence in §2; §10 tests rerun: B-live eligible/cancelled POST/read/retry through actual Vite; direct and proxied STOMP for both origins; foreign origins rejected PASS |
| P5-T01 | PASS | PASS | Final typecheck, 37 frontend tests and production build PASS on Node 24.19.0. Typed REST/STOMP source/contracts reviewed. |
| P5-T02 | PASS | PASS | Implementation and verification retained from §15.6. Committed broadcaster unit tests and actual Kafka/Python/Vite/STOMP mapping integration PASS, now also client SEND rejection. |
| P5-T03 | NOT VERIFIED | PASS | User explicitly confirms all browser scenarios A–G passed in the final sign-off request. This closes the manual browser-smoke gate as USER-REPORTED acceptance; agent browser execution and raw browser artifacts are not claimed. |
| P5-T04 | NOT VERIFIED | NOT VERIFIED | Exact recommended Node 22.23.3 pairing was not executed. Available Node 24.19.0 passes final checks and meets package engine floor. Nonblocking recommendation. |
| P5-T05 | PARTIAL | PARTIAL | Optional proposed app error boundary remains absent. No router/provider framework is required for this screen. Nonblocking structure recommendation. |
| P5-T06 | PARTIAL | PASS | H01/M01 resolved with passing regressions; pagination race regression PASS; final requested checks pass; prior integration/smoke/simulator evidence retained and applicable; mandatory browser smoke accepted on explicit user confirmation. Source/API/event/local instructions reviewed. All mandatory completion rows PASS. |

**Final totals: 60 PASS, 1 PARTIAL, 0 FAIL, 1 NOT VERIFIED (62 rows).** All mandatory rows are PASS. The PARTIAL row (P5-T05) is a proposed-structure recommendation; the NOT VERIFIED row (P5-T04) is the exact recommended Node pairing. Neither is an unmet mandatory Phase 5 deliverable. P5-T03 is PASS by explicit user-reported manual acceptance, and P5-T06 is therefore PASS alongside the passing automated/source review.

### 16.4 Nonblocking recommendations and environment limits

- Exact recommended Node 22.23.3 checks remain NOT VERIFIED. Final frontend checks use installed Node 24.19.0, which meets the package engine floor; no runtime pin was changed.
- The proposed React error boundary remains an optional recommendation. No new router/state-management framework is introduced solely to match the proposed folder description.
- The agent did not execute browser acceptance. User-confirmed A–G results close the manual smoke gate, while browser versions, raw network/frame artifacts, screen-reader/device coverage and an actual heartbeat black-hole trace remain independently unverified.
- Unresolved recovery identity survives React/component remounts within the loaded JavaScript page; full page reload, closed tab or process-crash persistence is outside the in-memory store's lifetime.
- Existing local-demo limits remain: ephemeral STOMP with REST resync, ten-second background discovery, full-journey diagnostics and per-row route queries. This is still a local-only unauthenticated demo.

### 16.5 Git review and intended completion baseline

Reviewed branch: `main`. Configured upstream: `origin/main`. Configured fetch/push remote: `https://github.com/samir-saudagar/vortex.git`. Local parent before finalization: `9bf78afdc732ed281dc2e7487e83d2ab3d5d8a39` (`Complete VORTEX Phase 4`); the cached tracking ref matched that parent. No branch switch, history rewrite or force-push is authorized or used.

Every changed and untracked file was reviewed (source/diffs directly, npm lock entries structurally). The lock review found no existing dependency version changes, registry credentials, unexpected registry hosts or new install scripts. A credential-marker scan found no private key/GitHub/AWS/Slack token markers in the intended files. `.env.example` contains the existing documented local-only defaults, not the user's ignored `.env`. Ignore rules exclude `.env`, simulator `.local` journal, virtual environment, node_modules, dist, Gradle/build caches and outputs. No migrations, graph/Compose configuration, simulator code or Phase 4 workflow/route/movement/check-in/retry implementation changed unexpectedly. The Java diff adds read projections, the planned STOMP adapter, exact-origin configuration and explicit scheduler binding only.

GitHub authentication was unavailable during the initial remote review: HTTPS fetch/ls-remote could not obtain a username/credential, the SSH agent had no identities, and the IDE terminal also could not authenticate. Thus the cached origin/main ref is **not proof of the current GitHub tip**. The requested local completion commit is prepared after the final checks; normal push and equality of local SHA with the remote branch must be established separately once authentication is available. No remote-baseline verification is claimed in this pre-commit report. The final user-facing Git result must state the actual commit/push outcome.

The single requested completion commit message is `feat: complete Phase 5 operations dashboard and live tracking`. Only the following 51 reviewed Phase 5 paths are intended for that commit:

- `.env.example`
- `README.md`
- `backend/build.gradle.kts`
- `backend/src/main/java/com/vortex/api/LocalDevelopmentOrigins.java`
- `backend/src/main/java/com/vortex/api/rest/JourneyController.java`
- `backend/src/main/java/com/vortex/api/rest/RestConfiguration.java`
- `backend/src/main/java/com/vortex/api/websocket/CommittedUpdateListener.java`
- `backend/src/main/java/com/vortex/api/websocket/WebSocketConfiguration.java`
- `backend/src/main/java/com/vortex/bagstate/api/BagQuery.java`
- `backend/src/main/java/com/vortex/bagstate/application/PersistentBagLifecycle.java`
- `backend/src/main/java/com/vortex/bagstate/persistence/JdbcBagRepository.java`
- `backend/src/main/java/com/vortex/messaging/kafka/KafkaMessagingConfiguration.java`
- `backend/src/main/resources/application.yaml`
- `backend/src/test/java/com/vortex/api/JourneyControllerTest.java`
- `backend/src/test/java/com/vortex/api/LocalDevelopmentOriginsTest.java`
- `backend/src/test/java/com/vortex/api/websocket/CommittedUpdateListenerTest.java`
- `backend/src/test/java/com/vortex/workflow/Phase4KafkaIntegrationTest.java`
- `backend/src/test/java/com/vortex/workflow/Phase4WorkflowIntegrationTest.java`
- `docs/ARCHITECTURE.md`
- `docs/DATA_MODEL.md`
- `docs/DECISIONS_AND_OPEN_QUESTIONS.md`
- `docs/EVENT_CATALOG.md`
- `docs/IMPLEMENTATION_PLAN.md`
- `docs/PHASE5_AUDIT.md`
- `docs/PHASE5_RESULTS.md`
- `docs/PROJECT_STRUCTURE.md`
- `frontend/package-lock.json`
- `frontend/package.json`
- `frontend/src/api/client.ts`
- `frontend/src/app/App.tsx`
- `frontend/src/app/useOperations.ts`
- `frontend/src/components/Status.tsx`
- `frontend/src/features/bags/BagDetails.tsx`
- `frontend/src/features/bags/RouteProgress.tsx`
- `frontend/src/features/bags/recoveryRequests.ts`
- `frontend/src/features/checkin/CheckIn.tsx`
- `frontend/src/realtime/feed.ts`
- `frontend/src/styles.css`
- `frontend/src/types/contracts.ts`
- `frontend/tests/api.test.ts`
- `frontend/tests/app.test.tsx`
- `frontend/tests/details.test.tsx`
- `frontend/tests/feed.test.ts`
- `frontend/tests/fixtures.ts`
- `frontend/tests/heartbeat.test.ts`
- `frontend/tests/operations.test.tsx`
- `frontend/tests/route.test.tsx`
- `frontend/tests/setup.ts`
- `frontend/tsconfig.json`
- `frontend/vite.config.ts`
- `frontend/vitest.config.ts`

All six authoritative documents' existing Phase 5 implementation/contract notes are part of that reviewed change set; they were not changed by the sign-off itself. No unrelated path, local secret, journal, database, log or generated artifact is included. The original audit and remediation history are preserved. **Phase 6 has not started.**
