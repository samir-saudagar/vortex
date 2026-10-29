package com.vortex.bagstate.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BagQuery {
    Optional<BagSnapshot> find(UUID bagId);
    boolean tagExists(String bagTag);
    List<HistoryItem> history(UUID bagId);

    record HistoryItem(UUID historyId, String recordType, String previousState, String currentState,
                       String reasonCode, String nodeId, Instant occurredAt, Instant recordedAt,
                       String details, Long stateVersion) { }
}
