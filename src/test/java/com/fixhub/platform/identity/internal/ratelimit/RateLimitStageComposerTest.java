package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RateLimitStageComposerTest {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Duration OVERLAP = Duration.ofHours(25);
    private static final Duration RETENTION = Duration.ofHours(24).plusNanos(1);
    private static final String EMAIL = "synthetic@example.test";

    @Test
    void composesExactlyTheFiveApprovedStagesWithOneVersion() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer = composer(snapshot);
            CanonicalOrigin origin = origin();
            assertStage(
                    composer.registrationCoarse(origin, START),
                    RateLimitStagePersistence.Stage.REGISTRATION_COARSE,
                    RateLimitPolicy.REGISTRATION_ORIGIN,
                    1);
            assertStage(
                    composer.registrationEmail(EMAIL, START),
                    RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                    RateLimitPolicy.REGISTRATION_EMAIL,
                    1);
            assertStage(
                    composer.resendCoarse(origin, START),
                    RateLimitStagePersistence.Stage.RESEND_COARSE,
                    RateLimitPolicy.RESEND_ORIGIN,
                    1);
            assertStage(
                    composer.resendEmail(EMAIL, START),
                    RateLimitStagePersistence.Stage.RESEND_EMAIL,
                    RateLimitPolicy.RESEND_EMAIL,
                    1);
            assertStage(
                    composer.verificationCoarse(origin, START),
                    RateLimitStagePersistence.Stage.VERIFICATION_COARSE,
                    RateLimitPolicy.VERIFICATION_ORIGIN,
                    1);
        }
    }

    @Test
    void rotationFansOutEveryPolicyAtExactStartAndEndBoundaries() {
        try (RateLimitHmacKeySnapshot snapshot = rotating()) {
            RateLimitStageComposer composer = composer(snapshot);
            CanonicalOrigin origin = origin();
            assertVersions(composer.registrationCoarse(origin, START.minusNanos(1)), 2, 2);
            assertVersions(composer.registrationCoarse(origin, START), 1, 2, 1, 2);
            assertVersions(composer.resendCoarse(origin, START.plusSeconds(1)), 1, 2, 1, 2);
            assertVersions(
                    composer.verificationCoarse(origin, START.plus(OVERLAP).minusNanos(1)),
                    1,
                    2,
                    1,
                    2);
            assertVersions(composer.registrationCoarse(origin, START.plus(OVERLAP)), 1, 1);
            assertVersions(
                    composer.registrationCoarse(origin, START.plus(OVERLAP).plusNanos(1)), 1, 1);
            assertVersions(composer.resendEmail(EMAIL, START), 1, 2);
            assertVersions(composer.registrationEmail(EMAIL, START), 1, 2);
            assertVersions(composer.verificationCoarse(origin, START.plus(OVERLAP)), 1, 1);
        }
    }

    @Test
    void propagatesExactSettingsAndPreservesTypedHmacDigests() {
        try (RateLimitHmacKeySnapshot snapshot = rotating()) {
            RateLimitPolicySettings settings = settings();
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            RateLimitStageComposer composer = new RateLimitStageComposer(protector, settings);
            CanonicalOrigin origin = origin();
            RateLimitStageComposer.StageDescription coarse =
                    composer.registrationCoarse(origin, START);
            RateLimitStagePersistence.StageWork work =
                    RateLimitStagePersistence.StageWork.create(coarse.stage(), coarse.inputs());
            byte[] originBytes = origin.bytes();
            try {
                List<RateLimitProtectedKey> expectedOrigin =
                        protector.protectOrigin(
                                RateLimitPolicy.REGISTRATION_ORIGIN, originBytes, START);
                List<RateLimitProtectedKey> expectedGlobal =
                        protector.protectGlobal(RateLimitPolicy.FH011_GLOBAL, START);
                for (int index = 0; index < work.buckets().size(); index++) {
                    RateLimitStagePersistence.BucketWork bucket = work.buckets().get(index);
                    RateLimitProtectedKey expected =
                            index < 2 ? expectedOrigin.get(index) : expectedGlobal.get(index - 2);
                    byte[] digest = expected.digest();
                    try {
                        assertThat(MessageDigest.isEqual(bucket.keyDigest, digest)).isTrue();
                    } finally {
                        Arrays.fill(digest, (byte) 0);
                    }
                    assertThat(bucket.keyVersion).isEqualTo(expected.version());
                    assertSettings(bucket, settings);
                }
            } finally {
                Arrays.fill(originBytes, (byte) 0);
                work.clear();
            }

            RateLimitStageComposer.StageDescription resend = composer.resendEmail(EMAIL, START);
            RateLimitStagePersistence.StageWork resendWork =
                    RateLimitStagePersistence.StageWork.create(resend.stage(), resend.inputs());
            try {
                for (RateLimitStagePersistence.BucketWork bucket : resendWork.buckets()) {
                    assertSettings(bucket, settings);
                    assertThat(bucket.cooldown).isEqualTo(Duration.ofNanos(1));
                }
            } finally {
                resendWork.clear();
            }
        }
    }

    @Test
    void emailStageUsesOnlyItsTypedProtectedEmailAndKeepsTheVersion() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            RateLimitStageComposer.StageDescription description =
                    new RateLimitStageComposer(protector, settings())
                            .registrationEmail(EMAIL, START);
            RateLimitStagePersistence.StageWork work =
                    RateLimitStagePersistence.StageWork.create(
                            description.stage(), description.inputs());
            byte[] expected =
                    protector
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, START)
                            .getFirst()
                            .digest();
            try {
                assertThat(work.buckets()).hasSize(1);
                assertThat(work.buckets().getFirst().policy)
                        .isEqualTo(RateLimitPolicy.REGISTRATION_EMAIL);
                assertThat(work.buckets().getFirst().keyVersion).isEqualTo(1);
                assertThat(MessageDigest.isEqual(work.buckets().getFirst().keyDigest, expected))
                        .isTrue();
            } finally {
                Arrays.fill(expected, (byte) 0);
                work.clear();
            }
        }
    }

    @Test
    void preservesExplicitZeroResendCooldownAndFractionalRetention() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer =
                    new RateLimitStageComposer(
                            new RateLimitIdentifierProtector(snapshot), settings(Duration.ZERO));
            RateLimitStageComposer.StageDescription description =
                    composer.resendEmail(EMAIL, START);
            RateLimitStagePersistence.StageWork work =
                    RateLimitStagePersistence.StageWork.create(
                            description.stage(), description.inputs());
            try {
                assertThat(work.buckets()).hasSize(1);
                assertThat(work.buckets().getFirst().cooldown).isEqualTo(Duration.ZERO);
                assertThat(work.buckets().getFirst().retention).isEqualTo(RETENTION);
            } finally {
                work.clear();
            }
        }
    }

    @Test
    void stageDescriptionRejectsInvalidWorkWithOneSanitizedFailure() throws Exception {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer = composer(snapshot);
            RateLimitStageComposer.StageDescription coarse =
                    composer.registrationCoarse(origin(), START);
            RateLimitStageComposer.StageDescription email =
                    composer.registrationEmail(EMAIL, START);
            rejectDescription(null, email.inputs());
            rejectDescription(email.stage(), null);
            rejectDescription(email.stage(), List.of());
            rejectDescription(
                    email.stage(), Arrays.asList((RateLimitStagePersistence.BucketInput) null));
            rejectDescription(coarse.stage(), coarse.inputs().subList(0, 1));
            rejectDescription(coarse.stage(), email.inputs());
            RateLimitStagePersistence.BucketInput duplicate = email.inputs().getFirst();
            rejectDescription(email.stage(), List.of(duplicate, duplicate));
            assertThat(reflectDescription(coarse.stage(), coarse.inputs()).inputs()).hasSize(2);
        }
    }

    @Test
    void rejectsInconsistentActiveVersionsBeforeReturningCoarseWork() {
        RateLimitIdentifierProtector protector = mock(RateLimitIdentifierProtector.class);
        when(protector.protectOrigin(
                        eq(RateLimitPolicy.REGISTRATION_ORIGIN), any(byte[].class), eq(START)))
                .thenReturn(List.of(protectedKey(1), protectedKey(2)));
        when(protector.protectGlobal(eq(RateLimitPolicy.FH011_GLOBAL), eq(START)))
                .thenReturn(List.of(protectedKey(2), protectedKey(1)));
        RateLimitStageComposer composer = new RateLimitStageComposer(protector, settings());
        assertThatThrownBy(() -> composer.registrationCoarse(origin(), START))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Inconsistent rate-limit key versions");
        when(protector.protectGlobal(eq(RateLimitPolicy.FH011_GLOBAL), eq(START)))
                .thenReturn(List.of(protectedKey(1)));
        assertThatThrownBy(() -> composer.registrationCoarse(origin(), START))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Inconsistent rate-limit key versions");
    }

    @Test
    void failedSecondDerivationCannotReturnPartialStage() {
        RateLimitIdentifierProtector protector = mock(RateLimitIdentifierProtector.class);
        when(protector.protectOrigin(
                        eq(RateLimitPolicy.REGISTRATION_ORIGIN), any(byte[].class), eq(START)))
                .thenReturn(List.of(protectedKey(1)));
        when(protector.protectGlobal(eq(RateLimitPolicy.FH011_GLOBAL), eq(START)))
                .thenThrow(new IllegalStateException("Rate-limit HMAC operation is unavailable"));
        RateLimitStageComposer composer = new RateLimitStageComposer(protector, settings());
        assertThatThrownBy(() -> composer.registrationCoarse(origin(), START))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate-limit HMAC operation is unavailable");
    }

    @Test
    void rejectsMissingInputsInvalidEmailAndClosedSnapshotWithSanitizedMessages() {
        assertThatThrownBy(() -> new RateLimitStageComposer(null, settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit stage composition input");
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer = composer(snapshot);
            assertThatThrownBy(() -> composer.registrationCoarse(null, START))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate-limit stage composition input");
            assertThatThrownBy(() -> composer.registrationCoarse(origin(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate-limit stage composition input");
            assertThatThrownBy(() -> composer.resendEmail(null, START))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate-limit stage composition input");
            assertThatThrownBy(() -> composer.registrationEmail(" Synthetic@Example.Test ", START))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate-limit HMAC input");
            snapshot.close();
            assertThatThrownBy(() -> composer.registrationCoarse(origin(), START))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Rate-limit HMAC key snapshot is closed");
        }
    }

    @Test
    void returnedStageIsImmutableRedactedAndIndependentOfCallerOrigin() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer = composer(snapshot);
            byte[] supplied = {0x04, 10, 20, 30, 40};
            CanonicalOrigin origin = new CanonicalOrigin(supplied);
            RateLimitStageComposer.StageDescription description =
                    composer.registrationCoarse(origin, START);
            assertThat(description.toString()).isEqualTo("StageDescription[REDACTED]");
            assertThat(composer.toString()).isEqualTo("RateLimitStageComposer[REDACTED]");
            assertThatThrownBy(() -> description.inputs().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(supplied[0]).isEqualTo((byte) 0x04);
            assertThat(supplied[4]).isEqualTo((byte) 40);
            assertThat(Modifier.isPublic(RateLimitStageComposer.class.getModifiers())).isFalse();
            assertThat(Modifier.isFinal(RateLimitStageComposer.class.getModifiers())).isTrue();
            assertThat(RateLimitStageComposer.class.isRecord()).isFalse();
        }
    }

    @Test
    void immutableComposerSupportsConcurrentIndependentCalls() throws Exception {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitStageComposer composer = composer(snapshot);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Future<Integer>> futures = new ArrayList<>();
            try {
                for (int index = 0; index < 12; index++) {
                    futures.add(
                            executor.submit(
                                    () ->
                                            composer.registrationCoarse(origin(), START)
                                                    .inputs()
                                                    .size()));
                }
                for (Future<Integer> future : futures) {
                    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(2);
                }
            } finally {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void assertStage(
            RateLimitStageComposer.StageDescription description,
            RateLimitStagePersistence.Stage expectedStage,
            RateLimitPolicy expectedPolicy,
            int expectedVersion) {
        assertThat(description.stage()).isEqualTo(expectedStage);
        RateLimitStagePersistence.StageWork work =
                RateLimitStagePersistence.StageWork.create(
                        description.stage(), description.inputs());
        try {
            assertThat(work.buckets()).hasSize(expectedStage.name().endsWith("COARSE") ? 2 : 1);
            assertThat(work.buckets().getFirst().policy).isEqualTo(expectedPolicy);
            for (RateLimitStagePersistence.BucketWork bucket : work.buckets()) {
                assertThat(bucket.keyVersion).isEqualTo(expectedVersion);
                assertSettings(bucket, settings());
            }
            if (work.buckets().size() == 2) {
                assertThat(work.buckets().get(1).policy).isEqualTo(RateLimitPolicy.FH011_GLOBAL);
            }
        } finally {
            work.clear();
        }
    }

    private static void assertVersions(
            RateLimitStageComposer.StageDescription description, int... expectedVersions) {
        RateLimitStagePersistence.StageWork work =
                RateLimitStagePersistence.StageWork.create(
                        description.stage(), description.inputs());
        try {
            assertThat(work.buckets()).hasSize(expectedVersions.length);
            for (int index = 0; index < expectedVersions.length; index++) {
                assertThat(work.buckets().get(index).keyVersion).isEqualTo(expectedVersions[index]);
            }
        } finally {
            work.clear();
        }
    }

    private static void assertSettings(
            RateLimitStagePersistence.BucketWork bucket, RateLimitPolicySettings settings) {
        RateLimitPolicySettings.Setting setting = settings.forPolicy(bucket.policy);
        assertThat(bucket.limit).isEqualTo(setting.limit());
        assertThat(bucket.window).isEqualTo(setting.window());
        assertThat(bucket.cooldown).isEqualTo(setting.cooldown());
        assertThat(bucket.retention).isEqualTo(RETENTION);
    }

    private static RateLimitStageComposer composer(RateLimitHmacKeySnapshot snapshot) {
        return new RateLimitStageComposer(new RateLimitIdentifierProtector(snapshot), settings());
    }

    private static RateLimitPolicySettings settings() {
        return settings(Duration.ofNanos(1));
    }

    private static RateLimitPolicySettings settings(Duration resendCooldown) {
        EnumMap<RateLimitPolicy, RateLimitPolicySettings.Setting> supplied =
                new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            supplied.put(
                    policy,
                    new RateLimitPolicySettings.Setting(
                            policy == RateLimitPolicy.FH011_GLOBAL ? 300 : 5,
                            Duration.ofHours(1),
                            policy == RateLimitPolicy.RESEND_EMAIL ? resendCooldown : null));
        }
        return new RateLimitPolicySettings(supplied, RETENTION);
    }

    private static CanonicalOrigin origin() {
        return new CanonicalOrigin(new byte[] {0x04, 10, 20, 30, 40});
    }

    private static RateLimitHmacKeySnapshot steady() {
        return RateLimitHmacKeySnapshot.create(
                reference -> syntheticKey(0x11), 1, "synthetic-current", null, null, null, null);
    }

    private static RateLimitHmacKeySnapshot rotating() {
        return RateLimitHmacKeySnapshot.create(
                reference -> syntheticKey(reference.equals("synthetic-current") ? 0x11 : 0x22),
                1,
                "synthetic-current",
                2,
                "synthetic-previous",
                START,
                OVERLAP);
    }

    private static byte[] syntheticKey(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    private static RateLimitProtectedKey protectedKey(int version) {
        return new RateLimitProtectedKey(version, new byte[32]);
    }

    private static void rejectDescription(
            RateLimitStagePersistence.Stage stage,
            List<RateLimitStagePersistence.BucketInput> inputs) {
        assertThatThrownBy(() -> reflectDescription(stage, inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit stage description");
    }

    private static RateLimitStageComposer.StageDescription reflectDescription(
            RateLimitStagePersistence.Stage stage,
            List<RateLimitStagePersistence.BucketInput> inputs)
            throws ReflectiveOperationException {
        Constructor<RateLimitStageComposer.StageDescription> constructor =
                RateLimitStageComposer.StageDescription.class.getDeclaredConstructor(
                        RateLimitStagePersistence.Stage.class, List.class);
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(stage, inputs);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("Unexpected stage-description failure");
        }
    }
}
