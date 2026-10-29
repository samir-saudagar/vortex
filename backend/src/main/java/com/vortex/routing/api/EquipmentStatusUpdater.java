package com.vortex.routing.api;
import com.vortex.routing.domain.EquipmentStatus;
import java.time.Instant;
import java.util.UUID;
public interface EquipmentStatusUpdater {
    boolean update(String equipmentId, EquipmentStatus status, Instant reportedAt, UUID reportId, UUID correlation, String reason);
}
