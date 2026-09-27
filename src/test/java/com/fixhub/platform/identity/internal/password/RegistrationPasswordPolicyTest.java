package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RegistrationPasswordPolicyTest {

    private final RegistrationPasswordPolicy policy = new RegistrationPasswordPolicy();

    @Test
    void normalizesCanonicallyEquivalentPasswordsWithNfc() {
        String decomposed = "synthetic-" + "e\u0301".repeat(12);
        String composed = "synthetic-" + "é".repeat(12);

        String normalizedDecomposed = policy.normalizeAndValidate(decomposed);
        String normalizedComposed = policy.normalizeAndValidate(composed);

        assertThat(normalizedDecomposed.equals(normalizedComposed)).isTrue();
    }

    @Test
    void rejectsMalformedUtf16BeforeNormalization() {
        String malformed = "x".repeat(14) + '\uD800';

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.normalizeAndValidate(malformed))
                .withMessage("Password contains malformed Unicode");
    }

    @Test
    void enforcesNormalizedCodePointBoundaries() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.normalizeAndValidate("x".repeat(14)))
                .withMessage("Password must contain between 15 and 128 Unicode code points");
        assertThat(policy.normalizeAndValidate("x".repeat(15)).codePointCount(0, 15) == 15)
                .isTrue();
        assertThat(policy.normalizeAndValidate("x".repeat(128)).codePointCount(0, 128) == 128)
                .isTrue();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.normalizeAndValidate("x".repeat(129)))
                .withMessage("Password must contain between 15 and 128 Unicode code points");
    }

    @Test
    void countsSupplementaryCharactersAsOneCodePoint() {
        String password = "x".repeat(14) + new String(Character.toChars(0x1F642));

        String normalized = policy.normalizeAndValidate(password);

        assertThat(normalized.codePointCount(0, normalized.length()) == 15).isTrue();
        assertThat(normalized.length() == 16).isTrue();
    }

    @Test
    void preservesLeadingTrailingAndInternalWhitespace() {
        String password = " \t" + "x".repeat(12) + "  \n";

        String normalized = policy.normalizeAndValidate(password);

        assertThat(normalized.equals(password)).isTrue();
    }

    @Test
    void acceptsAnAllWhitespacePasswordAtTheMinimumCodePointBoundary() {
        String normalized = policy.normalizeAndValidate(" ".repeat(15));

        assertThat(normalized.codePointCount(0, normalized.length()) == 15).isTrue();
        assertThat(normalized.trim().isEmpty()).isTrue();
    }

    @Test
    void rejectsNullWithoutIncludingPasswordMaterialInTheDiagnostic() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.normalizeAndValidate(null))
                .withMessage("Password is required");
    }
}
