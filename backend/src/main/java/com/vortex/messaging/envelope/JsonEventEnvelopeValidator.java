package com.vortex.messaging.envelope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.bagstate.domain.BagState;
import com.vortex.flight.domain.FlightStatus;
import com.vortex.movement.domain.MovementCommandStatus;
import com.vortex.routing.domain.EquipmentStatus;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Strict envelope and event-payload contract validator for version 1. */
public final class JsonEventEnvelopeValidator implements EventEnvelopeValidator {
    public static final int MAX_ENVELOPE_BYTES = 1_048_576;

    private static final Set<EventType> ROOT_FACTS = EnumSet.of(
            EventType.BAG_CHECKED_IN,
            EventType.EQUIPMENT_STATUS_REPORTED);

    private final ObjectMapper objectMapper;

    public JsonEventEnvelopeValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    @Override
    public EventEnvelope validate(byte[] utf8Json) {
        if (utf8Json == null || utf8Json.length == 0) {
            throw failure(EnvelopeValidationCode.EMPTY_MESSAGE, "Event envelope is empty");
        }
        if (utf8Json.length > MAX_ENVELOPE_BYTES) {
            throw failure(EnvelopeValidationCode.ENVELOPE_TOO_LARGE,
                    "Event envelope exceeds the 1048576-byte limit");
        }

        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8Json))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw failure(EnvelopeValidationCode.INVALID_UTF8, "Event envelope is not valid UTF-8");
        }

        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
        } catch (JsonProcessingException exception) {
            throw failure(EnvelopeValidationCode.INVALID_JSON, "Event envelope is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw failure(EnvelopeValidationCode.ENVELOPE_NOT_OBJECT, "Event envelope must be a JSON object");
        }

        UUID eventId = uuid(root, "eventId");
        String eventTypeName = text(root, "eventType");
        EventType eventType = EventType.fromWireName(eventTypeName);
        if (eventType == null) {
            throw failure(EnvelopeValidationCode.UNSUPPORTED_EVENT_TYPE, "Event type is not in the version 1 catalog");
        }
        int eventVersion = integer(root, "eventVersion");
        if (eventVersion != 1) {
            throw failure(EnvelopeValidationCode.UNSUPPORTED_EVENT_VERSION, "Only eventVersion 1 is supported");
        }
        var occurredAt = utcTimestamp(root, "occurredAt");
        String producer = text(root, "producer");
        AggregateType aggregateType = enumValue(root, "aggregateType", AggregateType.class);
        String aggregateId = text(root, "aggregateId");
        UUID correlationId = uuid(root, "correlationId");
        UUID causationId = nullableUuid(root, "causationId");
        if (causationId == null && !ROOT_FACTS.contains(eventType)) {
            throw failure(EnvelopeValidationCode.INVALID_PAYLOAD,
                    "causationId may be null only for a root fact");
        }
        JsonNode payload = required(root, "payload");
        if (!payload.isObject()) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE, "payload must be a JSON object");
        }

        validateAggregate(eventType, aggregateType, aggregateId);
        validatePayload(eventType, payload, eventId);
        validateAggregatePayloadIdentity(eventType, aggregateId, payload);
        return new EventEnvelope(eventId, eventType, eventVersion, occurredAt, producer,
                aggregateType, aggregateId, correlationId, causationId, payload.deepCopy());
    }

    private static void validatePayload(EventType eventType, JsonNode payload, UUID envelopeEventId) {
        switch (eventType) {
            case BAG_CHECKED_IN -> {
                uuid(payload, "bagId"); text(payload, "bagTag"); text(payload, "flightId");
                text(payload, "checkInNodeId"); utcTimestamp(payload, "checkedInAt");
            }
            case FLIGHT_STATUS_VERIFIED -> {
                uuid(payload, "bagId"); text(payload, "flightId"); text(payload, "departureAirport");
                text(payload, "destinationAirport"); enumValue(payload, "flightStatus", FlightStatus.class,
                        EnumSet.of(FlightStatus.SCHEDULED, FlightStatus.BOARDING));
                utcTimestamp(payload, "checkedAt");
            }
            case FLIGHT_VERIFICATION_FAILED -> {
                uuid(payload, "bagId"); text(payload, "flightId"); text(payload, "failureCode");
                text(payload, "safeReason"); utcTimestamp(payload, "checkedAt");
            }
            case ROUTE_PLANNED -> {
                uuid(payload, "bagId"); uuid(payload, "routeId"); text(payload, "fromNodeId");
                text(payload, "destinationNodeId"); text(payload, "networkVersion"); text(payload, "algorithm");
                nonNegativeLong(payload, "totalCost"); nonNegativeInteger(payload, "stepCount");
            }
            case ROUTING_BLOCKED -> {
                uuid(payload, "bagId"); text(payload, "fromNodeId"); text(payload, "destinationNodeId");
                text(payload, "networkVersion"); text(payload, "failureCode"); text(payload, "safeReason");
            }
            case BAG_STATE_CHANGED -> {
                uuid(payload, "bagId"); nullableEnumValue(payload, "previousState", BagState.class);
                enumValue(payload, "currentState", BagState.class); nullableText(payload, "confirmedNodeId");
                nonNegativeLong(payload, "stateVersion"); text(payload, "reasonCode"); uuid(payload, "historyId");
            }
            case EQUIPMENT_STATUS_CHANGED -> {
                text(payload, "equipmentId"); nullableEnumValue(payload, "previousStatus", EquipmentStatus.class);
                enumValue(payload, "currentStatus", EquipmentStatus.class); utcTimestamp(payload, "changedAt");
                uuid(payload, "sourceReportId");
            }
            case EQUIPMENT_MOVEMENT_COMMAND -> {
                UUID commandId = uuid(payload, "commandId");
                if (!envelopeEventId.equals(commandId)) {
                    throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER,
                            "EquipmentMovementCommand eventId must equal commandId");
                }
                uuid(payload, "bagId"); uuid(payload, "routeId"); positiveInteger(payload, "routeStepNo");
                text(payload, "fromNodeId"); text(payload, "toNodeId"); text(payload, "equipmentId");
                positiveInteger(payload, "attempt");
                var issuedAt = utcTimestamp(payload, "issuedAt");
                var deadlineAt = utcTimestamp(payload, "deadlineAt");
                if (!deadlineAt.isAfter(issuedAt)) {
                    throw failure(EnvelopeValidationCode.INVALID_PAYLOAD, "deadlineAt must be after issuedAt");
                }
            }
            case EQUIPMENT_COMMAND_RESULT -> {
                uuid(payload, "commandId"); uuid(payload, "bagId"); uuid(payload, "routeId");
                positiveInteger(payload, "routeStepNo"); text(payload, "equipmentId");
                enumValue(payload, "status", MovementCommandStatus.class, EnumSet.of(
                        MovementCommandStatus.ACCEPTED, MovementCommandStatus.STARTED,
                        MovementCommandStatus.SUCCEEDED, MovementCommandStatus.REJECTED,
                        MovementCommandStatus.FAILED));
                utcTimestamp(payload, "reportedAt"); optionalText(payload, "failureCode"); optionalText(payload, "safeReason");
            }
            case SCANNER_OBSERVATION -> {
                UUID observationId = uuid(payload, "observationId");
                if (!envelopeEventId.equals(observationId)) {
                    throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER,
                            "ScannerObservation eventId must equal observationId");
                }
                uuid(payload, "bagId"); uuid(payload, "routeId"); uuid(payload, "commandId");
                positiveInteger(payload, "routeStepNo"); text(payload, "equipmentId"); text(payload, "nodeId");
                text(payload, "scannerId"); enumLiteral(payload, "observationType", Set.of("BAG_DETECTED"));
                utcTimestamp(payload, "observedAt");
            }
            case EQUIPMENT_STATUS_REPORTED -> {
                UUID reportId = uuid(payload, "reportId");
                if (!envelopeEventId.equals(reportId)) {
                    throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER,
                            "EquipmentStatusReported eventId must equal reportId");
                }
                text(payload, "equipmentId"); enumValue(payload, "status", EquipmentStatus.class);
                utcTimestamp(payload, "reportedAt"); optionalText(payload, "reason");
            }
        }
    }

    private static void validateAggregate(EventType eventType, AggregateType aggregateType, String aggregateId) {
        boolean equipmentAggregate = eventType == EventType.EQUIPMENT_STATUS_CHANGED
                || eventType == EventType.EQUIPMENT_STATUS_REPORTED;
        if (equipmentAggregate != (aggregateType == AggregateType.EQUIPMENT)) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE,
                    "aggregateType does not match the event's aggregate");
        }
        if (aggregateType == AggregateType.BAG) {
            parseUuid(aggregateId, "aggregateId");
        }
    }

    private static void validateAggregatePayloadIdentity(EventType eventType, String aggregateId, JsonNode payload) {
        if (eventType == EventType.EQUIPMENT_STATUS_CHANGED || eventType == EventType.EQUIPMENT_STATUS_REPORTED) {
            if (!aggregateId.equals(text(payload, "equipmentId"))) {
                throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER, "aggregateId must match equipmentId");
            }
            return;
        }
        if (payload.has("bagId") && !aggregateId.equals(payload.get("bagId").asText())) {
            throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER, "aggregateId must match bagId");
        }
    }

    private static JsonNode required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw failure(EnvelopeValidationCode.REQUIRED_FIELD_MISSING, "Required field is missing: " + field);
        }
        return value;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE, field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE, field + " must be a non-empty string or null");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE, field + " must be a non-empty string when present");
        }
        return value.asText();
    }

    private static UUID uuid(JsonNode object, String field) {
        return parseUuid(text(object, field), field);
    }

    private static UUID nullableUuid(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER, field + " must be a UUID string or null");
        }
        return parseUuid(value.asText(), field);
    }

    private static UUID parseUuid(String value, String field) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER, field + " must be a canonical UUID");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw failure(EnvelopeValidationCode.INVALID_IDENTIFIER, field + " must be a canonical UUID");
        }
    }

    private static int integer(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure(EnvelopeValidationCode.INVALID_FIELD_TYPE, field + " must be an integer");
        }
        return value.intValue();
    }

    private static void positiveInteger(JsonNode object, String field) {
        if (integer(object, field) <= 0) {
            throw failure(EnvelopeValidationCode.INVALID_PAYLOAD, field + " must be positive");
        }
    }

    private static void nonNegativeInteger(JsonNode object, String field) {
        if (integer(object, field) < 0) {
            throw failure(EnvelopeValidationCode.INVALID_PAYLOAD, field + " must not be negative");
        }
    }

    private static long nonNegativeLong(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw failure(EnvelopeValidationCode.INVALID_PAYLOAD, field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode object, String field, Class<E> enumType) {
        return enumValue(object, field, enumType, EnumSet.allOf(enumType));
    }

    private static <E extends Enum<E>> E nullableEnumValue(JsonNode object, String field, Class<E> enumType) {
        JsonNode value = required(object, field);
        if (value.isNull()) {
            return null;
        }
        return enumLiteral(value, field, enumType, EnumSet.allOf(enumType));
    }

    private static <E extends Enum<E>> E enumValue(JsonNode object, String field, Class<E> enumType, Set<E> allowed) {
        return enumLiteral(required(object, field), field, enumType, allowed);
    }

    private static void enumLiteral(JsonNode object, String field, Set<String> allowed) {
        String value = text(object, field);
        if (!allowed.contains(value)) {
            throw failure(EnvelopeValidationCode.INVALID_ENUM_VALUE, field + " contains an unsupported value");
        }
    }

    private static <E extends Enum<E>> E enumLiteral(JsonNode value, String field, Class<E> enumType, Set<E> allowed) {
        if (!value.isTextual()) {
            throw failure(EnvelopeValidationCode.INVALID_ENUM_VALUE, field + " must be a string enum value");
        }
        try {
            E parsed = Enum.valueOf(enumType, value.asText());
            if (!allowed.contains(parsed)) {
                throw failure(EnvelopeValidationCode.INVALID_ENUM_VALUE, field + " contains an unsupported value");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw failure(EnvelopeValidationCode.INVALID_ENUM_VALUE, field + " contains an unsupported value");
        }
    }

    private static java.time.Instant utcTimestamp(JsonNode object, String field) {
        String value = text(object, field);
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (!parsed.getOffset().equals(ZoneOffset.UTC)) {
                throw failure(EnvelopeValidationCode.INVALID_TIMESTAMP, field + " must use UTC offset");
            }
            return parsed.toInstant();
        } catch (java.time.format.DateTimeParseException exception) {
            throw failure(EnvelopeValidationCode.INVALID_TIMESTAMP, field + " must be an RFC 3339 UTC timestamp");
        }
    }

    private static EnvelopeValidationException failure(EnvelopeValidationCode code, String message) {
        return new EnvelopeValidationException(code, message);
    }
}
