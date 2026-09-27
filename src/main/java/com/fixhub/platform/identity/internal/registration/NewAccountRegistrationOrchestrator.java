package com.fixhub.platform.identity.internal.registration;

import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.fixhub.platform.identity.internal.password.PasswordEncodingService;
import com.fixhub.platform.identity.internal.password.RegistrationPasswordChecks;
import com.fixhub.platform.identity.internal.verification.VerificationTokenCryptography;
import com.fixhub.platform.identity.internal.verification.VerificationTokenIssuance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Unwired until real email admission and after-commit delivery are available. */
final class NewAccountRegistrationOrchestrator {

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    private final RegistrationPasswordChecks passwordChecks;
    private final RegistrationEmailAdmission emailAdmission;
    private final PasswordEncodingService passwordEncoding;
    private final VerificationTokenCryptography tokenCryptography;
    private final AccountRepository accountRepository;
    private final ExistingAccountRegistrationWork existingAccountWork;
    private final NewAccountRegistrationPersistence persistence;
    private final Clock clock;

    NewAccountRegistrationOrchestrator(
            RegistrationPasswordChecks passwordChecks,
            RegistrationEmailAdmission emailAdmission,
            PasswordEncodingService passwordEncoding,
            VerificationTokenCryptography tokenCryptography,
            AccountRepository accountRepository,
            ExistingAccountRegistrationWork existingAccountWork,
            NewAccountRegistrationPersistence persistence,
            Clock clock) {
        this.passwordChecks = Objects.requireNonNull(passwordChecks);
        this.emailAdmission = Objects.requireNonNull(emailAdmission);
        this.passwordEncoding = Objects.requireNonNull(passwordEncoding);
        this.tokenCryptography = Objects.requireNonNull(tokenCryptography);
        this.accountRepository = Objects.requireNonNull(accountRepository);
        this.existingAccountWork = Objects.requireNonNull(existingAccountWork);
        this.persistence = Objects.requireNonNull(persistence);
        this.clock = Objects.requireNonNull(clock);
    }

    void register(String email, String rawPassword, String persistedLocale) {
        Account candidate = Account.create(email, null, persistedLocale);
        String normalizedPassword = passwordChecks.normalizeValidateAndCheck(rawPassword);
        emailAdmission.admit(candidate.getEmailNormalized());
        String encodedCredential = passwordEncoding.encodeNormalizedPassword(normalizedPassword);

        VerificationTokenIssuance issuance = tokenCryptography.issue();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);
        byte[] digest = issuance.digest();
        try {
            if (accountRepository.existsByEmailNormalized(candidate.getEmailNormalized())) {
                existingAccountWork.execute();
            } else {
                persistence.persist(
                        candidate.getEmail(),
                        persistedLocale,
                        encodedCredential,
                        digest,
                        expiresAt);
            }
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }
}

@FunctionalInterface
interface RegistrationEmailAdmission {

    void admit(String normalizedEmail);
}
