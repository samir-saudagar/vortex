package com.vortex.movement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One movement attempt for one immutable route edge. */
public record MovementCommand(
        UUID commandId,
        UUID bagId,
        UUID routeId,
        int routeStepNo,
        String fromNodeId,
        String toNodeId,
        String equipmentId,
        int attempt,
        Instant issuedAt,
        Instant deadlineAt) {

    public MovementCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(bagId, "bagId");
        Objects.requireNonNull(routeId, "routeId");
        requireText(fromNodeId, "fromNodeId");
        requireText(toNodeId, "toNodeId");
        requireText(equipmentId, "equipmentId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (routeStepNo < 1 || attempt < 1) {
            throw new IllegalArgumentException("routeStepNo and attempt must be positive");
        }
        if (!deadlineAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after issuedAt");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
