package com.fixhub.platform.identity.internal.password;

import java.text.Normalizer;

/** Validates only the syntax portion of the registration-password policy. */
class RegistrationPasswordPolicy {

    private static final int MINIMUM_CODE_POINTS = 15;
    private static final int MAXIMUM_CODE_POINTS = 128;

    String normalizeAndValidate(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }

        rejectMalformedUtf16(password);
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount < MINIMUM_CODE_POINTS || codePointCount > MAXIMUM_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Password must contain between 15 and 128 Unicode code points");
        }
        return normalized;
    }

    private static void rejectMalformedUtf16(String password) {
        for (int index = 0; index < password.length(); index++) {
            char current = password.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= password.length()
                        || !Character.isLowSurrogate(password.charAt(index + 1))) {
                    throw new IllegalArgumentException("Password contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Password contains malformed Unicode");
            }
        }
    }
}
