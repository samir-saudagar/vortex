package com.vortex.flight.api;

import com.vortex.flight.domain.Flight;

import java.util.Optional;

/** Read-only catalog boundary owned by Flight Validation. */
public interface FlightCatalog {
    Optional<Flight> findById(String flightId);
}
