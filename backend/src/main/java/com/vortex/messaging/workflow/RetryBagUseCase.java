package com.vortex.messaging.workflow;
import java.util.UUID;
public interface RetryBagUseCase {
    Accepted retry(UUID bagId, String key);
    record Accepted(UUID bagId, UUID correlationId, String state) { }
}
