package com.fixhub.platform.identity.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CredentialRepositoryIntegrationTest {

    @Autowired private AccountRepository accountRepository;

    @Autowired private CredentialRepository credentialRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistsQueriesUpdatesAndAuditsCredential() throws InterruptedException {
        Account account = savedAccount("credential-persistence@example.com");
        Credential saved =
                credentialRepository.save(
                        Credential.create(
                                account,
                                CredentialType.PASSWORD,
                                "{synthetic-v1}initial-encoded-material"));

        assertThat(saved.getId()).isInstanceOf(Long.class).isPositive();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getVersion()).isZero();
        assertThat(
                        credentialRepository.existsByAccountIdAndCredentialType(
                                account.getId(), CredentialType.PASSWORD))
                .isTrue();

        Credential found =
                credentialRepository
                        .findByAccountIdAndCredentialType(account.getId(), CredentialType.PASSWORD)
                        .orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getAccount().getId()).isEqualTo(account.getId());
        assertThat(found.getCredentialType()).isEqualTo(CredentialType.PASSWORD);
        Instant originalCreatedAt = found.getCreatedAt();
        Instant originalUpdatedAt = found.getUpdatedAt();

        Thread.sleep(5);
        String replacement = "{replacement-scheme}updated-encoded-material";
        found.replaceSecretHash(replacement);
        credentialRepository.save(found);

        Credential updated =
                credentialRepository
                        .findByAccountIdAndCredentialType(account.getId(), CredentialType.PASSWORD)
                        .orElseThrow();
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT secret_hash FROM identity_credentials WHERE id = ?",
                                String.class,
                                updated.getId()))
                .isEqualTo(replacement);
    }

    @Test
    void rejectsStaleConcurrentUpdate() {
        Account account = savedAccount("credential-locking@example.com");
        Credential saved =
                credentialRepository.save(
                        Credential.create(
                                account,
                                CredentialType.PASSWORD,
                                "{synthetic-v1}locking-encoded-material"));
        Credential first =
                credentialRepository
                        .findByAccountIdAndCredentialType(account.getId(), CredentialType.PASSWORD)
                        .orElseThrow();
        Credential stale =
                credentialRepository
                        .findByAccountIdAndCredentialType(account.getId(), CredentialType.PASSWORD)
                        .orElseThrow();
        assertThat(first).isNotSameAs(stale);

        first.replaceSecretHash("{synthetic-v2}first-update-material");
        credentialRepository.save(first);
        stale.replaceSecretHash("{synthetic-v2}stale-update-material");

        assertThatThrownBy(() -> credentialRepository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void rejectsSecondPasswordCredentialForSameAccount() {
        Account account = savedAccount("credential-duplicate@example.com");
        credentialRepository.save(
                Credential.create(
                        account, CredentialType.PASSWORD, "{synthetic-v1}first-encoded-material"));

        assertThatThrownBy(
                        () ->
                                credentialRepository.save(
                                        Credential.create(
                                                account,
                                                CredentialType.PASSWORD,
                                                "{synthetic-v2}second-encoded-material")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsPasswordCredentialForDifferentAccounts() {
        Account firstAccount = savedAccount("credential-first-owner@example.com");
        Account secondAccount = savedAccount("credential-second-owner@example.com");

        Credential first =
                credentialRepository.save(
                        Credential.create(
                                firstAccount,
                                CredentialType.PASSWORD,
                                "{synthetic-v1}first-owner-material"));
        Credential second =
                credentialRepository.save(
                        Credential.create(
                                secondAccount,
                                CredentialType.PASSWORD,
                                "{synthetic-v1}second-owner-material"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void rejectsCredentialForMissingAccount() {
        assertCredentialConstraintViolation(
                "fk_identity_credentials_account",
                Long.MAX_VALUE,
                "PASSWORD",
                "{synthetic-v1}missing-account-material",
                0);
    }

    @Test
    void restrictsAccountDeletionWhileCredentialExists() {
        Account account = savedAccount("credential-delete-restrict@example.com");
        credentialRepository.save(
                Credential.create(
                        account,
                        CredentialType.PASSWORD,
                        "{synthetic-v1}delete-restrict-material"));

        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                jdbcTemplate.update(
                                        "DELETE FROM identity_accounts WHERE id = ?",
                                        account.getId()));

        assertPostgresConstraint(exception, "fk_identity_credentials_account");
    }

    @Test
    void databaseChecksRejectInvalidCredentialValues() {
        Account account = savedAccount("credential-database-checks@example.com");

        assertCredentialConstraintViolation(
                "ck_identity_credentials_type",
                account.getId(),
                "UNKNOWN",
                "{synthetic-v1}valid-encoded-material",
                0);
        assertCredentialConstraintViolation(
                "ck_identity_credentials_secret_format",
                account.getId(),
                "PASSWORD",
                "synthetic-malformed-material",
                0);
        assertCredentialConstraintViolation(
                "ck_identity_credentials_secret_format", account.getId(), "PASSWORD", " ", 0);
        assertCredentialConstraintViolation(
                "ck_identity_credentials_version_non_negative",
                account.getId(),
                "PASSWORD",
                "{synthetic-v1}valid-encoded-material",
                -1);
    }

    @Test
    void repositoryExposesExactlyTheApprovedApi() {
        assertThat(CredentialRepository.class.getGenericInterfaces())
                .singleElement()
                .satisfies(
                        repositoryType ->
                                assertThat(repositoryType.getTypeName())
                                        .isEqualTo(
                                                Repository.class.getName()
                                                        + "<"
                                                        + Credential.class.getName()
                                                        + ", java.lang.Long>"));

        assertThat(
                        Arrays.stream(CredentialRepository.class.getDeclaredMethods())
                                .map(CredentialRepositoryIntegrationTest::methodSignature))
                .containsExactlyInAnyOrder(
                        Credential.class.getName() + " save(" + Credential.class.getName() + ")",
                        "java.util.Optional<"
                                + Credential.class.getName()
                                + "> findByAccountIdAndCredentialType(java.lang.Long, "
                                + CredentialType.class.getName()
                                + ")",
                        "boolean existsByAccountIdAndCredentialType(java.lang.Long, "
                                + CredentialType.class.getName()
                                + ")");
    }

    @Test
    void flywayAppliedAccountAndCredentialMigrations() {
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT version FROM flyway_schema_history WHERE version IN ('2', '3') ORDER BY installed_rank",
                                String.class))
                .containsExactly("2", "3");
    }

    private Account savedAccount(String email) {
        return accountRepository.save(Account.create(email, null, "en"));
    }

    private void assertCredentialConstraintViolation(
            String constraintName,
            long accountId,
            String credentialType,
            String secretHash,
            long version) {
        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO identity_credentials (
                                            account_id, credential_type, secret_hash, version,
                                            created_at, updated_at
                                        ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                        """,
                                        accountId,
                                        credentialType,
                                        secretHash,
                                        version));

        assertPostgresConstraint(exception, constraintName);
    }

    private static void assertPostgresConstraint(
            DataIntegrityViolationException exception, String constraintName) {
        assertThat(exception)
                .rootCause()
                .isInstanceOfSatisfying(
                        PSQLException.class,
                        cause ->
                                assertThat(cause.getServerErrorMessage().getConstraint())
                                        .isEqualTo(constraintName));
    }

    private static String methodSignature(java.lang.reflect.Method method) {
        return method.getGenericReturnType().getTypeName()
                + " "
                + method.getName()
                + "("
                + Arrays.stream(method.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName)
                        .collect(java.util.stream.Collectors.joining(", "))
                + ")";
    }
}
