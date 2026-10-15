package com.vortex.messaging.workflow;

import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.envelope.EventEnvelope;
import com.vortex.messaging.envelope.EventType;

import java.util.Set;

/** Typed extension point for movement-owned report processing, introduced in Phase 4. */
public interface SimulatorReportHandler {
    Set<EventType> eventTypes();

    ProcessingOutcome handle(EventEnvelope event);
}
