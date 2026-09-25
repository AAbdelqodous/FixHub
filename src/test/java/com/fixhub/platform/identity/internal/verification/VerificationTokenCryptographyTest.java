package com.fixhub.platform.identity.internal.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerificationTokenCryptographyTest {

    private static final String INVALID_TOKEN_MESSAGE = "Verification token has invalid format";
    private static final String BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    @Test
    void issuesExactlyThirtyTwoBytesAsFortyThreeUnpaddedBase64UrlCharacters() {
        CapturingRandomByteSource source = new CapturingRandomByteSource(sequence(1));
        VerificationTokenIssuance issuance = new VerificationTokenCryptography(source).issue();

        assertTrue(source.requestedLength() == 32, "Unexpected random-byte request length");
        assertTrue(issuance.publicToken().length() == 43, "Unexpected public-token length");
        assertTrue(
                issuance.publicToken().chars().allMatch(this::isBase64UrlCharacter),
                "Public token used a non-Base64url character");
        assertTrue(issuance.publicToken().indexOf('=') < 0, "Public token contained padding");
    }

    @Test
    void hashesRawBytesWithSha256RatherThanEncodedAsciiText() throws Exception {
        byte[] raw = sequence(9);
        VerificationTokenIssuance issuance =
                new VerificationTokenCryptography(new CapturingRandomByteSource(raw)).issue();
        byte[] expectedRawDigest = MessageDigest.getInstance("SHA-256").digest(raw);
        byte[] encodedTextDigest =
                MessageDigest.getInstance("SHA-256")
                        .digest(
                                issuance.publicToken()
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] actualDigest = issuance.digest();

        assertTrue(
                MessageDigest.isEqual(actualDigest, expectedRawDigest),
                "Digest did not match the raw token bytes");
        assertTrue(
                !MessageDigest.isEqual(actualDigest, encodedTextDigest),
                "Digest unexpectedly matched encoded token text");

        Arrays.fill(raw, (byte) 0);
        Arrays.fill(expectedRawDigest, (byte) 0);
        Arrays.fill(encodedTextDigest, (byte) 0);
        Arrays.fill(actualDigest, (byte) 0);
    }

    @Test
    void successiveIssuanceConsumesFreshRandomInput() {
        CapturingRandomByteSource source = new CapturingRandomByteSource(sequence(3), sequence(71));
        VerificationTokenCryptography cryptography = new VerificationTokenCryptography(source);

        VerificationTokenIssuance first = cryptography.issue();
        VerificationTokenIssuance second = cryptography.issue();

        assertTrue(source.invocations() == 2, "Random source was not invoked for each issuance");
        assertTrue(
                !first.publicToken().equals(second.publicToken()),
                "Successive issuance reused public-token material");
    }

    @Test
    void generatedTokenRoundTripsToItsDigest() {
        VerificationTokenCryptography cryptography =
                new VerificationTokenCryptography(new CapturingRandomByteSource(sequence(19)));
        VerificationTokenIssuance issuance = cryptography.issue();
        byte[] decodedDigest = cryptography.decodeAndDigest(issuance.publicToken());
        byte[] issuedDigest = issuance.digest();

        assertTrue(
                MessageDigest.isEqual(decodedDigest, issuedDigest),
                "Generated token did not round trip to its digest");

        Arrays.fill(decodedDigest, (byte) 0);
        Arrays.fill(issuedDigest, (byte) 0);
    }

    @Test
    void rejectsNullWithGenericMessage() {
        assertInvalidToken(null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 41, 42, 44, 45, 86})
    void rejectsEveryInvalidLengthClass(int length) {
        assertInvalidToken("A".repeat(length));
    }

    @Test
    void rejectsPaddingStandardBase64WhitespaceNonAsciiAndMalformedCharacters() {
        assertInvalidToken("A".repeat(42) + "=");
        assertInvalidToken("A".repeat(42) + "+");
        assertInvalidToken("A".repeat(42) + "/");
        assertInvalidToken("A".repeat(42) + " ");
        assertInvalidToken("A".repeat(42) + "\n");
        assertInvalidToken("A".repeat(42) + "\u00e9");
        assertInvalidToken("A".repeat(42) + "%");
    }

    @Test
    void rejectsNonCanonicalPadBitAlias() {
        VerificationTokenIssuance issuance =
                new VerificationTokenCryptography(new CapturingRandomByteSource(sequence(29)))
                        .issue();
        String alias = nonCanonicalAlias(issuance.publicToken());

        assertInvalidToken(alias);
    }

    @Test
    void invalidInputIsRejectedBeforeDecodingOrDigestingAndDecodedBytesAreClearedInFinally()
            throws Exception {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/fixhub/platform/identity/internal/verification/VerificationTokenCryptography.java"));
        int methodStart = source.indexOf("public byte[] decodeAndDigest(String encodedToken)");
        int methodEnd = source.indexOf("static boolean isCanonicalPublicToken", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(
                method.indexOf("requireStrictShape(encodedToken)")
                        < method.indexOf("decoded = decode(encodedToken)"),
                "Shape validation did not precede decoding");
        assertTrue(
                method.indexOf("decoded = decode(encodedToken)")
                        < method.indexOf("return digest(decoded)"),
                "Decoding did not precede digesting");
        assertTrue(method.contains("finally"), "Decoded-byte clearing was not in a finally block");
        assertTrue(
                method.contains("Arrays.fill(decoded, (byte) 0)"),
                "Decoded-byte buffer was not cleared");
    }

    @Test
    void clearsGenerationBufferOnSuccessAndFailure() {
        CapturingRandomByteSource successful = new CapturingRandomByteSource(sequence(37));
        new VerificationTokenCryptography(successful).issue();
        assertTrue(
                allZero(successful.destination()), "Successful generation buffer was not cleared");

        AtomicReference<byte[]> failedDestination = new AtomicReference<>();
        RandomByteSource failing =
                destination -> {
                    failedDestination.set(destination);
                    Arrays.fill(destination, (byte) 91);
                    throw new IllegalStateException("Synthetic random-source failure");
                };
        assertThrows(
                IllegalStateException.class,
                () -> new VerificationTokenCryptography(failing).issue(),
                "Expected synthetic random-source failure");
        assertTrue(allZero(failedDestination.get()), "Failed generation buffer was not cleared");
    }

    @Test
    void validationFailuresNeverContainSubmittedValue() {
        String submitted = "A".repeat(42) + "%";
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new VerificationTokenCryptography(
                                                new CapturingRandomByteSource(sequence(1)))
                                        .decodeAndDigest(submitted),
                        "Expected invalid token rejection");

        assertTrue(
                INVALID_TOKEN_MESSAGE.equals(exception.getMessage()),
                "Validation failure did not use the generic message");
        assertTrue(
                !exception.toString().contains(submitted),
                "Validation failure exposed submitted token material");
    }

    private void assertInvalidToken(String submitted) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new VerificationTokenCryptography(
                                                new CapturingRandomByteSource(sequence(1)))
                                        .decodeAndDigest(submitted),
                        "Expected invalid token rejection");
        assertTrue(
                INVALID_TOKEN_MESSAGE.equals(exception.getMessage()),
                "Invalid token did not use the generic validation message");
        if (submitted != null && submitted.length() >= 8) {
            assertTrue(
                    !exception.toString().contains(submitted),
                    "Invalid-token exception exposed submitted material");
        }
    }

    private boolean isBase64UrlCharacter(int character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_';
    }

    private static String nonCanonicalAlias(String canonical) {
        int lastIndex = canonical.length() - 1;
        int alphabetIndex = BASE64_URL_ALPHABET.indexOf(canonical.charAt(lastIndex));
        char alias = BASE64_URL_ALPHABET.charAt(alphabetIndex + 1);
        return canonical.substring(0, lastIndex) + alias;
    }

    private static boolean allZero(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sequence(int start) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (start + index);
        }
        return bytes;
    }

    private static final class CapturingRandomByteSource implements RandomByteSource {

        private final byte[][] values;
        private int invocations;
        private byte[] destination;

        private CapturingRandomByteSource(byte[]... values) {
            this.values = values;
        }

        @Override
        public void nextBytes(byte[] destination) {
            this.destination = destination;
            byte[] value = values[invocations++];
            System.arraycopy(value, 0, destination, 0, value.length);
        }

        private int requestedLength() {
            return destination.length;
        }

        private int invocations() {
            return invocations;
        }

        private byte[] destination() {
            return destination;
        }
    }
}
