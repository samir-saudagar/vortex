package com.vortex.bagstate.api;

import com.vortex.bagstate.domain.BagState;
import java.time.Instant;
import java.util.UUID;

public record BagSnapshot(UUID bagId, String bagTag, String flightId, String checkInNodeId,
                          String confirmedNodeId, BagState state, UUID routeId, UUID activeCommandId,
                          UUID correlationId, long stateVersion, String failureCode, String failureReason,
                          Instant checkedInAt, Instant updatedAt, UUID workflowEventId,
                          String verifiedDepartureAirport, String verifiedDestinationAirport,
                          String verifiedFlightStatus, Instant verifiedAt) {
    public boolean expects(UUID eventId, UUID correlation) {
        return eventId.equals(workflowEventId) && correlationId.equals(correlation);
    }
}
