package com.fixhub.platform.identity.internal.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fixhub.platform.FixhubCoreApplication;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.core.ApplicationModules;

class VerificationTokenSecurityConfigurationTest {

    @Test
    void factoryCreatesServiceBackedByOneOrdinarySecureRandom() throws Exception {
        VerificationTokenSecurityConfiguration configuration =
                new VerificationTokenSecurityConfiguration();
        VerificationTokenCryptography cryptography = configuration.verificationTokenCryptography();

        Field sourceField =
                VerificationTokenCryptography.class.getDeclaredField("randomByteSource");
        sourceField.setAccessible(true);
        Object source = sourceField.get(cryptography);
        Field secureRandomField = SecureRandomByteSource.class.getDeclaredField("secureRandom");
        secureRandomField.setAccessible(true);
        Object secureRandom = secureRandomField.get(source);

        assertTrue(
                source.getClass() == SecureRandomByteSource.class,
                "Production service used an unexpected random-byte source");
        assertTrue(
                SecureRandom.class.isAssignableFrom(secureRandom.getClass()),
                "Production random-byte source was not SecureRandom-backed");
        assertTrue(
                Arrays.stream(SecureRandomByteSource.class.getDeclaredFields())
                                .filter(field -> field.getType() == SecureRandom.class)
                                .count()
                        == 1,
                "Production random-byte source did not own exactly one SecureRandom");
    }

    @Test
    void exposesOnlyTheCryptographyServiceAsASpringBean() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(
                        VerificationTokenSecurityConfiguration.class)) {
            assertTrue(
                    context.getBeansOfType(VerificationTokenCryptography.class).size() == 1,
                    "Cryptography service bean count was unexpected");
            assertTrue(
                    context.getBeansOfType(RandomByteSource.class).isEmpty(),
                    "RandomByteSource was exposed as an application bean");
            assertTrue(
                    context.getBeansOfType(SecureRandom.class).isEmpty(),
                    "SecureRandom was exposed as an application bean");
        }
    }

    @Test
    void configurationHasOnePackagePrivateBeanFactoryAndNoProperties() {
        Method[] beanMethods =
                Arrays.stream(VerificationTokenSecurityConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Bean.class))
                        .toArray(Method[]::new);

        assertTrue(beanMethods.length == 1, "Configuration did not declare exactly one bean");
        assertTrue(
                beanMethods[0].getReturnType() == VerificationTokenCryptography.class,
                "Configuration exposed an unexpected bean type");
        assertTrue(
                !java.lang.reflect.Modifier.isPublic(beanMethods[0].getModifiers()),
                "Cryptography bean factory was public");
        assertTrue(
                !java.lang.reflect.Modifier.isPublic(
                        VerificationTokenSecurityConfiguration.class.getModifiers()),
                "Cryptography configuration was public");
        assertTrue(
                VerificationTokenSecurityConfiguration.class.isAnnotationPresent(
                        Configuration.class),
                "Cryptography configuration annotation was missing");
        assertTrue(
                !VerificationTokenSecurityConfiguration.class.isAnnotationPresent(
                        ConfigurationProperties.class),
                "Cryptographic parameters were configurable");
    }

    @Test
    void productionSourcesContainNoForbiddenRandomnessOrConfiguration() throws Exception {
        String cryptography =
                Files.readString(
                        Path.of(
                                "src/main/java/com/fixhub/platform/identity/internal/verification/VerificationTokenCryptography.java"));
        String configuration =
                Files.readString(
                        Path.of(
                                "src/main/java/com/fixhub/platform/identity/internal/verification/VerificationTokenSecurityConfiguration.java"));
        String sources = cryptography + configuration;

        assertTrue(!sources.contains("getInstanceStrong"), "Strong blocking random API was used");
        assertTrue(!sources.contains("ThreadLocalRandom"), "ThreadLocalRandom was used");
        assertTrue(!sources.contains("UUID"), "UUID-based token generation was used");
        assertTrue(
                !sources.contains("currentTimeMillis"),
                "Timestamp-based token generation was used");
        assertTrue(!sources.contains("nanoTime"), "Timestamp-based token generation was used");
        assertTrue(!sources.contains("setSeed("), "Production randomness used an explicit seed");
        assertTrue(!sources.contains("ConfigurationProperties"), "Crypto properties were added");
        assertTrue(
                !sources.contains("java.util.Random;") && !sources.contains("new Random("),
                "Non-cryptographic Random was used");
    }

    @Test
    void remainsInsideClosedIdentityModule() {
        assertTrue(
                VerificationTokenCryptography.class
                        .getPackageName()
                        .startsWith("com.fixhub.platform.identity.internal."),
                "Cryptography service escaped Identity internals");
        assertTrue(
                VerificationTokenIssuance.class
                        .getPackageName()
                        .startsWith("com.fixhub.platform.identity.internal."),
                "Issuance wrapper escaped Identity internals");
        assertTrue(
                VerificationTokenSecurityConfiguration.class
                        .getPackageName()
                        .startsWith("com.fixhub.platform.identity.internal."),
                "Cryptography configuration escaped Identity internals");
        ApplicationModules.of(FixhubCoreApplication.class).verify();
    }
}
