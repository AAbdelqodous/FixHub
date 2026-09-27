package com.fixhub.platform.identity.internal.password;

/** Safe internal outcome for a password present in the validated local blocklist. */
final class CompromisedPasswordException extends IllegalArgumentException {

    CompromisedPasswordException() {
        super("Password is present in the compromised-password blocklist");
    }
}
