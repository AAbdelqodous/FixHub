package com.fixhub.platform.identity.internal.ratelimit;

import java.util.Objects;
import java.util.function.Supplier;

/** Unwired registration rate-limit admission with validation between the two committed stages. */
final class RegistrationRateLimitAdmissionCoordinator {

    private final PostgresRateLimitOperationTimeSource operationTimeSource;
    private final RateLimitStageComposer composer;
    private final RateLimitStagePersistence persistence;

    RegistrationRateLimitAdmissionCoordinator(
            PostgresRateLimitOperationTimeSource operationTimeSource,
            RateLimitStageComposer composer,
            RateLimitStagePersistence persistence) {
        this.operationTimeSource = Objects.requireNonNull(operationTimeSource);
        this.composer = Objects.requireNonNull(composer);
        this.persistence = Objects.requireNonNull(persistence);
    }

    RateLimitStagePersistence.StageResult admit(
            CanonicalOrigin origin, Supplier<String> validatedEmail) {
        Objects.requireNonNull(validatedEmail);
        RateLimitOperationTime operationTime = operationTimeSource.acquire();
        RateLimitStageComposer.StageDescription coarse =
                composer.registrationCoarse(origin, operationTime.instant());
        RateLimitStagePersistence.StageResult coarseResult =
                persistence.evaluate(coarse.stage(), coarse.inputs(), operationTime);
        if (!coarseResult.isAdmitted()) {
            return coarseResult;
        }

        String normalizedEmail = validatedEmail.get();
        RateLimitStageComposer.StageDescription email =
                composer.registrationEmail(normalizedEmail, operationTime.instant());
        return persistence.evaluate(email.stage(), email.inputs(), operationTime);
    }
}
