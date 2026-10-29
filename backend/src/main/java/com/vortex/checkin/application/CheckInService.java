package com.vortex.checkin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.bagstate.api.BagLifecycle;
import com.vortex.bagstate.api.BagQuery;
import com.vortex.messaging.envelope.RequestIdentity;
import com.vortex.checkin.api.CheckInUseCase;
import com.vortex.messaging.workflow.WorkflowConflictException;
import com.vortex.routing.api.NetworkCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;

@Service
public class CheckInService implements CheckInUseCase {
    private final JdbcTemplate jdbc;
    private final BagLifecycle bags;
    private final BagQuery queries;
    private final NetworkCatalog network;
    private final ObjectMapper mapper;
    private final Clock clock;
    public CheckInService(JdbcTemplate jdbc, BagLifecycle bags, BagQuery queries, NetworkCatalog network, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.bags = bags; this.queries = queries; this.network = network; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public Accepted checkIn(String key, Request request) {
        RequestIdentity.validateKey(key);
        if (request == null || !RequestIdentity.identifier(request.bagTag()) || !RequestIdentity.identifier(request.flightId())) {
            throw new IllegalArgumentException("bagTag and flightId must contain 1–128 letters, digits, dots, underscores or hyphens");
        }
        String hash = RequestIdentity.hash("POST\n/api/bags/check-in\n" + request.bagTag() + "\n" + request.flightId());
        lock("checkin-key:" + key);
        var previous = jdbc.query("SELECT request_hash, response_body::text FROM vortex.checkin_requests WHERE idempotency_key = ?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, key);
        if (!previous.isEmpty()) {
            if (!previous.getFirst()[0].equals(hash)) throw new WorkflowConflictException("IDEMPOTENCY_KEY_CONFLICT", "Idempotency key was used for another request");
            try { return mapper.readValue(previous.getFirst()[1], Accepted.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("stored check-in response is invalid", e); }
        }
        lock("checkin-tag:" + request.bagTag());
        if (queries.tagExists(request.bagTag())) {
            throw new WorkflowConflictException("DUPLICATE_BAG_TAG", "Bag tag has already been checked in");
        }
        UUID id = UUID.randomUUID(), correlation = UUID.randomUUID();
        bags.create(id, request.bagTag(), request.flightId(), network.active().checkInNodeId(), correlation, UUID.randomUUID());
        Accepted result = new Accepted(id, request.bagTag(), request.flightId(), correlation, "CHECKED_IN",
                "/api/bags/" + id, "/api/bags/" + id + "/history");
        jdbc.update("INSERT INTO vortex.checkin_requests (idempotency_key, request_hash, bag_id, response_status, response_body, created_at) VALUES (?, ?, ?, 202, ?::jsonb, ?)",
                key, hash, id, mapper.valueToTree(result).toString(), Timestamp.from(clock.instant()));
        return result;
    }

    private void lock(String key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key); }
}
