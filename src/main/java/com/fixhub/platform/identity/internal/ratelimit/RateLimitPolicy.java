package com.fixhub.platform.identity.internal.ratelimit;

enum RateLimitPolicy {
    REGISTRATION_EMAIL,
    REGISTRATION_ORIGIN,
    RESEND_EMAIL,
    RESEND_ORIGIN,
    VERIFICATION_ORIGIN,
    FH011_GLOBAL
}
