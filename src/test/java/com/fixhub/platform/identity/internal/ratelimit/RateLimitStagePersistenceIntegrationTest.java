package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fixhub.platform.TestcontainersConfiguration;
import java.lang.reflect.Constructor;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RateLimitStagePersistenceIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration RETENTION = Duration.ofDays(2);
    private static final AtomicInteger NEXT_DIGEST = new AtomicInteger();

    @Autowired private RateLimitStagePersistence facade;
    @Autowired private RateLimitStageWriter writer;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private JdbcTemplate jdbc;
    private final List<byte[]> ownedDigests = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        try {
            for (byte[] digest : ownedDigests) {
                jdbc.update("DELETE FROM identity_rate_limit_buckets WHERE key_digest = ?", digest);
            }
        } finally {
            reset(jdbc);
        }
    }

    @Test
    void flywayCreatedExactV5ColumnsConstraintsAndIndex() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns "
                                        + "WHERE table_name = 'identity_rate_limit_buckets' ORDER BY ordinal_position",
                                String.class))
                .containsExactly(
                        "id",
                        "policy",
                        "key_version",
                        "key_digest",
                        "window_start",
                        "window_end",
                        "request_count",
                        "cooldown_until",
                        "retention_expires_at",
                        "version",
                        "created_at",
                        "updated_at");
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name || ':' || data_type || ':' || is_nullable || ':' "
                                        + "|| is_identity || ':' || COALESCE(column_default, '<none>') "
                                        + "FROM information_schema.columns WHERE table_name = "
                                        + "'identity_rate_limit_buckets' ORDER BY ordinal_position",
                                String.class))
                .containsExactly(
                        "id:bigint:NO:YES:<none>",
                        "policy:character varying:NO:NO:<none>",
                        "key_version:integer:NO:NO:<none>",
                        "key_digest:bytea:NO:NO:<none>",
                        "window_start:timestamp with time zone:NO:NO:<none>",
                        "window_end:timestamp with time zone:NO:NO:<none>",
                        "request_count:bigint:NO:NO:<none>",
                        "cooldown_until:timestamp with time zone:YES:NO:<none>",
                        "retention_expires_at:timestamp with time zone:NO:NO:<none>",
                        "version:bigint:NO:NO:<none>",
                        "created_at:timestamp with time zone:NO:NO:<none>",
                        "updated_at:timestamp with time zone:NO:NO:<none>");
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns "
                                        + "WHERE table_name = 'identity_rate_limit_buckets' "
                                        + "AND data_type = 'timestamp with time zone' "
                                        + "AND datetime_precision = 6",
                                String.class))
                .containsExactlyInAnyOrder(
                        "window_start",
                        "window_end",
                        "cooldown_until",
                        "retention_expires_at",
                        "created_at",
                        "updated_at");
        assertThat(
                        jdbc.queryForList(
                                "SELECT conname FROM pg_constraint WHERE conrelid = "
                                        + "'identity_rate_limit_buckets'::regclass ORDER BY conname",
                                String.class))
                .contains(
                        "pk_identity_rate_limit_buckets",
                        "ck_identity_rate_limit_buckets_policy",
                        "ck_identity_rate_limit_buckets_key_version_positive",
                        "ck_identity_rate_limit_buckets_digest_length",
                        "ck_identity_rate_limit_buckets_window_order",
                        "ck_identity_rate_limit_buckets_count_positive",
                        "ck_identity_rate_limit_buckets_cooldown",
                        "ck_identity_rate_limit_buckets_retention_order",
                        "ck_identity_rate_limit_buckets_version_non_negative",
                        "uq_identity_rate_limit_buckets_key");
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE tablename = "
                                        + "'identity_rate_limit_buckets'",
                                String.class))
                .contains("ix_identity_rate_limit_buckets_retention_expires_at");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version IN "
                                        + "('1','2','3','4','5') AND success",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void databaseEnforcesDigestVersionCountWindowCooldownRetentionAndUniqueness() {
        Instant start = Instant.parse("2099-01-01T00:00:00Z");
        Instant end = start.plus(HOUR);
        byte[] digest = digest();
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        0,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        new byte[31],
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        0,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        end,
                                        start,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        start.plusSeconds(30),
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(Duration.ofHours(23)),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        -1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "UNAPPROVED",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        insertBucket("REGISTRATION_EMAIL", 1, digest, start, end, 1, null, end.plus(RETENTION), 0);
        assertThatThrownBy(
                        () ->
                                insertBucket(
                                        "REGISTRATION_EMAIL",
                                        1,
                                        digest,
                                        start,
                                        end,
                                        1,
                                        null,
                                        end.plus(RETENTION),
                                        0))
                .isInstanceOf(RuntimeException.class);
        assertThat(count(digest)).isEqualTo(1);
    }

    @Test
    void currentWindowDoesNotReuseExpiredPreviousWindow() {
        byte[] digest = digest();
        Instant previousStart =
                jdbc.queryForObject(
                                "SELECT date_trunc('day', transaction_timestamp()) - INTERVAL '1 day'",
                                Timestamp.class)
                        .toInstant();
        insertBucket(
                "REGISTRATION_EMAIL",
                1,
                digest,
                previousStart,
                previousStart.plus(Duration.ofDays(1)),
                1,
                null,
                previousStart.plus(Duration.ofDays(3)),
                0);
        var current =
                input(RateLimitPolicy.REGISTRATION_EMAIL, 1, digest, Duration.ofDays(1), 1, null);
        assertThat(
                        evaluate(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL, current)
                                .isAdmitted())
                .isTrue();
        assertThat(count(digest)).isEqualTo(2);
    }

    @Test
    void realProxyExecutesExactSqlWithOneTransactionClockAndCommitsBeforeReturn() {
        assertThat(AopUtils.isAopProxy(writer)).isTrue();
        byte[] digest = digest();
        AtomicInteger clockCalls = new AtomicInteger();
        AtomicInteger upsertCalls = new AtomicInteger();
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean afterCommit = new AtomicBoolean();
        AtomicInteger completion = new AtomicInteger(-1);
        doAnswer(
                        invocation -> {
                            clockCalls.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
        doAnswer(
                        invocation -> {
                            upsertCalls.incrementAndGet();
                            active.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                            afterCommit.set(true);
                                        }

                                        @Override
                                        public void afterCompletion(int status) {
                                            completion.set(status);
                                        }
                                    });
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));

        assertThat(
                        evaluate(
                                RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                input(
                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                        1,
                                        digest,
                                        HOUR,
                                        5,
                                        null)))
                .satisfies(result -> assertThat(result.isAdmitted()).isTrue());
        assertThat(clockCalls).hasValue(1);
        assertThat(upsertCalls).hasValue(1);
        assertThat(active).isTrue();
        assertThat(afterCommit).isTrue();
        assertThat(completion).hasValue(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(count(digest)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class,
                                digest))
                .isEqualTo(1L);
    }

    @Test
    void activeCallerTransactionFailsBeforeWriterAndLeavesCallerTransactionIntact() {
        byte[] digest = digest();
        var item = input(RateLimitPolicy.REGISTRATION_EMAIL, 1, digest, HOUR, 1, null);
        RateLimitOperationTime operationTime = operationTime();
        AtomicBoolean afterCommit = new AtomicBoolean();
        assertThat(AopUtils.isAopProxy(writer)).isTrue();
        transactions.executeWithoutResult(
                status -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    jdbc.queryForObject(
                            "SELECT set_config('application_name', 'fh011-slice5a-caller', true)",
                            String.class);
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    afterCommit.set(true);
                                }
                            });
                    assertThatThrownBy(
                                    () ->
                                            facade.evaluate(
                                                    RateLimitStagePersistence.Stage
                                                            .REGISTRATION_EMAIL,
                                                    List.of(item),
                                                    operationTime))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Rate-limit stage requires no active caller transaction");
                    assertThatThrownBy(() -> facade.evaluate(null, null, operationTime))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Rate-limit stage requires no active caller transaction");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT current_setting('application_name')",
                                            String.class))
                            .isEqualTo("fh011-slice5a-caller");
                    assertThat(status.isRollbackOnly()).isFalse();
                });
        assertThat(afterCommit).isTrue();
        assertThat(count(digest)).isZero();
        verify(jdbc, never())
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
    }

    @Test
    void dualVersionStageUsesCanonicalPolicyVersionOrderAndUpdatesAllRows() {
        List<String> calls = new CopyOnWriteArrayList<>();
        doAnswer(
                        invocation -> {
                            PreparedStatement probe = mock(PreparedStatement.class);
                            invocation.<PreparedStatementSetter>getArgument(1).setValues(probe);
                            ArgumentCaptor<String> policy = ArgumentCaptor.forClass(String.class);
                            ArgumentCaptor<Integer> version =
                                    ArgumentCaptor.forClass(Integer.class);
                            verify(probe).setString(eq(1), policy.capture());
                            verify(probe).setInt(eq(2), version.capture());
                            calls.add(policy.getValue() + ":" + version.getValue());
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));
        List<RateLimitStagePersistence.BucketInput> inputs =
                List.of(
                        input(RateLimitPolicy.REGISTRATION_ORIGIN, 2, digest(), HOUR, 5, null),
                        input(RateLimitPolicy.FH011_GLOBAL, 2, digest(), HOUR, 5, null),
                        input(RateLimitPolicy.REGISTRATION_ORIGIN, 1, digest(), HOUR, 5, null),
                        input(RateLimitPolicy.FH011_GLOBAL, 1, digest(), HOUR, 5, null));
        assertThat(
                        evaluate(RateLimitStagePersistence.Stage.REGISTRATION_COARSE, inputs)
                                .isAdmitted())
                .isTrue();
        assertThat(calls)
                .containsExactly(
                        "FH011_GLOBAL:1",
                        "FH011_GLOBAL:2",
                        "REGISTRATION_ORIGIN:1",
                        "REGISTRATION_ORIGIN:2");
        for (byte[] digest : ownedDigests) {
            assertThat(count(digest)).isEqualTo(1);
        }
    }

    @Test
    void writerSortsSyntheticDigestBytesUsingUnsignedPostgresqlByteaOrder() throws Exception {
        List<Integer> observedLeadingBytes = new CopyOnWriteArrayList<>();
        doAnswer(
                        invocation -> {
                            PreparedStatement probe = mock(PreparedStatement.class);
                            invocation.<PreparedStatementSetter>getArgument(1).setValues(probe);
                            ArgumentCaptor<byte[]> keyBytes = ArgumentCaptor.forClass(byte[].class);
                            verify(probe).setBytes(eq(3), keyBytes.capture());
                            observedLeadingBytes.add(Byte.toUnsignedInt(keyBytes.getValue()[0]));
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));
        List<RateLimitStagePersistence.BucketWork> unsorted =
                List.of(
                        new RateLimitStagePersistence.BucketWork(syntheticKey(0xff)),
                        new RateLimitStagePersistence.BucketWork(syntheticKey(0x80)),
                        new RateLimitStagePersistence.BucketWork(syntheticKey(0x00)),
                        new RateLimitStagePersistence.BucketWork(syntheticKey(0x7f)));
        Constructor<RateLimitStagePersistence.StageWork> constructor =
                RateLimitStagePersistence.StageWork.class.getDeclaredConstructor(List.class);
        constructor.setAccessible(true);
        RateLimitStagePersistence.StageWork work = constructor.newInstance(unsorted);
        try {
            assertThat(AopUtils.isAopProxy(writer)).isTrue();
            writer.execute(work, operationTime());
            assertThat(observedLeadingBytes).containsExactly(0, 127, 128, 255);
            assertThat(
                            jdbc.query(
                                    "SELECT key_digest FROM identity_rate_limit_buckets "
                                            + "WHERE key_digest IN (?, ?, ?, ?) ORDER BY key_digest",
                                    (resultSet, rowNumber) ->
                                            Byte.toUnsignedInt(resultSet.getBytes(1)[0]),
                                    ownedDigests.get(0),
                                    ownedDigests.get(1),
                                    ownedDigests.get(2),
                                    ownedDigests.get(3)))
                    .containsExactlyElementsOf(observedLeadingBytes);
            for (byte[] digest : ownedDigests) {
                assertThat(count(digest)).isEqualTo(1);
            }
        } finally {
            work.clear();
        }
    }

    @Test
    void alignsWindowToUtcEpochAndEnforcesThresholdWithoutIncrementingRejection() {
        byte[] digest = digest();
        var item = input(RateLimitPolicy.REGISTRATION_EMAIL, 1, digest, HOUR, 2, null);
        assertThat(evaluate(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL, item).isAdmitted())
                .isTrue();
        assertThat(evaluate(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL, item).isAdmitted())
                .isTrue();
        var denied = evaluate(RateLimitStagePersistence.Stage.REGISTRATION_EMAIL, item);
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive().isLessThanOrEqualTo(3600);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class,
                                digest))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extract(epoch FROM window_start)::bigint % 3600 "
                                        + "FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class, digest))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extract(epoch FROM window_end - window_start)::bigint "
                                        + "FROM identity_rate_limit_buckets "
                                        + "WHERE key_digest = ?",
                                Long.class,
                                digest))
                .isEqualTo(HOUR.toSeconds());
    }

    @Test
    void resendCooldownRejectsWithPositiveWaitAndRetentionIsAfterLaterBoundary() {
        byte[] digest = digest();
        var item =
                input(
                        RateLimitPolicy.RESEND_EMAIL,
                        1,
                        digest,
                        Duration.ofDays(1),
                        5,
                        Duration.ofMinutes(5));
        assertThat(evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, item).isAdmitted())
                .isTrue();
        var denied = evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, item);
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 300L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT retention_expires_at >= "
                                        + "GREATEST(window_end, cooldown_until) + INTERVAL '24 hours' "
                                        + "FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Boolean.class,
                                digest))
                .isTrue();
    }

    @Test
    void zeroResendCooldownStillEnforcesTheWindowCount() {
        byte[] digest = digest();
        var item = input(RateLimitPolicy.RESEND_EMAIL, 1, digest, HOUR, 2, Duration.ZERO);
        assertThat(evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, item).isAdmitted())
                .isTrue();
        assertThat(evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, item).isAdmitted())
                .isTrue();
        var denied = evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, item);
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class,
                                digest))
                .isEqualTo(2L);
    }

    @Test
    void exactLongestWaitRoundsDatabaseWindowAndCooldownBoundaries() {
        byte[] exhausted = digest();
        byte[] cooling = digest();
        var first =
                input(RateLimitPolicy.RESEND_EMAIL, 1, exhausted, HOUR, 5, Duration.ofMinutes(5));
        var second =
                input(RateLimitPolicy.RESEND_EMAIL, 2, cooling, HOUR, 5, Duration.ofMinutes(5));
        AtomicReference<Instant> sampledDatabaseInstant = new AtomicReference<>();
        AtomicReference<Instant> windowEnd = new AtomicReference<>();
        AtomicInteger clockCalls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            Instant databaseInstant = (Instant) invocation.callRealMethod();
                            clockCalls.incrementAndGet();
                            sampledDatabaseInstant.set(databaseInstant);
                            Instant start =
                                    Instant.ofEpochSecond(
                                            Math.floorDiv(
                                                            databaseInstant.getEpochSecond(),
                                                            HOUR.toSeconds())
                                                    * HOUR.toSeconds());
                            Instant end = start.plus(HOUR);
                            windowEnd.set(end);
                            Instant cooldownEnd = databaseInstant.plusSeconds(1).plusNanos(1_000);
                            insertBucket(
                                    "RESEND_EMAIL",
                                    1,
                                    exhausted,
                                    start,
                                    end,
                                    5,
                                    null,
                                    end.plus(RETENTION),
                                    0);
                            insertBucket(
                                    "RESEND_EMAIL",
                                    2,
                                    cooling,
                                    start,
                                    end,
                                    1,
                                    cooldownEnd,
                                    (cooldownEnd.isAfter(end) ? cooldownEnd : end).plus(RETENTION),
                                    0);
                            return databaseInstant;
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
        var denied = evaluate(RateLimitStagePersistence.Stage.RESEND_EMAIL, List.of(second, first));
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(clockCalls).hasValue(1);
        long windowWholeSeconds =
                Duration.between(sampledDatabaseInstant.get(), windowEnd.get()).toSeconds();
        boolean partialWindowSecond =
                Duration.between(sampledDatabaseInstant.get(), windowEnd.get()).toNanosPart() > 0;
        long exactWindowWait = windowWholeSeconds + (partialWindowSecond ? 1 : 0);
        assertThat(denied.retryAfterSeconds()).isEqualTo(Math.max(exactWindowWait, 2L));
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(count(exhausted)).isZero();
        assertThat(count(cooling)).isZero();
    }

    @Test
    void longerCooldownWinsAfterBothProductionUpsertsReject() {
        byte[] exhausted = digest();
        byte[] cooling = digest();
        var exhaustedInput =
                input(RateLimitPolicy.RESEND_EMAIL, 1, exhausted, HOUR, 5, Duration.ofMinutes(5));
        var coolingInput =
                input(RateLimitPolicy.RESEND_EMAIL, 2, cooling, HOUR, 5, Duration.ofMinutes(5));
        AtomicReference<Instant> databaseInstant = new AtomicReference<>();
        AtomicReference<Instant> storedWindowEnd = new AtomicReference<>();
        AtomicReference<Instant> storedCooldownEnd = new AtomicReference<>();
        List<Integer> attemptedVersions = new CopyOnWriteArrayList<>();
        AtomicBoolean allAttemptsTransactional = new AtomicBoolean(true);
        AtomicInteger clockCalls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            Instant sampled = (Instant) invocation.callRealMethod();
                            clockCalls.incrementAndGet();
                            databaseInstant.set(sampled);
                            Instant start =
                                    Instant.ofEpochSecond(
                                            Math.floorDiv(
                                                            sampled.getEpochSecond(),
                                                            HOUR.toSeconds())
                                                    * HOUR.toSeconds());
                            Instant shortWindowEnd = sampled.plusSeconds(10).plusMillis(250);
                            Instant longCooldownEnd = sampled.plusSeconds(120).plusNanos(1_000);
                            storedWindowEnd.set(shortWindowEnd);
                            storedCooldownEnd.set(longCooldownEnd);
                            insertBucket(
                                    "RESEND_EMAIL",
                                    1,
                                    exhausted,
                                    start,
                                    shortWindowEnd,
                                    5,
                                    null,
                                    shortWindowEnd.plus(RETENTION),
                                    0);
                            Instant coolingWindowEnd = start.plus(HOUR);
                            Instant later =
                                    longCooldownEnd.isAfter(coolingWindowEnd)
                                            ? longCooldownEnd
                                            : coolingWindowEnd;
                            insertBucket(
                                    "RESEND_EMAIL",
                                    2,
                                    cooling,
                                    start,
                                    coolingWindowEnd,
                                    1,
                                    longCooldownEnd,
                                    later.plus(RETENTION),
                                    0);
                            return sampled;
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
        doAnswer(
                        invocation -> {
                            allAttemptsTransactional.compareAndSet(
                                    true,
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            PreparedStatement probe = mock(PreparedStatement.class);
                            invocation.<PreparedStatementSetter>getArgument(1).setValues(probe);
                            verify(probe).setString(1, "RESEND_EMAIL");
                            ArgumentCaptor<Integer> version =
                                    ArgumentCaptor.forClass(Integer.class);
                            verify(probe).setInt(eq(2), version.capture());
                            attemptedVersions.add(version.getValue());
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));

        assertThat(AopUtils.isAopProxy(writer)).isTrue();
        var denied =
                evaluate(
                        RateLimitStagePersistence.Stage.RESEND_EMAIL,
                        List.of(coolingInput, exhaustedInput));
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(clockCalls).hasValue(1);
        assertThat(attemptedVersions).containsExactly(1, 2);
        assertThat(allAttemptsTransactional).isTrue();

        Duration windowRemainder = Duration.between(databaseInstant.get(), storedWindowEnd.get());
        Duration cooldownRemainder =
                Duration.between(databaseInstant.get(), storedCooldownEnd.get());
        assertThat(windowRemainder).isEqualTo(Duration.ofSeconds(10).plusMillis(250));
        assertThat(cooldownRemainder).isEqualTo(Duration.ofSeconds(120).plusNanos(1_000));
        long exactWindowWait = windowRemainder.getSeconds() + 1;
        long exactCooldownWait = cooldownRemainder.getSeconds() + 1;
        assertThat(exactCooldownWait).isGreaterThan(exactWindowWait);
        assertThat(denied.retryAfterSeconds()).isEqualTo(exactCooldownWait);
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(count(exhausted)).isZero();
        assertThat(count(cooling)).isZero();
    }

    @Test
    void laterRejectionRollsBackEarlierBucketAndLeavesIdentityTablesUnchanged() {
        byte[] global = digest();
        byte[] origin = digest();
        var stage = RateLimitStagePersistence.Stage.REGISTRATION_COARSE;
        var globalInput = input(RateLimitPolicy.FH011_GLOBAL, 1, global, HOUR, 5, null);
        var originInput = input(RateLimitPolicy.REGISTRATION_ORIGIN, 1, origin, HOUR, 1, null);
        assertThat(evaluate(stage, List.of(originInput, globalInput)).isAdmitted()).isTrue();
        List<String> before = identityFingerprints();
        AtomicInteger completion = new AtomicInteger(-1);
        observeRollback(completion);
        var denied = evaluate(stage, List.of(originInput, globalInput));
        assertThat(denied.isAdmitted()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(completion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(count(global)).isEqualTo(1);
        assertThat(count(origin)).isEqualTo(1);
        assertThat(identityFingerprints()).isEqualTo(before);
    }

    @Test
    void realPostgresqlErrorOnSecondBucketRollsBackFirstBucket() {
        byte[] global = digest();
        byte[] origin = digest();
        var stage = RateLimitStagePersistence.Stage.REGISTRATION_COARSE;
        var globalInput =
                input(RateLimitPolicy.FH011_GLOBAL, 1, global, Duration.ofDays(1), 2, null);
        var originInput =
                input(RateLimitPolicy.REGISTRATION_ORIGIN, 1, origin, Duration.ofDays(1), 2, null);
        assertThat(evaluate(stage, List.of(globalInput, originInput)).isAdmitted()).isTrue();
        jdbc.update(
                "UPDATE identity_rate_limit_buckets SET version = 9223372036854775807 "
                        + "WHERE key_digest = ?",
                origin);
        AtomicInteger completion = new AtomicInteger(-1);
        observeRollback(completion);
        assertThatThrownBy(() -> evaluate(stage, List.of(originInput, globalInput)))
                .isInstanceOf(RuntimeException.class);
        assertThat(completion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class,
                                global))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                Long.class,
                                origin))
                .isEqualTo(1L);
    }

    @Test
    void concurrentFirstUseAndThresholdRaceUseOneUniqueBucket() throws Exception {
        concurrentPair(2, 2, 0);
        concurrentPair(1, 1, 1);
    }

    private void concurrentPair(long limit, int expectedAdmitted, int expectedDenied)
            throws Exception {
        byte[] digest = digest();
        var item =
                input(
                        RateLimitPolicy.REGISTRATION_EMAIL,
                        1,
                        digest,
                        Duration.ofDays(1),
                        limit,
                        null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<RateLimitStagePersistence.StageResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                                        throw new AssertionError("start timeout");
                                    }
                                    return evaluate(
                                            RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                            item);
                                }));
            }
            assertThat(ready.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<RateLimitStagePersistence.StageResult> results = new ArrayList<>();
            for (Future<RateLimitStagePersistence.StageResult> future : futures) {
                results.add(future.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(RateLimitStagePersistence.StageResult::isAdmitted))
                    .hasSize(expectedAdmitted);
            assertThat(results.stream().filter(result -> !result.isAdmitted()))
                    .hasSize(expectedDenied);
            assertThat(count(digest)).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT request_count FROM identity_rate_limit_buckets WHERE key_digest = ?",
                                    Long.class,
                                    digest))
                    .isEqualTo((long) expectedAdmitted);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void stageAgeRejectsBothOutsideBoundariesBeforeAnyUpsert() {
        RateLimitOperationTime operationTime = operationTime();
        byte[] beforeDigest = digest();
        byte[] afterDigest = digest();

        assertAgeFailureBeforeUpsert(
                operationTime, operationTime.instant().minusNanos(1), beforeDigest);
        reset(jdbc);
        assertAgeFailureBeforeUpsert(
                operationTime, operationTime.instant().plusSeconds(3600).plusNanos(1), afterDigest);
    }

    @Test
    void stageAgeAcceptsBothInclusiveBoundaries() {
        RateLimitOperationTime operationTime = operationTime();
        byte[] first = digest();
        byte[] second = digest();
        stubStageInstant(operationTime.instant());
        assertThat(
                        facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        first,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime)
                                .isAdmitted())
                .isTrue();
        reset(jdbc);
        stubStageInstant(operationTime.instant().plusSeconds(3600));
        assertThat(
                        facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        second,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime)
                                .isAdmitted())
                .isTrue();
        assertThat(count(first)).isEqualTo(1);
        assertThat(count(second)).isEqualTo(1);
    }

    @Test
    void laterAgeRejectionDoesNotRefundCommittedCoarseStage() {
        RateLimitOperationTime operationTime = operationTime();
        byte[] originDigest = digest();
        byte[] globalDigest = digest();
        byte[] emailDigest = digest();
        List<RateLimitStagePersistence.BucketInput> coarse =
                List.of(
                        input(RateLimitPolicy.REGISTRATION_ORIGIN, 1, originDigest, HOUR, 5, null),
                        input(RateLimitPolicy.FH011_GLOBAL, 1, globalDigest, HOUR, 5, null));
        assertThat(
                        facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_COARSE,
                                        coarse,
                                        operationTime)
                                .isAdmitted())
                .isTrue();

        stubStageInstant(operationTime.instant().plusSeconds(3600).plusNanos(1));
        assertThatThrownBy(
                        () ->
                                facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        emailDigest,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid rate-limit operation time")
                .hasNoCause();
        assertThat(count(originDigest)).isEqualTo(1);
        assertThat(count(globalDigest)).isEqualTo(1);
        assertThat(count(emailDigest)).isZero();
    }

    @Test
    void coarseAndEmailStagesReuseOperationTimeButSampleSeparateStageTimes() {
        RateLimitOperationTime operationTime = operationTime();
        byte[] originDigest = digest();
        byte[] globalDigest = digest();
        byte[] emailDigest = digest();
        AtomicInteger stageClockReads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            stageClockReads.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));

        assertThat(
                        facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_COARSE,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_ORIGIN,
                                                        1,
                                                        originDigest,
                                                        HOUR,
                                                        5,
                                                        null),
                                                input(
                                                        RateLimitPolicy.FH011_GLOBAL,
                                                        1,
                                                        globalDigest,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime)
                                .isAdmitted())
                .isTrue();
        assertThat(
                        facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        emailDigest,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime)
                                .isAdmitted())
                .isTrue();
        assertThat(stageClockReads).hasValue(2);
    }

    private void assertAgeFailureBeforeUpsert(
            RateLimitOperationTime operationTime, Instant stageInstant, byte[] digest) {
        AtomicInteger completion = new AtomicInteger(-1);
        doAnswer(
                        invocation -> {
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            completion.set(status);
                                        }
                                    });
                            return stageInstant;
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
        assertThatThrownBy(
                        () ->
                                facade.evaluate(
                                        RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                                        List.of(
                                                input(
                                                        RateLimitPolicy.REGISTRATION_EMAIL,
                                                        1,
                                                        digest,
                                                        HOUR,
                                                        5,
                                                        null)),
                                        operationTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid rate-limit operation time")
                .hasNoCause();
        assertThat(completion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(jdbc, never())
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));
        assertThat(count(digest)).isZero();
    }

    private void stubStageInstant(Instant stageInstant) {
        doAnswer(invocation -> stageInstant)
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitStageWriter.TIMESTAMP_SQL), any(RowMapper.class));
    }

    private RateLimitStagePersistence.StageResult evaluate(
            RateLimitStagePersistence.Stage stage, RateLimitStagePersistence.BucketInput item) {
        return facade.evaluate(stage, List.of(item), operationTime());
    }

    private RateLimitStagePersistence.StageResult evaluate(
            RateLimitStagePersistence.Stage stage,
            List<RateLimitStagePersistence.BucketInput> items) {
        return facade.evaluate(stage, items, operationTime());
    }

    private RateLimitOperationTime operationTime() {
        Timestamp timestamp =
                jdbc.queryForObject(
                        "SELECT transaction_timestamp() AS test_operation_instant",
                        Timestamp.class);
        return new RateLimitOperationTime(timestamp.toInstant());
    }

    private static RateLimitStagePersistence.BucketInput input(
            RateLimitPolicy policy,
            int version,
            byte[] digest,
            Duration window,
            long limit,
            Duration cooldown) {
        return new RateLimitStagePersistence.BucketInput(
                policy, version, digest, window, limit, cooldown, RETENTION);
    }

    private byte[] digest() {
        return digestWithLeadingByte(0x5a);
    }

    private byte[] digestWithLeadingByte(int firstByte) {
        int value = NEXT_DIGEST.incrementAndGet();
        byte[] digest = new byte[32];
        digest[0] = (byte) firstByte;
        digest[1] = 0x5a;
        digest[28] = (byte) (value >>> 24);
        digest[29] = (byte) (value >>> 16);
        digest[30] = (byte) (value >>> 8);
        digest[31] = (byte) value;
        ownedDigests.add(digest.clone());
        return digest;
    }

    private RateLimitStagePersistence.BucketInput syntheticKey(int firstByte) {
        byte[] digest = digestWithLeadingByte(firstByte);
        return input(RateLimitPolicy.REGISTRATION_EMAIL, 1, digest, HOUR, 5, null);
    }

    private long count(byte[] digest) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity_rate_limit_buckets WHERE key_digest = ?",
                Long.class,
                digest);
    }

    private List<String> identityFingerprints() {
        return List.of(
                fingerprint("identity_accounts"),
                fingerprint("identity_credentials"),
                fingerprint("identity_email_verification_tokens"));
    }

    private String fingerprint(String table) {
        return jdbc.queryForObject(
                "SELECT md5(COALESCE(string_agg(row_to_json(t)::text, ',' ORDER BY t.id), '')) "
                        + "FROM "
                        + table
                        + " t",
                String.class);
    }

    private void insertBucket(
            String policy,
            int version,
            byte[] digest,
            Instant start,
            Instant end,
            long count,
            Instant cooldown,
            Instant retention,
            long optimisticVersion) {
        jdbc.update(
                "INSERT INTO identity_rate_limit_buckets "
                        + "(policy, key_version, key_digest, window_start, window_end, request_count, "
                        + "cooldown_until, retention_expires_at, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                policy,
                version,
                digest,
                Timestamp.from(start),
                Timestamp.from(end),
                count,
                cooldown == null ? null : Timestamp.from(cooldown),
                Timestamp.from(retention),
                optimisticVersion,
                Timestamp.from(start),
                Timestamp.from(start));
    }

    private void observeRollback(AtomicInteger completion) {
        AtomicBoolean registered = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            if (registered.compareAndSet(false, true)) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isTrue();
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int status) {
                                                completion.set(status);
                                            }
                                        });
                            }
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .query(
                        eq(TransactionalRateLimitStageWriter.UPSERT_SQL),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));
    }
}
