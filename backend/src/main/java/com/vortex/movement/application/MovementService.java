package com.vortex.movement.application;

import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.*;
import com.vortex.movement.api.*;
import com.vortex.movement.domain.*;
import com.vortex.routing.api.*;
import com.vortex.routing.api.RouteQuery.StoredRoute;
import com.vortex.routing.domain.RouteStep;
import com.vortex.routing.domain.EquipmentStatus;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional
public class MovementService implements MovementStarter, MovementReports, MovementRecovery, MovementTimeouts {
    private final JdbcTemplate jdbc;
    private final BagLifecycle bags;
    private final RouteQuery routes;
    private final RoutePlanLifecycle lifecycle;
    private final EquipmentAvailability equipment;
    private final EquipmentStatusUpdater equipmentStatus;
    private final OutboxWriter outbox;
    private final EventFactory events;
    private final Clock clock;
    private final Duration commandTimeout;
    private final Duration observationTimeout;

    public MovementService(JdbcTemplate jdbc, BagLifecycle bags, RouteQuery routes, RoutePlanLifecycle lifecycle,
                           EquipmentAvailability equipment, EquipmentStatusUpdater equipmentStatus, OutboxWriter outbox, EventFactory events, Clock clock,
                           @Value("${vortex.movement.command-timeout:30s}") Duration commandTimeout,
                           @Value("${vortex.movement.observation-timeout:30s}") Duration observationTimeout) {
        this.jdbc = jdbc; this.bags = bags; this.routes = routes; this.lifecycle = lifecycle; this.equipment = equipment;
        this.equipmentStatus = equipmentStatus;
        this.outbox = outbox; this.events = events; this.clock = clock;
        if (commandTimeout.isNegative() || commandTimeout.isZero() || observationTimeout.isNegative() || observationTimeout.isZero())
            throw new IllegalArgumentException("Movement deadlines must be positive");
        this.commandTimeout = commandTimeout; this.observationTimeout = observationTimeout;
    }

    public boolean start(UUID id, UUID routeId, UUID source, UUID correlation) {
        BagSnapshot bag = bags.lock(id).orElse(null);
        if (bag == null) return false;
        if (!bag.expects(source, correlation) || bag.state() != BagState.ROUTE_PLANNED || !routeId.equals(bag.routeId()))
            return reject(bag, source, "STALE_MOVEMENT_WORKFLOW", clock.instant(), null);
        StoredRoute route = routes.route(routeId).orElseThrow();
        if (!route.status().equals("PLANNED") || route.steps().isEmpty())
            return reject(bag, source, "ROUTE_NOT_STARTABLE", clock.instant(), null);
        jdbc.update("INSERT INTO vortex.route_progress (route_id, bag_id, next_step_no, last_confirmed_node_id, status, updated_at) VALUES (?, ?, 1, ?, 'READY', ?)",
                routeId, id, bag.confirmedNodeId(), now());
        lifecycle.activate(routeId);
        bags.workflow(id, null);
        issue(bag, route, 1, source);
        return true;
    }

