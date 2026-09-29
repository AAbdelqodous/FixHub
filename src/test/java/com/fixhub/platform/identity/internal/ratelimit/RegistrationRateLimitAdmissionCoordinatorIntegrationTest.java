package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fixhub.platform.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RegistrationRateLimitAdmissionCoordinatorIntegrationTest {

    private static final String EMAIL = "synthetic-coordinator@example.test";
    private static final int SUCCESS_VERSION = 900101;
    private static final int EXPIRED_VERSION = 900102;
    private static final int REJECTED_VERSION = 900103;
    private static final int VALIDATION_FAILURE_VERSION = 900104;

    @Autowired private PostgresRateLimitOperationTimeSource operationTimeSource;
    @Autowired private RateLimitStagePersistence persistence;
    @Autowired private ApplicationContext applicationContext;
    @MockitoSpyBean private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        try {
            jdbc.update(
                    "DELETE FROM identity_rate_limit_buckets WHERE key_version IN (?, ?, ?, ?)",
                    SUCCESS_VERSION,
                    EXPIRED_VERSION,
                    REJECTED_VERSION,
                    VALIDATION_FAILURE_VERSION);
        } finally {
            reset(jdbc);
        }
    }

    @Test
    void acquiresOnceBeforeCoarseAndReusesTimeAcrossBothCommittedStages() {
        assertThat(
                        applicationContext.getBeansOfType(
                                RegistrationRateLimitAdmissionCoordinator.class))
                .isEmpty();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Instant> decision = new AtomicReference<>();
        List<Integer> completions = new ArrayList<>();
        observeTimestamps(reads, decision, completions, false);

        try (RateLimitHmacKeySnapshot snapshot = snapshot(SUCCESS_VERSION)) {
            PostgresRateLimitOperationTimeSource source = spy(operationTimeSource);
            RateLimitStageComposer composer = spy(composer(snapshot));
            RateLimitStagePersistence stages = spy(persistence);
            RegistrationRateLimitAdmissionCoordinator coordinator =
                    new RegistrationRateLimitAdmissionCoordinator(source, composer, stages);
            CanonicalOrigin origin = origin();

            assertThat(
                            coordinator
                                    .admit(
                                            origin,
                                            () -> {
                                                assertThat(reads).hasValue(2);
                                                assertThat(completions)
                                                        .containsExactly(
                                                                TransactionSynchronization
                                                                        .STATUS_COMMITTED,
                                                                TransactionSynchronization
                                                                        .STATUS_COMMITTED);
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isFalse();
                                                assertThat(
                                                                count(
                                                                        "REGISTRATION_ORIGIN",
                                                                        SUCCESS_VERSION))
                                                        .isEqualTo(1);
                                                assertThat(count("FH011_GLOBAL", SUCCESS_VERSION))
                                                        .isEqualTo(1);
                                                assertThat(
                                                                count(
                                                                        "REGISTRATION_EMAIL",
                                                                        SUCCESS_VERSION))
                                                        .isZero();
                                                return EMAIL;
                                            })
                                    .isAdmitted())
                    .isTrue();

            assertThat(reads).hasValue(3);
            assertThat(completions)
                    .containsExactly(
                            TransactionSynchronization.STATUS_COMMITTED,
                            TransactionSynchronization.STATUS_COMMITTED,
                            TransactionSynchronization.STATUS_COMMITTED);
            assertThat(count("REGISTRATION_EMAIL", SUCCESS_VERSION)).isEqualTo(1);
            verify(source, times(1)).acquire();
            verify(composer).registrationCoarse(origin, decision.get());
            verify(composer).registrationEmail(EMAIL, decision.get());
            ArgumentCaptor<RateLimitOperationTime> times =
                    ArgumentCaptor.forClass(RateLimitOperationTime.class);
            verify(stages, times(2)).evaluate(any(), any(), times.capture());
            assertThat(times.getAllValues().get(0)).isSameAs(times.getAllValues().get(1));
            assertThat(times.getValue().instant()).isEqualTo(decision.get());
            InOrder order = inOrder(source, composer, stages);
            order.verify(source).acquire();
            order.verify(composer).registrationCoarse(origin, decision.get());
            order.verify(stages)
                    .evaluate(
                            eq(RateLimitStagePersistence.Stage.REGISTRATION_COARSE),
                            any(),
                            eq(times.getValue()));
            order.verify(composer).registrationEmail(EMAIL, decision.get());
            order.verify(stages)
                    .evaluate(
                            eq(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL),
                            any(),
                            eq(times.getValue()));
        }
    }

    @Test
    void expiredEmailStageRollsBackWithoutRefundingCommittedCoarseBuckets() {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Instant> decision = new AtomicReference<>();
        List<Integer> completions = new ArrayList<>();
        observeTimestamps(reads, decision, completions, true);

        try (RateLimitHmacKeySnapshot snapshot = snapshot(EXPIRED_VERSION)) {
            RegistrationRateLimitAdmissionCoordinator coordinator =
                    new RegistrationRateLimitAdmissionCoordinator(
                            operationTimeSource, composer(snapshot), persistence);
            assertThatThrownBy(() -> coordinator.admit(origin(), () -> EMAIL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid rate-limit operation time")
                    .hasNoCause();
            assertThat(reads).hasValue(3);
            assertThat(completions)
                    .containsExactly(
                            TransactionSynchronization.STATUS_COMMITTED,
                            TransactionSynchronization.STATUS_COMMITTED,
                            TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(count("REGISTRATION_ORIGIN", EXPIRED_VERSION)).isEqualTo(1);
            assertThat(count("FH011_GLOBAL", EXPIRED_VERSION)).isEqualTo(1);
            assertThat(count("REGISTRATION_EMAIL", EXPIRED_VERSION)).isZero();
        }
    }

    @Test
    void rejectedCoarseStageNeverInvokesValidationOrEmailStage() {
        try (RateLimitHmacKeySnapshot snapshot = snapshot(REJECTED_VERSION)) {
            RateLimitStageComposer fixtureComposer = composer(snapshot);
            CanonicalOrigin origin = origin();
            RateLimitOperationTime fixtureTime = operationTimeSource.acquire();
            RateLimitStageComposer.StageDescription coarse =
                    fixtureComposer.registrationCoarse(origin, fixtureTime.instant());
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(
                                persistence
                                        .evaluate(coarse.stage(), coarse.inputs(), fixtureTime)
                                        .isAdmitted())
                        .isTrue();
            }
            assertThat(requestCount("REGISTRATION_ORIGIN", REJECTED_VERSION)).isEqualTo(5);
            assertThat(requestCount("FH011_GLOBAL", REJECTED_VERSION)).isEqualTo(5);

            RateLimitStageComposer composer = spy(fixtureComposer);
            RateLimitStagePersistence stages = spy(persistence);
            RegistrationRateLimitAdmissionCoordinator coordinator =
                    new RegistrationRateLimitAdmissionCoordinator(
                            operationTimeSource, composer, stages);
            AtomicInteger supplierCalls = new AtomicInteger();
            RateLimitStagePersistence.StageResult result =
                    coordinator.admit(
                            origin,
                            () -> {
                                supplierCalls.incrementAndGet();
                                return EMAIL;
                            });

            assertThat(result.isAdmitted()).isFalse();
            assertThat(result.retryAfterSeconds()).isPositive();
            assertThat(supplierCalls).hasValue(0);
            verify(composer, never()).registrationEmail(any(), any());
            verify(stages, never())
                    .evaluate(eq(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL), any(), any());
            assertThat(requestCount("REGISTRATION_ORIGIN", REJECTED_VERSION)).isEqualTo(5);
            assertThat(requestCount("FH011_GLOBAL", REJECTED_VERSION)).isEqualTo(5);
            assertThat(count("REGISTRATION_EMAIL", REJECTED_VERSION)).isZero();
        }
    }

    @Test
    void validationFailurePropagatesAfterCoarseCommitWithoutEmailStage() {
        try (RateLimitHmacKeySnapshot snapshot = snapshot(VALIDATION_FAILURE_VERSION)) {
            RateLimitStageComposer composer = spy(composer(snapshot));
            RateLimitStagePersistence stages = spy(persistence);
            RegistrationRateLimitAdmissionCoordinator coordinator =
                    new RegistrationRateLimitAdmissionCoordinator(
                            operationTimeSource, composer, stages);
            IllegalStateException failure =
                    new IllegalStateException("synthetic validation failure");
            AtomicInteger supplierCalls = new AtomicInteger();

            assertThatThrownBy(
                            () ->
                                    coordinator.admit(
                                            origin(),
                                            () -> {
                                                supplierCalls.incrementAndGet();
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isFalse();
                                                assertThat(
                                                                requestCount(
                                                                        "REGISTRATION_ORIGIN",
                                                                        VALIDATION_FAILURE_VERSION))
                                                        .isEqualTo(1);
                                                assertThat(
                                                                requestCount(
                                                                        "FH011_GLOBAL",
                                                                        VALIDATION_FAILURE_VERSION))
                                                        .isEqualTo(1);
                                                throw failure;
                                            }))
                    .isSameAs(failure);

            assertThat(supplierCalls).hasValue(1);
            verify(composer, never()).registrationEmail(any(), any());
            verify(stages, never())
                    .evaluate(eq(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL), any(), any());
            assertThat(requestCount("REGISTRATION_ORIGIN", VALIDATION_FAILURE_VERSION))
                    .isEqualTo(1);
            assertThat(requestCount("FH011_GLOBAL", VALIDATION_FAILURE_VERSION)).isEqualTo(1);
            assertThat(count("REGISTRATION_EMAIL", VALIDATION_FAILURE_VERSION)).isZero();
            verify(jdbc, times(2))
                    .queryForObject(
                            eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                            any(RowMapper.class));
        }
    }

    private void observeTimestamps(
            AtomicInteger reads,
            AtomicReference<Instant> decision,
            List<Integer> completions,
            boolean expireEmail) {
        doAnswer(
                        invocation -> {
                            int read = reads.incrementAndGet();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isEqualTo(read == 1);
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            completions.add(status);
                                        }
                                    });
                            if (read == 3 && expireEmail) {
                                return decision.get().plusSeconds(3600).plusNanos(1);
                            }
                            Instant sampled = (Instant) invocation.callRealMethod();
                            if (read == 1) {
                                decision.set(sampled);
                            }
                            return sampled;
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));
    }

    private int count(String policy, int version) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_rate_limit_buckets WHERE policy = ? AND key_version = ?",
                Integer.class,
                policy,
                version);
    }

    private long requestCount(String policy, int version) {
        return jdbc.queryForObject(
                "SELECT request_count FROM identity_rate_limit_buckets WHERE policy = ? AND key_version = ?",
                Long.class,
                policy,
                version);
    }

    private static RateLimitStageComposer composer(RateLimitHmacKeySnapshot snapshot) {
        EnumMap<RateLimitPolicy, RateLimitPolicySettings.Setting> values =
                new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            values.put(
                    policy,
                    new RateLimitPolicySettings.Setting(
                            5,
                            Duration.ofHours(1),
                            policy == RateLimitPolicy.RESEND_EMAIL ? Duration.ZERO : null));
        }
        return new RateLimitStageComposer(
                new RateLimitIdentifierProtector(snapshot),
                new RateLimitPolicySettings(values, Duration.ofDays(2)));
    }

    private static RateLimitHmacKeySnapshot snapshot(int version) {
        return RateLimitHmacKeySnapshot.create(
                reference -> {
                    byte[] key = new byte[32];
                    Arrays.fill(key, (byte) 0x51);
                    return key;
                },
                version,
                "synthetic-coordinator-key",
                null,
                null,
                null,
                null);
    }

    private static CanonicalOrigin origin() {
        return CanonicalOrigin.fromAddress(new byte[] {10, 20, 30, 40});
    }
}
