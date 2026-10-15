package com.vortex.bagstate.domain;

import java.util.Optional;

/** Read-only result of the bag-state policy; only policy code can construct one. */
public final class TransitionDecision {
    private final boolean accepted;
    private final boolean stateChanged;
    private final Optional<BagState> previousState;
    private final Optional<BagState> currentState;
    private final Optional<String> confirmedNodeUpdate;
    private final String reasonCode;

    TransitionDecision(boolean accepted, boolean stateChanged, Optional<BagState> previousState,
                       Optional<BagState> currentState, Optional<String> confirmedNodeUpdate, String reasonCode) {
        this.accepted = accepted;
        this.stateChanged = stateChanged;
        this.previousState = previousState == null ? Optional.empty() : previousState;
        this.currentState = currentState == null ? Optional.empty() : currentState;
        this.confirmedNodeUpdate = confirmedNodeUpdate == null ? Optional.empty() : confirmedNodeUpdate;
        this.reasonCode = reasonCode;
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (stateChanged && (this.currentState.isEmpty() || this.previousState.equals(this.currentState))) {
            throw new IllegalArgumentException("stateChanged must identify a different resulting state");
        }
        if (!stateChanged && !this.previousState.equals(this.currentState)) {
            throw new IllegalArgumentException("unchanged decisions must retain the current state");
        }
        if (!accepted && (stateChanged || this.confirmedNodeUpdate.isPresent())) {
            throw new IllegalArgumentException("rejected transitions cannot mutate state or confirmed node");
        }
        if (this.confirmedNodeUpdate.isPresent() && !stateChanged) {
            throw new IllegalArgumentException("confirmed node updates require an accepted state transition");
        }
    }

    public boolean accepted() {
        return accepted;
    }

    public boolean stateChanged() {
        return stateChanged;
    }

    public Optional<BagState> previousState() {
        return previousState;
    }

    public Optional<BagState> currentState() {
        return currentState;
    }

    public Optional<String> confirmedNodeUpdate() {
        return confirmedNodeUpdate;
    }

    public String reasonCode() {
        return reasonCode;
    }

    static TransitionDecision rejected(BagState current, String reason) {
        return new TransitionDecision(false, false, Optional.ofNullable(current), Optional.ofNullable(current), Optional.empty(), reason);
    }

    static TransitionDecision applied(BagState previous, BagState next, String reason) {
        return new TransitionDecision(true, previous != next, Optional.ofNullable(previous), Optional.of(next), Optional.empty(), reason);
    }

    static TransitionDecision noStateChange(BagState current, String reason) {
        return new TransitionDecision(true, false, Optional.of(current), Optional.of(current), Optional.empty(), reason);
    }

    static TransitionDecision scannerAccepted(BagState previous, BagState next, String nodeId, String reason) {
        return new TransitionDecision(true, previous != next, Optional.of(previous), Optional.of(next), Optional.of(nodeId), reason);
    }
}
