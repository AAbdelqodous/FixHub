package com.fixhub.platform.identity.internal.verification;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class VerificationTokenCryptography {

    private static final int RAW_TOKEN_LENGTH = 32;
    private static final int ENCODED_TOKEN_LENGTH = 43;
    private static final int DIGEST_LENGTH = 32;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String INVALID_TOKEN_MESSAGE = "Verification token has invalid format";

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final RandomByteSource randomByteSource;

    VerificationTokenCryptography(RandomByteSource randomByteSource) {
        if (randomByteSource == null) {
            throw new IllegalArgumentException("Random byte source is required");
        }
        this.randomByteSource = randomByteSource;
    }

    public VerificationTokenIssuance issue() {
        byte[] rawToken = new byte[RAW_TOKEN_LENGTH];
        byte[] digest = null;
        try {
            randomByteSource.nextBytes(rawToken);
            String publicToken = TOKEN_ENCODER.encodeToString(rawToken);
            if (publicToken.length() != ENCODED_TOKEN_LENGTH) {
                throw new IllegalStateException("Verification token generation failed");
            }
            digest = digest(rawToken);
            return new VerificationTokenIssuance(publicToken, digest);
        } finally {
            Arrays.fill(rawToken, (byte) 0);
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    public byte[] decodeAndDigest(String encodedToken) {
        requireStrictShape(encodedToken);

        byte[] decoded = null;
        try {
            decoded = decode(encodedToken);
            if (decoded.length != RAW_TOKEN_LENGTH
                    || !TOKEN_ENCODER.encodeToString(decoded).equals(encodedToken)) {
                throw invalidToken();
            }
            return digest(decoded);
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    static boolean isCanonicalPublicToken(String encodedToken) {
        try {
            requireStrictShape(encodedToken);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        byte[] decoded = null;
        try {
            decoded = TOKEN_DECODER.decode(encodedToken);
            return decoded.length == RAW_TOKEN_LENGTH
                    && TOKEN_ENCODER.encodeToString(decoded).equals(encodedToken);
        } catch (IllegalArgumentException exception) {
            return false;
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    private static void requireStrictShape(String encodedToken) {
        if (encodedToken == null || encodedToken.length() != ENCODED_TOKEN_LENGTH) {
            throw invalidToken();
        }
        for (int index = 0; index < encodedToken.length(); index++) {
            char character = encodedToken.charAt(index);
            if (!isBase64UrlCharacter(character)) {
                throw invalidToken();
            }
        }
    }

    private static boolean isBase64UrlCharacter(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_';
    }

    private static byte[] decode(String encodedToken) {
        try {
            return TOKEN_DECODER.decode(encodedToken);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static byte[] digest(byte[] rawToken) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(rawToken);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required verification-token digest is unavailable");
        }
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException(INVALID_TOKEN_MESSAGE);
    }
}

@FunctionalInterface
interface RandomByteSource {

    void nextBytes(byte[] destination);
}

final class SecureRandomByteSource implements RandomByteSource {

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void nextBytes(byte[] destination) {
        secureRandom.nextBytes(destination);
    }
}
