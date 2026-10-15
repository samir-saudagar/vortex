package com.vortex.routing.domain;

public record NetworkNode(
        String nodeId,
        NetworkNodeType type,
        String airportDestinationCode,
        String scannerEquipmentId,
        boolean enabled) {
}
