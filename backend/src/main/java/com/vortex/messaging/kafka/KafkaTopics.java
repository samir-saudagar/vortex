package com.vortex.messaging.kafka;

import com.vortex.messaging.envelope.EventType;

/** Approved topic names and the Java-owned event routing policy. */
public final class KafkaTopics {
    public static final String WORKFLOW = "vortex.workflow-events";
    public static final String COMMANDS = "vortex.equipment-commands";
    public static final String REPORTS = "vortex.simulator-reports";
    public static final String QUARANTINE = "vortex.quarantine";

    private KafkaTopics() {
    }

    public static String forJavaOutbox(EventType type) {
        return switch (type) {
            case EQUIPMENT_MOVEMENT_COMMAND -> COMMANDS;
            case BAG_CHECKED_IN, FLIGHT_STATUS_VERIFIED, FLIGHT_VERIFICATION_FAILED,
                    ROUTE_PLANNED, ROUTING_BLOCKED, BAG_STATE_CHANGED, EQUIPMENT_STATUS_CHANGED -> WORKFLOW;
            case EQUIPMENT_COMMAND_RESULT, SCANNER_OBSERVATION, EQUIPMENT_STATUS_REPORTED ->
                    throw new IllegalArgumentException("simulator report events are not Java outbox events");
        };
    }
}
