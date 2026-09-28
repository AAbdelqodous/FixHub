package com.fixhub.platform.identity.internal.ratelimit;

/** Provider-neutral boundary; no production implementation exists in Slice 5B. */
@FunctionalInterface
interface RateLimitSecretResolver {

    /**
     * Resolves an opaque nonblank reference. Each success transfers a fresh, exclusively
     * caller-owned decoded-key array; failures return no key material. A resolver must never return
     * shared or resolver-owned mutable storage, including a directly cached array.
     */
    byte[] resolve(String reference);
}
