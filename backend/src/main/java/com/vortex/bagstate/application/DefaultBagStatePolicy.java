package com.vortex.bagstate.application;

import com.vortex.bagstate.api.BagStatePolicy;
import com.vortex.bagstate.domain.BagState;
import com.vortex.bagstate.domain.BagStateTransitionPolicy;
import com.vortex.bagstate.domain.BagTransitionTrigger;
import com.vortex.bagstate.domain.ScannerEvidence;
import com.vortex.bagstate.domain.TransitionDecision;

import java.util.Objects;

public final class DefaultBagStatePolicy implements BagStatePolicy {
    private final BagStateTransitionPolicy policy;

    public DefaultBagStatePolicy(BagStateTransitionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public TransitionDecision decide(BagState current, BagTransitionTrigger trigger, ScannerEvidence evidence) {
        return policy.decide(current, trigger, evidence);
    }
}
