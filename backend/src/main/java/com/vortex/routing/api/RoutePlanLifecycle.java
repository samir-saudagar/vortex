package com.vortex.routing.api;
import java.util.UUID;
public interface RoutePlanLifecycle {
    void activate(UUID routeId);
    void complete(UUID routeId);
    void supersede(UUID routeId);
}
