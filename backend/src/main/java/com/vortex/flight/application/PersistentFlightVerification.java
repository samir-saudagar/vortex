package com.vortex.flight.application;

import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.*;
import com.vortex.flight.api.*;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import com.vortex.routing.api.NetworkCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class PersistentFlightVerification implements FlightVerification {
    private final BagLifecycle bags;
    private final FlightCatalog flights;
    private final NetworkCatalog network;
    private final OutboxWriter outbox;
    private final EventFactory events;
    private final Clock clock;
    public PersistentFlightVerification(BagLifecycle bags, FlightCatalog flights, NetworkCatalog network,
                                         OutboxWriter outbox, EventFactory events, Clock clock) {
        this.bags = bags; this.flights = flights; this.network = network; this.outbox = outbox; this.events = events; this.clock = clock;
    }
    @Transactional
    public boolean verify(UUID id, UUID source, UUID correlation) {
        BagSnapshot bag = bags.lock(id).orElse(null);
        if (bag == null) return false;
        if (!bag.expects(source, correlation) || bag.state() != BagState.CHECKED_IN) {
            bags.record(id, "WORKFLOW_RESULT", "STALE_FLIGHT_WORKFLOW", source, clock.instant(), null);
            return false;
        }
        // One catalog snapshot supplies both eligibility and the persisted verified destination.
        var flight = flights.findById(bag.flightId());
        var decision = new FlightEligibilityService(ignored -> flight).verify(bag.flightId(), network.active().airportCode(), clock.instant());
        EventEnvelope fact;
        if (decision.eligible()) {
            var f = flight.orElseThrow();
            bags.verifiedFlight(id, f.departureAirport(), f.destinationAirport(), f.status().name(), clock.instant());
            fact = events.bag(EventType.FLIGHT_STATUS_VERIFIED, id, correlation, source, events.payload("bagId", id,
                    "flightId", f.flightId(), "departureAirport", f.departureAirport(), "destinationAirport", f.destinationAirport(),
                    "flightStatus", f.status(), "checkedAt", clock.instant()));
            outbox.append(fact);
            bags.transition(id, BagTransitionTrigger.FLIGHT_VERIFIED, source, clock.instant(), null, null, null);
            bags.workflow(id, fact.eventId());
        } else {
            String code = decision.failure().orElseThrow().name();
            fact = events.bag(EventType.FLIGHT_VERIFICATION_FAILED, id, correlation, source, events.payload("bagId", id,
                    "flightId", bag.flightId(), "failureCode", code, "safeReason", "Flight is not eligible: " + code,
                    "checkedAt", clock.instant()));
            outbox.append(fact);
            bags.transition(id, BagTransitionTrigger.FLIGHT_VERIFICATION_FAILED, source, clock.instant(), code, "Flight is not eligible: " + code, null);
            bags.workflow(id, null);
        }
        return true;
    }
}
