package com.vortex.movement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Simulator command result. It intentionally carries no confirmed node. */
public record EquipmentCommandResult(
        UUID commandId,
        UUID bagId,
        UUID routeId,
        int routeStepNo,
        String equipmentId,
        MovementCommandStatus status,
        Instant reportedAt,
        Optional<String> failureCode,
        Optional<String> safeReason) {

    public EquipmentCommandResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(bagId, "bagId");
        Objects.requireNonNull(routeId, "routeId");
        if (routeStepNo < 1) {
            throw new IllegalArgumentException("routeStepNo must be positive");
        }
        if (equipmentId == null || equipmentId.isBlank()) {
            throw new IllegalArgumentException("equipmentId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reportedAt, "reportedAt");
        failureCode = failureCode == null ? Optional.empty() : failureCode;
        safeReason = safeReason == null ? Optional.empty() : safeReason;
    }
}
