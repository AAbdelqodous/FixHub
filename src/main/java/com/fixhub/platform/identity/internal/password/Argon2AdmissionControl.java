package com.fixhub.platform.identity.internal.password;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds concurrent memory-hard encodes without retaining work or placing callers in a queue. */
final class Argon2AdmissionControl {

    private final Semaphore permits;

    Argon2AdmissionControl(int maximumConcurrency) {
        this.permits = new Semaphore(maximumConcurrency);
    }

    Optional<Permit> tryAcquire() {
        if (!permits.tryAcquire()) {
            return Optional.empty();
        }
        return Optional.of(new Permit(permits));
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
