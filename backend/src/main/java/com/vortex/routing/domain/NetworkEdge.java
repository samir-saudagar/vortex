package com.vortex.routing.domain;

public record NetworkEdge(
        String edgeId,
        String fromNodeId,
        String toNodeId,
        String equipmentId,
        int cost,
        Integer travelTimeMs,
        boolean enabled) {
}
