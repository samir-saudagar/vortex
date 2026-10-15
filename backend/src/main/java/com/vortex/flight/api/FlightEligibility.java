package com.vortex.flight.api;

import com.vortex.flight.domain.FlightEligibilityDecision;

import java.time.Instant;

/** Typed in-process boundary for applying the approved flight check-in rule. */
public interface FlightEligibility {
    FlightEligibilityDecision verify(String flightId, String configuredDepartureAirport, Instant checkedAt);
}