    private void issue(BagSnapshot bag, StoredRoute route, int stepNo, UUID cause) {
        RouteStep step = route.steps().get(stepNo - 1);
        UUID command = UUID.randomUUID();
        // PostgreSQL and Python retain microseconds; normalize before storing and sending.
        Instant issued = clock.instant().truncatedTo(ChronoUnit.MICROS), deadline = issued.plus(commandTimeout).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO vortex.movement_commands (command_id, bag_id, route_id, route_step_no, attempt, equipment_id,
                  from_node_id, to_node_id, status, issued_at, deadline_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?, 'ISSUED', ?, ?)
                """, command, bag.bagId(), route.routeId(), stepNo, step.equipmentId(), step.fromNodeId(), step.toNodeId(),
                timestamp(issued), timestamp(deadline));
        bags.selection(bag.bagId(), route.routeId(), command);
        jdbc.update("UPDATE vortex.route_progress SET active_command_id = ?, status = 'WAITING_FOR_COMMAND', updated_at = ?, row_version = row_version + 1 WHERE route_id = ?",
                command, now(), route.routeId());
        BagSnapshot pending = bags.transition(bag.bagId(), stepNo == 1 ? BagTransitionTrigger.FIRST_COMMAND_ISSUED : BagTransitionTrigger.NEXT_COMMAND_ISSUED,
                command, issued, null, null, null);
        if (!equipment.available(step.equipmentId()) || !equipment.available(step.expectedScannerId())) {
            fail(pending, "REJECTED", "EQUIPMENT_NOT_AVAILABLE", "Equipment or checkpoint scanner is unavailable", UUID.randomUUID(), issued);
            return;
        }
        outbox.append(events.bag(command, EventType.EQUIPMENT_MOVEMENT_COMMAND, bag.bagId(), bag.correlationId(), cause,
                events.payload("commandId", command, "bagId", bag.bagId(), "routeId", route.routeId(), "routeStepNo", stepNo,
                        "fromNodeId", step.fromNodeId(), "toNodeId", step.toNodeId(), "equipmentId", step.equipmentId(),
                        "attempt", 1, "issuedAt", issued, "deadlineAt", deadline)));
    }

    public boolean result(EquipmentCommandResult result, UUID source, UUID correlation, UUID cause) {
        BagSnapshot bag = bags.lock(result.bagId()).orElse(null);
        if (bag == null) return false;
        expireLocked(bag);
        bag = bags.lock(result.bagId()).orElseThrow();
        Map<String, Object> command = command(result.commandId());
        String mismatch = match(bag, command, result.routeId(), result.commandId(), result.routeStepNo(), result.equipmentId(), correlation, cause, result.reportedAt());
        if (mismatch != null) return reject(bag, source, mismatch, result.reportedAt(), events.payload("commandId", result.commandId(), "status", result.status()).toString());
        String old = (String) command.get("status");
        boolean valid = switch (result.status()) {
            case ACCEPTED -> old.equals("ISSUED") && bag.state() == BagState.MOVEMENT_PENDING;
            case STARTED -> Set.of("ISSUED", "ACCEPTED").contains(old) && bag.state() == BagState.MOVEMENT_PENDING;
            case SUCCEEDED -> old.equals("STARTED") && bag.state() == BagState.IN_TRANSIT;
            case FAILED, REJECTED -> Set.of("ISSUED", "ACCEPTED", "STARTED").contains(old)
                    && Set.of(BagState.MOVEMENT_PENDING, BagState.IN_TRANSIT).contains(bag.state());
            default -> false;
        };
        Timestamp started = (Timestamp) command.get("started_at");
        if (!valid || (started != null && result.reportedAt().isBefore(started.toInstant())))
            return reject(bag, source, "OUT_OF_ORDER_COMMAND_RESULT", result.reportedAt(), null);
        switch (result.status()) {
            case ACCEPTED -> {
                jdbc.update("UPDATE vortex.movement_commands SET status = 'ACCEPTED' WHERE command_id = ?", result.commandId());
                bags.record(bag.bagId(), "WORKFLOW_RESULT", "COMMAND_ACKNOWLEDGED", source, result.reportedAt(), null);
            }
            case STARTED -> {
                jdbc.update("UPDATE vortex.movement_commands SET status = 'STARTED', started_at = ? WHERE command_id = ?", timestamp(result.reportedAt()), result.commandId());
                bags.transition(bag.bagId(), BagTransitionTrigger.COMMAND_STARTED, source, result.reportedAt(), null, null, null);
            }
            case SUCCEEDED -> {
                jdbc.update("UPDATE vortex.movement_commands SET status = 'SUCCEEDED', completed_at = ? WHERE command_id = ?", timestamp(result.reportedAt()), result.commandId());
                jdbc.update("UPDATE vortex.route_progress SET status = 'WAITING_FOR_OBSERVATION', observation_deadline_at = ?, updated_at = ?, row_version = row_version + 1 WHERE route_id = ?",
                        timestamp(clock.instant().plus(observationTimeout)), now(), result.routeId());
                bags.record(bag.bagId(), "WORKFLOW_RESULT", "COMMAND_SUCCEEDED_AWAIT_SCANNER", source, result.reportedAt(), null);
            }
            case FAILED, REJECTED -> fail(bag, result.status().name(), result.failureCode().orElse("COMMAND_" + result.status()),
                    result.safeReason().orElse("Movement command did not complete"), source, result.reportedAt());
            default -> throw new IllegalStateException("unexpected command status");
        }
        return true;
    }

    public boolean observation(ScannerObservation observation, UUID source, UUID correlation, UUID cause, String payload) {
        BagSnapshot bag = bags.lock(observation.bagId()).orElse(null);
        if (bag == null) return false;
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vortex.equipment_observations WHERE observation_id = ?)", Boolean.class, observation.observationId()))) {
            return reject(bag, source, "DUPLICATE_OBSERVATION", observation.observedAt(), payload);
        }
        expireLocked(bag);
        bag = bags.lock(observation.bagId()).orElseThrow();
        Map<String, Object> command = command(observation.commandId());
        String rejection = match(bag, command, observation.routeId(), observation.commandId(), observation.routeStepNo(),
                observation.equipmentId(), correlation, cause, observation.observedAt());
        StoredRoute route = routes.route(observation.routeId()).orElse(null);
        RouteStep step = route == null || observation.routeStepNo() > route.steps().size() ? null : route.steps().get(observation.routeStepNo() - 1);
        boolean finalStep = route != null && observation.routeStepNo() == route.steps().size();
        if (rejection == null && (step == null || !step.toNodeId().equals(observation.nodeId()) || !step.expectedScannerId().equals(observation.scannerId()))) rejection = "WRONG_CHECKPOINT";
        if (rejection == null && (!"SUCCEEDED".equals(command.get("status")) || bag.state() != BagState.IN_TRANSIT)) rejection = "COMMAND_NOT_SUCCEEDED";
        if (rejection == null && observation.observedAt().isBefore(((Timestamp) command.get("completed_at")).toInstant())) rejection = "OBSERVATION_BEFORE_COMPLETION";
        if (rejection == null && finalStep && !routes.finalMakeup(route.routeId(), observation.nodeId(), observation.scannerId(), bag.verifiedDestinationAirport())) rejection = "FINAL_MAKEUP_GUARD_FAILED";
        Map<String, Object> progress = progress(observation.routeId());
        if (rejection == null && (progress == null || !"WAITING_FOR_OBSERVATION".equals(progress.get("status"))
                || ((Number) progress.get("next_step_no")).intValue() != observation.routeStepNo())) rejection = "INACTIVE_ROUTE_STEP";
        persistObservation(observation, source, correlation, rejection, payload);
        if (rejection != null) {
            bags.record(bag.bagId(), "OBSERVATION_DECISION", rejection, source, observation.observedAt(), payload);
            return false;
        }
        ScannerEvidence evidence = new ScannerEvidence(observation.nodeId(), true, true, true, true, true, true, true, true,
                finalStep, finalStep, finalStep);
        BagSnapshot confirmed = bags.transition(bag.bagId(), finalStep ? BagTransitionTrigger.FINAL_MAKEUP_SCANNER_ACCEPTED : BagTransitionTrigger.INTERMEDIATE_SCANNER_ACCEPTED,
                source, observation.observedAt(), "SCANNER_OBSERVATION_ACCEPTED", null, evidence);
        jdbc.update("""
                UPDATE vortex.route_progress SET next_step_no = next_step_no + 1, last_confirmed_node_id = ?,
                  active_command_id = NULL, status = ?, observation_deadline_at = NULL, updated_at = ?, row_version = row_version + 1
                WHERE route_id = ?
                """, observation.nodeId(), finalStep ? "COMPLETE" : "READY", now(), route.routeId());
        if (finalStep) {
            lifecycle.complete(route.routeId());
            bags.selection(bag.bagId(), route.routeId(), null);
        } else {
            issue(confirmed, route, observation.routeStepNo() + 1, source);
        }
        return true;
    }

    private String match(BagSnapshot bag, Map<String, Object> command, UUID route, UUID commandId, int step, String item,
                         UUID correlation, UUID cause, Instant reportedAt) {
        if (command == null) return "UNKNOWN_COMMAND";
        if (!bag.correlationId().equals(correlation) || !commandId.equals(cause)) return "REPORT_CORRELATION_MISMATCH";
        if (!route.equals(bag.routeId()) || !commandId.equals(bag.activeCommandId()) || !bag.bagId().equals(command.get("bag_id"))
                || !route.equals(command.get("route_id"))) return "INACTIVE_ROUTE_OR_COMMAND";
        if (((Number) command.get("route_step_no")).intValue() != step || !item.equals(command.get("equipment_id"))) return "WRONG_STEP_OR_EQUIPMENT";
        if (reportedAt.isBefore(((Timestamp) command.get("issued_at")).toInstant())) return "REPORT_BEFORE_ISSUE";
        if (Set.of("SUPERSEDED", "TIMED_OUT", "REJECTED", "FAILED").contains(command.get("status"))) return "TERMINAL_COMMAND";
        return null;
    }

    private void persistObservation(ScannerObservation o, UUID source, UUID correlation, String rejection, String payload) {
        // Unknown references cannot satisfy the business FKs; their full decision is retained in bag history.
        boolean referencesExist = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vortex.route_progress WHERE route_id = ?)
                AND EXISTS(SELECT 1 FROM vortex.movement_commands WHERE command_id = ?)
                AND EXISTS(SELECT 1 FROM vortex.equipment WHERE equipment_id = ?)
                AND EXISTS(SELECT 1 FROM vortex.equipment WHERE equipment_id = ?)
                """, Boolean.class, o.routeId(), o.commandId(), o.equipmentId(), o.scannerId()));
        if (!referencesExist) return;
        jdbc.update("""
                INSERT INTO vortex.equipment_observations (observation_id, event_id, bag_id, route_id, command_id,
                  route_step_no, equipment_id, node_id, scanner_id, observed_at, received_at, decision, rejection_code,
                  envelope_correlation_id, validated_payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, o.observationId(), source, o.bagId(), o.routeId(), o.commandId(), o.routeStepNo(), o.equipmentId(),
                o.nodeId(), o.scannerId(), timestamp(o.observedAt()), now(), rejection == null ? "ACCEPTED" : "REJECTED", rejection, correlation, payload);
    }

    public void expire(UUID id) { bags.lock(id).ifPresent(this::expireLocked); }
    public boolean equipmentStatus(String id, EquipmentStatus status, Instant reported, UUID report, UUID correlation, String reason) {
        return equipmentStatus.update(id, status, reported, report, correlation, reason);
    }
    private void expireLocked(BagSnapshot bag) {
        if (bag.activeCommandId() == null || !Set.of(BagState.MOVEMENT_PENDING, BagState.IN_TRANSIT).contains(bag.state())) return;
        Map<String, Object> command = command(bag.activeCommandId());
        if (Set.of("ISSUED", "ACCEPTED", "STARTED").contains(command.get("status"))
                && !clock.instant().isBefore(((Timestamp) command.get("deadline_at")).toInstant())) {
            fail(bag, "TIMED_OUT", "COMMAND_TIMEOUT", "No terminal result arrived before the command deadline", UUID.randomUUID(), clock.instant());
        } else if ("SUCCEEDED".equals(command.get("status"))) {
            Map<String, Object> progress = progress(bag.routeId());
            Timestamp deadline = (Timestamp) progress.get("observation_deadline_at");
            if ("WAITING_FOR_OBSERVATION".equals(progress.get("status")) && deadline != null && !clock.instant().isBefore(deadline.toInstant()))
                fail(bag, null, "OBSERVATION_TIMEOUT", "No matching scanner observation arrived before its deadline", UUID.randomUUID(), clock.instant());
        }
    }

    private void fail(BagSnapshot bag, String commandStatus, String code, String reason, UUID source, Instant occurred) {
        if (commandStatus != null) jdbc.update("UPDATE vortex.movement_commands SET status = ?, completed_at = ?, failure_code = ?, failure_reason = ? WHERE command_id = ?",
                commandStatus, timestamp(occurred), code, reason, bag.activeCommandId());
        jdbc.update("UPDATE vortex.route_progress SET status = 'FAILED', updated_at = ?, row_version = row_version + 1 WHERE route_id = ?", now(), bag.routeId());
        bags.transition(bag.bagId(), BagTransitionTrigger.COMMAND_FAILED, source, occurred, code, reason, null);
    }

    public void supersede(UUID id, UUID routeId) {
        jdbc.update("UPDATE vortex.route_progress SET status = 'SUPERSEDED', updated_at = ?, row_version = row_version + 1 WHERE bag_id = ? AND route_id = ? AND status <> 'COMPLETE'", now(), id, routeId);
        jdbc.update("UPDATE vortex.movement_commands SET status = 'SUPERSEDED' WHERE bag_id = ? AND route_id = ?", id, routeId);
        lifecycle.supersede(routeId);
        bags.selection(id, routeId, null);
    }
    private boolean reject(BagSnapshot bag, UUID source, String code, Instant occurred, String details) {
        bags.record(bag.bagId(), "WORKFLOW_RESULT", code, source, occurred, details);
        return false;
    }
    private Map<String, Object> command(UUID id) {
        return jdbc.queryForList("SELECT * FROM vortex.movement_commands WHERE command_id = ?", id).stream().findFirst().orElse(null);
    }
    private Map<String, Object> progress(UUID id) {
        return jdbc.queryForList("SELECT * FROM vortex.route_progress WHERE route_id = ?", id).stream().findFirst().orElse(null);
    }
    private Timestamp now() { return timestamp(clock.instant()); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
}
