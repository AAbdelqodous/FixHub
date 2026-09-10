package com.fixhub.platform.identity.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixhub.platform.identity.internal.account.Account;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialTest {

    private static final String SYNTHETIC_ENCODED_SECRET =
            "{synthetic-v1}encoded-material-for-testing";

    @Test
    void createsPasswordCredentialFromEncodedMaterial() throws ReflectiveOperationException {
        Account account = newAccount();

        Credential credential =
                Credential.create(account, CredentialType.PASSWORD, SYNTHETIC_ENCODED_SECRET);

        assertThat(credential.getAccount()).isSameAs(account);
        assertThat(credential.getCredentialType()).isEqualTo(CredentialType.PASSWORD);
        assertThat(readSecretHash(credential)).isEqualTo(SYNTHETIC_ENCODED_SECRET);
        assertThat(credential.getVersion()).isZero();
    }

    @Test
    void requiresAccount() {
        assertThatThrownBy(
                        () ->
                                Credential.create(
                                        null, CredentialType.PASSWORD, SYNTHETIC_ENCODED_SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresCredentialType() {
        assertThatThrownBy(() -> Credential.create(newAccount(), null, SYNTHETIC_ENCODED_SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "   ",
                "synthetic-plaintext",
                "{}encoded-material",
                "{   }encoded-material",
                "{synthetic-v1}",
                "{synthetic-v1}   ",
                "{synthetic-v1",
                "synthetic-v1}encoded-material",
                "{{synthetic-v1}encoded-material"
            })
    void rejectsBlankPlaintextOrMalformedEncodedMaterial(String secretHash) {
        assertThatThrownBy(
                        () -> Credential.create(newAccount(), CredentialType.PASSWORD, secretHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encoded secret must use the required format");
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic-v1", "future.algorithm-42", "vendor/custom"})
    void acceptsRepresentativeAlgorithmIdentifiers(String algorithmIdentifier)
            throws ReflectiveOperationException {
        String secretHash = "{" + algorithmIdentifier + "}encoded-material";

        Credential credential =
                Credential.create(newAccount(), CredentialType.PASSWORD, secretHash);

        assertThat(readSecretHash(credential)).isEqualTo(secretHash);
    }

    @Test
    void acceptsEncodedMaterialAtColumnCapacity() throws ReflectiveOperationException {
        String prefix = "{synthetic-boundary}";
        String secretHash = prefix + "x".repeat(512 - prefix.length());

        assertThat(secretHash).hasSize(512);
        assertThat(
                        readSecretHash(
                                Credential.create(
                                        newAccount(), CredentialType.PASSWORD, secretHash)))
                .isEqualTo(secretHash);
    }

    @Test
    void rejectsEncodedMaterialAboveColumnCapacity() {
        String prefix = "{synthetic-boundary}";
        String secretHash = prefix + "x".repeat(513 - prefix.length());

        assertThat(secretHash).hasSize(513);
        assertThatThrownBy(
                        () -> Credential.create(newAccount(), CredentialType.PASSWORD, secretHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secretHash);
    }

    @Test
    void replacesOnlyWithEncodedMaterial() throws ReflectiveOperationException {
        Credential credential =
                Credential.create(newAccount(), CredentialType.PASSWORD, SYNTHETIC_ENCODED_SECRET);
        String replacement = "{replacement-scheme}replacement-encoded-material";

        credential.replaceSecretHash(replacement);

        assertThat(readSecretHash(credential)).isEqualTo(replacement);
        assertThatThrownBy(() -> credential.replaceSecretHash("not-encoded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-encoded");
        assertThat(readSecretHash(credential)).isEqualTo(replacement);
    }

    @Test
    void doesNotExposeSecretHashThroughGeneratedOrStringMethods() {
        Credential credential =
                Credential.create(newAccount(), CredentialType.PASSWORD, SYNTHETIC_ENCODED_SECRET);

        assertThat(credential.toString()).doesNotContain(SYNTHETIC_ENCODED_SECRET);
        assertThat(Arrays.toString(new Object[] {credential}))
                .doesNotContain(SYNTHETIC_ENCODED_SECRET);
        assertThat(Arrays.stream(Credential.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("getSecretHash");
        assertThat(
                        Arrays.stream(Credential.class.getDeclaredMethods())
                                .map(method -> method.getName()))
                .doesNotContain("toString", "equals", "hashCode");
    }

    @Test
    void mapsRequiredLazyUnidirectionalAccountAssociation() throws NoSuchFieldException {
        Field account = Credential.class.getDeclaredField("account");
        ManyToOne association = account.getAnnotation(ManyToOne.class);

        assertThat(association).isNotNull();
        assertThat(association.fetch()).isEqualTo(FetchType.LAZY);
        assertThat(association.optional()).isFalse();
        assertThat(
                        Arrays.stream(Account.class.getDeclaredFields())
                                .map(field -> field.getGenericType().getTypeName()))
                .noneMatch(type -> type.contains(Credential.class.getName()));
    }

    @Test
    void declaresOnlyPasswordCredentialType() {
        assertThat(CredentialType.values()).containsExactly(CredentialType.PASSWORD);
    }

    private static Account newAccount() {
        return Account.create("credential-owner@example.com", null, "en");
    }

    private static String readSecretHash(Credential credential)
            throws ReflectiveOperationException {
        Field secretHash = Credential.class.getDeclaredField("secretHash");
        secretHash.setAccessible(true);
        return (String) secretHash.get(credential);
    }
}
