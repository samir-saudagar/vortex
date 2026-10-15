package com.vortex.bagstate.api;

import com.vortex.bagstate.domain.BagState;
import com.vortex.bagstate.domain.BagTransitionTrigger;
import com.vortex.bagstate.domain.ScannerEvidence;
import com.vortex.bagstate.domain.TransitionDecision;

/** Typed in-process boundary for evaluating legal state transitions. */
public interface BagStatePolicy {
    TransitionDecision decide(BagState current, BagTransitionTrigger trigger, ScannerEvidence evidence);
}
