package com.vortex.routing.api;

import com.vortex.routing.domain.RouteStep;
import java.util.*;
public interface RouteQuery {
    Optional<StoredRoute> latest(UUID bagId);
    Optional<StoredRoute> route(UUID routeId);
    boolean finalMakeup(UUID routeId, String node, String scanner, String verifiedDestination);
    record StoredRoute(UUID routeId, UUID bagId, int routeNumber, String status, String networkVersion,
                       String sourceNodeId, String destinationNodeId, String algorithm, long totalCost,
                       List<RouteStep> steps, String decisionSummary) { }
}
