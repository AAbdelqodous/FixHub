package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class Argon2BenchmarkHarnessTest {

    @Test
    void createsTheRequiredDeduplicatedConcurrencyMatrix() {
        assertThat(Argon2BenchmarkHarness.concurrencyMatrix(1).equals(List.of(1, 2, 4))).isTrue();
        assertThat(Argon2BenchmarkHarness.concurrencyMatrix(2).equals(List.of(1, 2, 4))).isTrue();
        assertThat(Argon2BenchmarkHarness.concurrencyMatrix(4).equals(List.of(1, 2, 4))).isTrue();
        assertThat(Argon2BenchmarkHarness.concurrencyMatrix(6).equals(List.of(1, 2, 4, 6)))
                .isTrue();
    }

    @Test
    void rejectsInvalidCandidateConcurrency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Argon2BenchmarkHarness.concurrencyMatrix(0));
    }

    @Test
    void requiresEveryRequestedMeasurementBeforeStatisticsCanBeReported() {
        Argon2BenchmarkHarness.requireCompleteMeasurements(3, List.of(1L, 2L, 3L));

        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                Argon2BenchmarkHarness.requireCompleteMeasurements(
                                        3, Collections.nCopies(2, 1L)));
    }

    @Test
    void awaitsEverySubmittedFutureBeforeReportingAnOperationFailure() {
        List<Future<Long>> futures = new ArrayList<>();
        futures.add(new RecordingFuture(null, new IllegalStateException("Synthetic failure")));
        RecordingFuture successfulFuture = new RecordingFuture(2L, null);
        futures.add(successfulFuture);

        assertThatIllegalStateException()
                .isThrownBy(() -> Argon2BenchmarkHarness.collectMeasurements(2, futures))
                .withMessage("Benchmark operation failed");
        assertThat(successfulFuture.wasAwaited()).isTrue();
    }

    private static final class RecordingFuture implements Future<Long> {

        private final Long result;
        private final Throwable failure;
        private boolean awaited;

        private RecordingFuture(Long result, Throwable failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Long get() throws ExecutionException {
            awaited = true;
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return result;
        }

        @Override
        public Long get(long timeout, java.util.concurrent.TimeUnit unit)
                throws ExecutionException {
            return get();
        }

        boolean wasAwaited() {
            return awaited;
        }
    }
}
