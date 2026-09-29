package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitOperationTimeTest {

    private static final String INVALID = "Invalid rate-limit operation time";

    @Test
    void acceptsBothInclusiveBoundaries() {
        Instant decision = Instant.parse("2026-09-29T00:00:00.123456Z");
        RateLimitOperationTime time = new RateLimitOperationTime(decision);

        time.requireValidStageInstant(decision);
        time.requireValidStageInstant(decision.plusSeconds(3600));
        assertThat(time.instant()).isSameAs(decision);
    }

    @Test
    void rejectsNegativeExcessiveAndNullStageTimesWithFixedError() {
        Instant decision = Instant.parse("2026-09-29T00:00:00Z");
        RateLimitOperationTime time = new RateLimitOperationTime(decision);

        assertInvalid(() -> time.requireValidStageInstant(decision.minusNanos(1)));
        assertInvalid(() -> time.requireValidStageInstant(decision.plusSeconds(3600).plusNanos(1)));
        assertInvalid(() -> time.requireValidStageInstant(null));
        assertThatThrownBy(() -> new RateLimitOperationTime(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID)
                .hasNoCause();
    }

    @Test
    void validatesExtremeInstantsWithoutConstructingAnUnrepresentableEnd() {
        RateLimitOperationTime earliest = new RateLimitOperationTime(Instant.MIN);
        earliest.requireValidStageInstant(Instant.MIN.plusSeconds(3600));
        assertInvalid(() -> earliest.requireValidStageInstant(Instant.MAX));

        RateLimitOperationTime latest = new RateLimitOperationTime(Instant.MAX);
        latest.requireValidStageInstant(Instant.MAX);
        assertInvalid(() -> latest.requireValidStageInstant(Instant.MAX.minusNanos(1)));
    }

    @Test
    void renderingAndSurfaceDoNotRevealTimestampOrMutableState() {
        RateLimitOperationTime time =
                new RateLimitOperationTime(Instant.parse("2026-09-29T01:02:03Z"));
        assertThat(time.toString()).isEqualTo("RateLimitOperationTime[REDACTED]");
        assertThat(RateLimitOperationTime.class.isRecord()).isFalse();
        assertThat(java.lang.reflect.Modifier.isFinal(RateLimitOperationTime.class.getModifiers()))
                .isTrue();
        assertThat(RateLimitOperationTime.class.getDeclaredFields())
                .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .hasSize(1);
    }

    private static void assertInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INVALID)
                .hasNoCause();
    }
}
