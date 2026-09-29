package com.fixhub.platform.identity.internal.ratelimit;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/** One PostgreSQL-sourced decision instant for a future complete rate-limited operation. */
final class RateLimitOperationTime {

    private static final Duration MAXIMUM_AGE = Duration.ofHours(1);
    private static final String INVALID = "Invalid rate-limit operation time";

    private final Instant instant;

    RateLimitOperationTime(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException(INVALID);
        }
        this.instant = instant;
    }

    Instant instant() {
        return instant;
    }

    void requireValidStageInstant(Instant stageInstant) {
        if (stageInstant == null || stageInstant.isBefore(instant)) {
            throw invalidStageTime();
        }
        try {
            if (Duration.between(instant, stageInstant).compareTo(MAXIMUM_AGE) > 0) {
                throw invalidStageTime();
            }
        } catch (DateTimeException | ArithmeticException failure) {
            throw invalidStageTime();
        }
    }

    static IllegalStateException invalidStageTime() {
        return new IllegalStateException(INVALID);
    }

    @Override
    public String toString() {
        return "RateLimitOperationTime[REDACTED]";
    }
}
