package com.vortex.messaging.deduplication;

/** Durable result recorded with the consumer's PostgreSQL effects. */
public enum ProcessingOutcome {
    PROCESSED,
    REJECTED
}
