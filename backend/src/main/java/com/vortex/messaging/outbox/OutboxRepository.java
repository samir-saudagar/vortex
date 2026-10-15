package com.vortex.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<OutboxMessage> claimNext(Instant now, Duration lease) {
        String sql = """
                WITH candidate AS (
                    SELECT current_row.outbox_id
                    FROM vortex.outbox current_row
                    WHERE ((current_row.status = 'PENDING' AND current_row.next_attempt_at <= ?)
                        OR (current_row.status = 'IN_FLIGHT' AND current_row.claimed_until <= ?))
                      AND NOT EXISTS (
                          SELECT 1
                          FROM vortex.outbox earlier
                          WHERE earlier.aggregate_type = current_row.aggregate_type
                            AND earlier.aggregate_id = current_row.aggregate_id
                            AND earlier.aggregate_sequence < current_row.aggregate_sequence
                            AND earlier.status <> 'PUBLISHED'
                      )
                    ORDER BY current_row.next_attempt_at, current_row.outbox_id
                    LIMIT 1
                    FOR UPDATE OF current_row SKIP LOCKED
                )
                UPDATE vortex.outbox claimed
                SET status = 'IN_FLIGHT',
                    attempts = claimed.attempts + 1,
                    claimed_until = ?
                FROM candidate
                WHERE claimed.outbox_id = candidate.outbox_id
                RETURNING claimed.outbox_id, claimed.event_id::text, claimed.topic, claimed.message_key,
                          claimed.envelope::text, claimed.aggregate_type, claimed.aggregate_id,
                          claimed.aggregate_sequence, claimed.attempts
                """;
        List<OutboxMessage> rows = jdbc.query(sql, (rs, row) -> new OutboxMessage(
                rs.getLong("outbox_id"), rs.getString("event_id"), rs.getString("topic"),
                rs.getString("message_key"), compactJson(rs.getString("envelope")),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getLong("aggregate_sequence"), rs.getInt("attempts")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plus(lease)));
        return rows.stream().findFirst();
    }

    public boolean markPublished(OutboxMessage message, Instant publishedAt) {
        return jdbc.update("""
                        UPDATE vortex.outbox
                        SET status = 'PUBLISHED', published_at = ?, claimed_until = NULL, last_error = NULL
                        WHERE outbox_id = ? AND status = 'IN_FLIGHT' AND attempts = ?
                        """, Timestamp.from(publishedAt), message.outboxId(), message.attempts()) == 1;
    }

    public boolean markFailure(OutboxMessage message, Instant now, Instant retryAt, boolean exhausted,
                               String safeError) {
        String status = exhausted ? "FAILED" : "PENDING";
        return jdbc.update("""
                        UPDATE vortex.outbox
                        SET status = ?, next_attempt_at = ?, claimed_until = NULL, last_error = ?
                        WHERE outbox_id = ? AND status = 'IN_FLIGHT' AND attempts = ?
                        """, status, Timestamp.from(exhausted ? now : retryAt), safeError,
                message.outboxId(), message.attempts()) == 1;
    }

    public OutboxCounts counts() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'IN_FLIGHT') AS in_flight,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM vortex.outbox
                """, (rs, row) -> new OutboxCounts(
                rs.getLong("pending"), rs.getLong("in_flight"), rs.getLong("failed")));
    }

    public boolean requeueFailed(long outboxId, Instant now) {
        return jdbc.update("""
                UPDATE vortex.outbox SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                    claimed_until = NULL, last_error = NULL
                WHERE outbox_id = ? AND status = 'FAILED'
                """, Timestamp.from(now), outboxId) == 1;
    }

    private byte[] compactJson(String json) {
        try {
            // jsonb::text inserts whitespace; it must not inflate a valid envelope above its byte limit.
            return mapper.writeValueAsBytes(mapper.readTree(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored outbox envelope could not be encoded", exception);
        }
    }

    public record OutboxCounts(long pending, long inFlight, long failed) {
    }
}
