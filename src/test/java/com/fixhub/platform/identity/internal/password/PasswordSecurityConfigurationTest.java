package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordSecurityConfigurationTest {

    private static final String SYNTHETIC_PASSWORD = "synthetic-password-value-only-for-tests";

    @Test
    void configuresOnlyTheApprovedArgon2idEncoderAtTheApprovedBaseline() {
        PasswordEncoder encoder = PasswordSecurityConfiguration.createPasswordEncoder(properties());

        String encoded = encoder.encode(SYNTHETIC_PASSWORD);

        assertThat(encoded.startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$")).isTrue();
        assertThat(encoder.matches(SYNTHETIC_PASSWORD, encoded)).isTrue();
    }

    @Test
    void appliesHigherConfiguredParametersToTheArgon2Encoding() {
        PasswordEncoder encoder =
                PasswordSecurityConfiguration.createPasswordEncoder(
                        properties(20, 48, 2, 20_000, 3));

        String encoded = encoder.encode(SYNTHETIC_PASSWORD);
        String[] segments = encoded.split("\\$", -1);

        assertThat(encoded.startsWith("{argon2id}$argon2id$")).isTrue();
        assertThat(segments.length == 6).isTrue();
        assertThat(segments[3].equals("m=20000,t=3,p=2")).isTrue();
        assertThat(Base64.getDecoder().decode(segments[4]).length == 20).isTrue();
        assertThat(Base64.getDecoder().decode(segments[5]).length == 48).isTrue();
        assertThat(encoder.matches(SYNTHETIC_PASSWORD, encoded)).isTrue();
    }

    @Test
    void invalidIdentifiersAndCredentialsFailClosedWithoutFallbackAlgorithms() {
        PasswordEncoder encoder = PasswordSecurityConfiguration.createPasswordEncoder(properties());

        assertThat(failsClosed(encoder, null)).isTrue();
        assertThat(failsClosed(encoder, "encoded-without-identifier")).isTrue();
        assertThat(failsClosed(encoder, "{argon2id")).isTrue();
        assertThat(failsClosed(encoder, "{}value")).isTrue();
        assertThat(failsClosed(encoder, "{unknown}value")).isTrue();
        for (String identifier : new String[] {"bcrypt", "scrypt", "pbkdf2", "noop", "plaintext"}) {
            assertThat(failsClosed(encoder, "{" + identifier + "}value")).isTrue();
        }
    }

    private static boolean failsClosed(PasswordEncoder encoder, String encodedCredential) {
        try {
            return !encoder.matches(SYNTHETIC_PASSWORD, encodedCredential);
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private static IdentityPasswordProperties properties() {
        return properties(16, 32, 1, 19_456, 2);
    }

    private static IdentityPasswordProperties properties(
            int saltBytes, int hashBytes, int parallelism, int memoryKib, int iterations) {
        return new IdentityPasswordProperties(
                new IdentityPasswordProperties.Argon2(
                        saltBytes,
                        hashBytes,
                        parallelism,
                        memoryKib,
                        iterations,
                        new IdentityPasswordProperties.Admission(1, 1)));
    }
}
