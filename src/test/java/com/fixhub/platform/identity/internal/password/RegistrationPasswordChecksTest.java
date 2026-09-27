package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class RegistrationPasswordChecksTest {

    private static final String RAW = "synthetic-e\u0301-password";
    private static final String NORMALIZED = "synthetic-é-password";

    @Test
    void normalizesBeforeCheckingTheCompletePassword() {
        PasswordBlocklist blocklist = mock(PasswordBlocklist.class);
        RegistrationPasswordChecks checks = new RegistrationPasswordChecks(blocklist);

        assertThat(checks.normalizeValidateAndCheck(RAW)).isEqualTo(NORMALIZED);
        verify(blocklist).assertNotCompromised(NORMALIZED);
    }

    @Test
    void rejectsMalformedAndShortPasswordsBeforeTheBlocklist() {
        PasswordBlocklist blocklist = mock(PasswordBlocklist.class);
        RegistrationPasswordChecks checks = new RegistrationPasswordChecks(blocklist);

        assertThatThrownBy(() -> checks.normalizeValidateAndCheck("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checks.normalizeValidateAndCheck("synthetic-password\uD800"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(blocklist, never()).assertNotCompromised("short");
    }

    @Test
    void propagatesACompleteValueBlocklistRejectionWithoutRenderingThePassword() {
        PasswordBlocklist blocklist = mock(PasswordBlocklist.class);
        RegistrationPasswordChecks checks = new RegistrationPasswordChecks(blocklist);
        org.mockito.Mockito.doThrow(new CompromisedPasswordException())
                .when(blocklist)
                .assertNotCompromised(NORMALIZED);

        assertThatThrownBy(() -> checks.normalizeValidateAndCheck(RAW))
                .isInstanceOf(CompromisedPasswordException.class)
                .hasMessageNotContaining(RAW)
                .hasMessageNotContaining(NORMALIZED);
    }
}
