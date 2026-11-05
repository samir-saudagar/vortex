package com.vortex.bagstate.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BagQuery {
    Optional<BagSnapshot> find(UUID bagId);
    boolean tagExists(String bagTag);
    List<HistoryItem> history(UUID bagId);

    Page list(String search, String state, int page, int size);
    Summary summary();

    record Page(List<BagSnapshot> items, long total, int page, int size) { }
    record Summary(long total, long active, long completed, long failed) { }

    record HistoryItem(UUID historyId, String recordType, String previousState, String currentState,
                       String reasonCode, String nodeId, Instant occurredAt, Instant recordedAt,
                       String details, Long stateVersion) { }
}
