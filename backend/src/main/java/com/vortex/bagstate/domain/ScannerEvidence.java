package com.vortex.bagstate.domain;

/** Facts that Movement must validate before asking Bag State to accept a scanner checkpoint. */
public record ScannerEvidence(
        String observedNodeId,
        boolean structurallyValidAndNew,
        boolean activeRouteMatches,
        boolean correlationMatches,
        boolean activeCommandMatches,
        boolean expectedStepMatches,
        boolean observedAfterCommandIssue,
        boolean expectedScannerMatches,
        boolean commandSucceeded,
        boolean finalStep,
        boolean configuredFinalMakeupScanner,
        boolean matchesVerifiedFlightDestination) {

    public boolean validCheckpoint() {
        return !blank(observedNodeId)
                && structurallyValidAndNew
                && activeRouteMatches
                && correlationMatches
                && activeCommandMatches
                && expectedStepMatches
                && observedAfterCommandIssue
                && expectedScannerMatches
                && commandSucceeded;
    }

    public boolean validFinalMakeupArrival() {
        return validCheckpoint()
                && finalStep
                && configuredFinalMakeupScanner
                && matchesVerifiedFlightDestination;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
