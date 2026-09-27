package com.fixhub.platform.identity.internal.password;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manually invoked evidence harness; it is intentionally not a JUnit test and does not impose a CI
 * latency threshold. Its limited coordination code measures only the configured synthetic Argon2
 * workload, not production traffic or native-memory capacity.
 */
final class Argon2BenchmarkHarness {

    static final int WARM_UP_OPERATIONS = 30;
    static final int MEASURED_OPERATIONS = 100;

    private Argon2BenchmarkHarness() {}

    public static void main(String[] arguments) throws Exception {
        int candidateMaximumConcurrency =
                arguments.length == 1 ? Integer.parseInt(arguments[0]) : 1;
        for (int concurrency : concurrencyMatrix(candidateMaximumConcurrency)) {
            print(runScenario(concurrency));
        }
    }

    static List<Integer> concurrencyMatrix(int candidateMaximumConcurrency) {
        if (candidateMaximumConcurrency < 1) {
            throw new IllegalArgumentException("Concurrency must be positive");
        }
        Set<Integer> values = new LinkedHashSet<>();
        values.add(1);
        values.add(2);
        values.add(4);
        values.add(candidateMaximumConcurrency);
        return List.copyOf(values);
    }

    static void requireCompleteMeasurements(int requestedCount, List<Long> durations) {
        if (durations.size() != requestedCount) {
            throw new IllegalStateException("Benchmark did not complete every requested operation");
        }
    }

    private static BenchmarkResult runScenario(int concurrency) throws Exception {
        PasswordEncodingService service =
                new PasswordEncodingService(
                        PasswordSecurityConfiguration.createPasswordEncoder(
                                properties(concurrency)),
                        new Argon2AdmissionControl(concurrency));
        for (int index = 0; index < WARM_UP_OPERATIONS; index++) {
            service.encodeNormalizedPassword(syntheticPassword(index));
        }

        OperatingSystemMXBean operatingSystem =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long committedVirtualMemoryBefore = operatingSystem.getCommittedVirtualMemorySize();
        List<Long> durations = measure(service, concurrency);
        long heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long committedVirtualMemoryAfter = operatingSystem.getCommittedVirtualMemorySize();

        Collections.sort(durations);
        return new BenchmarkResult(
                concurrency,
                WARM_UP_OPERATIONS,
                durations.size(),
                percentileMillis(durations, 0.50),
                percentileMillis(durations, 0.95),
                operatingSystem.getProcessCpuLoad(),
                heapAfter - heapBefore,
                committedVirtualMemoryAfter - committedVirtualMemoryBefore,
                RuntimeMetadata.capture());
    }

    private static List<Long> measure(PasswordEncodingService service, int concurrency)
            throws InterruptedException {
        List<Long> durations = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(Math.min(concurrency, MEASURED_OPERATIONS));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Long>> futures = new ArrayList<>();
        boolean completed = false;
        try {
            for (int index = 0; index < MEASURED_OPERATIONS; index++) {
                int operation = index;
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    try {
                                        start.await();
                                        long started = System.nanoTime();
                                        service.encodeNormalizedPassword(
                                                syntheticPassword(operation));
                                        return System.nanoTime() - started;
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(
                                                "Benchmark coordination interrupted", exception);
                                    }
                                }));
            }
            ready.await();
            start.countDown();
            durations.addAll(collectMeasurements(MEASURED_OPERATIONS, futures));
            completed = true;
            return durations;
        } finally {
            shutdown(executor, completed);
        }
    }

    static List<Long> collectMeasurements(int requestedCount, List<? extends Future<Long>> futures)
            throws InterruptedException {
        List<Long> durations = new ArrayList<>();
        Throwable firstFailure = null;
        for (Future<Long> future : futures) {
            try {
                durations.add(future.get());
            } catch (ExecutionException exception) {
                if (firstFailure == null) {
                    firstFailure = exception.getCause();
                }
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Benchmark operation failed", firstFailure);
        }
        requireCompleteMeasurements(requestedCount, durations);
        return durations;
    }

    private static void shutdown(ExecutorService executor, boolean completed)
            throws InterruptedException {
        if (completed) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }
        if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Benchmark executor did not terminate");
            }
        }
    }

    private static void print(BenchmarkResult result) {
        System.out.printf(
                Locale.ROOT,
                "argon2-benchmark concurrency=%d warmups=%d samples=%d p50Millis=%.3f p95Millis=%.3f processCpuLoad=%.4f heapDeltaBytes=%d processCommittedVirtualMemoryDeltaBytes=%d committedVirtualMemory=observational-only-not-native-argon2-memory java=%s os=%s processors=%d containerMetadata=standard-jvm-api-unavailable productionEquivalentNativeMemoryAndCapacityEvidence=PENDING%n",
                result.concurrency(),
                result.warmups(),
                result.measurements(),
                result.p50Millis(),
                result.p95Millis(),
                result.processCpuLoad(),
                result.heapDeltaBytes(),
                result.committedVirtualMemoryDeltaBytes(),
                result.runtimeMetadata().javaVersion(),
                result.runtimeMetadata().operatingSystem(),
                result.runtimeMetadata().availableProcessors());
    }

    private static double percentileMillis(List<Long> durations, double percentile) {
        int index =
                Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * percentile) - 1);
        return durations.get(index) / 1_000_000.0;
    }

    private static IdentityPasswordProperties properties(int concurrency) {
        return new IdentityPasswordProperties(
                new IdentityPasswordProperties.Argon2(
                        16,
                        32,
                        1,
                        19_456,
                        2,
                        new IdentityPasswordProperties.Admission(concurrency, 1)),
                new IdentityPasswordProperties.Blocklist(
                        "classpath:/blocklist/synthetic-blocklist.txt",
                        "classpath:/blocklist/synthetic-blocklist.manifest",
                        "A".repeat(64),
                        "1AA26D0926F96FCFEEB0221C7A58C73FB10ED3D6C7C193A2EAA39989E0431418",
                        100_000,
                        3,
                        "test-synthetic-1"));
    }

    private static String syntheticPassword(int operation) {
        return "synthetic-benchmark-password-" + operation + "-only";
    }

    private record BenchmarkResult(
            int concurrency,
            int warmups,
            int measurements,
            double p50Millis,
            double p95Millis,
            double processCpuLoad,
            long heapDeltaBytes,
            long committedVirtualMemoryDeltaBytes,
            RuntimeMetadata runtimeMetadata) {}

    private record RuntimeMetadata(
            String javaVersion, String operatingSystem, int availableProcessors) {

        private static RuntimeMetadata capture() {
            return new RuntimeMetadata(
                    System.getProperty("java.runtime.version"),
                    System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    Runtime.getRuntime().availableProcessors());
        }
    }
}
