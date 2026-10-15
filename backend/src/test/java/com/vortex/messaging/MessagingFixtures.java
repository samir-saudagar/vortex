package com.vortex.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vortex.messaging.envelope.*;

import java.time.Instant;
import java.util.UUID;

public final class MessagingFixtures {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final EventEnvelopeJsonCodec CODEC = new EventEnvelopeJsonCodec(MAPPER, new JsonEventEnvelopeValidator(MAPPER));
    public static final Instant NOW = Instant.parse("2030-01-15T10:15:30Z");

    private MessagingFixtures() { }

    public static EventEnvelope equipmentChanged(String equipmentId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("equipmentId", equipmentId).put("previousStatus", "AVAILABLE").put("currentStatus", "BLOCKED")
                .put("changedAt", NOW.toString()).put("sourceReportId", UUID.randomUUID().toString());
        return new EventEnvelope(UUID.randomUUID(), EventType.EQUIPMENT_STATUS_CHANGED, 1, NOW, "route-planning",
                AggregateType.EQUIPMENT, equipmentId, UUID.randomUUID(), UUID.randomUUID(), payload);
    }

    public static EventEnvelope equipmentReport(String equipmentId) {
        UUID id = UUID.randomUUID();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("equipmentId", equipmentId).put("reportId", id.toString()).put("status", "AVAILABLE")
                .put("reportedAt", NOW.toString());
        return new EventEnvelope(id, EventType.EQUIPMENT_STATUS_REPORTED, 1, NOW, "python-equipment-simulator",
                AggregateType.EQUIPMENT, equipmentId, UUID.randomUUID(), null, payload);
    }

    public static EventEnvelope scannerReport() {
        UUID id = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID bagId = UUID.randomUUID();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("observationId", id.toString()).put("bagId", bagId.toString())
                .put("routeId", UUID.randomUUID().toString()).put("commandId", commandId.toString())
                .put("routeStepNo", 1).put("equipmentId", "belt-west").put("nodeId", "junction-west")
                .put("scannerId", "scanner-west").put("observationType", "BAG_DETECTED")
                .put("observedAt", NOW.toString());
        return new EventEnvelope(id, EventType.SCANNER_OBSERVATION, 1, NOW, "python-equipment-simulator",
                AggregateType.BAG, bagId.toString(), UUID.randomUUID(), commandId, payload);
    }
}
