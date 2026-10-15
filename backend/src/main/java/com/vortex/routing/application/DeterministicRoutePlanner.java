package com.vortex.routing.application;

import com.vortex.routing.api.RoutePlanner;
import com.vortex.routing.domain.DijkstraPathPlanner;
import com.vortex.routing.domain.NetworkGraph;
import com.vortex.routing.domain.RouteDecision;

import java.util.Objects;

/** Application-facing facade around the deterministic graph algorithm. */
public final class DeterministicRoutePlanner implements RoutePlanner {
    private final DijkstraPathPlanner planner;

    public DeterministicRoutePlanner(DijkstraPathPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    public RouteDecision plan(NetworkGraph graph, String sourceNodeId, String destinationAirportCode) {
        return planner.plan(graph, sourceNodeId, destinationAirportCode);
    }
}
