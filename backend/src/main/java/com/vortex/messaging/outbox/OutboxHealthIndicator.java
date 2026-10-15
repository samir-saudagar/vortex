package com.vortex.messaging.outbox;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {
    private final OutboxRepository repository;

    public OutboxHealthIndicator(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        OutboxRepository.OutboxCounts counts = repository.counts();
        Health.Builder health = counts.failed() > 0 ? Health.down() : Health.up();
        return health.withDetail("pending", counts.pending())
                .withDetail("inFlight", counts.inFlight())
                .withDetail("failed", counts.failed())
                .build();
    }
}
