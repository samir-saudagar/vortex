package com.vortex.checkin.api;

import java.util.UUID;

public interface CheckInUseCase {
    Accepted checkIn(String key, Request request);
    record Request(String bagTag, String flightId) { }
    record Accepted(UUID bagId, String bagTag, String flightId, UUID correlationId,
                    String state, String statusUrl, String historyUrl) { }
}
