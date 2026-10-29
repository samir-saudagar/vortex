package com.vortex.bagstate.api;

import com.vortex.bagstate.domain.BagTransitionTrigger;
import com.vortex.bagstate.domain.ScannerEvidence;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Only this owner writes bags and bag_history. Calls join the caller's transaction. */
public interface BagLifecycle {
    Optional<BagSnapshot> lock(UUID bagId);
    BagSnapshot create(UUID bagId, String tag, String flightId, String nodeId, UUID correlationId, UUID rootEvent);
    BagSnapshot transition(UUID bagId, BagTransitionTrigger trigger, UUID eventId, Instant occurredAt,
                           String reason, String failureReason, ScannerEvidence evidence);
    void workflow(UUID bagId, UUID expectedEvent);
    void selection(UUID bagId, UUID routeId, UUID commandId);
    void verifiedFlight(UUID bagId, String departure, String destination, String status, Instant checkedAt);
    void record(UUID bagId, String type, String reason, UUID eventId, Instant occurredAt, String details);
}
