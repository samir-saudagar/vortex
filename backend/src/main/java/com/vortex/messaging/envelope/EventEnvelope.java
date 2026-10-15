package com.vortex.messaging.envelope;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        AggregateType aggregateType,
        String aggregateId,
        UUID correlationId,
        UUID causationId,
        JsonNode payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion != 1) {
            throw new IllegalArgumentException("only eventVersion 1 is supported");
        }
        if (producer == null || producer.isBlank() || aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("producer and aggregateId must not be blank");
        }
    }
}
