package com.fixhub.platform.identity.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.core.BaseConnection;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationTokenRepositoryIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");
    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();
    private static final AtomicLong DIGEST_SEQUENCE = new AtomicLong();

    @Autowired private AccountRepository accountRepository;

    @Autowired private EmailVerificationTokenRepository tokenRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private javax.sql.DataSource dataSource;

    @Test
    void flywayAppliesV1ThroughV4InOrderAndHibernateValidates() {
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT version FROM flyway_schema_history ORDER BY installed_rank",
                                String.class))
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void schemaHasExactlyTheApprovedColumnsAndTypes() {
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name, data_type, udt_name, is_nullable,
                               character_maximum_length, datetime_precision,
                               is_identity, identity_generation
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'identity_email_verification_tokens'
                        ORDER BY ordinal_position
                        """);

        assertThat(columns)
                .extracting(row -> row.get("column_name"))
                .containsExactly(
                        "id",
                        "account_id",
                        "token_digest",
                        "expires_at",
                        "terminal_reason",
                        "terminal_at",
                        "version",
                        "created_at",
                        "updated_at");
        assertColumn(columns, "id", "bigint", "int8", "NO", null, null, "YES", "BY DEFAULT");
        assertColumn(columns, "account_id", "bigint", "int8", "NO", null, null, "NO", null);
        assertColumn(columns, "token_digest", "bytea", "bytea", "NO", null, null, "NO", null);
        assertColumn(
                columns,
                "expires_at",
                "timestamp with time zone",
                "timestamptz",
                "NO",
                null,
                6,
                "NO",
                null);
        assertColumn(
                columns,
                "terminal_reason",
                "character varying",
                "varchar",
                "YES",
                16,
                null,
                "NO",
                null);
        assertColumn(
                columns,
                "terminal_at",
                "timestamp with time zone",
                "timestamptz",
                "YES",
                null,
                6,
                "NO",
                null);
        assertColumn(columns, "version", "bigint", "int8", "NO", null, null, "NO", null);
        assertColumn(
                columns,
                "created_at",
                "timestamp with time zone",
                "timestamptz",
                "NO",
                null,
                6,
                "NO",
                null);
        assertColumn(
                columns,
                "updated_at",
                "timestamp with time zone",
                "timestamptz",
                "NO",
                null,
                6,
                "NO",
                null);
    }

    @Test
    void schemaHasEveryApprovedConstraint() {
        assertThat(
                        jdbcTemplate.queryForList(
                                """
                                SELECT conname
                                FROM pg_constraint
                                WHERE conrelid = 'identity_email_verification_tokens'::regclass
                                  AND conname NOT LIKE 'identity_email_verification_tokens_%_not_null'
                                ORDER BY conname
                                """,
                                String.class))
                .containsExactlyInAnyOrder(
                        "pk_identity_email_verification_tokens",
                        "fk_identity_email_verification_tokens_account",
                        "uq_identity_email_verification_tokens_digest",
                        "ck_identity_email_verification_tokens_digest_length",
                        "ck_identity_email_verification_tokens_terminal_reason",
                        "ck_identity_email_verification_tokens_terminal_pair",
                        "ck_identity_email_verification_tokens_expiry_after_creation",
                        "ck_identity_email_verification_tokens_version_non_negative");
    }

    @Test
    void schemaHasEveryApprovedIndexAndPredicate() {
        List<Map<String, Object>> indexes =
                jdbcTemplate.queryForList(
                        """
                        SELECT indexname, indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'identity_email_verification_tokens'
                        """);

        assertThat(indexes)
                .extracting(row -> row.get("indexname"))
                .containsExactlyInAnyOrder(
                        "pk_identity_email_verification_tokens",
                        "uq_identity_email_verification_tokens_digest",
                        "uq_identity_email_verification_tokens_open_account",
                        "ix_identity_email_verification_tokens_open_expires_at",
                        "ix_identity_email_verification_tokens_terminal_at");
        assertIndex(
                indexes,
                "uq_identity_email_verification_tokens_open_account",
                true,
                "(account_id)",
                "terminal_reason IS NULL");
        assertIndex(
                indexes,
                "ix_identity_email_verification_tokens_open_expires_at",
                false,
                "(expires_at, id)",
                "terminal_reason IS NULL");
        assertIndex(
                indexes,
                "ix_identity_email_verification_tokens_terminal_at",
                false,
                "(terminal_at, id)",
                "terminal_reason IS NOT NULL");
    }

    @Test
    void persistsAndQueriesOnlyA32ByteDigest() {
        Account account = savedAccount();
        byte[] digest = uniqueDigest();
        EmailVerificationToken saved =
                tokenRepository.save(EmailVerificationToken.create(account, digest, EXPIRY));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT token_digest FROM identity_email_verification_tokens WHERE id = ?",
                                byte[].class,
                                saved.getId()))
                .containsExactly(digest);
        assertThat(tokenRepository.findByTokenDigest(digest)).isPresent();
        assertThat(tokenRepository.findOpenByAccountId(account.getId()).orElseThrow().getId())
                .isEqualTo(saved.getId());
    }

    @Test
    void diagnosticHardeningRemovesDigestDetailButRetainsSafeConstraintIdentity(
            CapturedOutput capturedOutput) throws Exception {
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT current_setting('log_error_verbosity')", String.class))
                .isEqualTo("terse");

        Account first = savedAccount();
        Account second = savedAccount();
        byte[] digest = uniqueDigest();
        tokenRepository.save(EmailVerificationToken.create(first, digest, EXPIRY));

        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                tokenRepository.save(
                                        EmailVerificationToken.create(second, digest, EXPIRY)));
        assertThat(exception).isNotNull();
        assertConstraintIdentity(exception, "uq_identity_email_verification_tokens_digest");
        PSQLException sqlException = (PSQLException) findCause(exception, PSQLException.class);
        assertThat(sqlException.getSQLState()).isEqualTo("23505");
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            BaseConnection baseConnection = connection.unwrap(BaseConnection.class);
            assertThat(baseConnection.getLogServerErrorDetail()).isFalse();
        }

        StringWriter renderedStackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(renderedStackTrace));
        assertSafeDiagnostics(
                digest,
                exception.getMessage(),
                exception.getLocalizedMessage(),
                exception.toString(),
                renderedStackTrace.toString(),
                capturedOutput.toString());
    }

    @Test
    void applicationDataSourceHasNoPrivilegedInitializationStatement() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getConnectionInitSql()).isNull();
    }

    @Test
    void rejectsWrongDigestLengthsAtThePostgresqlBoundary() {
        Account account = savedAccount();
        assertConstraint(
                "ck_identity_email_verification_tokens_digest_length",
                insertSql(),
                account.getId(),
                new byte[31],
                EXPIRY,
                null,
                null,
                0L);
        assertConstraint(
                "ck_identity_email_verification_tokens_digest_length",
                insertSql(),
                account.getId(),
                new byte[33],
                EXPIRY,
                null,
                null,
                0L);
    }

    @Test
    void rejectsDuplicateDigestAndAllowsDifferentAccountsToHaveOpenTokens() {
        Account first = savedAccount();
        Account second = savedAccount();
        byte[] digest = uniqueDigest();
        tokenRepository.save(EmailVerificationToken.create(first, digest, EXPIRY));

        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                tokenRepository.save(
                                        EmailVerificationToken.create(second, digest, EXPIRY)));
        assertThat(exception).isNotNull();
        assertConstraintIdentity(exception, "uq_identity_email_verification_tokens_digest");
        assertThat(
                        tokenRepository.save(
                                EmailVerificationToken.create(second, uniqueDigest(), EXPIRY)))
                .isNotNull();
    }

    @Test
    void enforcesOneOpenTokenPerAccountAndAllowsAReplacementAfterTerminalization() {
        Account account = savedAccount();
        byte[] firstDigest = uniqueDigest();
        byte[] secondDigest = uniqueDigest();
        EmailVerificationToken first =
                tokenRepository.save(EmailVerificationToken.create(account, firstDigest, EXPIRY));

        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                tokenRepository.save(
                                        EmailVerificationToken.create(
                                                account, secondDigest, EXPIRY)));
        assertThat(exception).isNotNull();
        assertConstraintIdentity(exception, "uq_identity_email_verification_tokens_open_account");

        first.expire(Instant.parse("2026-09-24T01:00:00Z"));
        tokenRepository.save(first);
        assertThat(
                        tokenRepository.save(
                                EmailVerificationToken.create(account, secondDigest, EXPIRY)))
                .isNotNull();
    }

    @Test
    void rejectsInvalidTerminalValuesAndMismatchedPairs() {
        Account account = savedAccount();
        byte[] invalidReasonDigest = uniqueDigest();
        byte[] terminalPairDigest = uniqueDigest();
        byte[] openPairDigest = uniqueDigest();
        assertConstraint(
                "ck_identity_email_verification_tokens_terminal_reason",
                insertSql(),
                account.getId(),
                invalidReasonDigest,
                EXPIRY,
                "UNKNOWN",
                CREATED_AT,
                0L);
        assertConstraint(
                "ck_identity_email_verification_tokens_terminal_pair",
                insertSql(),
                account.getId(),
                terminalPairDigest,
                EXPIRY,
                "CONSUMED",
                null,
                0L);
        assertConstraint(
                "ck_identity_email_verification_tokens_terminal_pair",
                insertSql(),
                account.getId(),
                openPairDigest,
                EXPIRY,
                null,
                CREATED_AT,
                0L);
    }

    @Test
    void rejectsInvalidExpiryVersionAndMissingAccount() {
        Account account = savedAccount();
        byte[] expiryDigest = uniqueDigest();
        byte[] versionDigest = uniqueDigest();
        byte[] missingAccountDigest = uniqueDigest();
        assertConstraint(
                "ck_identity_email_verification_tokens_expiry_after_creation",
                insertSql(),
                account.getId(),
                expiryDigest,
                CREATED_AT,
                null,
                null,
                0L);
        assertConstraint(
                "ck_identity_email_verification_tokens_expiry_after_creation",
                insertSql(),
                account.getId(),
                uniqueDigest(),
                CREATED_AT.minusNanos(1),
                null,
                null,
                0L);
        assertConstraint(
                "ck_identity_email_verification_tokens_version_non_negative",
                insertSql(),
                account.getId(),
                versionDigest,
                EXPIRY,
                null,
                null,
                -1L);
        assertConstraint(
                "fk_identity_email_verification_tokens_account",
                insertSql(),
                Long.MAX_VALUE,
                missingAccountDigest,
                EXPIRY,
                null,
                null,
                0L);
    }

    @Test
    void restrictsAccountDeletionAndRepositoryOpenQueryExcludesTerminalRows() {
        Account account = savedAccount();
        byte[] terminalDigest = uniqueDigest();
        byte[] openDigest = uniqueDigest();
        EmailVerificationToken terminal =
                tokenRepository.save(
                        EmailVerificationToken.create(account, terminalDigest, EXPIRY));
        terminal.invalidate(CREATED_AT);
        tokenRepository.save(terminal);
        EmailVerificationToken open =
                tokenRepository.save(EmailVerificationToken.create(account, openDigest, EXPIRY));

        assertThat(tokenRepository.findOpenByAccountId(account.getId()).orElseThrow().getId())
                .isEqualTo(open.getId());
        assertThat(
                        catchThrowableOfType(
                                DataIntegrityViolationException.class,
                                () ->
                                        jdbcTemplate.update(
                                                "DELETE FROM identity_accounts WHERE id = ?",
                                                account.getId())))
                .rootCause()
                .isInstanceOfSatisfying(
                        PSQLException.class,
                        cause ->
                                assertThat(cause.getServerErrorMessage().getConstraint())
                                        .isEqualTo(
                                                "fk_identity_email_verification_tokens_account"));
    }

    @Test
    void callerMutationCannotAlterPersistedDigest() {
        Account account = savedAccount();
        byte[] original = uniqueDigest();
        byte[] expected = original.clone();
        EmailVerificationToken token =
                tokenRepository.save(EmailVerificationToken.create(account, original, EXPIRY));
        original[0] = 120;

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT token_digest FROM identity_email_verification_tokens WHERE id = ?",
                                byte[].class,
                                token.getId()))
                .containsExactly(expected);
        assertThat(tokenRepository.findByTokenDigest(original)).isEmpty();
    }

    private Account savedAccount() {
        return accountRepository.save(
                Account.create(
                        "token-integration-" + ACCOUNT_SEQUENCE.incrementAndGet() + "@example.com",
                        null,
                        "en"));
    }

    private void assertConstraint(
            String constraint,
            String sql,
            long accountId,
            byte[] digest,
            Instant expiresAt,
            String terminalReason,
            Instant terminalAt,
            long version) {
        DataIntegrityViolationException exception =
                catchThrowableOfType(
                        DataIntegrityViolationException.class,
                        () ->
                                jdbcTemplate.update(
                                        sql,
                                        accountId,
                                        digest,
                                        timestamp(expiresAt),
                                        terminalReason,
                                        timestamp(terminalAt),
                                        version));
        assertThat(exception).isNotNull();
        assertConstraintIdentity(exception, constraint);
    }

    private static void assertColumn(
            List<Map<String, Object>> columns,
            String name,
            String dataType,
            String udtName,
            String nullable,
            Integer characterLength,
            Integer datetimePrecision,
            String identity,
            String generation) {
        Map<String, Object> row =
                columns.stream()
                        .filter(column -> column.get("column_name").equals(name))
                        .findFirst()
                        .orElseThrow();
        assertThat(row.get("data_type")).isEqualTo(dataType);
        assertThat(row.get("udt_name")).isEqualTo(udtName);
        assertThat(row.get("is_nullable")).isEqualTo(nullable);
        assertThat(row.get("character_maximum_length")).isEqualTo(characterLength);
        assertThat(row.get("datetime_precision")).isEqualTo(datetimePrecision);
        assertThat(row.get("is_identity")).isEqualTo(identity);
        assertThat(row.get("identity_generation")).isEqualTo(generation);
    }

    private static void assertConstraintIdentity(
            DataIntegrityViolationException exception, String expectedConstraint) {
        Throwable cause = findCause(exception, PSQLException.class);
        assertThat(cause).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) cause).getServerErrorMessage().getConstraint())
                .isEqualTo(expectedConstraint);
    }

    private static Throwable findCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null && !type.isInstance(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static void assertSafeDiagnostics(byte[] digest, String... diagnostics) {
        String knownDigest = HexFormat.of().formatHex(digest);
        for (String diagnostic : diagnostics) {
            String value = diagnostic == null ? "" : diagnostic;
            assertThat(value.contains(knownDigest))
                    .as("diagnostics must not contain the known digest")
                    .isFalse();
            assertThat(value.matches("(?is).*\\\\x[0-9a-f]{64}.*"))
                    .as("diagnostics must not contain a bytea digest")
                    .isFalse();
        }
    }

    private String insertSql() {
        return """
                INSERT INTO identity_email_verification_tokens
                    (account_id, token_digest, expires_at, terminal_reason, terminal_at,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
    }

    private static void assertIndex(
            List<Map<String, Object>> indexes,
            String name,
            boolean unique,
            String columns,
            String predicate) {
        String definition =
                indexes.stream()
                        .filter(row -> row.get("indexname").equals(name))
                        .map(row -> (String) row.get("indexdef"))
                        .findFirst()
                        .orElseThrow();
        assertThat(definition).contains(unique ? "CREATE UNIQUE INDEX" : "CREATE INDEX");
        assertThat(definition).contains(columns).contains(predicate);
    }

    private static byte[] digest(int start) {
        byte[] digest = new byte[32];
        for (int index = 0; index < digest.length; index++) {
            digest[index] = (byte) (start + index);
        }
        return digest;
    }

    private static byte[] uniqueDigest() {
        long sequence = DIGEST_SEQUENCE.incrementAndGet();
        byte[] digest = new byte[32];
        digest[0] = 0x7A;
        for (int index = 1; index < digest.length; index++) {
            digest[index] = (byte) (sequence + index);
        }
        return digest;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
