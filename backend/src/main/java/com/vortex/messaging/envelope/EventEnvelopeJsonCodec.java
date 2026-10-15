package com.vortex.messaging.envelope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/** Serializes event envelopes using the event catalog's exact wire names and UTF-8 contract. */
public final class EventEnvelopeJsonCodec {
    private final ObjectMapper mapper;
    private final EventEnvelopeValidator validator;

    public EventEnvelopeJsonCodec(ObjectMapper mapper, EventEnvelopeValidator validator) {
        this.mapper = mapper.copy();
        this.validator = validator;
    }

    public byte[] serialize(EventEnvelope envelope) {
        ObjectNode json = mapper.createObjectNode();
        json.put("eventId", envelope.eventId().toString());
        json.put("eventType", envelope.eventType().wireName());
        json.put("eventVersion", envelope.eventVersion());
        json.put("occurredAt", envelope.occurredAt().toString());
        json.put("producer", envelope.producer());
        json.put("aggregateType", envelope.aggregateType().name());
        json.put("aggregateId", envelope.aggregateId());
        json.put("correlationId", envelope.correlationId().toString());
        if (envelope.causationId() == null) {
            json.putNull("causationId");
        } else {
            json.put("causationId", envelope.causationId().toString());
        }
        json.set("payload", envelope.payload().deepCopy());
        try {
            byte[] bytes = mapper.writeValueAsBytes(json);
            validator.validate(bytes);
            return bytes;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event envelope could not be serialized", exception);
        }
    }

    public String asJson(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public EventEnvelope validate(byte[] bytes) {
        return validator.validate(bytes);
    }

    public EventEnvelope validate(byte[] bytes, String messageKey) {
        EventEnvelope event = validate(bytes);
        if (!event.aggregateId().equals(messageKey)) {
            throw new EnvelopeValidationException(EnvelopeValidationCode.INVALID_IDENTIFIER,
                    "Kafka message key must match aggregateId");
        }
        return event;
    }
}
