package com.vortex.bagstate.application;

import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.*;
import com.vortex.bagstate.persistence.JdbcBagRepository;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class PersistentBagLifecycle implements BagLifecycle, BagQuery {
    private final JdbcTemplate jdbc;
    private final JdbcBagRepository repository;
    private final OutboxWriter outbox;
    private final EventFactory events;
    private final Clock clock;
    private final BagStateTransitionPolicy policy = new BagStateTransitionPolicy();

    public PersistentBagLifecycle(JdbcTemplate jdbc, JdbcBagRepository repository, OutboxWriter outbox,
                                  EventFactory events, Clock clock) {
        this.jdbc = jdbc; this.repository = repository; this.outbox = outbox; this.events = events; this.clock = clock;
    }

    public Optional<BagSnapshot> lock(UUID id) { return repository.find(id, true); }
    public Optional<BagSnapshot> find(UUID id) { return repository.find(id, false); }
    public boolean tagExists(String tag) { return repository.tagExists(tag); }
    public List<HistoryItem> history(UUID id) { return repository.history(id); }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(String search, String state, int page, int size) { return repository.list(search, state, page, size); }
    @Transactional(readOnly = true)
    public Summary summary() { return repository.summary(); }

    public BagSnapshot create(UUID id, String tag, String flight, String node, UUID correlation, UUID root) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO vortex.bags (bag_id, bag_tag, flight_id, check_in_node_id, current_node_id,
                  current_state, correlation_id, state_version, checked_in_at, updated_at, workflow_event_id)
                VALUES (?, ?, ?, ?, ?, 'CHECKED_IN', ?, 1, ?, ?, ?)
                """, id, tag, flight, node, node, correlation, now, now, root);
        BagSnapshot bag = lock(id).orElseThrow();
        // The workflow root is sequence 1, before the initial state notification.
        outbox.append(events.bag(root, EventType.BAG_CHECKED_IN, id, correlation, null, events.payload(
                "bagId", id, "bagTag", tag, "flightId", flight, "checkInNodeId", node, "checkedInAt", clock.instant())));
        UUID history = insertHistory(bag, "STATE_TRANSITION", null, "CHECKED_IN", "CHECK_IN_ACCEPTED", null,
                clock.instant(), null);
        stateEvent(bag, null, "CHECK_IN_ACCEPTED", history, root);
        return bag;
    }

    public BagSnapshot transition(UUID id, BagTransitionTrigger trigger, UUID source, Instant occurred,
                                  String reason, String failureReason, ScannerEvidence evidence) {
        BagSnapshot before = lock(id).orElseThrow();
        TransitionDecision decision = policy.decide(before.state(), trigger, evidence);
        if (!decision.accepted()) {
            record(id, "WORKFLOW_RESULT", decision.reasonCode(), source, occurred, null);
            return before;
        }
        String code = reason == null ? decision.reasonCode() : reason;
        if (!decision.stateChanged()) {
            record(id, "WORKFLOW_RESULT", code, source, occurred, null);
            return before;
        }
        BagState next = decision.currentState().orElseThrow();
        boolean failed = next == BagState.FLIGHT_VERIFICATION_FAILED || next == BagState.ROUTING_BLOCKED
                || next == BagState.MOVEMENT_FAILED;
        int changed = jdbc.update("""
                UPDATE vortex.bags SET current_state = ?, current_node_id = ?, state_version = state_version + 1,
                  failure_code = ?, failure_reason = ?, updated_at = ? WHERE bag_id = ? AND state_version = ?
                """, next.name(), decision.confirmedNodeUpdate().orElse(before.confirmedNodeId()),
                failed ? code : null, failed ? failureReason : null, Timestamp.from(clock.instant()), id, before.stateVersion());
        if (changed != 1) throw new IllegalStateException("bag state version changed while locked");
        BagSnapshot bag = lock(id).orElseThrow();
        UUID history = insertHistory(bag, "STATE_TRANSITION", before.state().name(), next.name(), code,
                source, occurred, null);
        stateEvent(bag, before.state(), code, history, source == null ? history : source);
        return bag;
    }

    public void workflow(UUID id, UUID expected) {
        jdbc.update("UPDATE vortex.bags SET workflow_event_id = ? WHERE bag_id = ?", expected, id);
    }
    public void selection(UUID id, UUID route, UUID command) {
        jdbc.update("UPDATE vortex.bags SET current_route_id = ?, active_command_id = ? WHERE bag_id = ?", route, command, id);
    }
    public void verifiedFlight(UUID id, String departure, String destination, String status, Instant checkedAt) {
        jdbc.update("UPDATE vortex.bags SET verified_departure_airport = ?, verified_destination_airport = ?, verified_flight_status = ?, verified_at = ? WHERE bag_id = ?",
                departure, destination, status, Timestamp.from(checkedAt), id);
    }
    public void record(UUID id, String type, String reason, UUID source, Instant occurred, String details) {
        insertHistory(lock(id).orElseThrow(), type, null, null, reason, source, occurred, details);
    }

    private UUID insertHistory(BagSnapshot bag, String type, String previous, String current, String reason,
                               UUID source, Instant occurred, String details) {
        UUID history = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vortex.bag_history (history_id, bag_id, record_type, previous_state, current_state,
                  node_id, reason_code, details, event_id, route_id, command_id, state_version, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, history, bag.bagId(), type, previous, current, bag.confirmedNodeId(), reason, details, source,
                bag.routeId(), bag.activeCommandId(), bag.stateVersion(), Timestamp.from(occurred), Timestamp.from(clock.instant()));
        return history;
    }

    private void stateEvent(BagSnapshot bag, BagState previous, String reason, UUID history, UUID cause) {
        outbox.append(events.bag(EventType.BAG_STATE_CHANGED, bag.bagId(), bag.correlationId(), cause, events.payload(
                "bagId", bag.bagId(), "previousState", previous, "currentState", bag.state(),
                "confirmedNodeId", bag.confirmedNodeId(), "stateVersion", bag.stateVersion(),
                "reasonCode", reason, "historyId", history)));
    }
}
