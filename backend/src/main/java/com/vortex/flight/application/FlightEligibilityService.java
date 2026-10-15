package com.vortex.flight.application;

import com.vortex.flight.api.FlightCatalog;
import com.vortex.flight.api.FlightEligibility;
import com.vortex.flight.domain.Flight;
import com.vortex.flight.domain.FlightEligibilityDecision;
import com.vortex.flight.domain.FlightEligibilityFailure;
import com.vortex.flight.domain.FlightStatus;

import java.time.Instant;
import java.util.Objects;

/** Deterministic eligibility use case; callers supply the evaluation instant. */
public final class FlightEligibilityService implements FlightEligibility {
    private final FlightCatalog catalog;

    public FlightEligibilityService(FlightCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public FlightEligibilityDecision verify(String flightId, String configuredDepartureAirport, Instant checkedAt) {
        requireText(flightId, "flightId");
        requireText(configuredDepartureAirport, "configuredDepartureAirport");
        Objects.requireNonNull(checkedAt, "checkedAt");

        Flight flight = catalog.findById(flightId).orElse(null);
        if (flight == null) {
            return FlightEligibilityDecision.ineligible(flightId, FlightEligibilityFailure.UNKNOWN_FLIGHT);
        }
        if (!flight.departureAirport().equals(configuredDepartureAirport)) {
            return FlightEligibilityDecision.ineligible(flightId, FlightEligibilityFailure.WRONG_DEPARTURE_AIRPORT);
        }
        if (flight.status() != FlightStatus.SCHEDULED && flight.status() != FlightStatus.BOARDING) {
            return FlightEligibilityDecision.ineligible(flightId, FlightEligibilityFailure.FLIGHT_NOT_ELIGIBLE);
        }
        if (flight.checkInOpensAt().isPresent() && checkedAt.isBefore(flight.checkInOpensAt().get())) {
            return FlightEligibilityDecision.ineligible(flightId, FlightEligibilityFailure.CHECK_IN_NOT_OPEN);
        }
        if (!checkedAt.isBefore(flight.checkInClosesAt())) {
            return FlightEligibilityDecision.ineligible(flightId, FlightEligibilityFailure.CHECK_IN_CLOSED);
        }
        return FlightEligibilityDecision.eligible(flightId);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
