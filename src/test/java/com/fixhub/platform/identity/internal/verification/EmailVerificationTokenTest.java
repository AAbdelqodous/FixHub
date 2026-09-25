package com.fixhub.platform.identity.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixhub.platform.identity.internal.account.Account;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailVerificationTokenTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TERMINAL_AT = Instant.parse("2026-09-24T12:00:00Z");

    @Test
    void createsOpenTokenWithDefensivelyCopiedDigest() throws ReflectiveOperationException {
        byte[] digest = digest(7);
        EmailVerificationToken token = EmailVerificationToken.create(newAccount(), digest, EXPIRY);
        digest[0] = 99;

        assertThat(token.isOpen()).isTrue();
        assertThat(token.terminalReason()).isNull();
        assertThat(token.terminalAt()).isNull();
        assertThat(readVersion(token)).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 31, 33, 64})
    void rejectsNullOrInvalidDigestLength(Integer length) {
        byte[] digest = length == null ? null : new byte[length];

        assertThatThrownBy(() -> EmailVerificationToken.create(newAccount(), digest, EXPIRY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("\u0000");
    }

    @Test
    void rejectsNullAccount() {
        assertThatThrownBy(() -> EmailVerificationToken.create(null, digest(1), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account is required");
    }

    @Test
    void rejectsNullExpiry() {
        assertThatThrownBy(() -> EmailVerificationToken.create(newAccount(), digest(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expiry timestamp is required");
    }

    @Test
    void supportsEachApprovedTerminalTransition() {
        EmailVerificationToken consumed = openToken();
        consumed.consume(TERMINAL_AT);
        assertTerminal(consumed, EmailVerificationTokenTerminalReason.CONSUMED);

        EmailVerificationToken superseded = openToken();
        superseded.supersede(TERMINAL_AT);
        assertTerminal(superseded, EmailVerificationTokenTerminalReason.SUPERSEDED);

        EmailVerificationToken expired = openToken();
        expired.expire(TERMINAL_AT);
        assertTerminal(expired, EmailVerificationTokenTerminalReason.EXPIRED);

        EmailVerificationToken invalidated = openToken();
        invalidated.invalidate(TERMINAL_AT);
        assertTerminal(invalidated, EmailVerificationTokenTerminalReason.INVALIDATED);
    }

    @Test
    void rejectsNullTransitionTimestamp() {
        EmailVerificationToken token = openToken();

        assertThatThrownBy(() -> token.consume(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal timestamp is required");
        assertThat(token.isOpen()).isTrue();
    }

    @Test
    void rejectsRepeatAndCrossTerminalTransitions() {
        EmailVerificationToken token = openToken();
        token.consume(TERMINAL_AT);

        assertThatThrownBy(() -> token.consume(TERMINAL_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.supersede(TERMINAL_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.expire(TERMINAL_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.invalidate(TERMINAL_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(token.terminalReason()).isEqualTo(EmailVerificationTokenTerminalReason.CONSUMED);
        assertThat(token.terminalAt()).isEqualTo(TERMINAL_AT);
    }

    @Test
    void declaresExactlyFourTerminalReasons() {
        assertThat(EmailVerificationTokenTerminalReason.values())
                .containsExactly(
                        EmailVerificationTokenTerminalReason.CONSUMED,
                        EmailVerificationTokenTerminalReason.SUPERSEDED,
                        EmailVerificationTokenTerminalReason.EXPIRED,
                        EmailVerificationTokenTerminalReason.INVALIDATED);
    }

    @Test
    void hasNoRawTokenSurfaceOrSecretBearingDiagnostics() {
        assertThat(
                        Arrays.stream(EmailVerificationToken.class.getDeclaredFields())
                                .map(Field::getName))
                .doesNotContain("rawToken", "token", "bearerToken");
        assertThat(
                        Arrays.stream(EmailVerificationToken.class.getDeclaredFields())
                                .map(Field::getType))
                .doesNotContain(String.class);
        assertThat(Arrays.stream(EmailVerificationToken.class.getMethods()).map(Method::getName))
                .doesNotContain("getTokenDigest", "getRawToken", "getBearerToken");
        assertThat(
                        Arrays.stream(EmailVerificationToken.class.getDeclaredMethods())
                                .map(Method::getName))
                .doesNotContain("toString", "equals", "hashCode");
        assertThat(
                        Arrays.stream(EmailVerificationToken.class.getDeclaredMethods())
                                .filter(method -> method.getName().startsWith("set")))
                .noneMatch(method -> Modifier.isPublic(method.getModifiers()));
        assertThat(openToken().toString()).doesNotContain(Arrays.toString(digest(7)));
    }

    @Test
    void accountHasNoTokenCollection() {
        assertThat(
                        Arrays.stream(Account.class.getDeclaredFields())
                                .map(field -> field.getGenericType().getTypeName()))
                .noneMatch(type -> type.contains(EmailVerificationToken.class.getName()));
    }

    private static void assertTerminal(
            EmailVerificationToken token, EmailVerificationTokenTerminalReason reason) {
        assertThat(token.isOpen()).isFalse();
        assertThat(token.terminalReason()).isEqualTo(reason);
        assertThat(token.terminalAt()).isEqualTo(TERMINAL_AT);
    }

    private static EmailVerificationToken openToken() {
        return EmailVerificationToken.create(newAccount(), digest(7), EXPIRY);
    }

    private static Account newAccount() {
        return Account.create("token-owner@example.com", null, "en");
    }

    private static byte[] digest(int start) {
        byte[] digest = new byte[32];
        for (int index = 0; index < digest.length; index++) {
            digest[index] = (byte) (start + index);
        }
        return digest;
    }

    private static long readVersion(EmailVerificationToken token)
            throws ReflectiveOperationException {
        Field field = EmailVerificationToken.class.getDeclaredField("version");
        field.setAccessible(true);
        return field.getLong(token);
    }
}
