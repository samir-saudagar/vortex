package com.vortex.flight.api;

import java.util.UUID;
public interface FlightVerification {
    boolean verify(UUID bagId, UUID eventId, UUID correlationId);
}
