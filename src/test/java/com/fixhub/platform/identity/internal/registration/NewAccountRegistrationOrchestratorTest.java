package com.fixhub.platform.identity.internal.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.fixhub.platform.identity.internal.password.PasswordEncodingService;
import com.fixhub.platform.identity.internal.password.RegistrationPasswordChecks;
import com.fixhub.platform.identity.internal.verification.VerificationTokenCryptography;
import com.fixhub.platform.identity.internal.verification.VerificationTokenIssuance;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class NewAccountRegistrationOrchestratorTest {

    private static final String EMAIL = "Slice4D@Example.test";
    private static final String NORMALIZED_EMAIL = "slice4d@example.test";
    private static final String RAW_PASSWORD = "synthetic-e\u0301-password";
    private static final String NORMALIZED_PASSWORD = "synthetic-é-password";
    private static final String ENCODED = "{argon2id}synthetic-encoded-value";
    private static final Instant ISSUED_AT = Instant.parse("2099-01-01T00:00:00Z");

    private final RegistrationPasswordChecks checks = mock(RegistrationPasswordChecks.class);
    private final RegistrationEmailAdmission emailAdmission =
            mock(RegistrationEmailAdmission.class);
    private final PasswordEncodingService encoding = mock(PasswordEncodingService.class);
    private final VerificationTokenCryptography cryptography =
            mock(VerificationTokenCryptography.class);
    private final VerificationTokenIssuance issuance = mock(VerificationTokenIssuance.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ExistingAccountRegistrationWork existingWork =
            mock(ExistingAccountRegistrationWork.class);
    private final NewAccountRegistrationPersistence persistence =
            mock(NewAccountRegistrationPersistence.class);
    private final Clock clock = mock(Clock.class);

    private final NewAccountRegistrationOrchestrator orchestrator =
            new NewAccountRegistrationOrchestrator(
                    checks,
                    emailAdmission,
                    encoding,
                    cryptography,
                    accounts,
                    existingWork,
                    persistence,
                    clock);

    private byte[] digest;

    @BeforeEach
    void setUp() {
        digest = new byte[32];
        Arrays.fill(digest, (byte) 7);
        when(checks.normalizeValidateAndCheck(RAW_PASSWORD)).thenReturn(NORMALIZED_PASSWORD);
        when(encoding.encodeNormalizedPassword(NORMALIZED_PASSWORD)).thenReturn(ENCODED);
        when(cryptography.issue()).thenReturn(issuance);
        when(clock.instant()).thenReturn(ISSUED_AT);
        when(issuance.digest()).thenReturn(digest);
    }

    @Test
    void performsAdmissionBeforeEncodingAndLooksUpOnlyAfterIssuanceAndOneClockRead() {
        byte[][] persistedDigest = new byte[1][];
        doAnswer(
                        invocation -> {
                            persistedDigest[0] = ((byte[]) invocation.getArgument(3)).clone();
                            return NewAccountRegistrationPersistence.Result.CREATED;
                        })
                .when(persistence)
                .persist(eq(EMAIL), eq("en"), eq(ENCODED), any(), eq(ISSUED_AT.plusSeconds(86400)));

        orchestrator.register(EMAIL, RAW_PASSWORD, "en");

        InOrder order =
                inOrder(
                        checks,
                        emailAdmission,
                        encoding,
                        cryptography,
                        clock,
                        issuance,
                        accounts,
                        persistence);
        order.verify(checks).normalizeValidateAndCheck(RAW_PASSWORD);
        order.verify(emailAdmission).admit(NORMALIZED_EMAIL);
        order.verify(encoding).encodeNormalizedPassword(NORMALIZED_PASSWORD);
        order.verify(cryptography).issue();
        order.verify(clock).instant();
        order.verify(issuance).digest();
        order.verify(accounts).existsByEmailNormalized(NORMALIZED_EMAIL);
        order.verify(persistence)
                .persist(eq(EMAIL), eq("en"), eq(ENCODED), any(), eq(ISSUED_AT.plusSeconds(86400)));
        verify(clock).instant();
        assertThat(persistedDigest[0]).containsOnly((byte) 7);
        assertThat(digest).containsOnly((byte) 0);
        verify(issuance, never()).publicToken();
    }

    @Test
    void existingAccountCompletesTheSamePreparationAndEquivalentWork() {
        when(accounts.existsByEmailNormalized(NORMALIZED_EMAIL)).thenReturn(true);

        orchestrator.register(EMAIL, RAW_PASSWORD, "en");

        verify(checks).normalizeValidateAndCheck(RAW_PASSWORD);
        verify(emailAdmission).admit(NORMALIZED_EMAIL);
        verify(encoding).encodeNormalizedPassword(NORMALIZED_PASSWORD);
        verify(cryptography).issue();
        verify(existingWork).execute();
        verifyNoInteractions(persistence);
        assertThat(digest).containsOnly((byte) 0);
    }

    @Test
    void emailAdmissionRejectionStopsBeforeArgon2AndStateAccess() {
        IllegalStateException failure = new IllegalStateException("Synthetic admission rejection");
        org.mockito.Mockito.doThrow(failure).when(emailAdmission).admit(NORMALIZED_EMAIL);

        assertThatThrownBy(() -> orchestrator.register(EMAIL, RAW_PASSWORD, "en"))
                .isSameAs(failure);
        verifyNoInteractions(encoding, cryptography, accounts, existingWork, persistence, clock);
    }

    @Test
    void policyRejectionStopsBeforeEmailAdmission() {
        IllegalArgumentException failure =
                new IllegalArgumentException("Synthetic policy rejection");
        when(checks.normalizeValidateAndCheck(RAW_PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> orchestrator.register(EMAIL, RAW_PASSWORD, "en"))
                .isSameAs(failure);
        verifyNoInteractions(
                emailAdmission, encoding, cryptography, accounts, existingWork, persistence);
    }

    @Test
    void encodingSaturationStopsBeforeIssuanceAndLookup() {
        IllegalStateException failure = new IllegalStateException("Synthetic capacity rejection");
        when(encoding.encodeNormalizedPassword(NORMALIZED_PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> orchestrator.register(EMAIL, RAW_PASSWORD, "en"))
                .isSameAs(failure);
        verifyNoInteractions(cryptography, accounts, existingWork, persistence, clock);
    }

    @Test
    void lookupFailureRethrowsAndClearsTheWorkingDigest() {
        IllegalStateException failure = new IllegalStateException("Synthetic lookup failure");
        when(accounts.existsByEmailNormalized(NORMALIZED_EMAIL)).thenThrow(failure);

        assertThatThrownBy(() -> orchestrator.register(EMAIL, RAW_PASSWORD, "en"))
                .isSameAs(failure);
        assertThat(digest).containsOnly((byte) 0);
        verifyNoInteractions(existingWork, persistence);
    }

    @Test
    void mandatoryEmailAdmissionCannotBeOmitted() {
        assertThatThrownBy(
                        () ->
                                new NewAccountRegistrationOrchestrator(
                                        checks,
                                        null,
                                        encoding,
                                        cryptography,
                                        accounts,
                                        existingWork,
                                        persistence,
                                        clock))
                .isInstanceOf(NullPointerException.class);
    }
}
