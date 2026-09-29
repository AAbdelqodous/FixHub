package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitStagePersistenceTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration RETENTION = Duration.ofDays(2);
    private static final RateLimitOperationTime OPERATION_TIME =
            new RateLimitOperationTime(Instant.EPOCH);

    @Test
    void exposesExactlyTheApprovedPolicyNames() {
        assertThat(Arrays.stream(RateLimitPolicy.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "REGISTRATION_EMAIL",
                        "REGISTRATION_ORIGIN",
                        "RESEND_EMAIL",
                        "RESEND_ORIGIN",
                        "VERIFICATION_ORIGIN",
                        "FH011_GLOBAL");
    }

    @Test
    void copiesProtectedKeysWithoutClearingCallerOwnedArrays() {
        byte[] caller = digest(1);
        RateLimitStagePersistence.BucketInput input =
                input(RateLimitPolicy.REGISTRATION_EMAIL, 1, caller);
        caller[0] = 7;
        RateLimitStagePersistence.StageWork work =
                RateLimitStagePersistence.StageWork.create(
                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL, List.of(input));
        assertThat(work.buckets().getFirst().keyDigest[0]).isEqualTo((byte) 0x5a);
        work.clear();
        assertThat(work.buckets().getFirst().keyDigest).containsOnly((byte) 0);
        assertThat(caller[0]).isEqualTo((byte) 7);
        assertThat(input.toString()).doesNotContain(Arrays.toString(caller));
        assertThat(work.toString()).doesNotContain(Arrays.toString(caller));
    }

    @Test
    void validatesDigestVersionWindowLimitCooldownAndRetentionBeforeWriter() {
        assertThatThrownBy(() -> input(RateLimitPolicy.REGISTRATION_EMAIL, 0, digest(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(RateLimitPolicy.REGISTRATION_EMAIL, 1, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RateLimitStagePersistence.BucketInput(
                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                        1,
                                        digest(1),
                                        Duration.ofMillis(500),
                                        5,
                                        null,
                                        RETENTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RateLimitStagePersistence.BucketInput(
                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                        1,
                                        digest(1),
                                        HOUR,
                                        0,
                                        null,
                                        RETENTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RateLimitStagePersistence.BucketInput(
                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                        1,
                                        digest(1),
                                        HOUR,
                                        5,
                                        Duration.ofMinutes(5),
                                        RETENTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RateLimitStagePersistence.BucketInput(
                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                        1,
                                        digest(1),
                                        HOUR,
                                        5,
                                        null,
                                        Duration.ofHours(23)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsResendCooldownFromZeroThroughWindowAndRejectsOutOfRangeValues() {
        assertThat(resend(Duration.ZERO)).isNotNull();
        assertThat(resend(Duration.ofMinutes(5))).isNotNull();
        assertThat(resend(Duration.ofDays(1))).isNotNull();
        assertThatThrownBy(() -> resend(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resend(Duration.ofDays(1).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncompleteDuplicateContradictoryAndOversizeStagesBeforeWriter() {
        RateLimitStagePersistence.BucketInput global =
                input(RateLimitPolicy.FH011_GLOBAL, 1, digest(1));
        RateLimitStagePersistence.BucketInput origin =
                input(RateLimitPolicy.REGISTRATION_ORIGIN, 1, digest(2));
        RateLimitStagePersistence facade =
                new RateLimitStagePersistence(
                        (work, operationTime) -> {
                            throw new AssertionError("Writer reached");
                        });
        RateLimitStagePersistence.Stage stage = RateLimitStagePersistence.Stage.REGISTRATION_COARSE;
        assertThatThrownBy(() -> facade.evaluate(stage, List.of(global), OPERATION_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facade.evaluate(stage, List.of(global, global), OPERATION_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                facade.evaluate(
                                        stage,
                                        List.of(
                                                global,
                                                origin,
                                                input(RateLimitPolicy.FH011_GLOBAL, 2, digest(3)),
                                                new RateLimitStagePersistence.BucketInput(
                                                        RateLimitPolicy.REGISTRATION_ORIGIN,
                                                        2,
                                                        digest(4),
                                                        HOUR,
                                                        6,
                                                        null,
                                                        RETENTION)),
                                        OPERATION_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                facade.evaluate(
                                        stage,
                                        List.of(global, origin, global, origin, global),
                                        OPERATION_TIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesOnlySafeRejectionOutsideWriterAndClearsWorkingDigest() {
        AtomicInteger calls = new AtomicInteger();
        RateLimitStagePersistence facade =
                new RateLimitStagePersistence(
                        (work, operationTime) -> {
                            calls.incrementAndGet();
                            throw new StageRejected(9);
                        });
        RateLimitStagePersistence.StageResult result =
                facade.evaluate(
                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                        List.of(input(RateLimitPolicy.REGISTRATION_EMAIL, 1, digest(1))),
                        OPERATION_TIME);
        assertThat(calls).hasValue(1);
        assertThat(result.isAdmitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(9);
        assertThat(result.toString()).isEqualTo("StageResult[REJECTED]");
        assertThatThrownBy(
                        () ->
                                new RateLimitStagePersistence(
                                                (work, operationTime) -> {
                                                    throw new IllegalStateException(
                                                            "synthetic failure");
                                                })
                                        .evaluate(
                                                RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                                List.of(
                                                        input(
                                                                RateLimitPolicy.REGISTRATION_EMAIL,
                                                                1,
                                                                digest(2))),
                                                OPERATION_TIME))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingOperationTimeBeforeWriter() {
        RateLimitStagePersistence facade =
                new RateLimitStagePersistence(
                        (work, operationTime) -> {
                            throw new AssertionError("Writer reached");
                        });
        assertThatThrownBy(
                        () ->
                                facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        digest(1))),
                                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid rate-limit operation time")
                .hasNoCause();
    }

    private static RateLimitStagePersistence.BucketInput input(
            RateLimitPolicy policy, int version, byte[] digest) {
        return new RateLimitStagePersistence.BucketInput(
                policy, version, digest, HOUR, 5, null, RETENTION);
    }

    private static RateLimitStagePersistence.BucketInput resend(Duration cooldown) {
        return new RateLimitStagePersistence.BucketInput(
                RateLimitPolicy.RESEND_EMAIL,
                1,
                digest(9),
                Duration.ofDays(1),
                5,
                cooldown,
                RETENTION);
    }

    private static byte[] digest(int discriminator) {
        byte[] digest = new byte[32];
        digest[0] = 0x5a;
        digest[31] = (byte) discriminator;
        return digest;
    }
}
