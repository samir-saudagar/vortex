package com.vortex.bagstate.domain;

import java.util.Objects;

/** The sole deterministic policy for legal bag-state transitions. */
public final class BagStateTransitionPolicy {
    public TransitionDecision decide(BagState current, BagTransitionTrigger trigger, ScannerEvidence evidence) {
        Objects.requireNonNull(trigger, "trigger");

        if (current == null && trigger == BagTransitionTrigger.CHECK_IN_ACCEPTED) {
            return TransitionDecision.applied(null, BagState.CHECKED_IN, "CHECK_IN_ACCEPTED");
        }
        if (current == null) {
            return TransitionDecision.rejected(null, "INITIAL_STATE_REQUIRED");
        }

        return switch (trigger) {
            case FLIGHT_VERIFIED -> from(current, BagState.CHECKED_IN, BagState.FLIGHT_VERIFIED, "FLIGHT_VERIFIED");
            case FLIGHT_VERIFICATION_FAILED -> from(current, BagState.CHECKED_IN,
                    BagState.FLIGHT_VERIFICATION_FAILED, "FLIGHT_VERIFICATION_FAILED");
            case FLIGHT_RETRY_REQUESTED -> from(current, BagState.FLIGHT_VERIFICATION_FAILED,
                    BagState.CHECKED_IN, "FLIGHT_RETRY_ACCEPTED");
            case ROUTE_AVAILABLE -> from(current, BagState.FLIGHT_VERIFIED, BagState.ROUTE_PLANNED, "ROUTE_PLANNED");
            case ROUTE_UNAVAILABLE -> from(current, BagState.FLIGHT_VERIFIED, BagState.ROUTING_BLOCKED, "ROUTING_BLOCKED");
            case ROUTE_RETRY_AVAILABLE -> from(current, BagState.ROUTING_BLOCKED, BagState.ROUTE_PLANNED, "ROUTE_RETRY_PLANNED");
            case ROUTE_RETRY_UNAVAILABLE -> current == BagState.ROUTING_BLOCKED
                    ? TransitionDecision.noStateChange(current, "ROUTE_RETRY_STILL_UNREACHABLE")
                    : TransitionDecision.rejected(current, "ILLEGAL_ROUTE_RETRY");
            case FIRST_COMMAND_ISSUED -> from(current, BagState.ROUTE_PLANNED, BagState.MOVEMENT_PENDING,
                    "MOVEMENT_COMMAND_ISSUED");
            case COMMAND_ACCEPTED -> current == BagState.MOVEMENT_PENDING
                    ? TransitionDecision.noStateChange(current, "COMMAND_ACKNOWLEDGED")
                    : TransitionDecision.rejected(current, "ILLEGAL_COMMAND_ACKNOWLEDGEMENT");
            case COMMAND_STARTED -> from(current, BagState.MOVEMENT_PENDING, BagState.IN_TRANSIT, "MOVEMENT_STARTED");
            case COMMAND_SUCCEEDED -> current == BagState.IN_TRANSIT
                    ? TransitionDecision.noStateChange(current, "COMMAND_SUCCEEDED_AWAIT_SCANNER")
                    : TransitionDecision.rejected(current, "ILLEGAL_COMMAND_SUCCESS");
            case COMMAND_REJECTED, COMMAND_FAILED, COMMAND_TIMED_OUT ->
                    current == BagState.MOVEMENT_PENDING || current == BagState.IN_TRANSIT
                            ? TransitionDecision.applied(current, BagState.MOVEMENT_FAILED, "MOVEMENT_FAILED")
                            : TransitionDecision.rejected(current, "ILLEGAL_MOVEMENT_FAILURE");
            case INTERMEDIATE_SCANNER_ACCEPTED -> acceptIntermediate(current, evidence);
            case FINAL_MAKEUP_SCANNER_ACCEPTED -> acceptFinal(current, evidence);
            case NEXT_COMMAND_ISSUED -> from(current, BagState.AT_INTERMEDIATE_NODE, BagState.MOVEMENT_PENDING,
                    "NEXT_MOVEMENT_COMMAND_ISSUED");
            case MOVEMENT_RETRY_ROUTE_AVAILABLE -> from(current, BagState.MOVEMENT_FAILED, BagState.ROUTE_PLANNED,
                    "MOVEMENT_RETRY_REPLANNED");
            case MOVEMENT_RETRY_NO_ROUTE -> from(current, BagState.MOVEMENT_FAILED, BagState.ROUTING_BLOCKED,
                    "MOVEMENT_RETRY_BLOCKED");
            case CHECK_IN_ACCEPTED -> TransitionDecision.rejected(current, "CHECK_IN_ALREADY_ACCEPTED");
        };
    }

    private static TransitionDecision acceptIntermediate(BagState current, ScannerEvidence evidence) {
        if (current != BagState.IN_TRANSIT) {
            return TransitionDecision.rejected(current, "ILLEGAL_INTERMEDIATE_SCANNER_OBSERVATION");
        }
        if (evidence == null || !evidence.validCheckpoint() || evidence.finalStep()) {
            return TransitionDecision.rejected(current, "SCANNER_CHECKPOINT_NOT_VALIDATED");
        }
        return TransitionDecision.scannerAccepted(current, BagState.AT_INTERMEDIATE_NODE,
                evidence.observedNodeId(), "INTERMEDIATE_SCANNER_ACCEPTED");
    }

    private static TransitionDecision acceptFinal(BagState current, ScannerEvidence evidence) {
        if (current != BagState.IN_TRANSIT) {
            return TransitionDecision.rejected(current, "ILLEGAL_FINAL_SCANNER_OBSERVATION");
        }
        if (evidence == null || !evidence.validFinalMakeupArrival()) {
            return TransitionDecision.rejected(current, "FINAL_MAKEUP_GUARD_FAILED");
        }
        return TransitionDecision.scannerAccepted(current, BagState.AT_MAKEUP,
                evidence.observedNodeId(), "FINAL_MAKEUP_SCANNER_ACCEPTED");
    }

    private static TransitionDecision from(BagState actual, BagState expected, BagState next, String reason) {
        return actual == expected
                ? TransitionDecision.applied(actual, next, reason)
                : TransitionDecision.rejected(actual, "ILLEGAL_TRANSITION_" + actual + "_TO_" + next);
    }
}
