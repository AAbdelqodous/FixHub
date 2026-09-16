package com.fixhub.platform.identity.internal.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordBlocklistLoaderTest {

    private static final String BLOCKED_PASSWORD = "synthetic-blocked-password";
    private static final String BLOCKED_DIGEST = "177B78DCB3C576EDCFDF2ABF2CAC20FA12F3DCB7";
    private static final String ARTIFACT_SHA256 =
            "1AA26D0926F96FCFEEB0221C7A58C73FB10ED3D6C7C193A2EAA39989E0431418";
    private static final String SOURCE_SHA256 = "A".repeat(64);
    private static final String FIRST_DIGEST = BLOCKED_DIGEST;
    private static final String SECOND_DIGEST = "761FEC039234080C400B98E65667B5350CF92D0C";
    private static final String THIRD_DIGEST = "E4B202F99A973383D4723865760ABA29593C247B";

    @TempDir Path temporaryDirectory;

    @Test
    void loadsTheValidatedSyntheticArtifactAndManifest() {
        PasswordBlocklist blocklist = loadValid();

        assertThatThrownBy(() -> blocklist.assertNotCompromised(BLOCKED_PASSWORD))
                .isInstanceOf(CompromisedPasswordException.class)
                .hasMessage("Password is present in the compromised-password blocklist");
        blocklist.assertNotCompromised("synthetic-never-listed-password");
        blocklist.assertNotCompromised(" synthetic-blocked-password ");
        blocklist.assertNotCompromised("🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂🙂");
        assertThat(String.valueOf(blocklist)).doesNotContain(BLOCKED_PASSWORD, BLOCKED_DIGEST);
    }

    @Test
    void comparesTheCompleteNfcNormalizedPasswordBeforeEncoding() {
        PasswordBlocklist blocklist = loadValid();
        RegistrationPasswordPolicy policy = new RegistrationPasswordPolicy();
        String decomposed = "synthetic-" + "e\u0301" + "-password";
        String composed = "synthetic-é-password";

        assertThatThrownBy(
                        () ->
                                blocklist.assertNotCompromised(
                                        policy.normalizeAndValidate(decomposed)))
                .isInstanceOf(CompromisedPasswordException.class);
        assertThatThrownBy(
                        () -> blocklist.assertNotCompromised(policy.normalizeAndValidate(composed)))
                .isInstanceOf(CompromisedPasswordException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.normalizeAndValidate("x".repeat(14) + '\uD800'))
                .withMessage("Password contains malformed Unicode");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> blocklist.assertNotCompromised(null))
                .withMessage("Password is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> blocklist.assertNotCompromised(decomposed))
                .withMessage("Password must be NFC normalized");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> blocklist.assertNotCompromised("x".repeat(14) + '\uD800'))
                .withMessage("Password contains malformed Unicode");
    }

    @Test
    void blocklistRejectionPrecedesTheExistingEncodingBoundary() {
        PasswordBlocklist blocklist = loadValid();
        PasswordEncodingService service =
                new PasswordEncodingService(
                        PasswordSecurityConfiguration.createPasswordEncoder(passwordProperties()),
                        new Argon2AdmissionControl(1));

        assertThatThrownBy(
                        () -> {
                            String normalized =
                                    new RegistrationPasswordPolicy()
                                            .normalizeAndValidate(BLOCKED_PASSWORD);
                            blocklist.assertNotCompromised(normalized);
                            service.encodeNormalizedPassword(normalized);
                        })
                .isInstanceOf(CompromisedPasswordException.class);
        String encoded = service.encodeNormalizedPassword("synthetic-never-listed-password");
        assertThat(encoded.startsWith("{argon2id}")).isTrue();
    }

    @Test
    void rejectsMissingArtifactManifestAndDirectoryInputs() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("resource-directory"));
        assertFailure(
                properties(
                        directory.resolve("missing-artifact.txt").toUri().toString(),
                        validManifestPath().toUri().toString()));
        assertFailure(
                properties(
                        validArtifactPath().toUri().toString(),
                        directory.resolve("missing-manifest.txt").toUri().toString()));
        assertFailure(
                properties(directory.toUri().toString(), validManifestPath().toUri().toString()));
    }

    @Test
    void rejectsRemoteRelativeAndNetworkResourceLocations() {
        assertFailure(
                properties(
                        "https://example.invalid/artifact",
                        validManifestPath().toUri().toString()));
        assertFailure(
                properties(
                        "ftp://example.invalid/artifact", validManifestPath().toUri().toString()));
        assertFailure(properties("relative-artifact.txt", validManifestPath().toUri().toString()));
        assertFailure(
                properties(
                        "file://server/share/artifact.txt",
                        validManifestPath().toUri().toString()));
        assertFailure(
                properties(
                        validArtifactPath().toUri().toString(),
                        "https://example.invalid/manifest"));
        assertFailure(
                properties(
                        "classpath:/../application.yaml", validManifestPath().toUri().toString()));
    }

    @Test
    void rejectsChecksumVersionAndCountMismatches() {
        assertFailure(propertiesWithSourceSha256("B".repeat(64)));
        assertFailure(propertiesWithArtifactSha256("B".repeat(64)));
        assertFailure(
                loadCase(artifactBytes(), manifestFor(ARTIFACT_SHA256, "wrong-version", 3, 3)));
        assertFailure(
                loadCase(artifactBytes(), manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 2, 3)));
        assertFailure(
                loadCase(artifactBytes(), manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 2)));
    }

    @Test
    void rejectsMalformedArtifactEncodingsRecordsAndOrdering() {
        assertInvalidArtifact(new byte[0]);
        assertInvalidArtifact(bytes("\n"));
        assertInvalidArtifact(bytes("\uFEFF" + artifactText()));
        assertInvalidArtifact(bytes(artifactText().replace("\n", "\r\n")));
        assertInvalidArtifact(
                bytes(SECOND_DIGEST + "\n" + FIRST_DIGEST + "\n" + THIRD_DIGEST + "\n"));
        assertInvalidArtifact(
                bytes(FIRST_DIGEST + "\n" + FIRST_DIGEST + "\n" + THIRD_DIGEST + "\n"));
        assertInvalidArtifact(
                bytes(
                        FIRST_DIGEST.toLowerCase(Locale.ROOT)
                                + "\n"
                                + SECOND_DIGEST
                                + "\n"
                                + THIRD_DIGEST
                                + "\n"));
        assertInvalidArtifact(
                bytes(
                        "Z"
                                + FIRST_DIGEST.substring(1)
                                + "\n"
                                + SECOND_DIGEST
                                + "\n"
                                + THIRD_DIGEST
                                + "\n"));
        assertInvalidArtifact(
                bytes(
                        FIRST_DIGEST.substring(1)
                                + "\n"
                                + SECOND_DIGEST
                                + "\n"
                                + THIRD_DIGEST
                                + "\n"));
        assertInvalidArtifact(
                bytes(" " + FIRST_DIGEST + "\n" + SECOND_DIGEST + "\n" + THIRD_DIGEST + "\n"));
        assertInvalidArtifact(
                bytes(FIRST_DIGEST + "\n\n" + SECOND_DIGEST + "\n" + THIRD_DIGEST + "\n"));
        assertInvalidArtifact(
                bytes(FIRST_DIGEST + "\n" + SECOND_DIGEST + "\n" + THIRD_DIGEST + "\ntrailing\n"));
    }

    @Test
    void rejectsMalformedManifestStructureAndEncoding() throws Exception {
        assertFailure(
                loadCase(
                        artifactBytes(),
                        manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 3) + "unexpected\n"));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        "\uFEFF" + manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 3)));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 3)
                                .replace("\n", "\r\n")));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 3)
                                .replace(
                                        "license-review=reviewed",
                                        "license-review=reviewed\nversion=test-synthetic-1")));
        byte[] nonUtf8 = new byte[] {(byte) 0xC3, (byte) 0x28};
        Path artifact = temporaryDirectory.resolve("non-utf8-artifact.txt");
        Path manifest = temporaryDirectory.resolve("non-utf8-manifest.txt");
        Files.write(artifact, artifactBytes());
        Files.write(manifest, nonUtf8);
        assertFailure(properties(artifact.toUri().toString(), manifest.toUri().toString()));
    }

    @Test
    void rejectsManifestBoundsControlsAndUnsafeProvenance() {
        String valid = manifestFor(ARTIFACT_SHA256, "test-synthetic-1", 3, 3);
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace(
                                "source-url=https://example.invalid/hibp-synthetic.txt",
                                "source-url=https://user:secret@example.invalid/source")));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace(
                                "source-url=https://example.invalid/hibp-synthetic.txt",
                                "source-url=https://example.invalid/source?token=secret")));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace(
                                "source-url=https://example.invalid/hibp-synthetic.txt",
                                "source-url=https://example.invalid/source#fragment")));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace(
                                "source-url=https://example.invalid/hibp-synthetic.txt",
                                "source-url=http://example.invalid/source")));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace(
                                "generator-version=test-synthetic-generator-1",
                                "generator-version=" + "x".repeat(65))));
        assertFailure(loadCase(artifactBytes(), valid + "x".repeat(65_000)));
        assertFailure(
                loadCase(
                        artifactBytes(),
                        valid.replace("version=test-synthetic-1", "version=test\u0000synthetic")));
    }

    private void assertInvalidArtifact(byte[] artifact) {
        assertFailure(loadCase(artifact, manifestFor(sha256(artifact), "test-synthetic-1", 3, 3)));
    }

    private void assertFailure(IdentityPasswordProperties.Blocklist properties) {
        assertThatThrownBy(() -> PasswordBlocklistLoader.loadForTest(properties, 3))
                .isInstanceOf(PasswordBlocklistLoader.BlocklistFailure.class)
                .hasMessageStartingWith("Password blocklist validation failed:")
                .hasMessageNotContaining(BLOCKED_PASSWORD)
                .hasMessageNotContaining(BLOCKED_DIGEST);
    }

    private PasswordBlocklist loadValid() {
        return PasswordBlocklistLoader.loadForTest(properties(), 3);
    }

    private IdentityPasswordProperties.Blocklist properties() {
        return properties(
                validArtifactPath().toUri().toString(), validManifestPath().toUri().toString());
    }

    private IdentityPasswordProperties passwordProperties() {
        return new IdentityPasswordProperties(
                new IdentityPasswordProperties.Argon2(
                        16, 32, 1, 19_456, 2, new IdentityPasswordProperties.Admission(1, 1)),
                properties());
    }

    private IdentityPasswordProperties.Blocklist properties(String artifact, String manifest) {
        return new IdentityPasswordProperties.Blocklist(
                artifact, manifest, SOURCE_SHA256, ARTIFACT_SHA256, 3, 3, "test-synthetic-1");
    }

    private IdentityPasswordProperties.Blocklist propertiesWithSourceSha256(String sourceSha256) {
        return new IdentityPasswordProperties.Blocklist(
                validArtifactPath().toUri().toString(),
                validManifestPath().toUri().toString(),
                sourceSha256,
                ARTIFACT_SHA256,
                3,
                3,
                "test-synthetic-1");
    }

    private IdentityPasswordProperties.Blocklist propertiesWithArtifactSha256(
            String artifactSha256) {
        return new IdentityPasswordProperties.Blocklist(
                validArtifactPath().toUri().toString(),
                validManifestPath().toUri().toString(),
                SOURCE_SHA256,
                artifactSha256,
                3,
                3,
                "test-synthetic-1");
    }

    private IdentityPasswordProperties.Blocklist loadCase(byte[] artifact, String manifest) {
        try {
            Path directory = Files.createTempDirectory(temporaryDirectory, "blocklist-case-");
            Path artifactPath = directory.resolve("artifact.txt");
            Path manifestPath = directory.resolve("manifest.txt");
            Files.write(artifactPath, artifact);
            Files.writeString(manifestPath, manifest, StandardCharsets.UTF_8);
            String artifactSha256 = sha256(artifact);
            return new IdentityPasswordProperties.Blocklist(
                    artifactPath.toUri().toString(),
                    manifestPath.toUri().toString(),
                    SOURCE_SHA256,
                    artifactSha256,
                    3,
                    3,
                    "test-synthetic-1");
        } catch (Exception exception) {
            throw new AssertionError("Synthetic fixture setup failed", exception);
        }
    }

    private Path validArtifactPath() {
        return Path.of("src/test/resources/blocklist/synthetic-blocklist.txt").toAbsolutePath();
    }

    private Path validManifestPath() {
        return Path.of("src/test/resources/blocklist/synthetic-blocklist.manifest")
                .toAbsolutePath();
    }

    private static byte[] artifactBytes() {
        return artifactText().getBytes(StandardCharsets.UTF_8);
    }

    private static String artifactText() {
        return FIRST_DIGEST + "\n" + SECOND_DIGEST + "\n" + THIRD_DIGEST + "\n";
    }

    private static String manifestFor(
            String artifactSha256, String version, int hibpCount, int finalCount) {
        return String.join(
                        "\n",
                        "manifest-format-version=1",
                        "version=" + version,
                        "source-url=https://example.invalid/hibp-synthetic.txt",
                        "retrieval-date=2026-01-01",
                        "source-sha256=" + SOURCE_SHA256,
                        "transformation-procedure=fh011-top-100000-hibp-sha1-v1",
                        "artifact-format=uppercase-sha1-lf-v1",
                        "generator-version=test-synthetic-generator-1",
                        "ranking=count-descending-hash-ascending",
                        "artifact-sha256=" + artifactSha256,
                        "hibp-entry-count=" + hibpCount,
                        "supplemental-entry-count=3",
                        "duplicate-removal-count=3",
                        "final-entry-count=" + finalCount,
                        "license-review=reviewed")
                + "\n";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError("Synthetic checksum setup failed", exception);
        }
    }
}
