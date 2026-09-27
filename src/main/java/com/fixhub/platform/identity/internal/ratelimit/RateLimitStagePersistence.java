package com.fixhub.platform.identity.internal.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
final class RateLimitStagePersistence {

    private static final Duration MINIMUM_RETENTION = Duration.ofHours(24);
    private final RateLimitStageWriter writer;

    RateLimitStagePersistence(RateLimitStageWriter writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    StageResult evaluate(Stage stage, List<BucketInput> inputs) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Rate-limit stage requires no active caller transaction");
        }
        StageWork work = StageWork.create(stage, inputs);
        try {
            writer.execute(work);
            return StageResult.admitted();
        } catch (StageRejected rejected) {
            return StageResult.rejected(rejected.retryAfterSeconds());
        } finally {
            work.clear();
        }
    }

    enum Stage {
        REGISTRATION_COARSE(
                EnumSet.of(RateLimitPolicy.REGISTRATION_ORIGIN, RateLimitPolicy.FH011_GLOBAL)),
        REGISTRATION_EMAIL(EnumSet.of(RateLimitPolicy.REGISTRATION_EMAIL)),
        RESEND_COARSE(EnumSet.of(RateLimitPolicy.RESEND_ORIGIN, RateLimitPolicy.FH011_GLOBAL)),
        RESEND_EMAIL(EnumSet.of(RateLimitPolicy.RESEND_EMAIL)),
        VERIFICATION_COARSE(
                EnumSet.of(RateLimitPolicy.VERIFICATION_ORIGIN, RateLimitPolicy.FH011_GLOBAL));

        private final EnumSet<RateLimitPolicy> policies;

        Stage(EnumSet<RateLimitPolicy> policies) {
            this.policies = policies;
        }
    }

    static final class BucketInput {

        private final RateLimitPolicy policy;
        private final int keyVersion;
        private final byte[] keyDigest;
        private final Duration window;
        private final long limit;
        private final Duration cooldown;
        private final Duration retention;

        BucketInput(
                RateLimitPolicy policy,
                int keyVersion,
                byte[] keyDigest,
                Duration window,
                long limit,
                Duration cooldown,
                Duration retention) {
            this.policy = Objects.requireNonNull(policy);
            if (keyVersion <= 0 || keyDigest == null || keyDigest.length != 32) {
                throw new IllegalArgumentException("Invalid protected rate-limit key");
            }
            if (window == null
                    || window.isNegative()
                    || window.isZero()
                    || window.getNano() != 0
                    || limit <= 0) {
                throw new IllegalArgumentException("Invalid rate-limit window or threshold");
            }
            if (policy == RateLimitPolicy.RESEND_EMAIL) {
                if (cooldown == null || cooldown.isNegative() || cooldown.compareTo(window) > 0) {
                    throw new IllegalArgumentException("Invalid resend cooldown");
                }
            } else if (cooldown != null) {
                throw new IllegalArgumentException("Cooldown is limited to resend email");
            }
            if (retention == null || retention.compareTo(MINIMUM_RETENTION) < 0) {
                throw new IllegalArgumentException("Invalid rate-limit retention");
            }
            this.keyVersion = keyVersion;
            this.keyDigest = keyDigest.clone();
            this.window = window;
            this.limit = limit;
            this.cooldown = cooldown;
            this.retention = retention;
        }

        @Override
        public String toString() {
            return "BucketInput[REDACTED]";
        }
    }

    static final class StageResult {

        private static final StageResult ADMITTED = new StageResult(true, 0);
        private final boolean admitted;
        private final long retryAfterSeconds;

        private StageResult(boolean admitted, long retryAfterSeconds) {
            this.admitted = admitted;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static StageResult admitted() {
            return ADMITTED;
        }

        static StageResult rejected(long retryAfterSeconds) {
            if (retryAfterSeconds <= 0) {
                throw new IllegalArgumentException("Retry duration must be positive");
            }
            return new StageResult(false, retryAfterSeconds);
        }

        boolean isAdmitted() {
            return admitted;
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        @Override
        public String toString() {
            return admitted ? "StageResult[ADMITTED]" : "StageResult[REJECTED]";
        }
    }

    static final class StageWork {

        private final List<BucketWork> buckets;

        private StageWork(List<BucketWork> buckets) {
            this.buckets = buckets;
        }

        static StageWork create(Stage stage, List<BucketInput> inputs) {
            Objects.requireNonNull(stage);
            if (inputs == null || inputs.isEmpty() || inputs.size() > stage.policies.size() * 2) {
                throw new IllegalArgumentException("Invalid rate-limit stage size");
            }
            Map<Integer, EnumSet<RateLimitPolicy>> seen = new HashMap<>();
            Map<RateLimitPolicy, BucketInput> parameters = new HashMap<>();
            List<BucketWork> copied = new ArrayList<>(inputs.size());
            try {
                for (BucketInput input : inputs) {
                    if (input == null || !stage.policies.contains(input.policy)) {
                        throw new IllegalArgumentException("Invalid rate-limit stage policy");
                    }
                    EnumSet<RateLimitPolicy> policies =
                            seen.computeIfAbsent(
                                    input.keyVersion,
                                    ignored -> EnumSet.noneOf(RateLimitPolicy.class));
                    if (!policies.add(input.policy) || seen.size() > 2) {
                        throw new IllegalArgumentException("Duplicate or excess rate-limit key");
                    }
                    BucketInput first = parameters.putIfAbsent(input.policy, input);
                    if (first != null
                            && (!first.window.equals(input.window)
                                    || first.limit != input.limit
                                    || !Objects.equals(first.cooldown, input.cooldown)
                                    || !first.retention.equals(input.retention))) {
                        throw new IllegalArgumentException(
                                "Contradictory rate-limit policy parameters");
                    }
                    copied.add(new BucketWork(input));
                }
                for (EnumSet<RateLimitPolicy> policies : seen.values()) {
                    if (!policies.equals(stage.policies)) {
                        throw new IllegalArgumentException("Incomplete rate-limit stage");
                    }
                }
                return new StageWork(List.copyOf(copied));
            } catch (RuntimeException failure) {
                copied.forEach(BucketWork::clear);
                throw failure;
            }
        }

        List<BucketWork> buckets() {
            return buckets;
        }

        void clear() {
            buckets.forEach(BucketWork::clear);
        }

        @Override
        public String toString() {
            return "StageWork[REDACTED]";
        }
    }

    static final class BucketWork {

        final RateLimitPolicy policy;
        final int keyVersion;
        final byte[] keyDigest;
        final Duration window;
        final long limit;
        final Duration cooldown;
        final Duration retention;

        BucketWork(BucketInput input) {
            this.policy = input.policy;
            this.keyVersion = input.keyVersion;
            this.keyDigest = input.keyDigest.clone();
            this.window = input.window;
            this.limit = input.limit;
            this.cooldown = input.cooldown;
            this.retention = input.retention;
        }

        void clear() {
            Arrays.fill(keyDigest, (byte) 0);
        }

        @Override
        public String toString() {
            return "BucketWork[REDACTED]";
        }
    }
}

@FunctionalInterface
interface RateLimitStageWriter {

    void execute(RateLimitStagePersistence.StageWork work);
}

final class StageRejected extends RuntimeException {

    private final long retryAfterSeconds;

    StageRejected(long retryAfterSeconds) {
        super("Rate-limit stage rejected");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
