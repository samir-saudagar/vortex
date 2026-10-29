package com.vortex.messaging.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Constructs catalog envelopes; OutboxWriter still validates every encoded envelope. */
@Component
public class EventFactory {
    private final ObjectMapper mapper;
    private final Clock clock;

    public EventFactory(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public ObjectNode payload(Object... fields) {
        ObjectNode result = mapper.createObjectNode();
        for (int i = 0; i < fields.length; i += 2) {
            result.set((String) fields[i], mapper.valueToTree(fields[i + 1]));
        }
        return result;
    }

    public EventEnvelope bag(EventType type, UUID bagId, UUID correlationId, UUID cause, ObjectNode payload) {
        return bag(UUID.randomUUID(), type, bagId, correlationId, cause, payload);
    }

    public EventEnvelope bag(UUID id, EventType type, UUID bagId, UUID correlationId, UUID cause, ObjectNode payload) {
        return new EventEnvelope(id, type, 1, clock.instant(), "vortex-backend", AggregateType.BAG,
                bagId.toString(), correlationId, cause, payload);
    }
}
