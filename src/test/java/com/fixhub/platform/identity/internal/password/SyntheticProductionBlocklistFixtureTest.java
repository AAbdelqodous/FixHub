package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SyntheticProductionBlocklistFixtureTest {

    @Test
    void ordinaryProductionLoaderAcceptsExactlyOneHundredThousandSyntheticEntries()
            throws Exception {
        SyntheticProductionBlocklistFixture.Fixture fixture =
                SyntheticProductionBlocklistFixture.get();

        assertThat(Files.exists(fixture.artifact())).isTrue();
        assertThat(Files.exists(fixture.manifest())).isTrue();
        assertThat(Files.size(fixture.artifact())).isEqualTo(4_100_000L);
        assertThat(fixture.artifact().toString()).doesNotContain("src");
        assertThat(PasswordBlocklistLoader.load(properties(fixture))).isNotNull();
    }

    @Test
    void initializerSuppliesTheSameProductionConfigurationBeforeBinding() {
        new ApplicationContextRunner()
                .withInitializer(new SyntheticProductionBlocklistInitializer())
                .withUserConfiguration(PasswordSecurityConfiguration.class)
                .withPropertyValues(
                        "fixhub.identity.password.argon2.salt-bytes=16",
                        "fixhub.identity.password.argon2.hash-bytes=32",
                        "fixhub.identity.password.argon2.parallelism=1",
                        "fixhub.identity.password.argon2.memory-kib=19456",
                        "fixhub.identity.password.argon2.iterations=2",
                        "fixhub.identity.password.argon2.admission.max-concurrency=1",
                        "fixhub.identity.password.argon2.admission.retry-after-seconds=1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionCountPolicyRejectsWeakOrMismatchedConfiguration() {
        SyntheticProductionBlocklistFixture.Fixture fixture =
                SyntheticProductionBlocklistFixture.get();
        assertThatThrownBy(() -> PasswordBlocklistLoader.load(properties(fixture, 99_999, 100_000)))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
        assertThatThrownBy(
                        () -> PasswordBlocklistLoader.load(properties(fixture, 100_001, 100_000)))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
        assertThatThrownBy(() -> PasswordBlocklistLoader.load(properties(fixture, 100_000, 99_999)))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
        assertThatThrownBy(
                        () -> PasswordBlocklistLoader.load(properties(fixture, 100_000, 200_001)))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
    }

    @Test
    void productionArtifactBoundaryIsInclusiveAndOverflowSafe() {
        assertThat(PasswordBlocklistLoader.artifactByteLimit(100_000)).isEqualTo(4_100_000L);
        assertThat(PasswordBlocklistLoader.artifactByteLimit(200_000)).isEqualTo(8_200_000L);
        assertThatThrownBy(() -> PasswordBlocklistLoader.artifactByteLimit(200_001))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
        assertThatThrownBy(() -> PasswordBlocklistLoader.artifactByteLimit(Integer.MAX_VALUE))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class);
    }

    @Test
    void productionCountPolicyAcceptsValidSupplementalGrowth() throws Exception {
        SyntheticProductionBlocklistFixture.Fixture fixture =
                SyntheticProductionBlocklistFixture.get();
        Path directory = Files.createTempDirectory("fixhub-expanded-blocklist-");
        byte[] base = Files.readAllBytes(fixture.artifact());
        byte[] extra =
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] artifact = new byte[base.length + extra.length];
        System.arraycopy(base, 0, artifact, 0, base.length);
        System.arraycopy(extra, 0, artifact, base.length, extra.length);
        Path artifactPath = directory.resolve("artifact.txt");
        Path manifestPath = directory.resolve("manifest.txt");
        Files.write(artifactPath, artifact);
        String artifactSha256 =
                HexFormat.of()
                        .withUpperCase()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
        Files.writeString(
                manifestPath, manifest(artifactSha256, 100_001), StandardCharsets.US_ASCII);
        assertThat(
                        PasswordBlocklistLoader.load(
                                new IdentityPasswordProperties.Blocklist(
                                        artifactPath.toUri().toString(),
                                        manifestPath.toUri().toString(),
                                        "A".repeat(64),
                                        artifactSha256,
                                        100_000,
                                        100_001,
                                        fixture.version())))
                .isNotNull();
    }

    private static IdentityPasswordProperties.Blocklist properties(
            SyntheticProductionBlocklistFixture.Fixture fixture) {
        return properties(fixture, 100_000, 100_000);
    }

    private static IdentityPasswordProperties.Blocklist properties(
            SyntheticProductionBlocklistFixture.Fixture fixture,
            int expectedHibpCount,
            int expectedFinalCount) {
        return new IdentityPasswordProperties.Blocklist(
                fixture.artifact().toUri().toString(),
                fixture.manifest().toUri().toString(),
                "A".repeat(64),
                fixture.artifactSha256(),
                expectedHibpCount,
                expectedFinalCount,
                fixture.version());
    }

    private static String manifest(String artifactSha256, int finalCount) {
        return String.join(
                        "\n",
                        "manifest-format-version=1",
                        "version=test-production-100000",
                        "source-url=https://example.invalid/hibp-synthetic-100000.txt",
                        "retrieval-date=2026-01-01",
                        "source-sha256=" + "A".repeat(64),
                        "transformation-procedure=fh011-top-100000-hibp-sha1-v1",
                        "artifact-format=uppercase-sha1-lf-v1",
                        "generator-version=test-fixture-generator-1",
                        "ranking=count-descending-hash-ascending",
                        "artifact-sha256=" + artifactSha256,
                        "hibp-entry-count=100000",
                        "supplemental-entry-count=1",
                        "duplicate-removal-count=0",
                        "final-entry-count=" + finalCount,
                        "license-review=reviewed")
                + "\n";
    }
}
