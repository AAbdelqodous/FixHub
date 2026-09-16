package com.fixhub.platform.identity.internal.password;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Identity-internal write service for already normalized, policy-valid registration passwords.
 * Matching remains an FH-012 responsibility.
 */
public final class PasswordEncodingService {

    private final PasswordEncoder passwordEncoder;
    private final Argon2AdmissionControl admissionControl;

    PasswordEncodingService(
            PasswordEncoder passwordEncoder, Argon2AdmissionControl admissionControl) {
        this.passwordEncoder = passwordEncoder;
        this.admissionControl = admissionControl;
    }

    public String encodeNormalizedPassword(String normalizedPassword) {
        Argon2AdmissionControl.Permit permit =
                admissionControl.tryAcquire().orElseThrow(Argon2AdmissionSaturatedException::new);
        try {
            return passwordEncoder.encode(normalizedPassword);
        } finally {
            permit.close();
        }
    }
}
