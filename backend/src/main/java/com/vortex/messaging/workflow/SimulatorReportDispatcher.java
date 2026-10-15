package com.vortex.messaging.workflow;

import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.envelope.EventEnvelope;
import com.vortex.messaging.envelope.EventType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;

@Component
public class SimulatorReportDispatcher {
    private final Map<EventType, SimulatorReportHandler> handlers = new EnumMap<>(EventType.class);

    public SimulatorReportDispatcher(List<SimulatorReportHandler> registeredHandlers) {
        for (SimulatorReportHandler handler : registeredHandlers) {
            for (EventType eventType : handler.eventTypes()) {
                if (eventType != EventType.EQUIPMENT_COMMAND_RESULT && eventType != EventType.SCANNER_OBSERVATION
                        && eventType != EventType.EQUIPMENT_STATUS_REPORTED) {
                    throw new IllegalStateException("non-report event registered with simulator report handler");
                }
                if (handlers.putIfAbsent(eventType, handler) != null) {
                    throw new IllegalStateException("multiple report handlers registered for " + eventType);
                }
            }
        }
    }

    public ProcessingOutcome dispatch(EventEnvelope event) {
        SimulatorReportHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            throw new IllegalStateException("no simulator report handler is registered for " + event.eventType());
        }
        return handler.handle(event);
    }

    public void requireHandlers() {
        if (!handlers.keySet().containsAll(EnumSet.of(EventType.EQUIPMENT_COMMAND_RESULT,
                EventType.SCANNER_OBSERVATION, EventType.EQUIPMENT_STATUS_REPORTED))) {
            throw new IllegalStateException("Report consumers require Phase 4 handlers; keep VORTEX_KAFKA_CONSUMERS_ENABLED=false");
        }
    }
}
