package com.vortex.messaging.envelope;

public enum EventType {
    BAG_CHECKED_IN("BagCheckedIn"),
    FLIGHT_STATUS_VERIFIED("FlightStatusVerified"),
    FLIGHT_VERIFICATION_FAILED("FlightVerificationFailed"),
    ROUTE_PLANNED("RoutePlanned"),
    ROUTING_BLOCKED("RoutingBlocked"),
    BAG_STATE_CHANGED("BagStateChanged"),
    EQUIPMENT_STATUS_CHANGED("EquipmentStatusChanged"),
    EQUIPMENT_MOVEMENT_COMMAND("EquipmentMovementCommand"),
    EQUIPMENT_COMMAND_RESULT("EquipmentCommandResult"),
    SCANNER_OBSERVATION("ScannerObservation"),
    EQUIPMENT_STATUS_REPORTED("EquipmentStatusReported");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static EventType fromWireName(String value) {
        for (EventType type : values()) {
            if (type.wireName.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
