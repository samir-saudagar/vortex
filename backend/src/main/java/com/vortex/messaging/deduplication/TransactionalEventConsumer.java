package com.vortex.messaging.deduplication;

import com.vortex.messaging.envelope.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Makes processed-event insertion and handler effects one transaction. */
@Component
public class TransactionalEventConsumer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TransactionalEventConsumer(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public boolean consume(String consumerName, EventEnvelope event, Supplier<ProcessingOutcome> handler) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO vortex.processed_events (consumer_name, event_id, processed_at, outcome)
                    VALUES (?, ?, ?, 'PROCESSING') ON CONFLICT (consumer_name, event_id) DO NOTHING
                    """, consumerName, event.eventId(), Timestamp.from(Instant.now()));
            if (inserted == 0) return false;
            ProcessingOutcome outcome = Objects.requireNonNull(handler.get(), "handler outcome");
            jdbc.update("UPDATE vortex.processed_events SET outcome = ? WHERE consumer_name = ? AND event_id = ?",
                    outcome.name(), consumerName, event.eventId());
            return true;
        }));
    }
}
