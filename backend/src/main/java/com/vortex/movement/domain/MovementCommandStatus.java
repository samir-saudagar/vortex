package com.vortex.movement.domain;

public enum MovementCommandStatus {
    ISSUED,
    ACCEPTED,
    STARTED,
    SUCCEEDED,
    REJECTED,
    FAILED,
    TIMED_OUT,
    SUPERSEDED
}
