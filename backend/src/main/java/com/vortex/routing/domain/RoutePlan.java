package com.vortex.routing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identified immutable routing decision snapshot; persistence lifecycle is added in a later phase. */
public record RoutePlan(
        UUID routeId,
        UUID bagId,
        int routeNumber,
        RouteStatus status,
        PlannedRoute decision,
        Instant plannedAt) {

    public RoutePlan {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(bagId, "bagId");
        if (routeNumber < 1) {
            throw new IllegalArgumentException("routeNumber must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(plannedAt, "plannedAt");
    }
}
