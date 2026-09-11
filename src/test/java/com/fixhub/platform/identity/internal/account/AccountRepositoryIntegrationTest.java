package com.fixhub.platform.identity.internal.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fixhub.platform.TestcontainersConfiguration;
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
class AccountRepositoryIntegrationTest {

    @Autowired private AccountRepository accountRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistsQueriesAndAuditsAccount() throws InterruptedException {
        Account saved =
                accountRepository.save(
                        Account.create(" Person+tag@Example.COM ", "+96550000101", "es-ES"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getVersion()).isZero();
        Account foundByEmail =
                accountRepository.findByEmailNormalized("person+tag@example.com").orElseThrow();
        assertThat(foundByEmail.getId()).isEqualTo(saved.getId());
        assertThat(foundByEmail.getEmail()).isEqualTo("Person+tag@Example.COM");
        assertThat(accountRepository.existsByEmailNormalized("person+tag@example.com")).isTrue();
        assertThat(accountRepository.existsByPhone("+96550000101")).isTrue();

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        Instant originalCreatedAt = reloaded.getCreatedAt();
        Instant originalUpdatedAt = reloaded.getUpdatedAt();
        Thread.sleep(5);
        reloaded.changePreferredLocale("it-IT");
        accountRepository.save(reloaded);

        Account updated = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getPreferredLocale()).isEqualTo("it-IT");
    }

    @Test
    void rejectsDuplicateNormalizedEmail() {
        accountRepository.save(Account.create("Case@Test.example", null, "en"));

        assertThatThrownBy(
                        () ->
                                accountRepository.save(
                                        Account.create("case@test.example", null, "en")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicatePhoneButAllowsMultipleNullPhones() {
        accountRepository.save(Account.create("phone-one@example.com", "+96550000102", "en"));
        accountRepository.save(Account.create("phone-null-one@example.com", null, "en"));
        accountRepository.save(Account.create("phone-null-two@example.com", null, "en"));

        assertThatThrownBy(
                        () ->
                                accountRepository.save(
                                        Account.create(
                                                "phone-two@example.com", "+96550000102", "en")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsStaleConcurrentUpdate() {
        Account saved =
                accountRepository.save(Account.create("locking-account@example.com", null, "en"));
        Account first = accountRepository.findById(saved.getId()).orElseThrow();
        Account stale = accountRepository.findById(saved.getId()).orElseThrow();

        first.changePreferredLocale("fr");
        accountRepository.save(first);
        stale.changePreferredLocale("de");

        assertThatThrownBy(() -> accountRepository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void databaseChecksRejectInvalidAccountValues() {
        assertInvalidAccount("blank@example.com", "blank@example.com", null, " ", "ACTIVE", 0);
        assertInvalidAccount(
                "phone@example.com", "phone@example.com", "50000000", "en", "ACTIVE", 0);
        assertInvalidAccount("status@example.com", "status@example.com", null, "en", "UNKNOWN", 0);
        assertInvalidAccount(
                "version@example.com", "version@example.com", null, "en", "ACTIVE", -1);
    }

    @Test
    void databaseChecksRejectBlankEmailValues() {
        assertConstraintViolation("ck_identity_accounts_email_not_blank", " ", "email@example.com");
        assertConstraintViolation(
                "ck_identity_accounts_email_normalized_not_blank", "email@example.com", " ");
    }

    @Test
    void repositoryExposesExactlyTheApprovedApi() {
        assertThat(AccountRepository.class.getGenericInterfaces())
                .singleElement()
                .satisfies(
                        repositoryType ->
                                assertThat(repositoryType.getTypeName())
                                        .isEqualTo(
                                                Repository.class.getName()
                                                        + "<"
                                                        + Account.class.getName()
                                                        + ", java.lang.Long>"));

        assertThat(
                        Arrays.stream(AccountRepository.class.getDeclaredMethods())
                                .map(AccountRepositoryIntegrationTest::methodSignature))
                .containsExactlyInAnyOrder(
                        Account.class.getName() + " save(" + Account.class.getName() + ")",
                        "java.util.Optional<"
                                + Account.class.getName()
                                + "> findById(java.lang.Long)",
                        "java.util.Optional<"
                                + Account.class.getName()
                                + "> findByEmailNormalized(java.lang.String)",
                        "boolean existsByEmailNormalized(java.lang.String)",
                        "boolean existsByPhone(java.lang.String)");
    }

    @Test
    void flywayAppliedAccountMigration() {
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT version FROM flyway_schema_history WHERE version = '2'",
                                String.class))
                .containsExactly("2");
    }

    private void assertInvalidAccount(
            String email,
            String normalizedEmail,
            String phone,
            String locale,
            String status,
            long version) {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO identity_accounts (
                                            email, email_normalized, phone, preferred_locale, status,
                                            version, created_at, updated_at
                                        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                        """,
                                        email,
                                        normalizedEmail,
                                        phone,
                                        locale,
                                        status,
                                        version))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertConstraintViolation(
            String constraintName, String email, String normalizedEmail) {
        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO identity_accounts (
                                            email, email_normalized, preferred_locale, status,
                                            version, created_at, updated_at
                                        ) VALUES (?, ?, 'en', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                        """,
                                        email,
                                        normalizedEmail));

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
