package com.vortex.routing.domain;

public record RouteStep(
        int stepNo,
        String edgeId,
        String fromNodeId,
        String toNodeId,
        String equipmentId,
        String expectedScannerId,
        int edgeCost,
        Integer expectedTravelTimeMs) {

    public RouteStep {
        if (stepNo < 1 || edgeCost < 1 || (expectedTravelTimeMs != null && expectedTravelTimeMs < 1)) {
            throw new IllegalArgumentException("step number, edge cost and configured travel time must be positive");
        }
        requireText(edgeId, "edgeId");
        requireText(fromNodeId, "fromNodeId");
        requireText(toNodeId, "toNodeId");
        requireText(equipmentId, "equipmentId");
        requireText(expectedScannerId, "expectedScannerId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
