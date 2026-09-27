package com.fixhub.platform.identity.internal.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class NewAccountRegistrationPersistenceTest {

    private static final String EMAIL = "registration@example.com";
    private static final String LOCALE = "en-US";
    private static final String ENCODED_CREDENTIAL =
            "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final Instant EXPIRY = Instant.parse("2099-01-02T00:00:00Z");

    @Test
    void suppliesValidatedRegistrationStateToTheWriter() {
        byte[] callerDigest = digest(1);
        byte[] expectedDigest = callerDigest.clone();
        AtomicReference<RegistrationWrite> captured = new AtomicReference<>();
        NewAccountRegistrationPersistence persistence =
                new NewAccountRegistrationPersistence(captured::set);

        NewAccountRegistrationPersistence.Result result =
                persistence.persist(EMAIL, LOCALE, ENCODED_CREDENTIAL, callerDigest, EXPIRY);

        RegistrationWrite write = captured.get();
        assertThat(result).isEqualTo(NewAccountRegistrationPersistence.Result.CREATED);
        assertThat(write.account().getEmail().equals(EMAIL)).isTrue();
        assertThat(write.account().getEmailNormalized().equals(EMAIL)).isTrue();
        assertThat(write.account().getPhone()).isNull();
        assertThat(write.account().getPreferredLocale().equals(LOCALE)).isTrue();
        assertThat(write.account().getStatus().name().equals("PENDING_VERIFICATION")).isTrue();
        assertThat(write.credential().getAccount() == write.account()).isTrue();
        assertThat(write.credential().getCredentialType().name().equals("PASSWORD")).isTrue();
        assertThat(write.tokenExpiresAt().equals(EXPIRY)).isTrue();
        assertThat(MessageDigest.isEqual(write.tokenDigest(), new byte[32])).isTrue();
        assertThat(MessageDigest.isEqual(callerDigest, expectedDigest)).isTrue();
    }

    @Test
    void defensivelyCopiesTheDigestBeforeInvokingTheWriter() {
        byte[] callerDigest = digest(3);
        byte[] expectedDigest = callerDigest.clone();
        AtomicReference<Boolean> owned = new AtomicReference<>(false);
        NewAccountRegistrationPersistence persistence =
                new NewAccountRegistrationPersistence(
                        write -> {
                            callerDigest[0] ^= 0x7f;
                            owned.set(MessageDigest.isEqual(write.tokenDigest(), expectedDigest));
                            byte[] returned = write.tokenDigest();
                            returned[1] ^= 0x7f;
                            owned.set(
                                    owned.get()
                                            && MessageDigest.isEqual(
                                                    write.tokenDigest(), expectedDigest));
                        });

        persistence.persist(EMAIL, LOCALE, ENCODED_CREDENTIAL, callerDigest, EXPIRY);

        assertThat(owned.get()).isTrue();
    }

    @Test
    void classifiesTheExactStructuredEmailConstraintInAFiniteCauseChain() {
        AtomicInteger calls = new AtomicInteger();
        NewAccountRegistrationPersistence persistence =
                new NewAccountRegistrationPersistence(
                        write -> {
                            calls.incrementAndGet();
                            RuntimeException failure =
                                    integrityFailure("uq_identity_accounts_email_normalized");
                            for (int index = 0; index < 128; index++) {
                                failure = new IllegalStateException(failure);
                            }
                            throw failure;
                        });

        NewAccountRegistrationPersistence.Result result =
                persistence.persist(EMAIL, LOCALE, ENCODED_CREDENTIAL, digest(5), EXPIRY);

        assertThat(result).isEqualTo(NewAccountRegistrationPersistence.Result.NO_CHANGE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rethrowsSelfReferentialCause() {
        CyclicFailure failure = new CyclicFailure();
        failure.next = failure;

        assertRethrown(failure);
    }

    @Test
    void rethrowsMultiObjectCauseCycle() {
        CyclicFailure first = new CyclicFailure();
        CyclicFailure second = new CyclicFailure();
        first.next = second;
        second.next = first;

        assertRethrown(first);
    }

    @Test
    void rethrowsMessageOnlyConstraintName() {
        assertRethrown(
                new DataIntegrityViolationException("uq_identity_accounts_email_normalized"));
    }

    @Test
    void rethrowsAnotherStructuredConstraint() {
        assertRethrown(integrityFailure("uq_identity_email_verification_tokens_digest"));
    }

    @Test
    void rejectsInputsOutsideTheApprovedBoundaryBeforeWriting() {
        AtomicInteger calls = new AtomicInteger();
        NewAccountRegistrationPersistence persistence =
                new NewAccountRegistrationPersistence(write -> calls.incrementAndGet());

        assertRejected(persistence, null, LOCALE, ENCODED_CREDENTIAL, digest(8), EXPIRY);
        assertRejected(
                persistence,
                " registration@example.com ",
                LOCALE,
                ENCODED_CREDENTIAL,
                digest(9),
                EXPIRY);
        assertRejected(persistence, EMAIL, "en-us", ENCODED_CREDENTIAL, digest(10), EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, null, digest(11), EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, "{bcrypt}value", digest(12), EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, ENCODED_CREDENTIAL, null, EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, ENCODED_CREDENTIAL, new byte[31], EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, ENCODED_CREDENTIAL, new byte[33], EXPIRY);
        assertRejected(persistence, EMAIL, LOCALE, ENCODED_CREDENTIAL, digest(13), null);
        assertThat(calls.get()).isZero();
    }

    @Test
    void keepsTransactionOwnershipOnTheSeparateWriter() throws Exception {
        assertThat(
                        NewAccountRegistrationPersistence.class
                                .getMethod(
                                        "persist",
                                        String.class,
                                        String.class,
                                        String.class,
                                        byte[].class,
                                        Instant.class)
                                .isAnnotationPresent(Transactional.class))
                .isFalse();
        assertThat(
                        TransactionalNewAccountRegistrationWriter.class
                                .getMethod("persist", RegistrationWrite.class)
                                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void registrationWriteHasOnlyARedactedRepresentation() {
        RegistrationWrite write =
                RegistrationWrite.create(EMAIL, LOCALE, ENCODED_CREDENTIAL, digest(14), EXPIRY);

        String rendering = write.toString();

        assertThat(rendering.equals("RegistrationWrite[REDACTED]")).isTrue();
        assertThat(rendering.contains(EMAIL)).isFalse();
        assertThat(rendering.contains(ENCODED_CREDENTIAL)).isFalse();
    }

    private static void assertRejected(
            NewAccountRegistrationPersistence persistence,
            String email,
            String locale,
            String encodedCredential,
            byte[] tokenDigest,
            Instant expiry) {
        assertThatThrownBy(
                        () ->
                                persistence.persist(
                                        email, locale, encodedCredential, tokenDigest, expiry))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(
                        failure -> {
                            String message = failure.getMessage();
                            assertThat(message == null || !message.contains(EMAIL)).isTrue();
                            assertThat(message == null || !message.contains(ENCODED_CREDENTIAL))
                                    .isTrue();
                        });
    }

    private static DataIntegrityViolationException integrityFailure(String constraintName) {
        ConstraintViolationException constraintViolation =
                new ConstraintViolationException(
                        "Persistence constraint failed",
                        new SQLException(),
                        "insert",
                        constraintName);
        return new DataIntegrityViolationException("Persistence failed", constraintViolation);
    }

    private static void assertRethrown(RuntimeException failure) {
        NewAccountRegistrationPersistence persistence =
                new NewAccountRegistrationPersistence(
                        write -> {
                            throw failure;
                        });

        assertThatThrownBy(
                        () ->
                                persistence.persist(
                                        EMAIL, LOCALE, ENCODED_CREDENTIAL, digest(6), EXPIRY))
                .isSameAs(failure);
    }

    private static final class CyclicFailure extends RuntimeException {

        private Throwable next;

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }

    private static byte[] digest(int start) {
        byte[] digest = new byte[32];
        for (int index = 0; index < digest.length; index++) {
            digest[index] = (byte) (start + index);
        }
        return digest;
    }
}
