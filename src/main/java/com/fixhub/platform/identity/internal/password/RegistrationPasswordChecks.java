package com.fixhub.platform.identity.internal.password;

import org.springframework.stereotype.Component;

/** The narrow registration boundary for password syntax and complete-value blocklist checks. */
@Component
public final class RegistrationPasswordChecks {

    private final RegistrationPasswordPolicy policy = new RegistrationPasswordPolicy();
    private final PasswordBlocklist blocklist;

    RegistrationPasswordChecks(PasswordBlocklist blocklist) {
        this.blocklist = blocklist;
    }

    public String normalizeValidateAndCheck(String rawPassword) {
        String normalized = policy.normalizeAndValidate(rawPassword);
        blocklist.assertNotCompromised(normalized);
        return normalized;
    }
}
