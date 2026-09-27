package com.fixhub.platform.identity.internal.registration;

import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.credential.Credential;
import com.fixhub.platform.identity.internal.credential.CredentialType;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

@Component
public final class NewAccountRegistrationPersistence {

    private static final String EMAIL_UNIQUENESS_CONSTRAINT =
            "uq_identity_accounts_email_normalized";

    private final NewAccountRegistrationWriter writer;

    NewAccountRegistrationPersistence(NewAccountRegistrationWriter writer) {
        this.writer = writer;
    }

    public Result persist(
            String email,
            String preferredLocale,
            String encodedCredential,
            byte[] tokenDigest,
            Instant tokenExpiresAt) {
        RegistrationWrite write =
                RegistrationWrite.create(
                        email, preferredLocale, encodedCredential, tokenDigest, tokenExpiresAt);
        try {
            writer.persist(write);
            return Result.CREATED;
        } catch (RuntimeException failure) {
            if (hasEmailUniquenessConstraint(failure)) {
                return Result.NO_CHANGE;
            }
            throw failure;
        } finally {
            write.clearDigest();
        }
    }

    private static boolean hasEmailUniquenessConstraint(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && EMAIL_UNIQUENESS_CONSTRAINT.equals(
                            constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public enum Result {
        CREATED,
        NO_CHANGE
    }
}

@FunctionalInterface
interface NewAccountRegistrationWriter {

    void persist(RegistrationWrite write);
}

final class RegistrationWrite {

    private static final int TOKEN_DIGEST_LENGTH = 32;
    private static final String ARGON2ID_PREFIX = "{argon2id}";

    private final Account account;
    private final Credential credential;
    private final byte[] tokenDigest;
    private final Instant tokenExpiresAt;

    private RegistrationWrite(
            Account account, Credential credential, byte[] tokenDigest, Instant tokenExpiresAt) {
        this.account = account;
        this.credential = credential;
        this.tokenDigest = tokenDigest;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    static RegistrationWrite create(
            String email,
            String preferredLocale,
            String encodedCredential,
            byte[] tokenDigest,
            Instant tokenExpiresAt) {
        if (email == null || !email.equals(email.trim())) {
            throw new IllegalArgumentException("Registration email must be valid and trimmed");
        }
        if (encodedCredential == null || !encodedCredential.startsWith(ARGON2ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "Registration credential must use the required encoding");
        }
        if (tokenDigest == null || tokenDigest.length != TOKEN_DIGEST_LENGTH) {
            throw new IllegalArgumentException("Verification token digest has invalid format");
        }
        if (tokenExpiresAt == null) {
            throw new IllegalArgumentException("Verification token expiry is required");
        }

        Account account = Account.create(email, null, preferredLocale);
        Credential credential =
                Credential.create(account, CredentialType.PASSWORD, encodedCredential);
        return new RegistrationWrite(account, credential, tokenDigest.clone(), tokenExpiresAt);
    }

    Account account() {
        return account;
    }

    Credential credential() {
        return credential;
    }

    byte[] tokenDigest() {
        return tokenDigest.clone();
    }

    Instant tokenExpiresAt() {
        return tokenExpiresAt;
    }

    void clearDigest() {
        Arrays.fill(tokenDigest, (byte) 0);
    }

    @Override
    public String toString() {
        return "RegistrationWrite[REDACTED]";
    }
}
