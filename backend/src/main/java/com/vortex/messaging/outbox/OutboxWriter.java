package com.vortex.messaging.outbox;

import com.vortex.messaging.envelope.AggregateType;
import com.vortex.messaging.envelope.EventEnvelope;
import com.vortex.messaging.envelope.EventEnvelopeJsonCodec;
import com.vortex.messaging.kafka.KafkaTopics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Adds an event to the transactional outbox in the caller's PostgreSQL transaction. */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final EventEnvelopeJsonCodec codec;

    public OutboxWriter(JdbcTemplate jdbc, EventEnvelopeJsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Transactional
    public long append(EventEnvelope envelope) {
        String topic = KafkaTopics.forJavaOutbox(envelope.eventType());
        byte[] bytes = codec.serialize(envelope);
        long sequence = nextSequence(envelope);
        Timestamp createdAt = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO vortex.outbox (
                    event_id, event_type, event_version, topic, message_key, envelope,
                    aggregate_type, aggregate_id, aggregate_sequence, status, attempts,
                    created_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', 0, ?, ?)
                RETURNING outbox_id
                """, Long.class,
                envelope.eventId(), envelope.eventType().wireName(), envelope.eventVersion(), topic,
                envelope.aggregateId(), new String(bytes, StandardCharsets.UTF_8),
                envelope.aggregateType().name(), envelope.aggregateId(), sequence, createdAt, createdAt);
    }

    private long nextSequence(EventEnvelope envelope) {
        String table = envelope.aggregateType() == AggregateType.BAG ? "bags" : "equipment";
        String idColumn = envelope.aggregateType() == AggregateType.BAG ? "bag_id" : "equipment_id";
        String update = "UPDATE vortex." + table + " SET event_sequence = event_sequence + 1 WHERE "
                + idColumn + " = ? RETURNING event_sequence";
        Object aggregateId = envelope.aggregateType() == AggregateType.BAG
                ? UUID.fromString(envelope.aggregateId()) : envelope.aggregateId();
        Long sequence = jdbc.query(update, rs -> rs.next() ? rs.getLong(1) : null, aggregateId);
        if (sequence == null) {
            throw new IllegalArgumentException("outbox aggregate does not exist: " + envelope.aggregateType());
        }
        return sequence;
    }
}
