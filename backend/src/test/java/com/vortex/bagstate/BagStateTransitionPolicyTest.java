package com.vortex.bagstate;

import com.vortex.bagstate.domain.BagState;
import com.vortex.bagstate.domain.BagStateTransitionPolicy;
import com.vortex.bagstate.domain.BagTransitionTrigger;
import com.vortex.bagstate.domain.ScannerEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BagStateTransitionPolicyTest {
    private final BagStateTransitionPolicy policy = new BagStateTransitionPolicy();

    @Test
    void appliesEveryApprovedStateTransitionAndRetryOutcome() {
        List<Case> cases = List.of(
                new Case(null, BagTransitionTrigger.CHECK_IN_ACCEPTED, BagState.CHECKED_IN, true),
                new Case(BagState.CHECKED_IN, BagTransitionTrigger.FLIGHT_VERIFIED, BagState.FLIGHT_VERIFIED, true),
                new Case(BagState.CHECKED_IN, BagTransitionTrigger.FLIGHT_VERIFICATION_FAILED,
                        BagState.FLIGHT_VERIFICATION_FAILED, true),
                new Case(BagState.FLIGHT_VERIFICATION_FAILED, BagTransitionTrigger.FLIGHT_RETRY_REQUESTED,
                        BagState.CHECKED_IN, true),
                new Case(BagState.FLIGHT_VERIFIED, BagTransitionTrigger.ROUTE_AVAILABLE, BagState.ROUTE_PLANNED, true),
                new Case(BagState.FLIGHT_VERIFIED, BagTransitionTrigger.ROUTE_UNAVAILABLE, BagState.ROUTING_BLOCKED, true),
                new Case(BagState.ROUTING_BLOCKED, BagTransitionTrigger.ROUTE_RETRY_AVAILABLE,
                        BagState.ROUTE_PLANNED, true),
                new Case(BagState.ROUTING_BLOCKED, BagTransitionTrigger.ROUTE_RETRY_UNAVAILABLE,
                        BagState.ROUTING_BLOCKED, false),
                new Case(BagState.ROUTE_PLANNED, BagTransitionTrigger.FIRST_COMMAND_ISSUED,
                        BagState.MOVEMENT_PENDING, true),
                new Case(BagState.MOVEMENT_PENDING, BagTransitionTrigger.COMMAND_ACCEPTED,
                        BagState.MOVEMENT_PENDING, false),
                new Case(BagState.MOVEMENT_PENDING, BagTransitionTrigger.COMMAND_STARTED,
                        BagState.IN_TRANSIT, true),
                new Case(BagState.IN_TRANSIT, BagTransitionTrigger.COMMAND_SUCCEEDED,
                        BagState.IN_TRANSIT, false),
                new Case(BagState.MOVEMENT_PENDING, BagTransitionTrigger.COMMAND_REJECTED,
                        BagState.MOVEMENT_FAILED, true),
                new Case(BagState.IN_TRANSIT, BagTransitionTrigger.COMMAND_FAILED,
                        BagState.MOVEMENT_FAILED, true),
                new Case(BagState.IN_TRANSIT, BagTransitionTrigger.COMMAND_TIMED_OUT,
                        BagState.MOVEMENT_FAILED, true),
                new Case(BagState.IN_TRANSIT, BagTransitionTrigger.INTERMEDIATE_SCANNER_ACCEPTED,
                        BagState.AT_INTERMEDIATE_NODE, true),
                new Case(BagState.AT_INTERMEDIATE_NODE, BagTransitionTrigger.NEXT_COMMAND_ISSUED,
                        BagState.MOVEMENT_PENDING, true),
                new Case(BagState.MOVEMENT_FAILED, BagTransitionTrigger.MOVEMENT_RETRY_ROUTE_AVAILABLE,
                        BagState.ROUTE_PLANNED, true),
                new Case(BagState.MOVEMENT_FAILED, BagTransitionTrigger.MOVEMENT_RETRY_NO_ROUTE,
                        BagState.ROUTING_BLOCKED, true),
                new Case(BagState.IN_TRANSIT, BagTransitionTrigger.FINAL_MAKEUP_SCANNER_ACCEPTED,
                        BagState.AT_MAKEUP, true));

        for (Case testCase : cases) {
            var decision = policy.decide(testCase.current(), testCase.trigger(), evidenceFor(testCase.trigger()));
            assertTrue(decision.accepted(), testCase.toString());
            assertEquals(testCase.expected(), decision.currentState().orElseThrow(), testCase.toString());
            assertEquals(testCase.stateChanged(), decision.stateChanged(), testCase.toString());
        }
    }

    @Test
    void rejectsIllegalTransitionsAndTerminalStateChanges() {
        assertFalse(policy.decide(BagState.CHECKED_IN, BagTransitionTrigger.COMMAND_STARTED, null).accepted());
        assertFalse(policy.decide(BagState.MOVEMENT_PENDING, BagTransitionTrigger.COMMAND_SUCCEEDED, null).accepted());
        assertFalse(policy.decide(BagState.AT_MAKEUP, BagTransitionTrigger.FLIGHT_RETRY_REQUESTED, null).accepted());
        assertFalse(policy.decide(BagState.MOVEMENT_FAILED, BagTransitionTrigger.COMMAND_STARTED, null).accepted());
        assertFalse(policy.decide(null, BagTransitionTrigger.FLIGHT_VERIFIED, null).accepted());
    }

    @Test
    void finalArrivalRequiresEveryRouteCommandScannerAndDestinationGuard() {
        ScannerEvidence valid = evidence(true);
        var decision = policy.decide(BagState.IN_TRANSIT, BagTransitionTrigger.FINAL_MAKEUP_SCANNER_ACCEPTED, valid);
        assertTrue(decision.accepted());
        assertEquals(BagState.AT_MAKEUP, decision.currentState().orElseThrow());
        assertEquals("makeup-lhr", decision.confirmedNodeUpdate().orElseThrow());

        for (int missing = 0; missing < 11; missing++) {
            ScannerEvidence invalid = evidence(false, missing);
            var rejected = policy.decide(BagState.IN_TRANSIT,
                    BagTransitionTrigger.FINAL_MAKEUP_SCANNER_ACCEPTED, invalid);
            assertFalse(rejected.accepted(), "guard index " + missing);
            assertTrue(rejected.confirmedNodeUpdate().isEmpty());
            assertEquals(BagState.IN_TRANSIT, rejected.currentState().orElseThrow());
        }
    }

    @Test
    void onlyAcceptedScannerEvidenceCanSetConfirmedNode() {
        for (BagTransitionTrigger trigger : List.of(BagTransitionTrigger.COMMAND_ACCEPTED,
                BagTransitionTrigger.COMMAND_STARTED, BagTransitionTrigger.COMMAND_SUCCEEDED,
                BagTransitionTrigger.COMMAND_REJECTED, BagTransitionTrigger.COMMAND_FAILED,
                BagTransitionTrigger.COMMAND_TIMED_OUT)) {
            var decision = policy.decide(BagState.IN_TRANSIT, trigger, evidence(true));
            assertTrue(decision.confirmedNodeUpdate().isEmpty(), trigger.name());
        }

        var invalidObservation = policy.decide(BagState.IN_TRANSIT,
                BagTransitionTrigger.INTERMEDIATE_SCANNER_ACCEPTED, evidence(false));
        assertFalse(invalidObservation.accepted());
        assertTrue(invalidObservation.confirmedNodeUpdate().isEmpty());
    }

    private static ScannerEvidence evidenceFor(BagTransitionTrigger trigger) {
        if (trigger == BagTransitionTrigger.INTERMEDIATE_SCANNER_ACCEPTED) {
            return new ScannerEvidence("junction-west", true, true, true, true, true,
                    true, true, true, false, false, false);
        }
        if (trigger == BagTransitionTrigger.FINAL_MAKEUP_SCANNER_ACCEPTED) {
            return evidence(true);
        }
        return null;
    }

    private static ScannerEvidence evidence(boolean valid) {
        return evidence(valid, -1);
    }

    private static ScannerEvidence evidence(boolean valid, int falseField) {
        boolean[] guards = {valid, valid, valid, valid, valid, valid, valid, valid, valid, valid, valid};
        if (falseField >= 0) {
            guards[falseField] = false;
        }
        return new ScannerEvidence("makeup-lhr", guards[0], guards[1], guards[2], guards[3], guards[4],
                guards[5], guards[6], guards[7], guards[8], guards[9], guards[10]);
    }

    private record Case(BagState current, BagTransitionTrigger trigger, BagState expected, boolean stateChanged) {
    }
}
