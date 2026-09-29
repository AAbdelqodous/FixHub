package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fixhub.platform.TestcontainersConfiguration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class PostgresRateLimitOperationTimeSourceIntegrationTest {

    private static final String UNAVAILABLE = "Rate-limit operation time is unavailable";

    @Autowired private PostgresRateLimitOperationTimeSource source;
    @Autowired private TransactionalRateLimitOperationTimeReader reader;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private JdbcTemplate jdbc;

    @AfterEach
    void resetSpy() {
        reset(jdbc);
    }

    @Test
    void realProxyReadsOnceInItsOwnReadTransactionAndCompletesBeforeReturn() {
        assertThat(AopUtils.isAopProxy(reader)).isTrue();
        AtomicInteger queries = new AtomicInteger();
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean readOnly = new AtomicBoolean();
        AtomicBoolean afterCommit = new AtomicBoolean();
        AtomicInteger completion = new AtomicInteger(-1);
        AtomicReference<Instant> databaseValue = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            queries.incrementAndGet();
                            active.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            readOnly.set(
                                    TransactionSynchronizationManager
                                            .isCurrentTransactionReadOnly());
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
                            Instant value = (Instant) invocation.callRealMethod();
                            databaseValue.set(value);
                            return value;
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));

        RateLimitOperationTime acquired = source.acquire();
        assertThat(queries).hasValue(1);
        assertThat(active).isTrue();
        assertThat(readOnly).isTrue();
        assertThat(afterCommit).isTrue();
        assertThat(completion).hasValue(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(acquired.instant().equals(databaseValue.get())).isTrue();
        assertThat(acquired.toString()).isEqualTo("RateLimitOperationTime[REDACTED]");
    }

    @Test
    void activeCallerTransactionIsRejectedBeforeTimestampQuery() {
        transactions.executeWithoutResult(
                status -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    assertUnavailable(source::acquire);
                    assertThat(status.isRollbackOnly()).isFalse();
                });
        verify(jdbc, never())
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));
    }

    @Test
    void queryAndNullResultFailuresExposeNoCandidateOrDatabaseCause() {
        doThrow(new IllegalStateException("synthetic PostgreSQL DETAIL and connection data"))
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));
        assertUnavailable(source::acquire);

        reset(jdbc);
        doAnswer(invocation -> null)
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));
        assertUnavailable(source::acquire);
    }

    @Test
    void transactionCompletionFailureExposesNoCandidate() {
        doAnswer(
                        invocation -> {
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void beforeCommit(boolean readOnly) {
                                            throw new IllegalStateException(
                                                    "synthetic commit failure with SQL DETAIL");
                                        }
                                    });
                            return invocation.callRealMethod();
                        })
                .when(jdbc)
                .queryForObject(
                        eq(TransactionalRateLimitOperationTimeReader.TIMESTAMP_SQL),
                        any(RowMapper.class));
        assertUnavailable(source::acquire);
    }

    private static void assertUnavailable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UNAVAILABLE)
                .hasNoCause();
    }
}
