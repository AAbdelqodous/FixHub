package com.fixhub.platform.identity.internal.password;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/** Loads and fail-closed validates the versioned local digest artifact and manifest. */
final class PasswordBlocklistLoader {

    static final int SELECTED_HIBP_ENTRY_COUNT = 100_000;
    static final int MIN_FINAL_ENTRY_COUNT = 100_000;
    static final int MAX_FINAL_ENTRY_COUNT = 200_000;
    static final int DIGEST_HEX_LENGTH = 40;
    static final int ARTIFACT_RECORD_BYTES = DIGEST_HEX_LENGTH + 1;
    static final int MAX_ARTIFACT_BYTES = 8_200_000;
    private static final int MANIFEST_MAX_BYTES = 64 * 1024;
    private static final int MANIFEST_MAX_LINES = 32;
    private static final int MANIFEST_MAX_LINE_BYTES = 2_048;
    private static final int MANIFEST_MAX_KEY_BYTES = 64;
    private static final int MANIFEST_MAX_VALUE_BYTES = 1_024;
    private static final String TRANSFORMATION_PROCEDURE = "fh011-top-100000-hibp-sha1-v1";
    private static final String ARTIFACT_FORMAT = "uppercase-sha1-lf-v1";
    private static final String RANKING = "count-descending-hash-ascending";
    private static final String LICENSE_REVIEW = "reviewed";
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9A-F]{40}");
    private static final Pattern MANIFEST_KEY = Pattern.compile("[a-z0-9-]+");

    private PasswordBlocklistLoader() {}

    static PasswordBlocklist load(IdentityPasswordProperties.Blocklist properties) {
        return load(properties, SELECTED_HIBP_ENTRY_COUNT, true);
    }

    // Test-only seam: package-private and never reachable through Spring configuration.
    static PasswordBlocklist loadForTest(
            IdentityPasswordProperties.Blocklist properties, int syntheticSelectionCount) {
        if (syntheticSelectionCount < 1 || syntheticSelectionCount > SELECTED_HIBP_ENTRY_COUNT) {
            throw new IllegalArgumentException("Synthetic selection count is outside test bounds");
        }
        return load(properties, syntheticSelectionCount, false);
    }

    private static PasswordBlocklist load(
            IdentityPasswordProperties.Blocklist properties,
            int selectionCount,
            boolean productionPolicy) {
        try {
            validateFinalEntryCount(
                    properties.expectedFinalEntryCount(),
                    productionPolicy ? MIN_FINAL_ENTRY_COUNT : selectionCount);
            Map<String, String> manifest =
                    parseManifest(readManifest(properties.manifestLocation()));
            validateManifest(manifest, properties, selectionCount, productionPolicy);
            ArtifactResult artifact =
                    parseArtifact(
                            properties.artifactLocation(),
                            properties.expectedFinalEntryCount(),
                            productionPolicy ? MIN_FINAL_ENTRY_COUNT : selectionCount);
            requireEqual(artifact.sha256(), properties.artifactSha256(), "artifact checksum");
            requireEqual(artifact.sha256(), manifest.get("artifact-sha256"), "artifact checksum");
            int finalCount = parseCount(manifest, "final-entry-count");
            if (artifact.digests().size() != finalCount
                    || artifact.digests().size() != properties.expectedFinalEntryCount()) {
                throw invalid("final entry count");
            }
            return new PasswordBlocklist(artifact.digests());
        } catch (BlocklistFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("blocklist infrastructure");
        }
    }

    private static void validateManifest(
            Map<String, String> manifest,
            IdentityPasswordProperties.Blocklist properties,
            int selectionCount,
            boolean productionPolicy) {
        Set<String> required =
                Set.of(
                        "manifest-format-version",
                        "version",
                        "source-url",
                        "retrieval-date",
                        "source-sha256",
                        "transformation-procedure",
                        "artifact-format",
                        "generator-version",
                        "ranking",
                        "artifact-sha256",
                        "hibp-entry-count",
                        "supplemental-entry-count",
                        "duplicate-removal-count",
                        "final-entry-count",
                        "license-review");
        if (!manifest.keySet().equals(required)
                || parseCount(manifest, "manifest-format-version") != 1
                || !manifest.get("version").equals(properties.version())
                || !isSafeSourceUrl(manifest.get("source-url"))
                || !isDate(manifest.get("retrieval-date"))
                || !SHA256.matcher(manifest.get("source-sha256")).matches()
                || !manifest.get("source-sha256").equalsIgnoreCase(properties.sourceSha256())
                || !manifest.get("transformation-procedure").equals(TRANSFORMATION_PROCEDURE)
                || !manifest.get("artifact-format").equals(ARTIFACT_FORMAT)
                || !isBoundedText(manifest.get("generator-version"))
                || !manifest.get("ranking").equals(RANKING)
                || !SHA256.matcher(manifest.get("artifact-sha256")).matches()
                || !manifest.get("artifact-sha256").equalsIgnoreCase(properties.artifactSha256())
                || parseCount(manifest, "hibp-entry-count") != selectionCount
                || parseCount(manifest, "hibp-entry-count") != properties.expectedHibpEntryCount()
                || parseCount(manifest, "final-entry-count") != properties.expectedFinalEntryCount()
                || !manifest.get("license-review").equals(LICENSE_REVIEW)) {
            throw invalid("manifest provenance");
        }
        int supplemental = parseCount(manifest, "supplemental-entry-count");
        int duplicate = parseCount(manifest, "duplicate-removal-count");
        int finalCount = parseCount(manifest, "final-entry-count");
        try {
            if (duplicate > supplemental
                    || Math.addExact(selectionCount, Math.subtractExact(supplemental, duplicate))
                            != finalCount
                    || finalCount < (productionPolicy ? MIN_FINAL_ENTRY_COUNT : selectionCount)
                    || finalCount > MAX_FINAL_ENTRY_COUNT) {
                throw invalid("manifest counts");
            }
        } catch (ArithmeticException exception) {
            throw invalid("manifest counts");
        }
    }

    private static Map<String, String> parseManifest(byte[] bytes) {
        String text = decodeStrictUtf8(bytes);
        List<String> lines = strictLines(text, MANIFEST_MAX_LINES, MANIFEST_MAX_LINE_BYTES);
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator <= 0
                    || separator > MANIFEST_MAX_KEY_BYTES
                    || !MANIFEST_KEY.matcher(line.substring(0, separator)).matches()
                    || line.substring(separator + 1).isEmpty()
                    || line.length() - separator - 1 > MANIFEST_MAX_VALUE_BYTES
                    || values.put(line.substring(0, separator), line.substring(separator + 1))
                            != null) {
                throw invalid("manifest record");
            }
        }
        return values;
    }

    private static byte[] readManifest(String location) {
        try (InputStream input = resource(location)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MANIFEST_MAX_BYTES) {
                    throw invalid("manifest size");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (BlocklistFailure exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("configured blocklist resource");
        }
    }

    private static ArtifactResult parseArtifact(
            String location, int expectedCount, int minimumCount) {
        long maximumBytes = artifactByteLimit(expectedCount, minimumCount);
        try (InputStream input = resource(location)) {
            MessageDigest checksum = MessageDigest.getInstance("SHA-256");
            Set<String> digests = new HashSet<>();
            byte[] line = new byte[ARTIFACT_RECORD_BYTES];
            int length = 0;
            long bytesRead = 0;
            String previous = null;
            boolean sawLf = false;
            while (true) {
                int value = input.read();
                if (value == -1) {
                    if (length != 0 || !sawLf || digests.isEmpty()) {
                        throw invalid("artifact line format");
                    }
                    return new ArtifactResult(digests, checksum.digest());
                }
                bytesRead++;
                if (bytesRead > maximumBytes) {
                    throw invalid("artifact size");
                }
                checksum.update((byte) value);
                if (value == '\n') {
                    sawLf = true;
                    if (length != DIGEST_HEX_LENGTH) {
                        throw invalid("artifact record");
                    }
                    String digest = new String(line, 0, length, StandardCharsets.US_ASCII);
                    if (!DIGEST.matcher(digest).matches()
                            || (previous != null && digest.compareTo(previous) <= 0)
                            || !digests.add(digest)) {
                        throw invalid("artifact record");
                    }
                    if (digests.size() > expectedCount) {
                        throw invalid("artifact entry count");
                    }
                    previous = digest;
                    length = 0;
                } else {
                    if (value == '\r' || value == 0 || value < 0x20 || value > 0x7E) {
                        throw invalid("artifact line format");
                    }
                    if (length == line.length) {
                        throw invalid("artifact line length");
                    }
                    line[length++] = (byte) value;
                }
            }
        } catch (BlocklistFailure exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw invalid("configured blocklist resource");
        }
    }

    static long artifactByteLimit(int expectedCount) {
        return artifactByteLimit(expectedCount, MIN_FINAL_ENTRY_COUNT);
    }

    private static long artifactByteLimit(int expectedCount, int minimumCount) {
        validateFinalEntryCount(expectedCount, minimumCount);
        try {
            long bytes = Math.multiplyExact((long) expectedCount, ARTIFACT_RECORD_BYTES);
            if (bytes > MAX_ARTIFACT_BYTES) {
                throw invalid("artifact size");
            }
            return bytes;
        } catch (ArithmeticException exception) {
            throw invalid("artifact size");
        }
    }

    private static void validateFinalEntryCount(int finalCount, int minimumCount) {
        if (finalCount < minimumCount || finalCount > MAX_FINAL_ENTRY_COUNT) {
            throw invalid("final entry count");
        }
    }

    private static List<String> strictLines(String text, int maxLines, int maxLineBytes) {
        if (text.isEmpty() || text.charAt(0) == '\uFEFF' || !text.endsWith("\n")) {
            throw invalid("line format");
        }
        String body = text.substring(0, text.length() - 1);
        String[] lines = body.split("\\n", -1);
        if (lines.length > maxLines) {
            throw invalid("manifest line count");
        }
        for (String line : lines) {
            if (line.isEmpty() || line.indexOf('\r') >= 0 || line.length() > maxLineBytes) {
                throw invalid("line format");
            }
            for (int index = 0; index < line.length(); index++) {
                if (line.charAt(index) == 0 || line.charAt(index) < 0x20) {
                    throw invalid("line format");
                }
            }
        }
        return List.of(lines);
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        try {
            CharBuffer chars =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException exception) {
            throw invalid("UTF-8 encoding");
        }
    }

    private static InputStream resource(String location) throws IOException {
        if (location.startsWith("classpath:/")) {
            String classpathLocation = location.substring(10);
            if (classpathLocation.startsWith("/")
                    || classpathLocation.contains("\\")
                    || Arrays.stream(classpathLocation.split("/", -1)).anyMatch(".."::equals)) {
                throw invalid("blocklist resource location");
            }
            return new ClassPathResource(classpathLocation).getInputStream();
        }
        if (!location.startsWith("file:")) {
            throw invalid("blocklist resource location");
        }
        URI uri;
        try {
            uri = URI.create(location);
        } catch (IllegalArgumentException exception) {
            throw invalid("blocklist resource location");
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() != null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getPath() == null
                || uri.getPath().startsWith("\\\\")
                || uri.getPath().startsWith("//")) {
            throw invalid("blocklist resource location");
        }
        Path path = Path.of(uri).toAbsolutePath().normalize();
        if (!path.isAbsolute()
                || path.toString().startsWith("\\\\")
                || path.toString().startsWith("//")) {
            throw invalid("blocklist resource location");
        }
        Path realPath = path.toRealPath();
        if (realPath.toString().startsWith("\\\\") || realPath.toString().startsWith("//")) {
            throw invalid("blocklist resource location");
        }
        return new FileSystemResource(realPath).getInputStream();
    }

    private static int parseCount(Map<String, String> values, String key) {
        try {
            int count = Integer.parseInt(values.get(key));
            if (count < 0) {
                throw invalid("manifest count");
            }
            return count;
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalid("manifest count");
        }
    }

    private static boolean isSafeSourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getQuery() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isBoundedText(String value) {
        return value.length() <= 64 && value.chars().allMatch(character -> character >= 0x21);
    }

    private static void requireEqual(byte[] actual, String expected, String reason) {
        String actualHex = java.util.HexFormat.of().withUpperCase().formatHex(actual);
        if (!actualHex.equalsIgnoreCase(expected)) {
            throw invalid(reason);
        }
    }

    private static BlocklistFailure invalid(String reason) {
        return new BlocklistFailure("Password blocklist validation failed: " + reason);
    }

    private record ArtifactResult(Set<String> digests, byte[] sha256) {}

    static final class BlocklistFailure extends IllegalStateException {
        private BlocklistFailure(String message) {
            super(message);
        }
    }
}
