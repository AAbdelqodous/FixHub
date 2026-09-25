package com.fixhub.platform.identity.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixhub.platform.FixhubCoreApplication;
import com.fixhub.platform.identity.internal.account.Account;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.modulith.core.ApplicationModules;

class EmailVerificationTokenRepositorySurfaceTest {

    @Test
    void extendsOnlyTheNarrowRepositoryAndDeclaresExactlyThreeMethods() {
        assertThat(EmailVerificationTokenRepository.class.getGenericInterfaces())
                .singleElement()
                .satisfies(
                        type ->
                                assertThat(type.getTypeName())
                                        .isEqualTo(
                                                Repository.class.getName()
                                                        + "<"
                                                        + EmailVerificationToken.class.getName()
                                                        + ", java.lang.Long>"));
        assertThat(
                        Arrays.stream(EmailVerificationTokenRepository.class.getDeclaredMethods())
                                .map(this::signature))
                .containsExactlyInAnyOrder(
                        EmailVerificationToken.class.getName()
                                + " save("
                                + EmailVerificationToken.class.getName()
                                + ")",
                        Optional.class.getName()
                                + "<"
                                + EmailVerificationToken.class.getName()
                                + "> findOpenByAccountId(java.lang.Long)",
                        Optional.class.getName()
                                + "<"
                                + EmailVerificationToken.class.getName()
                                + "> findByTokenDigest(byte[])");
    }

    @Test
    void lookupMethodsUseExplicitQueriesAndParameters() throws NoSuchMethodException {
        Method open =
                EmailVerificationTokenRepository.class.getDeclaredMethod(
                        "findOpenByAccountId", Long.class);
        Method digest =
                EmailVerificationTokenRepository.class.getDeclaredMethod(
                        "findByTokenDigest", byte[].class);

        assertThat(open.getAnnotation(Query.class).value())
                .contains("token.account.id = :accountId", "token.terminalReason is null");
        assertThat(digest.getAnnotation(Query.class).value())
                .contains("token.tokenDigest = :tokenDigest");
        assertThat(open.getParameters()[0].getAnnotation(Param.class).value())
                .isEqualTo("accountId");
        assertThat(digest.getParameters()[0].getAnnotation(Param.class).value())
                .isEqualTo("tokenDigest");
    }

    @Test
    void remainsInsideClosedIdentityAndDoesNotAddAccountTokenCollection() {
        assertThat(EmailVerificationToken.class.getPackageName())
                .isEqualTo("com.fixhub.platform.identity.internal.verification");
        assertThat(EmailVerificationTokenRepository.class.getPackageName())
                .isEqualTo("com.fixhub.platform.identity.internal.verification");
        assertThat(
                        Arrays.stream(Account.class.getDeclaredFields())
                                .map(field -> field.getGenericType().getTypeName()))
                .noneMatch(type -> type.contains("EmailVerificationToken"));
        ApplicationModules.of(FixhubCoreApplication.class).verify();
    }

    private String signature(Method method) {
        return method.getGenericReturnType().getTypeName()
                + " "
                + method.getName()
                + "("
                + Arrays.stream(method.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName)
                        .collect(Collectors.joining(", "))
                + ")";
    }
}
