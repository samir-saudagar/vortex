package com.vortex.movement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScannerObservation(
        UUID observationId,
        UUID bagId,
        UUID routeId,
        UUID commandId,
        int routeStepNo,
        String equipmentId,
        String nodeId,
        String scannerId,
        Instant observedAt) {

    public ScannerObservation {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(bagId, "bagId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(commandId, "commandId");
        if (routeStepNo < 1) {
            throw new IllegalArgumentException("routeStepNo must be positive");
        }
        requireText(equipmentId, "equipmentId");
        requireText(nodeId, "nodeId");
        requireText(scannerId, "scannerId");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
