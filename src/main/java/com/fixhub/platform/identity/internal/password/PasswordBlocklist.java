package com.fixhub.platform.identity.internal.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Set;

/** Identity-internal immutable checker for the validated local digest artifact. */
final class PasswordBlocklist {

    private static final HexFormat UPPERCASE_HEX = HexFormat.of().withUpperCase();

    private final Set<String> digests;

    PasswordBlocklist(Set<String> digests) {
        this.digests = Set.copyOf(digests);
    }

    void assertNotCompromised(String normalizedPassword) {
        requireNormalizedInput(normalizedPassword);
        if (digests.contains(sha1(normalizedPassword))) {
            throw new CompromisedPasswordException();
        }
    }

    private static void requireNormalizedInput(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= password.length()
                        || !Character.isLowSurrogate(password.charAt(++index))) {
                    throw new IllegalArgumentException("Password contains malformed Unicode");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Password contains malformed Unicode");
            }
        }
        if (!Normalizer.isNormalized(password, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("Password must be NFC normalized");
        }
    }

    private static String sha1(String normalizedPassword) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest(normalizedPassword.getBytes(StandardCharsets.UTF_8));
            return UPPERCASE_HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required password lookup algorithm is unavailable");
        }
    }
}
