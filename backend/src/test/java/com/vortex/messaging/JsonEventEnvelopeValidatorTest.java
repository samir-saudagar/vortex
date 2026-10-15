package com.vortex.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.messaging.envelope.EnvelopeValidationCode;
import com.vortex.messaging.envelope.EnvelopeValidationException;
import com.vortex.messaging.envelope.JsonEventEnvelopeValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEventEnvelopeValidatorTest {
    private static final String BAG_ID = "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0";
    private static final String EVENT_ID = "61fce2ca-1d1c-4ce6-9d06-0aa49f84344d";
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonEventEnvelopeValidator validator = new JsonEventEnvelopeValidator(mapper);

    @Test
    void acceptsValidEnvelopeAndPreservesKnownContractFields() throws Exception {
        var envelope = validator.validate(json(validEnvelope()).getBytes(StandardCharsets.UTF_8));
        assertEquals(EVENT_ID, envelope.eventId().toString());
        assertEquals("BagCheckedIn", envelope.eventType().wireName());
        assertEquals(BAG_ID, envelope.aggregateId());
    }

    @Test
    void acceptsEveryVersionOnePayloadShapeInTheEventCatalog() throws Exception {
        for (Map<String, Object> envelope : catalogEnvelopes()) {
            validator.validate(json(envelope).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void requiresAContractFieldForEveryCatalogPayload() throws Exception {
        Map<String, String> requiredFields = Map.ofEntries(
                Map.entry("BagCheckedIn", "bagTag"),
                Map.entry("FlightStatusVerified", "flightStatus"),
                Map.entry("FlightVerificationFailed", "failureCode"),
                Map.entry("RoutePlanned", "routeId"),
                Map.entry("RoutingBlocked", "destinationNodeId"),
                Map.entry("BagStateChanged", "historyId"),
                Map.entry("EquipmentStatusChanged", "currentStatus"),
                Map.entry("EquipmentMovementCommand", "attempt"),
                Map.entry("EquipmentCommandResult", "reportedAt"),
                Map.entry("ScannerObservation", "scannerId"),
                Map.entry("EquipmentStatusReported", "reportId"));
        for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
            Map<String, Object> envelope = envelopeFor(entry.getKey());
            payload(envelope).remove(entry.getValue());
            assertCode(EnvelopeValidationCode.REQUIRED_FIELD_MISSING, envelope);
        }
    }

    @Test
    void rejectsMissingFieldsUnsupportedVersionsTypesAndIdentifiers() throws Exception {
        Map<String, Object> missingPayloadField = validEnvelope();
        payload(missingPayloadField).remove("flightId");
        assertCode(EnvelopeValidationCode.REQUIRED_FIELD_MISSING, missingPayloadField);

        Map<String, Object> unsupportedVersion = validEnvelope();
        unsupportedVersion.put("eventVersion", 2);
        assertCode(EnvelopeValidationCode.UNSUPPORTED_EVENT_VERSION, unsupportedVersion);

        Map<String, Object> badId = validEnvelope();
        payload(badId).put("bagId", "bag-1");
        assertCode(EnvelopeValidationCode.INVALID_IDENTIFIER, badId);

        Map<String, Object> missingEnvelopeField = validEnvelope();
        missingEnvelopeField.remove("correlationId");
        assertCode(EnvelopeValidationCode.REQUIRED_FIELD_MISSING, missingEnvelopeField);
    }

    @Test
    void rejectsUnknownEventTypesInvalidEnumsAndNonUtcTimestamps() throws Exception {
        Map<String, Object> unknown = validEnvelope();
        unknown.put("eventType", "FutureWorkflowFact");
        assertCode(EnvelopeValidationCode.UNSUPPORTED_EVENT_TYPE, unknown);

        Map<String, Object> enumPayload = validEnvelope();
        enumPayload.put("eventType", "FlightStatusVerified");
        enumPayload.put("causationId", EVENT_ID);
        enumPayload.put("payload", Map.of("bagId", BAG_ID, "flightId", "FL-1", "departureAirport", "JFK",
                "destinationAirport", "LHR", "flightStatus", "CANCELLED", "checkedAt", "2030-01-15T10:00:00Z"));
        assertCode(EnvelopeValidationCode.INVALID_ENUM_VALUE, enumPayload);

        Map<String, Object> wrongZone = validEnvelope();
        wrongZone.put("occurredAt", "2030-01-15T10:00:00+01:00");
        assertCode(EnvelopeValidationCode.INVALID_TIMESTAMP, wrongZone);
    }

    @Test
    void rejectsInvalidBagEquipmentCommandAndObservationEnums() throws Exception {
        assertInvalidEnumPayload("BagStateChanged", "currentState", "IN_BAG");
        assertInvalidEnumPayload("EquipmentStatusChanged", "currentStatus", "BROKEN");
        assertInvalidEnumPayload("EquipmentCommandResult", "status", "EXECUTING");
        assertInvalidEnumPayload("ScannerObservation", "observationType", "ARRIVED");
    }

    @Test
    void requiresCommandAndObservationEventIdsToMatchTheirPayloadIds() throws Exception {
        Map<String, Object> command = validEnvelope();
        command.put("eventType", "EquipmentMovementCommand");
        command.put("aggregateType", "BAG");
        command.put("causationId", EVENT_ID);
        command.put("payload", Map.of("commandId", BAG_ID, "bagId", BAG_ID, "routeId", BAG_ID,
                "routeStepNo", 1, "fromNodeId", "a", "toNodeId", "b", "equipmentId", "belt-1",
                "attempt", 1, "issuedAt", "2030-01-15T10:00:00Z", "deadlineAt", "2030-01-15T10:00:30Z"));
        assertCode(EnvelopeValidationCode.INVALID_IDENTIFIER, command);
    }

    @Test
    void acceptsExactlyOneMibibyteAndRejectsOneByteOver() throws Exception {
        byte[] exact = envelopeAtByteSize(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES);
        assertEquals(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES, exact.length);
        validator.validate(exact);

        byte[] tooLarge = envelopeAtByteSize(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES + 1);
        assertEquals(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES + 1, tooLarge.length);
        assertEquals(EnvelopeValidationCode.ENVELOPE_TOO_LARGE,
                assertThrows(EnvelopeValidationException.class, () -> validator.validate(tooLarge)).code());
    }

    @Test
    void countsUtf8BytesRatherThanJavaCharacterCount() throws Exception {
        Map<String, Object> envelope = validEnvelope();
        payload(envelope).put("optionalText", "é".repeat(530_000));
        byte[] bytes = json(envelope).getBytes(StandardCharsets.UTF_8);
        assertTrue(json(envelope).length() < JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES);
        assertTrue(bytes.length > JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES);
        assertEquals(EnvelopeValidationCode.ENVELOPE_TOO_LARGE,
                assertThrows(EnvelopeValidationException.class, () -> validator.validate(bytes)).code());
    }

    @Test
    void rejectsMalformedUtf8AndNullCausationOnNonRootFact() throws Exception {
        assertEquals(EnvelopeValidationCode.INVALID_UTF8,
                assertThrows(EnvelopeValidationException.class,
                        () -> validator.validate(new byte[]{(byte) 0xc3, (byte) 0x28})).code());
        Map<String, Object> nonRoot = validEnvelope();
        nonRoot.put("eventType", "RoutePlanned");
        nonRoot.put("causationId", null);
        nonRoot.put("payload", Map.of("bagId", BAG_ID, "routeId", EVENT_ID, "fromNodeId", "a",
                "destinationNodeId", "b", "networkVersion", "demo-v1", "algorithm", "DIJKSTRA_COST_V1",
                "totalCost", 1, "stepCount", 1));
        assertCode(EnvelopeValidationCode.INVALID_PAYLOAD, nonRoot);
    }

    private byte[] envelopeAtByteSize(int byteSize) throws Exception {
        Map<String, Object> envelope = validEnvelope();
        payload(envelope).put("padding", "");
        int base = json(envelope).getBytes(StandardCharsets.UTF_8).length;
        payload(envelope).put("padding", "x".repeat(byteSize - base));
        return json(envelope).getBytes(StandardCharsets.UTF_8);
    }

    private void assertCode(EnvelopeValidationCode expected, Map<String, Object> envelope) throws Exception {
        EnvelopeValidationException exception = assertThrows(EnvelopeValidationException.class,
                () -> validator.validate(json(envelope).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, exception.code());
    }

    private void assertInvalidEnumPayload(String eventType, String field, String value) throws Exception {
        Map<String, Object> envelope = envelopeFor(eventType);
        payload(envelope).put(field, value);
        assertCode(EnvelopeValidationCode.INVALID_ENUM_VALUE, envelope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private String json(Map<String, Object> envelope) throws Exception {
        return mapper.writeValueAsString(envelope);
    }

    private static Map<String, Object> validEnvelope() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bagId", BAG_ID);
        payload.put("bagTag", "BAG-1001");
        payload.put("flightId", "FL-204-2030-01-15");
        payload.put("checkInNodeId", "checkin-main");
        payload.put("checkedInAt", "2030-01-15T10:15:30Z");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", EVENT_ID);
        envelope.put("eventType", "BagCheckedIn");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", "2030-01-15T10:15:30Z");
        envelope.put("producer", "bag-checkin");
        envelope.put("aggregateType", "BAG");
        envelope.put("aggregateId", BAG_ID);
        envelope.put("correlationId", "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a");
        envelope.put("causationId", null);
        envelope.put("payload", payload);
        return envelope;
    }

    private static java.util.List<Map<String, Object>> catalogEnvelopes() {
        String time = "2030-01-15T10:15:30Z";
        return java.util.List.of(
                envelope("FlightStatusVerified", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "bagId", BAG_ID, "flightId", "FL-204", "departureAirport", "JFK",
                        "destinationAirport", "LHR", "flightStatus", "SCHEDULED", "checkedAt", time)),
                envelope("FlightVerificationFailed", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "bagId", BAG_ID, "flightId", "FL-404", "failureCode", "UNKNOWN_FLIGHT",
                        "safeReason", "Flight was not found", "checkedAt", time)),
                envelope("RoutePlanned", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "bagId", BAG_ID, "routeId", EVENT_ID, "fromNodeId", "checkin-main",
                        "destinationNodeId", "makeup-lhr", "networkVersion", "demo-v1",
                        "algorithm", "DIJKSTRA_COST_V1", "totalCost", 12, "stepCount", 2)),
                envelope("RoutingBlocked", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "bagId", BAG_ID, "fromNodeId", "checkin-main", "destinationNodeId", "makeup-lhr",
                        "networkVersion", "demo-v1", "failureCode", "NO_ROUTE", "safeReason", "No available path")),
                envelope("BagStateChanged", "BAG", BAG_ID, EVENT_ID, nullableMap(
                        "bagId", BAG_ID, "previousState", null, "currentState", "CHECKED_IN",
                        "confirmedNodeId", null, "stateVersion", 0, "reasonCode", "CHECK_IN_ACCEPTED",
                        "historyId", EVENT_ID)),
                envelope("EquipmentStatusChanged", "EQUIPMENT", "belt-west", EVENT_ID, nullableMap(
                        "equipmentId", "belt-west", "previousStatus", null, "currentStatus", "AVAILABLE",
                        "changedAt", time, "sourceReportId", EVENT_ID)),
                envelope("EquipmentMovementCommand", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "commandId", EVENT_ID, "bagId", BAG_ID, "routeId", "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c",
                        "routeStepNo", 1, "fromNodeId", "checkin-main", "toNodeId", "junction-west",
                        "equipmentId", "belt-west", "attempt", 1, "issuedAt", time,
                        "deadlineAt", "2030-01-15T10:16:00Z")),
                envelope("EquipmentCommandResult", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "commandId", EVENT_ID, "bagId", BAG_ID,
                        "routeId", "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c", "routeStepNo", 1,
                        "equipmentId", "belt-west", "status", "SUCCEEDED", "reportedAt", time)),
                envelope("ScannerObservation", "BAG", BAG_ID, EVENT_ID, Map.of(
                        "observationId", EVENT_ID, "bagId", BAG_ID,
                        "routeId", "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c", "commandId", EVENT_ID,
                        "routeStepNo", 1, "equipmentId", "belt-west", "nodeId", "junction-west",
                        "scannerId", "scanner-west", "observationType", "BAG_DETECTED", "observedAt", time)),
                envelope("EquipmentStatusReported", "EQUIPMENT", "belt-west", null, Map.of(
                        "reportId", EVENT_ID, "equipmentId", "belt-west", "status", "AVAILABLE", "reportedAt", time)));
    }

    private static Map<String, Object> envelope(String eventType, String aggregateType, String aggregateId,
                                                String causationId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>(validEnvelope());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("causationId", causationId);
        envelope.put("payload", payload);
        return envelope;
    }

    private static Map<String, Object> envelopeFor(String eventType) {
        Map<String, Object> original = catalogEnvelopes().stream()
                .filter(envelope -> envelope.get("eventType").equals(eventType))
                .findFirst()
                .orElseGet(() -> validEnvelope());
        Map<String, Object> copy = new LinkedHashMap<>(original);
        copy.put("payload", new LinkedHashMap<>(payload(original)));
        return copy;
    }

    private static Map<String, Object> nullableMap(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }
}
