package com.vortex.messaging.outbox;

public record OutboxMessage(
        long outboxId,
        String eventId,
        String topic,
        String messageKey,
        byte[] envelope,
        String aggregateType,
        String aggregateId,
        long aggregateSequence,
        int attempts) {
}
