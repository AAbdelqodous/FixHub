package com.fixhub.platform.identity.internal.verification;

public final class VerificationTokenIssuance {

    private static final int DIGEST_LENGTH = 32;

    private final String publicToken;
    private final byte[] digest;

    VerificationTokenIssuance(String publicToken, byte[] digest) {
        if (!VerificationTokenCryptography.isCanonicalPublicToken(publicToken)) {
            throw new IllegalArgumentException("Verification token has invalid format");
        }
        if (digest == null || digest.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("Verification token digest has invalid format");
        }
        this.publicToken = publicToken;
        this.digest = digest.clone();
    }

    public String publicToken() {
        return publicToken;
    }

    public byte[] digest() {
        return digest.clone();
    }

    @Override
    public String toString() {
        return "VerificationTokenIssuance[REDACTED]";
    }
}
