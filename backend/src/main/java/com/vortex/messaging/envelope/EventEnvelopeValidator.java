package com.vortex.messaging.envelope;

/** Validates UTF-8 JSON event contracts without performing messaging or workflow I/O. */
public interface EventEnvelopeValidator {
    EventEnvelope validate(byte[] utf8Json);
}
