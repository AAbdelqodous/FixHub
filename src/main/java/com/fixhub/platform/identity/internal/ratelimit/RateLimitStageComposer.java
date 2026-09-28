package com.fixhub.platform.identity.internal.ratelimit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure, unwired composition of the approved rate-limit stages. */
final class RateLimitStageComposer {

    private static final String INVALID_INPUT = "Invalid rate-limit stage composition input";
    private static final String INVALID_VERSIONS = "Inconsistent rate-limit key versions";
    private static final String INVALID_DESCRIPTION = "Invalid rate-limit stage description";

    private final RateLimitIdentifierProtector protector;
    private final RateLimitPolicySettings settings;

    RateLimitStageComposer(
            RateLimitIdentifierProtector protector, RateLimitPolicySettings settings) {
        if (protector == null || settings == null) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        this.protector = protector;
        this.settings = settings;
    }

    StageDescription registrationCoarse(CanonicalOrigin origin, Instant decisionInstant) {
        return coarse(
                RateLimitStagePersistence.Stage.REGISTRATION_COARSE,
                RateLimitPolicy.REGISTRATION_ORIGIN,
                origin,
                decisionInstant);
    }

    StageDescription registrationEmail(String normalizedEmail, Instant decisionInstant) {
        return email(
                RateLimitStagePersistence.Stage.REGISTRATION_EMAIL,
                RateLimitPolicy.REGISTRATION_EMAIL,
                normalizedEmail,
                decisionInstant);
    }

    StageDescription resendCoarse(CanonicalOrigin origin, Instant decisionInstant) {
        return coarse(
                RateLimitStagePersistence.Stage.RESEND_COARSE,
                RateLimitPolicy.RESEND_ORIGIN,
                origin,
                decisionInstant);
    }

    StageDescription resendEmail(String normalizedEmail, Instant decisionInstant) {
        return email(
                RateLimitStagePersistence.Stage.RESEND_EMAIL,
                RateLimitPolicy.RESEND_EMAIL,
                normalizedEmail,
                decisionInstant);
    }

    StageDescription verificationCoarse(CanonicalOrigin origin, Instant decisionInstant) {
        return coarse(
                RateLimitStagePersistence.Stage.VERIFICATION_COARSE,
                RateLimitPolicy.VERIFICATION_ORIGIN,
                origin,
                decisionInstant);
    }

    private StageDescription coarse(
            RateLimitStagePersistence.Stage stage,
            RateLimitPolicy originPolicy,
            CanonicalOrigin origin,
            Instant decisionInstant) {
        if (origin == null || decisionInstant == null) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        byte[] canonical = origin.bytes();
        try {
            List<RateLimitProtectedKey> originKeys =
                    protector.protectOrigin(originPolicy, canonical, decisionInstant);
            List<RateLimitProtectedKey> globalKeys =
                    protector.protectGlobal(RateLimitPolicy.FH011_GLOBAL, decisionInstant);
            validateVersions(originKeys, globalKeys);
            List<RateLimitStagePersistence.BucketInput> inputs =
                    new ArrayList<>(originKeys.size() + globalKeys.size());
            addInputs(inputs, originPolicy, originKeys);
            addInputs(inputs, RateLimitPolicy.FH011_GLOBAL, globalKeys);
            return new StageDescription(stage, inputs);
        } finally {
            Arrays.fill(canonical, (byte) 0);
        }
    }

    private StageDescription email(
            RateLimitStagePersistence.Stage stage,
            RateLimitPolicy policy,
            String normalizedEmail,
            Instant decisionInstant) {
        if (normalizedEmail == null || decisionInstant == null) {
            throw new IllegalArgumentException(INVALID_INPUT);
        }
        List<RateLimitProtectedKey> keys =
                protector.protectEmail(policy, normalizedEmail, decisionInstant);
        validateVersions(keys);
        List<RateLimitStagePersistence.BucketInput> inputs = new ArrayList<>(keys.size());
        addInputs(inputs, policy, keys);
        return new StageDescription(stage, inputs);
    }

    private void addInputs(
            List<RateLimitStagePersistence.BucketInput> inputs,
            RateLimitPolicy policy,
            List<RateLimitProtectedKey> keys) {
        RateLimitPolicySettings.Setting setting = settings.forPolicy(policy);
        for (RateLimitProtectedKey key : keys) {
            byte[] digest = key.digest();
            try {
                inputs.add(
                        new RateLimitStagePersistence.BucketInput(
                                policy,
                                key.version(),
                                digest,
                                setting.window(),
                                setting.limit(),
                                setting.cooldown(),
                                settings.retention()));
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    private static void validateVersions(List<RateLimitProtectedKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 2) {
            throw new IllegalStateException(INVALID_VERSIONS);
        }
        int first = version(keys.get(0));
        if (keys.size() == 2 && version(keys.get(1)) == first) {
            throw new IllegalStateException(INVALID_VERSIONS);
        }
    }

    private static void validateVersions(
            List<RateLimitProtectedKey> originKeys, List<RateLimitProtectedKey> globalKeys) {
        validateVersions(originKeys);
        validateVersions(globalKeys);
        if (originKeys.size() != globalKeys.size()) {
            throw new IllegalStateException(INVALID_VERSIONS);
        }
        for (int index = 0; index < originKeys.size(); index++) {
            if (originKeys.get(index).version() != globalKeys.get(index).version()) {
                throw new IllegalStateException(INVALID_VERSIONS);
            }
        }
    }

    private static int version(RateLimitProtectedKey key) {
        if (key == null || key.version() <= 0) {
            throw new IllegalStateException(INVALID_VERSIONS);
        }
        return key.version();
    }

    @Override
    public String toString() {
        return "RateLimitStageComposer[REDACTED]";
    }

    static final class StageDescription {

        private final RateLimitStagePersistence.Stage stage;
        private final List<RateLimitStagePersistence.BucketInput> inputs;

        private StageDescription(
                RateLimitStagePersistence.Stage stage,
                List<RateLimitStagePersistence.BucketInput> inputs) {
            try {
                List<RateLimitStagePersistence.BucketInput> copied = List.copyOf(inputs);
                RateLimitStagePersistence.StageWork validated =
                        RateLimitStagePersistence.StageWork.create(stage, copied);
                try {
                    this.stage = stage;
                    this.inputs = copied;
                } finally {
                    validated.clear();
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(INVALID_DESCRIPTION);
            }
        }

        RateLimitStagePersistence.Stage stage() {
            return stage;
        }

        List<RateLimitStagePersistence.BucketInput> inputs() {
            return inputs;
        }

        @Override
        public String toString() {
            return "StageDescription[REDACTED]";
        }
    }
}
