package com.fixhub.platform.identity.internal.password;

/** Safe internal outcome for a request that cannot begin memory-hard password encoding. */
final class Argon2AdmissionSaturatedException extends RuntimeException {

    Argon2AdmissionSaturatedException() {
        super("Password encoding capacity is temporarily unavailable");
    }
}
