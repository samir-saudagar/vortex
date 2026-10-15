package com.vortex.messaging.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Publishes committed outbox rows; a broker ack precedes marking a row PUBLISHED. */
@Component
@ConditionalOnProperty(name = "vortex.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_TOTAL_ATTEMPTS = 21; // initial attempt plus the documented 20 retries

    private final OutboxRepository repository;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final Duration sendTimeout;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, byte[]> kafka,
                       TransactionTemplate transactions,
                       @Value("${vortex.outbox.claim-lease-seconds:30}") long leaseSeconds,
                       @Value("${vortex.outbox.publish-timeout-seconds:10}") long timeoutSeconds,
                       Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.transactions = transactions;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.sendTimeout = Duration.ofSeconds(timeoutSeconds);
        this.clock = clock;
        if (timeoutSeconds <= 0 || leaseSeconds <= timeoutSeconds) {
            throw new IllegalArgumentException("outbox lease must exceed its positive publish timeout");
        }
    }

    @Scheduled(fixedDelayString = "${vortex.outbox.poll-interval-ms:500}")
    public void relayDueRows() {
        for (int sent = 0; sent < 100; sent++) {
            OutboxMessage message;
            try {
                message = transactions.execute(status -> repository.claimNext(clock.instant(), lease).orElse(null));
            } catch (RuntimeException exception) {
                log.warn("Outbox claim failed; database or query is unavailable ({})", exception.getClass().getSimpleName());
                return;
            }
            if (message == null) return;
            publish(message);
        }
    }

    private void publish(OutboxMessage message) {
        try {
            kafka.send(new ProducerRecord<>(message.topic(), message.messageKey(), message.envelope()))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean marked = Boolean.TRUE.equals(transactions.execute(status ->
                    repository.markPublished(message, clock.instant())));
            if (!marked) {
                log.warn("Outbox row {} was acknowledged but its lease changed before publish marking", message.outboxId());
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            boolean exhausted = message.attempts() >= MAX_TOTAL_ATTEMPTS;
            Instant now = clock.instant();
            Instant retryAt = now.plusSeconds(retryDelaySeconds(message.attempts()));
            String safeError = exception.getClass().getSimpleName();
            try {
                transactions.execute(status -> repository.markFailure(message, now, retryAt, exhausted, safeError));
                if (exhausted) {
                    log.error("Outbox row {} marked FAILED after {} attempts; explicit operator requeue is required",
                            message.outboxId(), message.attempts());
                } else {
                    log.warn("Outbox row {} publish failed on attempt {}; retry scheduled ({})",
                            message.outboxId(), message.attempts(), safeError);
                }
            } catch (RuntimeException dbException) {
                // Leave the lease in place. It will expire and be safely retried after PostgreSQL returns.
                log.error("Could not persist outbox failure for row {}; lease recovery will retry it ({})",
                        message.outboxId(), dbException.getClass().getSimpleName());
            }
        }
    }

    private static long retryDelaySeconds(int attempt) {
        return Math.min(60, 1L << Math.min(Math.max(0, attempt - 1), 6));
    }
}
