package com.vortex.routing.api;

import com.vortex.routing.domain.NetworkGraph;
import com.vortex.routing.domain.RouteDecision;

/** Typed in-process route-planning boundary. */
public interface RoutePlanner {
    RouteDecision plan(NetworkGraph graph, String sourceNodeId, String destinationAirportCode);
}
