package com.fixhub.platform.identity.internal.ratelimit;

import com.fixhub.platform.identity.internal.account.Account;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Pure, unwired protected-key derivation for the three approved identifier dimensions. */
final class RateLimitIdentifierProtector {

    private static final byte[] FRAME_PREFIX = "FH011-RATE-V1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMAIL = "EMAIL".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORIGIN = "ORIGIN".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GLOBAL = "GLOBAL".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GLOBAL_IDENTIFIER =
            "FH011_GLOBAL".getBytes(StandardCharsets.US_ASCII);
    private static final String INVALID_INPUT = "Invalid rate-limit HMAC input";

    private final RateLimitHmacKeySnapshot snapshot;

    RateLimitIdentifierProtector(RateLimitHmacKeySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        this.snapshot = snapshot;
    }

    List<RateLimitProtectedKey> protectEmail(
            RateLimitPolicy policy, String normalizedEmail, Instant decisionInstant) {
        if (policy != RateLimitPolicy.REGISTRATION_EMAIL
                && policy != RateLimitPolicy.RESEND_EMAIL) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        validateEmail(normalizedEmail);
        byte[] identifier = normalizedEmail.getBytes(StandardCharsets.UTF_8);
        try {
            return derive(policy, EMAIL, identifier, decisionInstant);
        } finally {
            clear(identifier);
        }
    }

    List<RateLimitProtectedKey> protectOrigin(
            RateLimitPolicy policy, byte[] canonicalOrigin, Instant decisionInstant) {
        if (policy != RateLimitPolicy.REGISTRATION_ORIGIN
                && policy != RateLimitPolicy.RESEND_ORIGIN
                && policy != RateLimitPolicy.VERIFICATION_ORIGIN) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        if (canonicalOrigin == null) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        byte[] identifier = canonicalOrigin.clone();
        try {
            validateOrigin(identifier);
            return derive(policy, ORIGIN, identifier, decisionInstant);
        } finally {
            clear(identifier);
        }
    }

    List<RateLimitProtectedKey> protectGlobal(RateLimitPolicy policy, Instant decisionInstant) {
        if (policy != RateLimitPolicy.FH011_GLOBAL) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        return derive(policy, GLOBAL, GLOBAL_IDENTIFIER, decisionInstant);
    }

    private List<RateLimitProtectedKey> derive(
            RateLimitPolicy policy, byte[] dimension, byte[] identifier, Instant decisionInstant) {
        byte[] policyBytes = policy.name().getBytes(StandardCharsets.UTF_8);
        byte[] frame =
                ByteBuffer.allocate(
                                FRAME_PREFIX.length
                                        + Integer.BYTES * 3
                                        + policyBytes.length
                                        + dimension.length
                                        + identifier.length)
                        .put(FRAME_PREFIX)
                        .putInt(policyBytes.length)
                        .put(policyBytes)
                        .putInt(dimension.length)
                        .put(dimension)
                        .putInt(identifier.length)
                        .put(identifier)
                        .array();
        try {
            return snapshot.derive(frame, decisionInstant);
        } finally {
            clear(policyBytes);
            clear(frame);
        }
    }

    private static void validateEmail(String normalizedEmail) {
        try {
            Account validated = Account.create(normalizedEmail, null, "en");
            if (!normalizedEmail.equals(validated.getEmail())
                    || !normalizedEmail.equals(validated.getEmailNormalized())) {
                throw new IllegalArgumentException(INVALID_INPUT);
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
    }

    private static void validateOrigin(byte[] identifier) {
        if (identifier.length == 5 && identifier[0] == 0x04) {
            return;
        }
        if (identifier.length != 17 || identifier[0] != 0x06) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        boolean mapped = true;
        for (int index = 1; index <= 10; index++) {
            mapped &= identifier[index] == 0;
        }
        mapped &= identifier[11] == (byte) 0xff && identifier[12] == (byte) 0xff;
        if (mapped) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
    }

    private static void clear(byte[] bytes) {
        Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public String toString() {
        return "RateLimitIdentifierProtector[REDACTED]";
    }
}
