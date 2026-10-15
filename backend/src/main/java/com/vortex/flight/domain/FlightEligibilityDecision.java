package com.vortex.flight.domain;

import java.util.Optional;

public record FlightEligibilityDecision(
        String flightId,
        boolean eligible,
        Optional<FlightEligibilityFailure> failure) {

    public FlightEligibilityDecision {
        if (flightId == null || flightId.isBlank()) {
            throw new IllegalArgumentException("flightId must not be blank");
        }
        failure = failure == null ? Optional.empty() : failure;
        if (eligible == failure.isPresent()) {
            throw new IllegalArgumentException("eligible decisions have no failure; ineligible decisions require one");
        }
    }

    public static FlightEligibilityDecision eligible(String flightId) {
        return new FlightEligibilityDecision(flightId, true, Optional.empty());
    }

    public static FlightEligibilityDecision ineligible(String flightId, FlightEligibilityFailure failure) {
        return new FlightEligibilityDecision(flightId, false, Optional.of(failure));
    }
}
