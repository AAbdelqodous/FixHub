package com.fixhub.platform.identity.internal.ratelimit;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A validated, unwired key snapshot. Its monitor serializes derivation with destruction. */
final class RateLimitHmacKeySnapshot implements AutoCloseable {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Duration MINIMUM_OVERLAP = Duration.ofHours(25);
    private static final String INVALID_CONFIGURATION = "Invalid rate-limit HMAC configuration";
    private static final String RESOLUTION_FAILURE = "Rate-limit HMAC key is unavailable";
    private static final String CRYPTO_FAILURE = "Rate-limit HMAC operation is unavailable";

    private final int currentVersion;
    private final byte[] currentKey;
    private final int previousVersion;
    private final byte[] previousKey;
    private final Instant rotationStart;
    private final Instant rotationEnd;
    private boolean closed;

    private RateLimitHmacKeySnapshot(
            int currentVersion,
            byte[] currentKey,
            int previousVersion,
            byte[] previousKey,
            Instant rotationStart,
            Instant rotationEnd) {
        this.currentVersion = currentVersion;
        this.currentKey = currentKey;
        this.previousVersion = previousVersion;
        this.previousKey = previousKey;
        this.rotationStart = rotationStart;
        this.rotationEnd = rotationEnd;
    }

    static RateLimitHmacKeySnapshot create(
            RateLimitSecretResolver resolver,
            int currentVersion,
            String currentReference,
            Integer previousVersion,
            String previousReference,
            Instant rotationStart,
            Duration overlap) {
        if (resolver == null || currentVersion <= 0 || !hasReference(currentReference)) {
            throw invalidConfiguration();
        }
        boolean rotation =
                previousVersion != null
                        || previousReference != null
                        || rotationStart != null
                        || overlap != null;
        Instant rotationEnd = null;
        if (rotation) {
            if (previousVersion == null
                    || previousVersion <= 0
                    || previousVersion == currentVersion
                    || !hasReference(previousReference)
                    || rotationStart == null
                    || overlap == null
                    || overlap.compareTo(MINIMUM_OVERLAP) < 0) {
                throw invalidConfiguration();
            }
            try {
                rotationEnd = rotationStart.plus(overlap);
            } catch (DateTimeException | ArithmeticException failure) {
                throw invalidConfiguration();
            }
        }

        byte[] currentResolved = null;
        byte[] previousResolved = null;
        byte[] currentOwned = null;
        byte[] previousOwned = null;
        try {
            currentResolved = resolve(resolver, currentReference);
            requireKey(currentResolved);
            if (rotation) {
                previousResolved = resolve(resolver, previousReference);
                requireKey(previousResolved);
                if (MessageDigest.isEqual(currentResolved, previousResolved)) {
                    throw invalidConfiguration();
                }
            }
            currentOwned = currentResolved.clone();
            if (rotation) {
                previousOwned = previousResolved.clone();
            }
            return new RateLimitHmacKeySnapshot(
                    currentVersion,
                    currentOwned,
                    rotation ? previousVersion : 0,
                    previousOwned,
                    rotationStart,
                    rotationEnd);
        } catch (RuntimeException failure) {
            clear(currentOwned);
            clear(previousOwned);
            throw failure;
        } finally {
            clear(currentResolved);
            clear(previousResolved);
        }
    }

    synchronized List<RateLimitProtectedKey> derive(byte[] frame, Instant decisionInstant) {
        if (closed) {
            throw new IllegalStateException("Rate-limit HMAC key snapshot is closed");
        }
        if (frame == null || decisionInstant == null) {
            throw new IllegalArgumentException("Invalid rate-limit HMAC input");
        }
        List<RateLimitProtectedKey> protectedKeys = new ArrayList<>(2);
        if (previousKey == null || !decisionInstant.isBefore(rotationStart)) {
            protectedKeys.add(hmac(currentVersion, currentKey, frame));
        }
        if (previousKey != null && decisionInstant.isBefore(rotationEnd)) {
            protectedKeys.add(hmac(previousVersion, previousKey, frame));
        }
        return List.copyOf(protectedKeys);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            clear(currentKey);
            clear(previousKey);
        }
    }

    private static RateLimitProtectedKey hmac(int version, byte[] key, byte[] frame) {
        byte[] digest = null;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            digest = mac.doFinal(frame);
            return new RateLimitProtectedKey(version, digest);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException(CRYPTO_FAILURE);
        } finally {
            clear(digest);
        }
    }

    private static byte[] resolve(RateLimitSecretResolver resolver, String reference) {
        try {
            byte[] resolved = resolver.resolve(reference);
            if (resolved == null) {
                throw new IllegalStateException(RESOLUTION_FAILURE);
            }
            return resolved;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(RESOLUTION_FAILURE);
        }
    }

    private static void requireKey(byte[] key) {
        if (key.length < 32) {
            throw invalidConfiguration();
        }
    }

    private static boolean hasReference(String reference) {
        return reference != null && !reference.isBlank();
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException(INVALID_CONFIGURATION);
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "RateLimitHmacKeySnapshot[REDACTED]";
    }
}
