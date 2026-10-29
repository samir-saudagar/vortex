package com.vortex.messaging.workflow;

import com.vortex.movement.api.MovementReports;
import com.vortex.movement.domain.*;
import com.vortex.routing.domain.EquipmentStatus;
import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.envelope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class Phase4SimulatorReportHandler implements SimulatorReportHandler {
    private final MovementReports movement;
    public Phase4SimulatorReportHandler(MovementReports movement) {
        this.movement = movement;
    }
    public Set<EventType> eventTypes() { return Set.of(EventType.EQUIPMENT_COMMAND_RESULT, EventType.SCANNER_OBSERVATION, EventType.EQUIPMENT_STATUS_REPORTED); }
    public ProcessingOutcome handle(EventEnvelope event) {
        JsonNode p = event.payload();
        boolean accepted = switch (event.eventType()) {
            case EQUIPMENT_COMMAND_RESULT -> movement.result(new EquipmentCommandResult(uuid(p, "commandId"), uuid(p, "bagId"), uuid(p, "routeId"),
                    p.path("routeStepNo").asInt(), p.path("equipmentId").asText(), MovementCommandStatus.valueOf(p.path("status").asText()),
                    Instant.parse(p.path("reportedAt").asText()), optional(p, "failureCode"), optional(p, "safeReason")), event.eventId(), event.correlationId(), event.causationId());
            case SCANNER_OBSERVATION -> movement.observation(new ScannerObservation(uuid(p, "observationId"), uuid(p, "bagId"), uuid(p, "routeId"), uuid(p, "commandId"),
                    p.path("routeStepNo").asInt(), p.path("equipmentId").asText(), p.path("nodeId").asText(), p.path("scannerId").asText(),
                    Instant.parse(p.path("observedAt").asText())), event.eventId(), event.correlationId(), event.causationId(), p.toString());
            case EQUIPMENT_STATUS_REPORTED -> movement.equipmentStatus(p.path("equipmentId").asText(), EquipmentStatus.valueOf(p.path("status").asText()),
                    Instant.parse(p.path("reportedAt").asText()), event.eventId(), event.correlationId(), optional(p, "reason").orElse(null));
            default -> throw new IllegalArgumentException("Unsupported simulator report");
        };
        return accepted ? ProcessingOutcome.PROCESSED : ProcessingOutcome.REJECTED;
    }
    private static UUID uuid(JsonNode p, String name) { return UUID.fromString(p.path(name).asText()); }
    private static Optional<String> optional(JsonNode p, String name) { return p.hasNonNull(name) ? Optional.of(p.path(name).asText()) : Optional.empty(); }
}
