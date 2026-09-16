package com.fixhub.platform.identity.internal.password;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity-internal password-writing settings. Production deployments must supply every value;
 * development defaults live only in the local profile.
 */
@Validated
@ConfigurationProperties("fixhub.identity.password")
public record IdentityPasswordProperties(@NotNull @Valid Argon2 argon2) {

    public record Argon2(
            @Min(16) int saltBytes,
            @Min(32) int hashBytes,
            @Min(1) int parallelism,
            @Min(19_456) int memoryKib,
            @Min(2) int iterations,
            @NotNull @Valid Admission admission) {}

    public record Admission(
            @Min(1) int maxConcurrency, @Min(1) @Max(3_600) int retryAfterSeconds) {}
}
