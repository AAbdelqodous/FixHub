package com.fixhub.platform.identity.internal.password;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test-only production-sized blocklist material; never packaged as application resources. */
final class SyntheticProductionBlocklistFixture {

    private static final String SOURCE_SHA256 = "A".repeat(64);
    private static final int ENTRY_COUNT = 100_000;

    private SyntheticProductionBlocklistFixture() {}

    static Fixture get() {
        return Holder.INSTANCE;
    }

    static String[] propertyValues() {
        Fixture fixture = get();
        return new String[] {
            "fixhub.identity.password.blocklist.artifact-location=" + fixture.artifact().toUri(),
            "fixhub.identity.password.blocklist.manifest-location=" + fixture.manifest().toUri(),
            "fixhub.identity.password.blocklist.source-sha256=" + SOURCE_SHA256,
            "fixhub.identity.password.blocklist.artifact-sha256=" + fixture.artifactSha256(),
            "fixhub.identity.password.blocklist.expected-hibp-entry-count=" + ENTRY_COUNT,
            "fixhub.identity.password.blocklist.expected-final-entry-count=" + ENTRY_COUNT,
            "fixhub.identity.password.blocklist.version=" + fixture.version()
        };
    }

    private static Fixture create() {
        try {
            Path directory = Files.createTempDirectory("fixhub-synthetic-blocklist-");
            Path artifact = publishArtifact(directory);
            String artifactSha256 = sha256(Files.readAllBytes(artifact));
            Path manifest = publishManifest(directory, artifactSha256);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(directory)));
            return new Fixture(artifact, manifest, artifactSha256, "test-production-100000");
        } catch (IOException exception) {
            throw new IllegalStateException("Synthetic blocklist fixture setup failed", exception);
        }
    }

    private static Path publishArtifact(Path directory) throws IOException {
        Path temporary = Files.createTempFile(directory, "artifact-", ".tmp");
        Path target = directory.resolve("synthetic-production-blocklist.txt");
        StringBuilder content = new StringBuilder(4_100_000);
        for (int index = 0; index < ENTRY_COUNT; index++) {
            content.append(String.format("%040X", index)).append('\n');
        }
        Files.writeString(temporary, content, StandardCharsets.US_ASCII);
        moveAtomically(temporary, target);
        return target;
    }

    private static Path publishManifest(Path directory, String artifactSha256) throws IOException {
        Path temporary = Files.createTempFile(directory, "manifest-", ".tmp");
        Path target = directory.resolve("synthetic-production-blocklist.manifest");
        String content =
                String.join(
                                "\n",
                                "manifest-format-version=1",
                                "version=test-production-100000",
                                "source-url=https://example.invalid/hibp-synthetic-100000.txt",
                                "retrieval-date=2026-01-01",
                                "source-sha256=" + SOURCE_SHA256,
                                "transformation-procedure=fh011-top-100000-hibp-sha1-v1",
                                "artifact-format=uppercase-sha1-lf-v1",
                                "generator-version=test-fixture-generator-1",
                                "ranking=count-descending-hash-ascending",
                                "artifact-sha256=" + artifactSha256,
                                "hibp-entry-count=100000",
                                "supplemental-entry-count=0",
                                "duplicate-removal-count=0",
                                "final-entry-count=100000",
                                "license-review=reviewed")
                        + "\n";
        Files.writeString(temporary, content, StandardCharsets.US_ASCII);
        moveAtomically(temporary, target);
        return target;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic blocklist checksum setup failed", exception);
        }
    }

    private static void delete(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best-effort cleanup of JVM-temporary test material.
                                }
                            });
        } catch (IOException ignored) {
            // Best-effort cleanup of JVM-temporary test material.
        }
    }

    record Fixture(Path artifact, Path manifest, String artifactSha256, String version) {}

    private static final class Holder {
        private static final Fixture INSTANCE = create();
    }
}
