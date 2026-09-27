package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class Argon2AdmissionControlTest {

    private static final String SYNTHETIC_PASSWORD = "synthetic-password-value-only-for-tests";

    @Test
    void immediatelyRejectsSaturationWithoutQueueing() {
        Argon2AdmissionControl control = new Argon2AdmissionControl(1);
        Argon2AdmissionControl.Permit heldPermit = control.tryAcquire().orElseThrow();

        assertThat(control.tryAcquire().isEmpty()).isTrue();
        assertThat(control.availablePermits() == 0).isTrue();

        heldPermit.close();
        assertThat(control.availablePermits() == 1).isTrue();
    }

    @Test
    void neverExceedsConfiguredConcurrencyAndReleasesEachPermitOnlyOnce() {
        Argon2AdmissionControl control = new Argon2AdmissionControl(2);
        Argon2AdmissionControl.Permit firstPermit = control.tryAcquire().orElseThrow();
        Argon2AdmissionControl.Permit secondPermit = control.tryAcquire().orElseThrow();

        assertThat(control.tryAcquire().isEmpty()).isTrue();
        firstPermit.close();
        firstPermit.close();
        assertThat(control.availablePermits() == 1).isTrue();
        secondPermit.close();
        assertThat(control.availablePermits() == 2).isTrue();
    }

    @Test
    void releasesThePermitAfterAnEncodingFailure() {
        Argon2AdmissionControl control = new Argon2AdmissionControl(1);
        PasswordEncodingService service = new PasswordEncodingService(failingEncoder(), control);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.encodeNormalizedPassword(SYNTHETIC_PASSWORD))
                .withMessage("Synthetic encoding failure");

        assertThat(control.availablePermits() == 1).isTrue();
    }

    @Test
    void rejectsAConcurrentCallerBeforeStartingItsEncodingAndReleasesAfterSuccess()
            throws Exception {
        CountDownLatch encodingStarted = new CountDownLatch(1);
        CountDownLatch allowEncodingToFinish = new CountDownLatch(1);
        AtomicInteger encodingInvocations = new AtomicInteger();
        Argon2AdmissionControl control = new Argon2AdmissionControl(1);
        PasswordEncodingService service =
                new PasswordEncodingService(
                        blockingEncoder(
                                encodingStarted, allowEncodingToFinish, encodingInvocations),
                        control);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> firstEncoding =
                    executor.submit(() -> service.encodeNormalizedPassword(SYNTHETIC_PASSWORD));
            assertThat(encodingStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.encodeNormalizedPassword(SYNTHETIC_PASSWORD))
                    .isInstanceOf(Argon2AdmissionSaturatedException.class)
                    .hasMessage("Password encoding capacity is temporarily unavailable");
            assertThat(encodingInvocations.get() == 1).isTrue();

            allowEncodingToFinish.countDown();
            assertThat(firstEncoding.get(5, TimeUnit.SECONDS).equals("synthetic-encoded-value"))
                    .isTrue();
            assertThat(control.availablePermits() == 1).isTrue();
        }
    }

    private static PasswordEncoder failingEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("Synthetic encoding failure");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
    }

    private static PasswordEncoder blockingEncoder(
            CountDownLatch encodingStarted,
            CountDownLatch allowEncodingToFinish,
            AtomicInteger encodingInvocations) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                encodingInvocations.incrementAndGet();
                encodingStarted.countDown();
                try {
                    if (!allowEncodingToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Synthetic test coordination failed");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Synthetic test coordination interrupted", exception);
                }
                return "synthetic-encoded-value";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
    }
}
