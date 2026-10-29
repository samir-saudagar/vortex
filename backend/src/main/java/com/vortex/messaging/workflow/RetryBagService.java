package com.vortex.messaging.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.*;
import com.vortex.movement.api.MovementRecovery;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Service
public class RetryBagService implements RetryBagUseCase {
    private final JdbcTemplate jdbc;
    private final BagLifecycle bags;
    private final MovementRecovery movement;
    private final OutboxWriter outbox;
    private final EventFactory events;
    private final ObjectMapper mapper;
    private final Clock clock;
    public RetryBagService(JdbcTemplate jdbc, BagLifecycle bags, MovementRecovery movement, OutboxWriter outbox,
                           EventFactory events, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.bags = bags; this.movement = movement; this.outbox = outbox; this.events = events; this.mapper = mapper; this.clock = clock;
    }
    @Transactional
    public Accepted retry(UUID id, String key) {
        RequestIdentity.validateKey(key);
        String hash = RequestIdentity.hash("POST\n/api/bags/" + id + "/retry\n{}");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "retry-key:" + key);
        var previous = jdbc.query("SELECT request_hash, response_body::text FROM vortex.workflow_retry_requests WHERE idempotency_key = ?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, key);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst()[0])) throw new WorkflowConflictException("IDEMPOTENCY_KEY_CONFLICT", "Retry key was used for another request");
            try { return mapper.readValue(previous.getFirst()[1], Accepted.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("stored retry response is invalid", e); }
        }
        BagSnapshot bag = bags.lock(id).orElseThrow(() -> new ResourceNotFoundException("BAG_NOT_FOUND", "Bag was not found"));
        if (!Set.of(BagState.FLIGHT_VERIFICATION_FAILED, BagState.ROUTING_BLOCKED, BagState.MOVEMENT_FAILED).contains(bag.state()) || bag.workflowEventId() != null)
            throw new WorkflowConflictException("BAG_NOT_RETRYABLE", "Bag is not in a failed stage or already has a pending retry");
        UUID retry = UUID.randomUUID();
        bags.record(id, "RETRY_REQUEST", "EXPLICIT_RETRY_ACCEPTED", retry, clock.instant(), null);
        EventEnvelope fact;
        if (bag.state() == BagState.FLIGHT_VERIFICATION_FAILED) {
            bag = bags.transition(id, BagTransitionTrigger.FLIGHT_RETRY_REQUESTED, null, clock.instant(), null, null, null);
            fact = events.bag(EventType.BAG_CHECKED_IN, id, bag.correlationId(), retry, events.payload("bagId", id,
                    "bagTag", bag.bagTag(), "flightId", bag.flightId(), "checkInNodeId", bag.checkInNodeId(), "checkedInAt", bag.checkedInAt()));
        } else {
            if (bag.state() == BagState.MOVEMENT_FAILED) movement.supersede(id, bag.routeId());
            fact = events.bag(EventType.FLIGHT_STATUS_VERIFIED, id, bag.correlationId(), retry, events.payload("bagId", id,
                    "flightId", bag.flightId(), "departureAirport", bag.verifiedDepartureAirport(), "destinationAirport", bag.verifiedDestinationAirport(),
                    "flightStatus", bag.verifiedFlightStatus(), "checkedAt", bag.verifiedAt()));
        }
        bags.workflow(id, fact.eventId());
        outbox.append(fact);
        Accepted result = new Accepted(id, bag.correlationId(), bag.state().name());
        jdbc.update("INSERT INTO vortex.workflow_retry_requests (idempotency_key, request_hash, bag_id, retry_id, accepted_at, response_body) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                key, hash, id, retry, Timestamp.from(clock.instant()), mapper.valueToTree(result).toString());
        return result;
    }
}
