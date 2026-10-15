package com.vortex.routing.domain;

public record EquipmentSnapshot(
        String equipmentId,
        EquipmentType type,
        EquipmentStatus status,
        boolean configuredEnabled) {
}
