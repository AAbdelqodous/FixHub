package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RateLimitHmacKeySnapshotTest {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Duration MINIMUM_OVERLAP = Duration.ofHours(25);
    private static final byte[] FRAME = {1, 2, 3};

    @Test
    void steadySnapshotCopiesAndClearsFreshResolverResult() throws Exception {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        RateLimitHmacKeySnapshot snapshot = steady(resolver);
        assertThat(resolver.issued).hasSize(1);
        assertThat(allZero(resolver.issued.getFirst())).isTrue();
        byte[] retained = ownedKey(snapshot, "currentKey");
        assertThat(allZero(retained)).isFalse();
        assertThat(snapshot.derive(FRAME, START).stream().map(RateLimitProtectedKey::version))
                .containsExactly(1);
        snapshot.close();
        assertThat(allZero(retained)).isTrue();
        assertThatThrownBy(() -> snapshot.derive(FRAME, START))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate-limit HMAC key snapshot is closed");
        snapshot.close();
    }

    @Test
    void acceptsLongerKeysAndRejectsShortKeysWithoutRetainingResolverResult() {
        SyntheticResolver longer = new SyntheticResolver(key(64, 0x11), key(32, 0x22));
        try (RateLimitHmacKeySnapshot ignored = steady(longer)) {
            assertThat(allZero(longer.issued.getFirst())).isTrue();
        }

        SyntheticResolver shortKey = new SyntheticResolver(key(31, 0x11), key(32, 0x22));
        assertThatThrownBy(() -> steady(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit HMAC configuration");
        assertThat(allZero(shortKey.issued.getFirst())).isTrue();
    }

    @Test
    void rejectsPartialDuplicateAndIdenticalRotationWithoutLeakingMaterial() {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        assertInvalid(resolver, 0, "synthetic-current", null, null, null, null);
        assertInvalid(resolver, 1, " ", null, null, null, null);
        assertInvalid(resolver, 1, "synthetic-current", 2, null, START, MINIMUM_OVERLAP);
        assertInvalid(
                resolver,
                1,
                "synthetic-current",
                null,
                "synthetic-previous",
                START,
                MINIMUM_OVERLAP);
        assertInvalid(
                resolver, 1, "synthetic-current", 2, "synthetic-previous", null, MINIMUM_OVERLAP);
        assertInvalid(resolver, 1, "synthetic-current", 2, "synthetic-previous", START, null);
        assertInvalid(
                resolver, 1, "synthetic-current", 0, "synthetic-previous", START, MINIMUM_OVERLAP);
        assertInvalid(
                resolver, 1, "synthetic-current", 1, "synthetic-previous", START, MINIMUM_OVERLAP);
        assertInvalid(
                resolver,
                1,
                "synthetic-current",
                2,
                "synthetic-previous",
                START,
                MINIMUM_OVERLAP.minusNanos(1));

        SyntheticResolver identical = new SyntheticResolver(key(32, 0x11), key(32, 0x11));
        assertInvalid(
                identical, 1, "synthetic-current", 2, "synthetic-previous", START, MINIMUM_OVERLAP);
        assertThat(identical.issued).hasSize(2);
        assertThat(identical.issued.stream().allMatch(RateLimitHmacKeySnapshotTest::allZero))
                .isTrue();
    }

    @Test
    void secondResolutionFailureClearsFirstResultAndSanitizesProviderDiagnostics() {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        resolver.failPrevious = true;
        assertThatThrownBy(() -> rotating(resolver, START, MINIMUM_OVERLAP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate-limit HMAC key is unavailable")
                .hasNoCause();
        assertThat(resolver.issued).hasSize(1);
        assertThat(allZero(resolver.issued.getFirst())).isTrue();
    }

    @Test
    void rotationEndOverflowFailsBeforeResolution() {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        assertThatThrownBy(() -> rotating(resolver, Instant.MAX, MINIMUM_OVERLAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit HMAC configuration");
        assertThat(resolver.issued).isEmpty();
    }

    @Test
    void rotationSelectsExactStartAndEndBoundaries() {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        try (RateLimitHmacKeySnapshot snapshot = rotating(resolver, START, MINIMUM_OVERLAP)) {
            assertVersions(snapshot, START.minusNanos(1), 2);
            assertVersions(snapshot, START, 1, 2);
            assertVersions(snapshot, START.plusSeconds(1), 1, 2);
            Instant end = START.plus(MINIMUM_OVERLAP);
            assertVersions(snapshot, end.minusNanos(1), 1, 2);
            assertVersions(snapshot, end, 1);
            assertVersions(snapshot, end.plusSeconds(1), 1);
            assertThat(resolver.issued.stream().allMatch(RateLimitHmacKeySnapshotTest::allZero))
                    .isTrue();
        }
    }

    @Test
    void concurrentResolutionReturnsDistinctArraysAndCloseRacesSafely() throws Exception {
        SyntheticResolver resolver = new SyntheticResolver(key(32, 0x11), key(32, 0x22));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> resolutions = new ArrayList<>();
        RateLimitHmacKeySnapshot snapshot = steady(resolver);
        Future<String> derivation = null;
        Future<?> closing = null;
        try {
            for (int index = 0; index < 4; index++) {
                resolutions.add(
                        executor.submit(
                                () -> {
                                    if (!start.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Synthetic start timeout");
                                    }
                                    return resolver.resolve("synthetic-current");
                                }));
            }
            start.countDown();
            Set<byte[]> identities = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<byte[]> resolution : resolutions) {
                byte[] owned = resolution.get(10, TimeUnit.SECONDS);
                assertThat(identities.add(owned)).isTrue();
                Arrays.fill(owned, (byte) 0);
            }
            assertThat(identities).hasSize(4);

            CountDownLatch race = new CountDownLatch(1);
            derivation =
                    executor.submit(
                            () -> {
                                if (!race.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Synthetic race timeout");
                                }
                                try {
                                    snapshot.derive(FRAME, START);
                                    return "DERIVED";
                                } catch (IllegalStateException closed) {
                                    return "CLOSED";
                                }
                            });
            closing =
                    executor.submit(
                            () -> {
                                if (!race.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Synthetic race timeout");
                                }
                                snapshot.close();
                                return null;
                            });
            race.countDown();
            assertThat(derivation.get(10, TimeUnit.SECONDS)).isIn("DERIVED", "CLOSED");
            closing.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> snapshot.derive(FRAME, START))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            for (Future<byte[]> resolution : resolutions) {
                resolution.cancel(true);
            }
            if (derivation != null) {
                derivation.cancel(true);
            }
            if (closing != null) {
                closing.cancel(true);
            }
            snapshot.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RateLimitHmacKeySnapshot steady(SyntheticResolver resolver) {
        return RateLimitHmacKeySnapshot.create(
                resolver, 1, "synthetic-current", null, null, null, null);
    }

    private static RateLimitHmacKeySnapshot rotating(
            SyntheticResolver resolver, Instant start, Duration overlap) {
        return RateLimitHmacKeySnapshot.create(
                resolver, 1, "synthetic-current", 2, "synthetic-previous", start, overlap);
    }

    private static void assertInvalid(
            SyntheticResolver resolver,
            int currentVersion,
            String currentReference,
            Integer previousVersion,
            String previousReference,
            Instant start,
            Duration overlap) {
        assertThatThrownBy(
                        () ->
                                RateLimitHmacKeySnapshot.create(
                                        resolver,
                                        currentVersion,
                                        currentReference,
                                        previousVersion,
                                        previousReference,
                                        start,
                                        overlap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit HMAC configuration");
    }

    private static void assertVersions(
            RateLimitHmacKeySnapshot snapshot, Instant instant, Integer... versions) {
        assertThat(snapshot.derive(FRAME, instant).stream().map(RateLimitProtectedKey::version))
                .containsExactly(versions);
    }

    private static byte[] ownedKey(RateLimitHmacKeySnapshot snapshot, String name)
            throws ReflectiveOperationException {
        Field field = RateLimitHmacKeySnapshot.class.getDeclaredField(name);
        field.setAccessible(true);
        return (byte[]) field.get(snapshot);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] key(int length, int fill) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static final class SyntheticResolver implements RateLimitSecretResolver {

        private final byte[] current;
        private final byte[] previous;
        private final List<byte[]> issued = Collections.synchronizedList(new ArrayList<>());
        private boolean failPrevious;

        private SyntheticResolver(byte[] current, byte[] previous) {
            this.current = current;
            this.previous = previous;
        }

        @Override
        public byte[] resolve(String reference) {
            if (failPrevious && reference.equals("synthetic-previous")) {
                throw new IllegalStateException("synthetic provider diagnostic");
            }
            byte[] copy =
                    reference.equals("synthetic-current") ? current.clone() : previous.clone();
            issued.add(copy);
            return copy;
        }
    }
}
