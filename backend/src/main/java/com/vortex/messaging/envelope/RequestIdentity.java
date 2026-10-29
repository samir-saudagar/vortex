package com.vortex.messaging.envelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Validation and hashing of HTTP idempotency identities. */
public final class RequestIdentity {
    private RequestIdentity() { }
    public static boolean identifier(String text) { return text != null && text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"); }
    public static void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw new IllegalArgumentException("Idempotency-Key must contain 1–128 visible ASCII characters");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
