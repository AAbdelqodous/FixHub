package com.fixhub.platform.identity.internal.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.fixhub.platform.identity.internal.credential.CredentialRepository;
import com.fixhub.platform.identity.internal.password.PasswordEncodingService;
import com.fixhub.platform.identity.internal.password.RegistrationPasswordChecks;
import com.fixhub.platform.identity.internal.verification.InitialEmailVerificationTokenPersistence;
import com.fixhub.platform.identity.internal.verification.VerificationTokenCryptography;
import com.fixhub.platform.identity.internal.verification.VerificationTokenIssuance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.aop.support.AopUtils;
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
class NewAccountRegistrationOrchestratorIntegrationTest {

    private static final String EMAIL_NAMESPACE = "fh011-slice4d-";
    private static final String EMAIL_NAMESPACE_PATTERN = EMAIL_NAMESPACE + "%@example.test";
    private static final String PASSWORD = "synthetic-slice4d-password-value";
    private static final Instant ISSUED_AT = Instant.parse("2099-01-01T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired private RegistrationPasswordChecks checks;
    @Autowired private PasswordEncodingService encoding;
    @Autowired private VerificationTokenCryptography cryptography;
    @Autowired private ExistingAccountRegistrationWork existingWork;
    @MockitoSpyBean private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext applicationContext;

    @MockitoSpyBean private AccountRepository accounts;
    @MockitoSpyBean private CredentialRepository credentials;
    @MockitoSpyBean private NewAccountRegistrationPersistence persistence;
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
            reset(accounts, credentials, persistence, tokenPersistence, jdbcTemplate);
        }
    }

    @Test
    void createsOneCompleteRegistrationThenPerformsEquivalentWorkForExistingEmail() {
        String email = uniqueEmail();
        List<String> admitted = new CopyOnWriteArrayList<>();
        NewAccountRegistrationOrchestrator orchestrator = orchestrator(admitted::add);

        orchestrator.register(email, PASSWORD, "en-US");
        IdentityState beforeEquivalentWork = identityState();
        AtomicInteger statementCalls = new AtomicInteger();
        AtomicBoolean activeTransaction = new AtomicBoolean();
        AtomicBoolean afterCommit = new AtomicBoolean();
        AtomicInteger completionStatus = new AtomicInteger(-1);
        doAnswer(
                        invocation -> {
                            statementCalls.incrementAndGet();
                            activeTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                            afterCommit.set(true);
                                        }

                                        @Override
                                        public void afterCompletion(int status) {
                                            completionStatus.set(status);
                                        }
                                    });
                            return invocation.callRealMethod();
                        })
                .when(jdbcTemplate)
                .queryForObject(
                        eq(ExistingAccountRegistrationWork.STATEMENT), any(RowMapper.class));
        orchestrator.register(email.toUpperCase(Locale.ROOT), PASSWORD, "en-US");

        assertThat(AopUtils.isAopProxy(existingWork)).isTrue();
        assertThat(statementCalls).hasValue(1);
        assertThat(activeTransaction).isTrue();
        assertThat(afterCommit).isTrue();
        assertThat(completionStatus).hasValue(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(identityState()).isEqualTo(beforeEquivalentWork);
        assertThat(admitted).containsExactly(email, email);
        assertThat(applicationContext.getBeansOfType(NewAccountRegistrationOrchestrator.class))
                .isEmpty();
        assertThat(accountCount(email)).isEqualTo(1L);
        assertThat(credentialCount(email)).isEqualTo(1L);
        assertThat(openTokenCount(email)).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_accounts "
                                        + "WHERE email_normalized = ? AND status = 'PENDING_VERIFICATION' "
                                        + "AND preferred_locale = 'en-US' AND phone IS NULL",
                                Long.class,
                                email))
                .isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_credentials c "
                                        + "JOIN identity_accounts a ON a.id = c.account_id "
                                        + "WHERE a.email_normalized = ? AND c.credential_type = 'PASSWORD' "
                                        + "AND c.secret_hash LIKE '{argon2id}%'",
                                Long.class, email))
                .isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM identity_email_verification_tokens t "
                                        + "JOIN identity_accounts a ON a.id = t.account_id "
                                        + "WHERE a.email_normalized = ? AND t.expires_at = ? "
                                        + "AND octet_length(t.token_digest) = 32 "
                                        + "AND t.terminal_reason IS NULL AND t.terminal_at IS NULL",
                                Long.class,
                                email,
                                java.sql.Timestamp.from(ISSUED_AT.plus(Duration.ofHours(24)))))
                .isEqualTo(1L);
        verify(persistence).persist(any(), any(), any(), any(), any());
    }

    @Test
    void existingNonPendingAccountRemainsUnchanged() {
        String email = uniqueEmail();
        NewAccountRegistrationOrchestrator orchestrator = orchestrator(value -> {});
        orchestrator.register(email, PASSWORD, "en");
        jdbcTemplate.update(
                "UPDATE identity_accounts SET status = 'ACTIVE' WHERE email_normalized = ?", email);

        orchestrator.register(email.toUpperCase(Locale.ROOT), PASSWORD, "en");

        assertThat(accountCount(email)).isEqualTo(1L);
        assertThat(credentialCount(email)).isEqualTo(1L);
        assertThat(tokenCount(email)).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM identity_accounts WHERE email_normalized = ?",
                                String.class,
                                email))
                .isEqualTo("ACTIVE");
    }

    @Test
    void credentialFailureOccursAfterAccountInsertAndRollsBackTheAggregate() {
        String email = uniqueEmail();
        IllegalStateException failure =
                new IllegalStateException("Synthetic credential write failure");
        boolean[] inserted = {false};
        doAnswer(
                        invocation -> {
                            inserted[0] = accountCount(email) == 1L;
                            throw failure;
                        })
                .when(credentials)
                .save(any());

        assertThatThrownBy(() -> orchestrator(value -> {}).register(email, PASSWORD, "en"))
                .isSameAs(failure);
        assertThat(inserted[0]).isTrue();
        assertThat(accountCount(email)).isZero();
        assertThat(credentialCount(email)).isZero();
        assertThat(tokenCount(email)).isZero();
    }

    @Test
    void tokenFailureRollsBackAccountAndCredential() {
        String email = uniqueEmail();
        IllegalStateException failure = new IllegalStateException("Synthetic token write failure");
        doAnswer(
                        invocation -> {
                            throw failure;
                        })
                .when(tokenPersistence)
                .saveInitial(any(), any(), any());

        assertThatThrownBy(() -> orchestrator(value -> {}).register(email, PASSWORD, "en"))
                .isSameAs(failure);
        assertThat(accountCount(email)).isZero();
        assertThat(credentialCount(email)).isZero();
        assertThat(tokenCount(email)).isZero();
    }

    @Test
    void concurrentEquivalentEmailsLeaveOneCompleteAggregate() throws Exception {
        String email = uniqueEmail();
        CountDownLatch atLookup = new CountDownLatch(2);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        List<NewAccountRegistrationPersistence.Result> results = new CopyOnWriteArrayList<>();
        List<byte[]> issuedDigests = new CopyOnWriteArrayList<>();
        VerificationTokenCryptography observedCryptography = spy(cryptography);
        doAnswer(
                        invocation -> {
                            VerificationTokenIssuance issued =
                                    (VerificationTokenIssuance) invocation.callRealMethod();
                            issuedDigests.add(issued.digest());
                            return issued;
                        })
                .when(observedCryptography)
                .issue();
        Answer<?> originalLookup =
                mockingDetails(accounts).getMockCreationSettings().getDefaultAnswer();
        Answer<?> originalSave =
                mockingDetails(accounts).getMockCreationSettings().getDefaultAnswer();
        Answer<?> originalPersist =
                mockingDetails(persistence).getMockCreationSettings().getDefaultAnswer();

        doAnswer(
                        invocation -> {
                            if (email.equals(invocation.getArgument(0))) {
                                atLookup.countDown();
                                await(atLookup);
                                await(releaseLookup);
                                return false;
                            }
                            return originalLookup.answer(invocation);
                        })
                .when(accounts)
                .existsByEmailNormalized(any());
        doAnswer(
                        invocation -> {
                            Account account = invocation.getArgument(0);
                            if (email.equals(account.getEmailNormalized())) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isTrue();
                                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                                jdbcTemplate.execute("SET LOCAL statement_timeout = '20s'");
                            }
                            return originalSave.answer(invocation);
                        })
                .when(accounts)
                .save(any(Account.class));
        doAnswer(
                        invocation -> {
                            NewAccountRegistrationPersistence.Result result =
                                    (NewAccountRegistrationPersistence.Result)
                                            originalPersist.answer(invocation);
                            results.add(result);
                            return result;
                        })
                .when(persistence)
                .persist(any(), any(), any(), any(), any());

        PasswordEncodingService concurrentEncoding = mock(PasswordEncodingService.class);
        when(concurrentEncoding.encodeNormalizedPassword(any()))
                .thenReturn("{argon2id}synthetic-concurrency-encoded-value");
        NewAccountRegistrationOrchestrator orchestrator =
                orchestrator(value -> {}, concurrentEncoding, observedCryptography);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new CopyOnWriteArrayList<>();
        try {
            futures.add(executor.submit(() -> orchestrator.register(email, PASSWORD, "en")));
            futures.add(
                    executor.submit(
                            () ->
                                    orchestrator.register(
                                            email.toUpperCase(Locale.ROOT), PASSWORD, "en")));
            await(atLookup);
            releaseLookup.countDown();
            for (Future<?> future : futures) {
                future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            assertThat(results)
                    .containsExactlyInAnyOrder(
                            NewAccountRegistrationPersistence.Result.CREATED,
                            NewAccountRegistrationPersistence.Result.NO_CHANGE);
            assertThat(issuedDigests).hasSize(2);
            assertThat(Arrays.equals(issuedDigests.get(0), issuedDigests.get(1))).isFalse();
            assertThat(accountCount(email)).isEqualTo(1L);
            assertThat(credentialCount(email)).isEqualTo(1L);
            assertThat(openTokenCount(email)).isEqualTo(1L);
            assertThat(tokenCount(email)).isEqualTo(1L);
        } finally {
            releaseLookup.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            issuedDigests.forEach(digest -> Arrays.fill(digest, (byte) 0));
            if (!executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Registration workers did not terminate");
            }
        }
    }

    private NewAccountRegistrationOrchestrator orchestrator(RegistrationEmailAdmission admission) {
        return orchestrator(admission, encoding);
    }

    private NewAccountRegistrationOrchestrator orchestrator(
            RegistrationEmailAdmission admission, PasswordEncodingService passwordEncoding) {
        return orchestrator(admission, passwordEncoding, cryptography);
    }

    private NewAccountRegistrationOrchestrator orchestrator(
            RegistrationEmailAdmission admission,
            PasswordEncodingService passwordEncoding,
            VerificationTokenCryptography tokenCryptography) {
        return new NewAccountRegistrationOrchestrator(
                checks,
                admission,
                passwordEncoding,
                tokenCryptography,
                accounts,
                existingWork,
                persistence,
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
    }

    private long accountCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_accounts WHERE email_normalized = ?",
                Long.class,
                email);
    }

    private IdentityState identityState() {
        return new IdentityState(
                jdbcTemplate.queryForList(
                        "SELECT md5(row_to_json(a)::text) FROM identity_accounts a ORDER BY a.id",
                        String.class),
                jdbcTemplate.queryForList(
                        "SELECT md5(row_to_json(c)::text) FROM identity_credentials c ORDER BY c.id",
                        String.class),
                jdbcTemplate.queryForList(
                        "SELECT md5(row_to_json(t)::text) "
                                + "FROM identity_email_verification_tokens t ORDER BY t.id",
                        String.class));
    }

    private record IdentityState(
            List<String> accounts, List<String> credentials, List<String> tokens) {}

    private long credentialCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_credentials c "
                        + "JOIN identity_accounts a ON a.id = c.account_id "
                        + "WHERE a.email_normalized = ?",
                Long.class,
                email);
    }

    private long tokenCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_email_verification_tokens t "
                        + "JOIN identity_accounts a ON a.id = t.account_id "
                        + "WHERE a.email_normalized = ?",
                Long.class,
                email);
    }

    private long openTokenCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_email_verification_tokens t "
                        + "JOIN identity_accounts a ON a.id = t.account_id "
                        + "WHERE a.email_normalized = ? AND t.terminal_reason IS NULL "
                        + "AND t.terminal_at IS NULL",
                Long.class,
                email);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("Registration coordination timed out");
        }
    }

    private static String uniqueEmail() {
        return EMAIL_NAMESPACE + UUID.randomUUID() + "@example.test";
    }
}
