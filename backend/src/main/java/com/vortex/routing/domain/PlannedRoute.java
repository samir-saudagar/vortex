package com.vortex.routing.domain;

import java.util.List;

public record PlannedRoute(
        String networkVersion,
        String sourceNodeId,
        String destinationNodeId,
        String algorithm,
        int algorithmVersion,
        long totalCost,
        List<RouteStep> steps) {

    public PlannedRoute {
        requireText(networkVersion, "networkVersion");
        requireText(sourceNodeId, "sourceNodeId");
        requireText(destinationNodeId, "destinationNodeId");
        requireText(algorithm, "algorithm");
        if (algorithmVersion < 1 || totalCost < 0) {
            throw new IllegalArgumentException("algorithm version must be positive and route cost non-negative");
        }
        steps = List.copyOf(steps);
        String expectedSource = sourceNodeId;
        long calculatedCost = 0L;
        for (int index = 0; index < steps.size(); index++) {
            RouteStep step = steps.get(index);
            if (step.stepNo() != index + 1 || !step.fromNodeId().equals(expectedSource)) {
                throw new IllegalArgumentException("route steps must be sequential and form one connected path");
            }
            expectedSource = step.toNodeId();
            calculatedCost += step.edgeCost();
            if (calculatedCost < 0) {
                throw new IllegalArgumentException("route cost overflow");
            }
        }
        if ((steps.isEmpty() && !sourceNodeId.equals(destinationNodeId))
                || (!steps.isEmpty() && !expectedSource.equals(destinationNodeId))
                || calculatedCost != totalCost) {
            throw new IllegalArgumentException("route steps must match source, destination and total cost");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
