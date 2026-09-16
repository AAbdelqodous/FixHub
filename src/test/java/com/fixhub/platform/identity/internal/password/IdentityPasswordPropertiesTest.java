package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdentityPasswordPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PasswordSecurityConfiguration.class);

    @Test
    void bindsTheApprovedDevelopmentValues() {
        contextRunner
                .withPropertyValues(validProperties())
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            IdentityPasswordProperties properties =
                                    context.getBean(IdentityPasswordProperties.class);
                            assertThat(properties.argon2().saltBytes() == 16).isTrue();
                            assertThat(properties.argon2().hashBytes() == 32).isTrue();
                            assertThat(properties.argon2().parallelism() == 1).isTrue();
                            assertThat(properties.argon2().memoryKib() == 19_456).isTrue();
                            assertThat(properties.argon2().iterations() == 2).isTrue();
                            assertThat(properties.argon2().admission().maxConcurrency() == 1)
                                    .isTrue();
                            assertThat(properties.argon2().admission().retryAfterSeconds() == 1)
                                    .isTrue();
                        });
    }

    @Test
    void rejectsMissingRequiredConfiguration() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsValuesAboveEverySecurityBaseline() {
        contextRunner
                .withPropertyValues(properties(20, 48, 2, 20_000, 3, 2, 3_600))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            IdentityPasswordProperties properties =
                                    context.getBean(IdentityPasswordProperties.class);
                            assertThat(properties.argon2().saltBytes() == 20).isTrue();
                            assertThat(properties.argon2().hashBytes() == 48).isTrue();
                            assertThat(properties.argon2().parallelism() == 2).isTrue();
                            assertThat(properties.argon2().memoryKib() == 20_000).isTrue();
                            assertThat(properties.argon2().iterations() == 3).isTrue();
                            assertThat(properties.argon2().admission().maxConcurrency() == 2)
                                    .isTrue();
                            assertThat(properties.argon2().admission().retryAfterSeconds() == 3_600)
                                    .isTrue();
                        });
    }

    @Test
    void rejectsEverySecurityBaselineLoweringAndInvalidAdmissionValue() {
        assertRejected("fixhub.identity.password.argon2.salt-bytes=15");
        assertRejected("fixhub.identity.password.argon2.hash-bytes=31");
        assertRejected("fixhub.identity.password.argon2.parallelism=0");
        assertRejected("fixhub.identity.password.argon2.memory-kib=19455");
        assertRejected("fixhub.identity.password.argon2.iterations=1");
        assertRejected("fixhub.identity.password.argon2.admission.max-concurrency=0");
        assertRejected("fixhub.identity.password.argon2.admission.max-concurrency=-1");
        assertRejected("fixhub.identity.password.argon2.admission.retry-after-seconds=0");
        assertRejected("fixhub.identity.password.argon2.admission.retry-after-seconds=3601");
    }

    private void assertRejected(String invalidProperty) {
        contextRunner
                .withPropertyValues(withInvalidProperty(invalidProperty))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] withInvalidProperty(String invalidProperty) {
        String[] properties = validProperties();
        for (int index = 0; index < properties.length; index++) {
            if (properties[index]
                    .substring(0, properties[index].indexOf('='))
                    .equals(invalidProperty.substring(0, invalidProperty.indexOf('=')))) {
                properties[index] = invalidProperty;
                return properties;
            }
        }
        throw new IllegalArgumentException("Unknown test property");
    }

    private static String[] validProperties() {
        return properties(16, 32, 1, 19_456, 2, 1, 1);
    }

    private static String[] properties(
            int saltBytes,
            int hashBytes,
            int parallelism,
            int memoryKib,
            int iterations,
            int maximumConcurrency,
            int retryAfterSeconds) {
        return new String[] {
            "fixhub.identity.password.argon2.salt-bytes=" + saltBytes,
            "fixhub.identity.password.argon2.hash-bytes=" + hashBytes,
            "fixhub.identity.password.argon2.parallelism=" + parallelism,
            "fixhub.identity.password.argon2.memory-kib=" + memoryKib,
            "fixhub.identity.password.argon2.iterations=" + iterations,
            "fixhub.identity.password.argon2.admission.max-concurrency=" + maximumConcurrency,
            "fixhub.identity.password.argon2.admission.retry-after-seconds=" + retryAfterSeconds
        };
    }
}
