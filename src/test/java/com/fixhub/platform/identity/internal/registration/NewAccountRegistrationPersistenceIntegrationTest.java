package com.fixhub.platform.identity.internal.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.fixhub.platform.identity.internal.credential.CredentialRepository;
import com.fixhub.platform.identity.internal.verification.InitialEmailVerificationTokenPersistence;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class NewAccountRegistrationPersistenceIntegrationTest {

    private static final String ENCODED_CREDENTIAL =
            "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final String EMAIL_NAMESPACE = "fh011-slice4c-";
    private static final String EMAIL_NAMESPACE_PATTERN = EMAIL_NAMESPACE + "%@example.test";
    private static final Instant EXPIRY = Instant.parse("2099-01-02T00:00:00Z");
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FUTURE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TRANSACTION_LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSACTION_STATEMENT_TIMEOUT = Duration.ofSeconds(20);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private NewAccountRegistrationPersistence persistence;

    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private AccountRepository accountRepository;

    @MockitoSpyBean private CredentialRepository credentialRepository;

    @MockitoSpyBean private InitialEmailVerificationTokenPersistence tokenPersistence;

    @AfterEach
    void cleanUp() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM identity_email_verification_tokens WHERE account_id IN "
                            + "(SELECT id FROM identity_accounts WHERE email_normalized LIKE ?)",
                    EMAIL_NAMESPACE_PATTERN);
            jdbcTemplate.update(
                    "DELETE FROM identity_credentials WHERE account_id IN "
                            + "(SELECT id FROM identity_accounts WHERE email_normalized LIKE ?)",
                    EMAIL_NAMESPACE_PATTERN);
            jdbcTemplate.update(
                    "DELETE FROM identity_accounts WHERE email_normalized LIKE ?",
                    EMAIL_NAMESPACE_PATTERN);
        } finally {
            reset(accountRepository, credentialRepository, tokenPersistence);
        }
    }

    @Test
    void persistsExactlyOnePendingAccountPasswordCredentialAndOpenToken() {
        String email = uniqueEmail();
        byte[] digest = uniqueDigest();

        NewAccountRegistrationPersistence.Result result =
                persistence.persist(email, "en-US", ENCODED_CREDENTIAL, digest, EXPIRY);

        assertThat(result).isEqualTo(NewAccountRegistrationPersistence.Result.CREATED);
        assertThat(accountCount(email)).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_accounts "
                                        + "WHERE email_normalized = ? AND phone IS NULL "
                                        + "AND preferred_locale = 'en-US' "
                                        + "AND status = 'PENDING_VERIFICATION'",
                                Long.class,
                                normalized(email)))
                .isEqualTo(1L);
        assertThat(passwordCredentialCount(email)).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_credentials c "
                                        + "JOIN identity_accounts a ON a.id = c.account_id "
                                        + "WHERE a.email_normalized = ? "
                                        + "AND c.credential_type = 'PASSWORD' "
                                        + "AND c.secret_hash = ?",
                                Long.class,
                                normalized(email),
                                ENCODED_CREDENTIAL))
                .isEqualTo(1L);
        assertThat(openTokenCount(email)).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_email_verification_tokens t "
                                        + "JOIN identity_accounts a ON a.id = t.account_id "
                                        + "WHERE a.email_normalized = ? AND t.token_digest = ? "
                                        + "AND t.expires_at = ? AND t.terminal_reason IS NULL "
                                        + "AND t.terminal_at IS NULL",
                                Long.class,
                                normalized(email),
                                digest,
                                Timestamp.from(EXPIRY)))
                .isEqualTo(1L);
    }

    @Test
    void exactEmailUniquenessConflictReturnsNoChangeAfterRollback() {
        String firstEmail = uniqueEmail();
        String caseVariant = firstEmail.toUpperCase(Locale.ROOT);
        persistence.persist(firstEmail, "en", ENCODED_CREDENTIAL, uniqueDigest(), EXPIRY);

        NewAccountRegistrationPersistence.Result result =
                persistence.persist(caseVariant, "en", ENCODED_CREDENTIAL, uniqueDigest(), EXPIRY);

        assertThat(result).isEqualTo(NewAccountRegistrationPersistence.Result.NO_CHANGE);
        assertThat(accountCount(firstEmail)).isEqualTo(1L);
        assertThat(credentialCount(firstEmail)).isEqualTo(1L);
        assertThat(tokenCount(firstEmail)).isEqualTo(1L);
    }

    @Test
    void concurrentNormalizedEmailConflictLeavesOneCompleteRegistration() throws Exception {
        String email = uniqueEmail();
        String caseVariant = email.toUpperCase(Locale.ROOT);
        byte[] firstDigest = uniqueDigest();
        byte[] secondDigest = uniqueDigest();
        assertThat(firstDigest).isNotEqualTo(secondDigest);
        CountDownLatch atAccountSave = new CountDownLatch(2);
        CountDownLatch releaseSaves = new CountDownLatch(1);
        Set<Long> workerThreads = ConcurrentHashMap.newKeySet();
        Answer<?> originalSave =
                mockingDetails(accountRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(
                        invocation -> {
                            Account account = invocation.getArgument(0);
                            if (normalized(email).equals(account.getEmailNormalized())) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isTrue();
                                workerThreads.add(Thread.currentThread().threadId());
                                configureTransactionSafety();
                                atAccountSave.countDown();
                                await(atAccountSave);
                                await(releaseSaves);
                            }
                            return originalSave.answer(invocation);
                        })
                .when(accountRepository)
                .save(any(Account.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<NewAccountRegistrationPersistence.Result>> futures = new ArrayList<>();
        try {
            futures.add(
                    executor.submit(
                            () ->
                                    persistence.persist(
                                            email, "en", ENCODED_CREDENTIAL, firstDigest, EXPIRY)));
            futures.add(
                    executor.submit(
                            () ->
                                    persistence.persist(
                                            caseVariant,
                                            "en",
                                            ENCODED_CREDENTIAL,
                                            secondDigest,
                                            EXPIRY)));
            await(atAccountSave);
            releaseSaves.countDown();

            NewAccountRegistrationPersistence.Result first =
                    futures.get(0).get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            NewAccountRegistrationPersistence.Result second =
                    futures.get(1).get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(List.of(first, second))
                    .containsExactlyInAnyOrder(
                            NewAccountRegistrationPersistence.Result.CREATED,
                            NewAccountRegistrationPersistence.Result.NO_CHANGE);
            assertThat(workerThreads).hasSize(2);
            assertThat(accountCount(email)).isEqualTo(1L);
            assertThat(passwordCredentialCount(email)).isEqualTo(1L);
            assertThat(credentialCount(email)).isEqualTo(1L);
            assertThat(openTokenCount(email)).isEqualTo(1L);
            assertThat(tokenCount(email)).isEqualTo(1L);
            assertThat(tokenDigestCount(email, firstDigest))
                    .isEqualTo(first == NewAccountRegistrationPersistence.Result.CREATED ? 1L : 0L);
            assertThat(tokenDigestCount(email, secondDigest))
                    .isEqualTo(
                            second == NewAccountRegistrationPersistence.Result.CREATED ? 1L : 0L);
        } finally {
            releaseSaves.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            if (!executor.awaitTermination(
                    EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Registration workers did not terminate");
            }
        }
    }

    @Test
    void credentialWriteFailureRethrowsAndRollsBackAccountBeforeTokenWrite() {
        String email = uniqueEmail();
        IllegalStateException failure =
                new IllegalStateException("Synthetic credential write failure");
        boolean[] accountInserted = {false};
        doAnswer(
                        invocation -> {
                            accountInserted[0] = accountCount(email) == 1L;
                            throw failure;
                        })
                .when(credentialRepository)
                .save(any());

        try {
            assertThatThrownBy(
                            () ->
                                    persistence.persist(
                                            email,
                                            "en",
                                            ENCODED_CREDENTIAL,
                                            uniqueDigest(),
                                            EXPIRY))
                    .isSameAs(failure);
            assertThat(accountInserted[0]).isTrue();
            assertThat(accountCount(email)).isZero();
            assertThat(credentialCount(email)).isZero();
            assertThat(tokenCount(email)).isZero();
            verify(tokenPersistence, never()).saveInitial(any(), any(), any());
        } finally {
            reset(credentialRepository, tokenPersistence);
        }
    }

    @Test
    void nonEmailConstraintFailureIsRethrownAndRollsBackAllWrites() {
        byte[] duplicateDigest = uniqueDigest();
        persistence.persist(uniqueEmail(), "en", ENCODED_CREDENTIAL, duplicateDigest, EXPIRY);
        String rejectedEmail = uniqueEmail();

        assertThatThrownBy(
                        () ->
                                persistence.persist(
                                        rejectedEmail,
                                        "en",
                                        ENCODED_CREDENTIAL,
                                        duplicateDigest,
                                        EXPIRY))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accountCount(rejectedEmail)).isZero();
        assertThat(credentialCount(rejectedEmail)).isZero();
        assertThat(tokenCount(rejectedEmail)).isZero();
    }

    private void configureTransactionSafety() {
        jdbcTemplate.execute(
                "SET LOCAL lock_timeout = '" + TRANSACTION_LOCK_TIMEOUT.toSeconds() + "s'");
        jdbcTemplate.execute(
                "SET LOCAL statement_timeout = '"
                        + TRANSACTION_STATEMENT_TIMEOUT.toSeconds()
                        + "s'");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("Registration coordination timed out");
        }
    }

    private long accountCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_accounts WHERE email_normalized = ?",
                Long.class,
                normalized(email));
    }

    private long credentialCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_credentials c "
                        + "JOIN identity_accounts a ON a.id = c.account_id "
                        + "WHERE a.email_normalized = ?",
                Long.class,
                normalized(email));
    }

    private long passwordCredentialCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_credentials c "
                        + "JOIN identity_accounts a ON a.id = c.account_id "
                        + "WHERE a.email_normalized = ? AND c.credential_type = 'PASSWORD'",
                Long.class,
                normalized(email));
    }

    private long tokenCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_email_verification_tokens t "
                        + "JOIN identity_accounts a ON a.id = t.account_id "
                        + "WHERE a.email_normalized = ?",
                Long.class,
                normalized(email));
    }

    private long openTokenCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_email_verification_tokens t "
                        + "JOIN identity_accounts a ON a.id = t.account_id "
                        + "WHERE a.email_normalized = ? AND t.terminal_reason IS NULL "
                        + "AND t.terminal_at IS NULL",
                Long.class,
                normalized(email));
    }

    private long tokenDigestCount(String email, byte[] digest) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_email_verification_tokens t "
                        + "JOIN identity_accounts a ON a.id = t.account_id "
                        + "WHERE a.email_normalized = ? AND t.token_digest = ?",
                Long.class,
                normalized(email),
                digest);
    }

    private static String normalized(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private static String uniqueEmail() {
        return EMAIL_NAMESPACE + UUID.randomUUID() + "@example.test";
    }

    private static byte[] uniqueDigest() {
        byte[] digest = new byte[32];
        RANDOM.nextBytes(digest);
        return digest;
    }
}
