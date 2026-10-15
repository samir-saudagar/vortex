package com.vortex.messaging.envelope;

public final class EnvelopeValidationException extends RuntimeException {
    private final EnvelopeValidationCode code;

    public EnvelopeValidationException(EnvelopeValidationCode code, String message) {
        super(message);
        this.code = code;
    }

    public EnvelopeValidationCode code() {
        return code;
    }
}
