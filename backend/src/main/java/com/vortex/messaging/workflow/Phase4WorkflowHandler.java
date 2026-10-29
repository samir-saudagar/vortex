package com.vortex.messaging.workflow;

import com.vortex.flight.api.FlightVerification;
import com.vortex.routing.api.RoutePlanning;
import com.vortex.movement.api.MovementStarter;
import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.envelope.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Phase4WorkflowHandler implements WorkflowEventHandler {
    private final FlightVerification flights;
    private final RoutePlanning routes;
    private final MovementStarter movement;
    public Phase4WorkflowHandler(FlightVerification flights, RoutePlanning routes, MovementStarter movement) {
        this.flights = flights; this.routes = routes; this.movement = movement;
    }
    public Set<EventType> eventTypes() { return Set.of(EventType.BAG_CHECKED_IN, EventType.FLIGHT_STATUS_VERIFIED, EventType.ROUTE_PLANNED); }
    public ProcessingOutcome handle(EventEnvelope event) {
        UUID bag = UUID.fromString(event.aggregateId());
        boolean accepted = switch (event.eventType()) {
            case BAG_CHECKED_IN -> flights.verify(bag, event.eventId(), event.correlationId());
            case FLIGHT_STATUS_VERIFIED -> routes.plan(bag, event.eventId(), event.correlationId());
            case ROUTE_PLANNED -> movement.start(bag, UUID.fromString(event.payload().path("routeId").asText()), event.eventId(), event.correlationId());
            default -> throw new IllegalArgumentException("Unsupported workflow action");
        };
        return accepted ? ProcessingOutcome.PROCESSED : ProcessingOutcome.REJECTED;
    }
}
