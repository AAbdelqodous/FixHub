package com.fixhub.platform.identity.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class EmailVerificationTokenConcurrencyIntegrationTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FUTURE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TRANSACTION_LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSACTION_STATEMENT_TIMEOUT = Duration.ofSeconds(20);
    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();

    @Autowired private AccountRepository accountRepository;

    @Autowired private EmailVerificationTokenRepository tokenRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void concurrentOpenInsertsForOneAccountLeaveAtMostOneCommittedRow() throws Exception {
        Account account = savedAccount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Boolean> results =
                runTwo(
                        ready,
                        start,
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            insert(account.getId(), digest(1));
                                            return true;
                                        }),
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            insert(account.getId(), digest(1));
                                            return true;
                                        }));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_email_verification_tokens "
                                        + "WHERE account_id = ? AND terminal_reason IS NULL",
                                Long.class,
                                account.getId()))
                .isEqualTo(1L);
    }

    @Test
    void concurrentDuplicateDigestInsertsCannotBothCommit() throws Exception {
        Account first = savedAccount();
        Account second = savedAccount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Boolean> results =
                runTwo(
                        ready,
                        start,
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            insert(first.getId(), digest(2));
                                            return true;
                                        }),
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            insert(second.getId(), digest(2));
                                            return true;
                                        }));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_email_verification_tokens "
                                        + "WHERE token_digest = ?",
                                Long.class,
                                digest(2)))
                .isEqualTo(1L);
    }

    @Test
    void staleEntityUpdatesUseOptimisticLockingAcrossIndependentTransactions() throws Exception {
        Account account = savedAccount();
        EmailVerificationToken saved =
                tokenRepository.save(EmailVerificationToken.create(account, digest(3), EXPIRY));
        EmailVerificationToken first = tokenRepository.findByTokenDigest(digest(3)).orElseThrow();
        EmailVerificationToken stale = tokenRepository.findByTokenDigest(digest(3)).orElseThrow();
        assertThat(first).isNotSameAs(stale);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results =
                runTwo(
                        ready,
                        start,
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            first.consume(Instant.parse("2026-09-24T01:00:00Z"));
                                            tokenRepository.save(first);
                                            return true;
                                        }),
                        () ->
                                inTransaction(
                                        ready,
                                        start,
                                        () -> {
                                            stale.supersede(Instant.parse("2026-09-24T02:00:00Z"));
                                            tokenRepository.save(stale);
                                            return true;
                                        }));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(tokenRepository.findByTokenDigest(digest(3)).orElseThrow().terminalReason())
                .isNotNull();
        assertThat(saved.getId()).isPositive();
    }

    @Test
    void rollbackLeavesTokenOpenAndACommittedTerminalStateCannotReopen() {
        Account account = savedAccount();
        EmailVerificationToken token =
                tokenRepository.save(EmailVerificationToken.create(account, digest(4), EXPIRY));

        assertThatThrownBy(
                        () ->
                                transactionTemplate.executeWithoutResult(
                                        status -> {
                                            token.expire(Instant.parse("2026-09-24T03:00:00Z"));
                                            tokenRepository.save(token);
                                            throw new IllegalStateException("synthetic rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        EmailVerificationToken afterRollback =
                tokenRepository.findByTokenDigest(digest(4)).orElseThrow();
        assertThat(afterRollback.isOpen()).isTrue();

        afterRollback.expire(Instant.parse("2026-09-24T04:00:00Z"));
        tokenRepository.save(afterRollback);
        assertThatThrownBy(() -> afterRollback.invalidate(Instant.parse("2026-09-24T05:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    private Account savedAccount() {
        return accountRepository.save(
                Account.create(
                        "token-concurrency-" + ACCOUNT_SEQUENCE.incrementAndGet() + "@example.com",
                        null,
                        "en"));
    }

    private void insert(long accountId, byte[] digest) {
        jdbcTemplate.update(
                """
                INSERT INTO identity_email_verification_tokens
                    (account_id, token_digest, expires_at, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                accountId,
                digest,
                Timestamp.from(EXPIRY));
    }

    private List<Boolean> runTwo(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<Boolean> first,
            Callable<Boolean> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(first));
            futures.add(executor.submit(second));
            await(ready, "worker readiness");
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(result(future));
            }
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(
                        EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Concurrency workers did not terminate");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Concurrency worker termination interrupted");
            }
        }
    }

    private boolean result(Future<Boolean> future) {
        try {
            return future.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrency future interrupted");
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AssertionError("Concurrency future timed out");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof DataIntegrityViolationException
                    || cause instanceof OptimisticLockingFailureException) {
                return false;
            }
            throw new AssertionError("Unexpected concurrency failure");
        }
    }

    private boolean inTransaction(CountDownLatch ready, CountDownLatch start, CheckedTask task) {
        return transactionTemplate.execute(
                status -> {
                    configureTransactionSafety();
                    ready.countDown();
                    await(start, "race start");
                    return task.run();
                });
    }

    private void configureTransactionSafety() {
        jdbcTemplate.execute(
                "SET LOCAL lock_timeout = '" + TRANSACTION_LOCK_TIMEOUT.toSeconds() + "s'");
        jdbcTemplate.execute(
                "SET LOCAL statement_timeout = '"
                        + TRANSACTION_STATEMENT_TIMEOUT.toSeconds()
                        + "s'");
    }

    private static void await(CountDownLatch latch, String phase) {
        try {
            if (!latch.await(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Concurrency coordination timed out: " + phase);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrency coordination interrupted: " + phase);
        }
    }

    private static byte[] digest(int start) {
        byte[] digest = new byte[32];
        for (int index = 0; index < digest.length; index++) {
            digest[index] = (byte) (start + index);
        }
        return digest;
    }

    @FunctionalInterface
    private interface CheckedTask {
        boolean run();
    }
}
