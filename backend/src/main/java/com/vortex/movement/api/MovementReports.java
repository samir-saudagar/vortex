package com.vortex.movement.api;
import com.vortex.movement.domain.*;
import com.vortex.routing.domain.EquipmentStatus;
import java.time.Instant;
import java.util.UUID;
public interface MovementReports {
    boolean result(EquipmentCommandResult result, UUID eventId, UUID correlationId, UUID causationId);
    boolean observation(ScannerObservation observation, UUID eventId, UUID correlationId, UUID causationId, String validatedPayload);
    boolean equipmentStatus(String equipmentId, EquipmentStatus status, Instant reportedAt, UUID reportId, UUID correlationId, String reason);
}
