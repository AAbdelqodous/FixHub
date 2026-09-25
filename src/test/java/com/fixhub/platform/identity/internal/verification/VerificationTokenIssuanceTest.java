package com.fixhub.platform.identity.internal.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class VerificationTokenIssuanceTest {

    private static final String BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    @Test
    void constructsWithCanonicalTokenAndDefensivelyOwnedDigest() {
        String token = generatedToken(5);
        byte[] callerDigest = digest(11);
        byte[] originalDigest = callerDigest.clone();
        VerificationTokenIssuance issuance = new VerificationTokenIssuance(token, callerDigest);

        callerDigest[0] ^= 1;
        byte[] firstAccess = issuance.digest();
        firstAccess[1] ^= 1;
        byte[] secondAccess = issuance.digest();

        assertTrue(issuance.publicToken().equals(token), "Public-token accessor changed the token");
        assertTrue(firstAccess != secondAccess, "Digest accessor reused a mutable array");
        assertTrue(
                MessageDigest.isEqual(secondAccess, originalDigest),
                "Caller mutation changed the stored digest");

        Arrays.fill(callerDigest, (byte) 0);
        Arrays.fill(originalDigest, (byte) 0);
        Arrays.fill(firstAccess, (byte) 0);
        Arrays.fill(secondAccess, (byte) 0);
    }

    @Test
    void rejectsNullInvalidShapeAndNonCanonicalTokenWithoutExposingValues() {
        assertTokenRejected(null);
        assertTokenRejected("A".repeat(42));
        assertTokenRejected("A".repeat(42) + "=");
        assertTokenRejected(nonCanonicalAlias(generatedToken(17)));
    }

    @Test
    void rejectsNullAndWrongLengthDigestWithGenericFailures() {
        String token = generatedToken(23);
        assertDigestRejected(token, null);
        assertDigestRejected(token, new byte[0]);
        assertDigestRejected(token, new byte[31]);
        assertDigestRejected(token, new byte[33]);
    }

    @Test
    void isFinalNonRecordAndDeclaresOnlyApprovedSecretAccessors() {
        assertTrue(
                Modifier.isFinal(VerificationTokenIssuance.class.getModifiers()),
                "Issuance wrapper was not final");
        assertTrue(!VerificationTokenIssuance.class.isRecord(), "Issuance wrapper was a record");

        String[] methodNames =
                Arrays.stream(VerificationTokenIssuance.class.getDeclaredMethods())
                        .map(Method::getName)
                        .toArray(String[]::new);
        assertTrue(
                Arrays.asList(methodNames)
                        .containsAll(Arrays.asList("publicToken", "digest", "toString")),
                "Issuance wrapper omitted an approved method");
        assertTrue(
                Arrays.stream(methodNames)
                        .noneMatch(
                                name ->
                                        name.equals("getPublicToken")
                                                || name.equals("getDigest")
                                                || name.equals("equals")
                                                || name.equals("hashCode")),
                "Issuance wrapper declared an unapproved value-rendering method");
    }

    @Test
    void hasFixedRedactedRepresentationsWithoutTokenOrDigest() {
        String token = generatedToken(31);
        byte[] digest = digest(47);
        VerificationTokenIssuance issuance = new VerificationTokenIssuance(token, digest);
        String representation = issuance.toString();
        String generatedRepresentation = Arrays.toString(new Object[] {issuance});

        assertTrue(
                representation.equals("VerificationTokenIssuance[REDACTED]"),
                "Issuance wrapper did not use the fixed redacted representation");
        assertTrue(!representation.contains(token), "toString exposed public-token material");
        assertTrue(
                !representation.contains(Arrays.toString(digest)),
                "toString exposed digest material");
        assertTrue(
                !generatedRepresentation.contains(token),
                "Generated object representation exposed public-token material");
        assertTrue(
                !generatedRepresentation.contains(Arrays.toString(digest)),
                "Generated object representation exposed digest material");

        Arrays.fill(digest, (byte) 0);
    }

    private static void assertTokenRejected(String token) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationTokenIssuance(token, digest(3)),
                        "Expected invalid public-token rejection");
        assertTrue(
                "Verification token has invalid format".equals(exception.getMessage()),
                "Public-token failure did not use the generic message");
        if (token != null) {
            assertTrue(
                    !exception.toString().contains(token),
                    "Public-token failure exposed submitted material");
        }
    }

    private static void assertDigestRejected(String token, byte[] digest) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationTokenIssuance(token, digest),
                        "Expected invalid digest rejection");
        assertTrue(
                "Verification token digest has invalid format".equals(exception.getMessage()),
                "Digest failure did not use the generic message");
        if (digest != null) {
            assertTrue(
                    !exception.toString().contains(Arrays.toString(digest)),
                    "Digest failure exposed submitted material");
        }
    }

    private static String generatedToken(int start) {
        byte[] raw = digest(start);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static String nonCanonicalAlias(String canonical) {
        int lastIndex = canonical.length() - 1;
        int alphabetIndex = BASE64_URL_ALPHABET.indexOf(canonical.charAt(lastIndex));
        return canonical.substring(0, lastIndex) + BASE64_URL_ALPHABET.charAt(alphabetIndex + 1);
    }

    private static byte[] digest(int start) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (start + index);
        }
        return bytes;
    }
}
