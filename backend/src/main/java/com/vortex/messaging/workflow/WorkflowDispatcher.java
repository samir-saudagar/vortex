package com.vortex.messaging.workflow;

import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.envelope.EventEnvelope;
import com.vortex.messaging.envelope.EventType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;

@Component
public class WorkflowDispatcher {
    private static final Set<EventType> REQUIRED = EnumSet.of(EventType.BAG_CHECKED_IN,
            EventType.FLIGHT_STATUS_VERIFIED, EventType.ROUTE_PLANNED);
    private final Map<EventType, WorkflowEventHandler> handlers = new EnumMap<>(EventType.class);

    public WorkflowDispatcher(List<WorkflowEventHandler> registeredHandlers) {
        for (WorkflowEventHandler handler : registeredHandlers) {
            for (EventType eventType : handler.eventTypes()) {
                if (!REQUIRED.contains(eventType)) {
                    throw new IllegalStateException("workflow handlers may register only actionable workflow facts");
                }
                if (handlers.putIfAbsent(eventType, handler) != null) {
                    throw new IllegalStateException("multiple workflow handlers registered for " + eventType);
                }
            }
        }
    }

    public ProcessingOutcome dispatch(EventEnvelope event) {
        if (event.eventType() == EventType.BAG_STATE_CHANGED || event.eventType() == EventType.EQUIPMENT_STATUS_CHANGED
                || event.eventType() == EventType.FLIGHT_VERIFICATION_FAILED || event.eventType() == EventType.ROUTING_BLOCKED) {
            return ProcessingOutcome.PROCESSED; // Notification facts and failures have no next automatic workflow stage.
        }
        WorkflowEventHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            throw new IllegalStateException("no workflow handler is registered for " + event.eventType());
        }
        return handler.handle(event);
    }

    public void requireHandlers() {
        if (!handlers.keySet().containsAll(REQUIRED)) {
            throw new IllegalStateException("Workflow consumers require Phase 4 handlers; keep VORTEX_KAFKA_CONSUMERS_ENABLED=false");
        }
    }
}
