package com.vortex.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.BagState;
import com.vortex.checkin.api.CheckInUseCase;
import com.vortex.messaging.deduplication.*;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.workflow.*;
import com.vortex.movement.api.*;
import com.vortex.routing.api.*;
import com.vortex.routing.domain.EquipmentStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vortex.outbox.enabled=false", "spring.kafka.listener.auto-startup=false", "vortex.movement.sweeper-enabled=false"})
@AutoConfigureMockMvc
@Import(Phase4WorkflowIntegrationTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "VORTEX_DB_INTEGRATION_TEST", matches = "true")
class Phase4WorkflowIntegrationTest {
    static final String FLIGHT = "FL-204-2030-01-15";
    static final String WORKFLOW = "phase4-workflow-test", REPORTS = "phase4-reports-test";
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckInUseCase checkin;
    @Autowired RetryBagUseCase retry;
    @Autowired BagQuery bags;
    @Autowired RouteQuery routes;
    @Autowired MovementQuery movement;
    @Autowired MovementTimeouts timeouts;
    @Autowired EquipmentStatusUpdater equipment;
    @Autowired TransactionalEventConsumer consumer;
    @Autowired WorkflowDispatcher dispatcher;
    @Autowired SimulatorReportDispatcher reports;
    @Autowired EventEnvelopeJsonCodec codec;
    @Autowired EventFactory events;
    @Autowired TransactionTemplate transactions;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock workflowClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        void advanceNanos(long nanos) { now = now.plusNanos(nanos); }
        void reset() { now = Instant.parse("2026-10-29T10:00:00Z"); }
    }
    @BeforeEach void requireIsolatedDatabase() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("vortex_test_"));
        clock.reset();
    }
    @AfterEach void restoreCatalog() {
        for (UUID id : jdbc.query("SELECT bag_id FROM vortex.bags WHERE bag_tag LIKE 'P4-%'", (rs, n) -> rs.getObject(1, UUID.class))) {
            Phase4DatabaseCleanup.bag(jdbc, transactions, id);
        }
        jdbc.update("DELETE FROM vortex.processed_events WHERE consumer_name IN (?, ?)", WORKFLOW, REPORTS);
        jdbc.update("DELETE FROM vortex.outbox WHERE aggregate_type = 'EQUIPMENT' AND event_type = 'EquipmentStatusChanged' AND envelope->'payload'->>'sourceReportId' = (SELECT last_report_id::text FROM vortex.equipment WHERE equipment_id = 'belt-west')");
        jdbc.update("UPDATE vortex.equipment SET status = CASE WHEN equipment_id = 'belt-blocked' THEN 'BLOCKED' ELSE 'AVAILABLE' END, configured_enabled = true, last_report_id = NULL, last_status_at = '2030-01-01T00:00:00Z' WHERE equipment_id NOT LIKE 'phase3-%'");
        jdbc.update("UPDATE vortex.flights SET status = 'SCHEDULED', destination_airport = 'LHR', departure_airport = 'JFK', check_in_opens_at = '2020-01-01T00:00:00Z', check_in_closes_at = '2030-01-15T12:00:00Z' WHERE flight_id = ?", FLIGHT);
    }
    private CheckInUseCase.Accepted checkin(String flight) {
        return checkin.checkIn(UUID.randomUUID().toString(), new CheckInUseCase.Request("P4-" + UUID.randomUUID(), flight));
    }
    private BagSnapshot bag(UUID id) { return bags.find(id).orElseThrow(); }
    private List<EventEnvelope> pendingWorkflow(UUID id) {
        return jdbc.query("SELECT envelope::text FROM vortex.outbox o WHERE aggregate_id = ? AND event_type <> 'EquipmentMovementCommand' AND NOT EXISTS(SELECT 1 FROM vortex.processed_events p WHERE p.consumer_name = ? AND p.event_id = o.event_id) ORDER BY aggregate_sequence",
                (rs, n) -> codec.validate(rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)), id.toString(), WORKFLOW);
    }
    private void dispatch(EventEnvelope event) { consumer.consume(WORKFLOW, event, () -> dispatcher.dispatch(event)); }
    private void drain(UUID id) {
        for (int i = 0; i < 20; i++) {
            List<EventEnvelope> pending = pendingWorkflow(id);
            if (pending.isEmpty()) return;
            pending.forEach(this::dispatch);
        }
        fail("workflow did not settle");
    }
    private UUID startedBag() { UUID id = checkin(FLIGHT).bagId(); drain(id); assertEquals(BagState.MOVEMENT_PENDING, bag(id).state()); return id; }
    private EventEnvelope command(UUID id) {
        UUID active = bag(id).activeCommandId();
        return jdbc.queryForObject("SELECT envelope::text FROM vortex.outbox WHERE event_id = ?", (rs, n) -> codec.validate(rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)), active);
    }
    private void receive(EventEnvelope event) {
        EventEnvelope validated = codec.validate(codec.serialize(event), event.aggregateId());
        consumer.consume(REPORTS, validated, () -> reports.dispatch(validated));
    }
    private EventEnvelope result(EventEnvelope command, String status) {
        ObjectNode p = (ObjectNode) command.payload().deepCopy();
        p.put("status", status).put("reportedAt", clock.instant().toString());
        if (status.equals("FAILED")) p.put("failureCode", "SIMULATOR_EXECUTION_UNCERTAIN").put("safeReason", "Simulator execution is uncertain");
        return events.bag(EventType.EQUIPMENT_COMMAND_RESULT, UUID.fromString(command.aggregateId()), command.correlationId(), command.eventId(), p);
    }
    private EventEnvelope observation(EventEnvelope command) {
        ObjectNode p = (ObjectNode) command.payload().deepCopy();
        UUID id = UUID.randomUUID();
        String node = p.path("toNodeId").asText();
        p.put("observationId", id.toString()).put("nodeId", node).put("scannerId", node.equals("junction-west") ? "scanner-west" : "scanner-lhr")
                .put("observationType", "BAG_DETECTED").put("observedAt", clock.instant().toString());
        return events.bag(id, EventType.SCANNER_OBSERVATION, UUID.fromString(command.aggregateId()), command.correlationId(), command.eventId(), p);
    }
    private void succeed(EventEnvelope command) {
        receive(result(command, "ACCEPTED")); receive(result(command, "STARTED")); receive(result(command, "SUCCEEDED"));
    }
    private long transitions(UUID id, String state) {
        return jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND current_state = ? AND record_type = 'STATE_TRANSITION'", Long.class, id, state);
    }

    @Test void operationsReadsArePaginatedSearchableAndScannerConfirmed() throws Exception {
        var baseline = bags.summary();
        UUID id = startedBag();
        UUID failed = checkin("UNKNOWN-PHASE5").bagId(); drain(failed);
        var summary = bags.summary();
        assertEquals(baseline.total() + 2, summary.total());
        assertEquals(baseline.active() + 1, summary.active());
        assertEquals(baseline.failed() + 1, summary.failed());
        var current = command(id); succeed(current);
        mvc.perform(get("/api/bags").param("search", id.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].confirmedSteps").value(0))
                .andExpect(jsonPath("$.items[0].bag.confirmedNodeId").value("checkin-main"));
        receive(observation(current));
        mvc.perform(get("/api/bags").param("search", id.toString())).andExpect(jsonPath("$.items[0].confirmedSteps").value(1));
        current = command(id); succeed(current); receive(observation(current));
        mvc.perform(get("/api/bags").param("search", bag(id).bagTag().toLowerCase()).param("state", "AT_MAKEUP"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].destinationNodeId").value("makeup-lhr"))
                .andExpect(jsonPath("$.items[0].confirmedSteps").value(2));
        mvc.perform(get("/api/bags").param("search", failed.toString()).param("state", "FLIGHT_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.items[0].bag.failureCode").value("UNKNOWN_FLIGHT"));
        assertTrue(bags.list("%", null, 0, 25).items().isEmpty(), "Wildcards must be literal search text");
        var first = bags.list("P4-", null, 0, 1); var second = bags.list("P4-", null, 1, 1);
        assertNotEquals(first.items().getFirst().bagId(), second.items().getFirst().bagId());
        assertEquals(first.total(), second.total());
        assertEquals(baseline.completed() + 1, bags.summary().completed());
    }

    @Test void durableAcceptanceAndConcurrentIdempotencyAreAtomic() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = new CheckInUseCase.Request("P4-" + UUID.randomUUID(), FLIGHT);
        try (var executor = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            var calls = new ArrayList<Future<CheckInUseCase.Accepted>>();
            for (int i = 0; i < 4; i++) calls.add(executor.submit(() -> { start.await(); return checkin.checkIn(key, request); }));
            start.countDown();
            var accepted = calls.getFirst().get(15, TimeUnit.SECONDS);
            for (var call : calls) assertEquals(accepted, call.get(15, TimeUnit.SECONDS));
            UUID id = accepted.bagId();
            assertEquals(1L, transitions(id, "CHECKED_IN"));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM vortex.outbox WHERE aggregate_id = ?", Integer.class, id.toString()));
            // A separate connection sees the complete committed acceptance.
            try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var statement = connection.prepareStatement("SELECT count(*) FROM vortex.checkin_requests WHERE bag_id = ?")) {
                statement.setObject(1, id);
                try (var rs = statement.executeQuery()) { assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); }
            }
            assertThrows(WorkflowConflictException.class, () -> checkin.checkIn(key, new CheckInUseCase.Request(request.bagTag(), "OTHER")));
            assertThrows(WorkflowConflictException.class, () -> checkin.checkIn(UUID.randomUUID().toString(), request));
            String rolledBackTag = "P4-" + UUID.randomUUID();
            assertThrows(IllegalStateException.class, () -> transactions.execute(s -> {
                checkin.checkIn(UUID.randomUUID().toString(), new CheckInUseCase.Request(rolledBackTag, FLIGHT));
                throw new IllegalStateException("injected before commit");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM vortex.bags WHERE bag_tag = ?", Integer.class, rolledBackTag));
        }
    }

    @Test void commandIssueUsesDatabaseAndPythonMicrosecondPrecision() {
        clock.advanceNanos(999); // PostgreSQL would round this up; Python truncates it.
        UUID id = startedBag(); EventEnvelope command = command(id);
        Instant wireIssue = Instant.parse(command.payload().path("issuedAt").asText());
        assertEquals(0, wireIssue.getNano() % 1000);
        var databaseIssue = jdbc.queryForObject("SELECT issued_at FROM vortex.movement_commands WHERE command_id = ?", java.sql.Timestamp.class, command.eventId());
        assertEquals(wireIssue, databaseIssue.toInstant());
        for (String status : List.of("ACCEPTED", "STARTED", "SUCCEEDED")) {
            EventEnvelope report = result(command, status);
            ((ObjectNode) report.payload()).put("reportedAt", wireIssue.toString());
            receive(report);
        }
        EventEnvelope scan = observation(command);
        ((ObjectNode) scan.payload()).put("observedAt", wireIssue.toString());
        receive(scan);
        assertEquals("junction-west", bag(id).confirmedNodeId());
        assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
    }

    @Test void happyJourneyRequiresEachScannerAndFinalReplayIsANoop() {
        UUID id = startedBag();
        EventEnvelope first = command(id);
        assertEquals("checkin-main", bag(id).confirmedNodeId());
        long pendingVersion = bag(id).stateVersion();
        receive(result(first, "ACCEPTED"));
        assertEquals(pendingVersion, bag(id).stateVersion());
        receive(result(first, "STARTED")); receive(result(first, "SUCCEEDED"));
        assertEquals("checkin-main", bag(id).confirmedNodeId());
        assertEquals(BagState.IN_TRANSIT, bag(id).state());
        receive(observation(first));
        assertEquals("junction-west", bag(id).confirmedNodeId());
        assertEquals(1L, transitions(id, "AT_INTERMEDIATE_NODE"));
        assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
        EventEnvelope last = command(id);
        assertNotEquals(first.eventId(), last.eventId());
        succeed(last);
        assertEquals("junction-west", bag(id).confirmedNodeId());
        EventEnvelope finalScan = observation(last);
        receive(finalScan);
        assertEquals(BagState.AT_MAKEUP, bag(id).state());
        assertEquals("makeup-lhr", bag(id).confirmedNodeId());
        assertEquals("COMPLETE", routes.latest(id).orElseThrow().status());
        assertEquals("COMPLETE", movement.progress(id).getFirst().get("status"));
        long version = bag(id).stateVersion();
        receive(finalScan); receive(result(last, "STARTED"));
        assertEquals(version, bag(id).stateVersion());
        assertEquals(1L, transitions(id, "AT_MAKEUP"));
        assertEquals(2, movement.commands(id).size());
        assertEquals(2, movement.observations(id).size());
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "FL-999-2030-01-15"})
    void unknownAndCancelledFlightsFailAsynchronously(String flight) {
        var accepted = checkin(flight);
        assertEquals(BagState.CHECKED_IN, bag(accepted.bagId()).state());
        drain(accepted.bagId());
        assertEquals(BagState.FLIGHT_VERIFICATION_FAILED, bag(accepted.bagId()).state());
        assertTrue(movement.commands(accepted.bagId()).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"DEPARTED", "WRONG_ORIGIN", "NOT_OPEN", "CLOSED"})
    void eligibilityFailuresAreDurable(String scenario) {
        switch (scenario) {
            case "DEPARTED" -> jdbc.update("UPDATE vortex.flights SET status = 'DEPARTED' WHERE flight_id = ?", FLIGHT);
            case "WRONG_ORIGIN" -> jdbc.update("UPDATE vortex.flights SET departure_airport = 'LAX' WHERE flight_id = ?", FLIGHT);
            case "NOT_OPEN" -> jdbc.update("UPDATE vortex.flights SET check_in_opens_at = '2029-01-01T00:00:00Z' WHERE flight_id = ?", FLIGHT);
            case "CLOSED" -> jdbc.update("UPDATE vortex.flights SET check_in_closes_at = '2025-01-01T00:00:00Z' WHERE flight_id = ?", FLIGHT);
        }
        var accepted = checkin(FLIGHT); drain(accepted.bagId());
        assertEquals(BagState.FLIGHT_VERIFICATION_FAILED, bag(accepted.bagId()).state());
        assertNotNull(bag(accepted.bagId()).failureCode());
    }

    @Test void noRouteIsVisibleAndOnlyExplicitRetryPlansAfterCorrection() {
        jdbc.update("UPDATE vortex.equipment SET status = 'BLOCKED' WHERE equipment_id = 'diverter-lhr'");
        UUID id = checkin(FLIGHT).bagId(); drain(id);
        assertEquals(BagState.ROUTING_BLOCKED, bag(id).state());
        assertEquals("BLOCKED", routes.latest(id).orElseThrow().status());
        assertTrue(routes.latest(id).orElseThrow().decisionSummary().contains("EQUIPMENT_NOT_AVAILABLE"));
        String key = UUID.randomUUID().toString();
        var failedRetry = retry.retry(id, key);
        assertThrows(WorkflowConflictException.class, () -> retry.retry(id, UUID.randomUUID().toString()));
        drain(id);
        assertEquals(BagState.ROUTING_BLOCKED, bag(id).state());
        assertEquals(1L, transitions(id, "ROUTING_BLOCKED"));
        assertEquals(failedRetry, retry.retry(id, key));
        jdbc.update("UPDATE vortex.equipment SET status = 'AVAILABLE' WHERE equipment_id = 'diverter-lhr'");
        assertEquals(BagState.ROUTING_BLOCKED, bag(id).state());
        retry.retry(id, UUID.randomUUID().toString()); drain(id);
        assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
        assertEquals(3, routes.latest(id).orElseThrow().routeNumber());
        assertNull(bag(id).failureCode());
    }

    @ParameterizedTest @ValueSource(strings = {"REJECTED", "FAILED"})
    void rejectedOrUncertainMovementRequiresReplacementFromLastConfirmedNode(String failure) {
        UUID id = startedBag(); EventEnvelope first = command(id);
        succeed(first); receive(observation(first));
        EventEnvelope old = command(id);
        receive(result(old, failure));
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("junction-west", bag(id).confirmedNodeId());
        String key = UUID.randomUUID().toString();
        var response = retry.retry(id, key); drain(id);
        assertEquals(response, retry.retry(id, key));
        assertNotEquals(old.eventId(), command(id).eventId());
        assertNotEquals(old.payload().get("routeId"), command(id).payload().get("routeId"));
        assertEquals("junction-west", command(id).payload().path("fromNodeId").asText());
        receive(result(old, "SUCCEEDED")); receive(observation(old));
        assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
        assertEquals("junction-west", bag(id).confirmedNodeId());
        assertEquals(3, movement.commands(id).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"belt-west", "diverter-lhr"})
    void phase6EquipmentFailureAtEitherEdgeRecoversFromLastScanner(String equipmentId) {
        UUID id = startedBag();
        var old = command(id);
        if (equipmentId.equals("diverter-lhr")) {
            succeed(old); receive(observation(old)); old = command(id);
        }
        assertEquals(equipmentId, old.payload().path("equipmentId").asText());
        String confirmed = bag(id).confirmedNodeId();
        long historyCount = bags.history(id).size();
        receive(result(old, "FAILED"));
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals(confirmed, bag(id).confirmedNodeId());
        String key = UUID.randomUUID().toString();
        var accepted = retry.retry(id, key);
        drain(id); // Acceptance is durable; replacement planning is asynchronous.
        assertEquals(confirmed, routes.latest(id).orElseThrow().sourceNodeId());
        assertNotEquals(old.payload().path("routeId").asText(), bag(id).routeId().toString());
        for (int step = 0; step < 3 && bag(id).state() != BagState.AT_MAKEUP; step++) {
            var next = command(id); succeed(next); receive(observation(next));
        }
        assertEquals(BagState.AT_MAKEUP, bag(id).state());
        assertEquals(accepted, retry.retry(id, key));
        assertTrue(bags.history(id).size() > historyCount);
        assertEquals(1, transitions(id, "AT_MAKEUP"));
    }

    @Test void phase6MissingFinalScannerNeverFabricatesArrivalAndCanBeReplanned() {
        UUID id = startedBag();
        var first = command(id); succeed(first); receive(observation(first));
        var last = command(id); succeed(last);
        assertEquals("junction-west", bag(id).confirmedNodeId());
        assertEquals(BagState.IN_TRANSIT, bag(id).state());
        assertEquals(0, transitions(id, "AT_MAKEUP"));
        clock.advance(30); timeouts.expire(id);
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("OBSERVATION_TIMEOUT", bag(id).failureCode());
        assertEquals("junction-west", bag(id).confirmedNodeId());
        retry.retry(id, UUID.randomUUID().toString()); drain(id);
        var replacement = command(id);
        assertEquals("junction-west", replacement.payload().path("fromNodeId").asText());
        // A delayed scan for the superseded successful command is diagnostic, not arrival.
        receive(observation(last));
        assertEquals("junction-west", bag(id).confirmedNodeId());
        assertEquals(0, transitions(id, "AT_MAKEUP"));
        succeed(replacement); receive(observation(replacement));
        assertEquals(BagState.AT_MAKEUP, bag(id).state());
        assertEquals(1, transitions(id, "AT_MAKEUP"));
    }

    @Test void thirtySecondCommandDeadlineDoesNotResetOnAcknowledgementOrStart() {
        UUID id = startedBag(); EventEnvelope command = command(id);
        clock.advance(20); receive(result(command, "ACCEPTED"));
        clock.advance(9); receive(result(command, "STARTED"));
        timeouts.expire(id); assertEquals(BagState.IN_TRANSIT, bag(id).state());
        clock.advance(1); timeouts.expire(id); timeouts.expire(id);
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("COMMAND_TIMEOUT", bag(id).failureCode());
        assertEquals("TIMED_OUT", movement.commands(id).getFirst().get("status"));
        assertEquals("checkin-main", bag(id).confirmedNodeId());
        assertEquals(1L, transitions(id, "MOVEMENT_FAILED"));
    }

    @Test void observationHasItsOwnThirtySecondDeadlineFromAcceptedSuccess() {
        UUID id = startedBag(); EventEnvelope command = command(id);
        clock.advance(25); succeed(command);
        clock.advance(29); timeouts.expire(id); assertEquals(BagState.IN_TRANSIT, bag(id).state());
        clock.advance(1); timeouts.expire(id);
        assertEquals("OBSERVATION_TIMEOUT", bag(id).failureCode());
        assertEquals("SUCCEEDED", movement.commands(id).getFirst().get("status"));
        receive(observation(command));
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("checkin-main", bag(id).confirmedNodeId());
    }

    @ParameterizedTest @ValueSource(strings = {"EARLY", "SCANNER", "NODE", "CORRELATION", "CAUSE", "STEP", "ROUTE", "EQUIPMENT", "TIMESTAMP", "UNKNOWN_COMMAND"})
    void wrongAndOutOfOrderObservationsAreDurablyRejected(String mismatch) {
        UUID id = startedBag(); EventEnvelope command = command(id);
        if (!mismatch.equals("EARLY")) succeed(command);
        EventEnvelope scan = observation(command);
        ObjectNode p = (ObjectNode) scan.payload();
        UUID correlation = scan.correlationId(), cause = scan.causationId();
        switch (mismatch) {
            case "SCANNER" -> p.put("scannerId", "unknown-scanner");
            case "NODE" -> p.put("nodeId", "makeup-lhr");
            case "CORRELATION" -> correlation = UUID.randomUUID();
            case "CAUSE" -> cause = UUID.randomUUID();
            case "STEP" -> p.put("routeStepNo", 2);
            case "ROUTE" -> p.put("routeId", UUID.randomUUID().toString());
            case "EQUIPMENT" -> p.put("equipmentId", "belt-east");
            case "TIMESTAMP" -> p.put("observedAt", clock.instant().minusSeconds(1).toString());
            case "UNKNOWN_COMMAND" -> p.put("commandId", UUID.randomUUID().toString());
        }
        scan = new EventEnvelope(scan.eventId(), scan.eventType(), 1, scan.occurredAt(), scan.producer(), scan.aggregateType(), scan.aggregateId(), correlation, cause, p);
        long version = bag(id).stateVersion(); receive(scan);
        assertEquals(version, bag(id).stateVersion());
        assertEquals("checkin-main", bag(id).confirmedNodeId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND record_type = 'OBSERVATION_DECISION'", Integer.class, id));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT outcome FROM vortex.processed_events WHERE consumer_name = ? AND event_id = ?", String.class, REPORTS, scan.eventId()));
    }

    @Test void movementRetryWithUnavailableRouteBecomesRoutingBlockedWithoutReplayingCommand() {
        UUID id = startedBag(); EventEnvelope command = command(id);
        receive(result(command, "FAILED"));
        jdbc.update("UPDATE vortex.equipment SET status = 'BLOCKED' WHERE equipment_id = 'diverter-lhr'");
        retry.retry(id, UUID.randomUUID().toString()); drain(id);
        assertEquals(BagState.ROUTING_BLOCKED, bag(id).state());
        assertEquals("checkin-main", bag(id).confirmedNodeId());
        assertEquals("BLOCKED", routes.latest(id).orElseThrow().status());
        assertEquals(1, movement.commands(id).size());
        assertEquals("SUPERSEDED", movement.commands(id).getFirst().get("status"));
    }

    @Test void lateResultCannotBeatAnExpiredDeadlineBetweenSweeps() {
        UUID id = startedBag(); EventEnvelope command = command(id);
        receive(result(command, "STARTED"));
        clock.advance(30); receive(result(command, "SUCCEEDED"));
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("COMMAND_TIMEOUT", bag(id).failureCode());
        assertEquals("checkin-main", bag(id).confirmedNodeId());
    }

    @Test void outOfOrderResultsAreAcknowledgedAsRejectedWithoutRegressingCommandOrBag() {
        UUID id = startedBag(); EventEnvelope command = command(id);
        receive(result(command, "SUCCEEDED"));
        assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
        assertEquals("ISSUED", movement.commands(id).getFirst().get("status"));
        receive(result(command, "STARTED"));
        long version = bag(id).stateVersion();
        receive(result(command, "ACCEPTED"));
        assertEquals(version, bag(id).stateVersion());
        assertEquals("STARTED", movement.commands(id).getFirst().get("status"));
        receive(result(command, "SUCCEEDED"));
        assertEquals("checkin-main", bag(id).confirmedNodeId());
    }

    @Test void unavailableEquipmentBetweenPlanningAndIssuanceFailsWithoutSendingACommand() {
        UUID id = checkin(FLIGHT).bagId();
        dispatch(pendingWorkflow(id).getFirst());
        EventEnvelope verified = pendingWorkflow(id).stream().filter(e -> e.eventType() == EventType.FLIGHT_STATUS_VERIFIED).findFirst().orElseThrow();
        dispatch(verified);
        jdbc.update("UPDATE vortex.equipment SET status = 'OFFLINE' WHERE equipment_id = 'belt-west'");
        drain(id);
        assertEquals(BagState.MOVEMENT_FAILED, bag(id).state());
        assertEquals("EQUIPMENT_NOT_AVAILABLE", bag(id).failureCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM vortex.outbox WHERE aggregate_id = ? AND event_type = 'EquipmentMovementCommand'", Integer.class, id.toString()));
    }

    @Test void invalidCheckinFieldsAndRetryBodiesReturn400WithoutDurableEffects() throws Exception {
        for (String body : List.of("{}", "{\"bagTag\":\" \",\"flightId\":\"FL\"}", "{\"bagTag\":\"BAG\",\"flightId\":null}",
                "{\"bagTag\":\"" + "x".repeat(129) + "\",\"flightId\":\"FL\"}")) {
            mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", " ").contentType("application/json").content("{\"bagTag\":\"BAG\",\"flightId\":\"FL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void finalGuardUsesVerifiedDestinationAndConfiguredMakeupScanner() {
        UUID id = startedBag(); EventEnvelope first = command(id); succeed(first); receive(observation(first));
        EventEnvelope last = command(id); succeed(last);
        jdbc.update("UPDATE vortex.network_nodes SET airport_destination_code = 'CDG' WHERE node_id = 'makeup-lhr'");
        try {
            receive(observation(last));
            assertEquals(BagState.IN_TRANSIT, bag(id).state());
            assertEquals("junction-west", bag(id).confirmedNodeId());
            assertEquals(0L, transitions(id, "AT_MAKEUP"));
        } finally { jdbc.update("UPDATE vortex.network_nodes SET airport_destination_code = 'LHR' WHERE node_id = 'makeup-lhr'"); }
    }

    @Test void explicitFlightRetryIsFencedAndRetryKeyIncludesBagPath() {
        jdbc.update("UPDATE vortex.flights SET status = 'CANCELLED' WHERE flight_id = ?", FLIGHT);
        UUID id = checkin(FLIGHT).bagId(); EventEnvelope old = pendingWorkflow(id).getFirst(); drain(id);
        assertEquals(BagState.FLIGHT_VERIFICATION_FAILED, bag(id).state());
        jdbc.update("UPDATE vortex.flights SET status = 'SCHEDULED' WHERE flight_id = ?", FLIGHT);
        String key = UUID.randomUUID().toString(); var accepted = retry.retry(id, key);
        // A delayed old fact has a different event ID from the accepted retry.
        EventEnvelope stale = events.bag(EventType.BAG_CHECKED_IN, id, bag(id).correlationId(), old.eventId(), (ObjectNode) old.payload());
        dispatch(stale);
        assertEquals(BagState.CHECKED_IN, bag(id).state());
        drain(id); assertEquals(BagState.MOVEMENT_PENDING, bag(id).state());
        assertEquals(accepted, retry.retry(id, key));
        UUID other = checkin("UNKNOWN").bagId(); drain(other);
        assertThrows(WorkflowConflictException.class, () -> retry.retry(other, key));
        assertThrows(WorkflowConflictException.class, () -> retry.retry(id, UUID.randomUUID().toString()));
    }

    @Test void equipmentReportsAreCanonicalOrderedAndUnknownEquipmentIsRejected() {
        UUID report = UUID.randomUUID(), correlation = UUID.randomUUID();
        assertTrue(equipment.update("belt-west", EquipmentStatus.BLOCKED, clock.instant(), report, correlation, "Demo blocked"));
        assertFalse(equipment.update("belt-west", EquipmentStatus.AVAILABLE, clock.instant().minusSeconds(1), UUID.randomUUID(), correlation, null));
        assertFalse(equipment.update("unknown", EquipmentStatus.AVAILABLE, clock.instant(), UUID.randomUUID(), correlation, null));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.outbox WHERE aggregate_id = 'belt-west' AND envelope->'payload'->>'sourceReportId' = ?", Integer.class, report.toString()));
    }

    @Test void restQueriesAndResponsesMatchTheDurableJourney() throws Exception {
        String key = UUID.randomUUID().toString(), tag = "P4-" + UUID.randomUUID();
        String body = "{\"bagTag\":\"" + tag + "\",\"flightId\":\"" + FLIGHT + "\"}";
        var response = mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("CHECKED_IN")).andReturn();
        UUID id = UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("bagId").asText());
        mvc.perform(get("/api/bags/{id}/route", id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(content().json(response.getResponse().getContentAsString()));
        mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BAG_TAG"));
        drain(id);
        mvc.perform(get("/api/bags/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("MOVEMENT_PENDING"));
        mvc.perform(get("/api/bags/{id}/route", id)).andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].cost").value(4));
        mvc.perform(get("/api/bags/{id}/history", id)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].currentState").value("CHECKED_IN"));
        mvc.perform(get("/api/bags/{id}/movement", id)).andExpect(status().isOk()).andExpect(jsonPath("$.commands[0].status").value("ISSUED"));
        mvc.perform(get("/api/flights?date=2030-01-15&origin=JFK")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/equipment")).andExpect(status().isOk());
        mvc.perform(get("/api/equipment/belt-west")).andExpect(status().isOk()).andExpect(jsonPath("$.equipmentId").value("belt-west"));
        mvc.perform(post("/api/bags/{id}/retry", id).header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/bags/check-in'].post").exists());
    }
}
