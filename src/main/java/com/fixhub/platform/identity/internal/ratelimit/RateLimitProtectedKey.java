package com.fixhub.platform.identity.internal.ratelimit;

/** An internal versioned HMAC result with no identifier or key material. */
final class RateLimitProtectedKey {

    private final int version;
    private final byte[] digest;

    RateLimitProtectedKey(int version, byte[] digest) {
        if (version <= 0 || digest == null || digest.length != 32) {
            throw new IllegalArgumentException("Invalid protected rate-limit key");
        }
        this.version = version;
        this.digest = digest.clone();
    }

    int version() {
        return version;
    }

    /** The caller owns and may clear this fresh copy. */
    byte[] digest() {
        return digest.clone();
    }

    @Override
    public String toString() {
        return "RateLimitProtectedKey[REDACTED]";
    }
}
