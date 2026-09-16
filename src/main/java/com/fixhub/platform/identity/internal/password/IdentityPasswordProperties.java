package com.fixhub.platform.identity.internal.password;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity-internal password-writing settings. Production deployments must supply every value;
 * development defaults live only in the local profile.
 */
@Validated
@ConfigurationProperties("fixhub.identity.password")
public record IdentityPasswordProperties(
        @NotNull @Valid Argon2 argon2, @NotNull @Valid Blocklist blocklist) {

    public record Argon2(
            @Min(16) int saltBytes,
            @Min(32) int hashBytes,
            @Min(1) int parallelism,
            @Min(19_456) int memoryKib,
            @Min(2) int iterations,
            @NotNull @Valid Admission admission) {}

    public record Admission(
            @Min(1) int maxConcurrency, @Min(1) @Max(3_600) int retryAfterSeconds) {}

    public record Blocklist(
            @NotBlank String artifactLocation,
            @NotBlank String manifestLocation,
            @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{64}") String sourceSha256,
            @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{64}") String artifactSha256,
            @Min(100_000) @Max(100_000) int expectedHibpEntryCount,
            @Min(100_000) @Max(200_000) int expectedFinalEntryCount,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String version) {}
}
