package com.fixhub.platform.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCatalogueConsistencyTest {

    private static final String SUMMARY_HEADING_TEXT = "Common catalogue summary";
    private static final List<String> TABLE_HEADERS =
            List.of("Code", "HTTP status", "Default detail");
    private static final Pattern ATX_HEADING_OPENING =
            Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");
    private static final Pattern STATUS_CELL = Pattern.compile("^(\\d{3})\\s+.+$");
    private static final Pattern SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");

    @Test
    void commonErrorCodeMatchesTheApprovedCatalogue() throws IOException {
        Map<String, CatalogueEntry> documented = parseCommonCatalogue(cataloguePath());
        Map<String, CommonErrorCode> implemented =
                Arrays.stream(CommonErrorCode.values())
                        .collect(Collectors.toMap(CommonErrorCode::code, code -> code));

        assertThat(implemented)
                .as("Implemented CommonErrorCode values must have unique stable codes")
                .hasSize(CommonErrorCode.values().length);
        assertThat(documented).as("The Common catalogue summary must not be empty").isNotEmpty();
        assertThat(implemented.keySet())
                .as("Implemented and documented Common stable codes must match")
                .containsExactlyInAnyOrderElementsOf(documented.keySet());

        documented.forEach(
                (code, expected) -> {
                    CommonErrorCode actual = implemented.get(code);
                    assertThat(actual)
                            .as("Catalogue code %s must be implemented by CommonErrorCode", code)
                            .isNotNull();
                    assertThat(actual.status())
                            .as("Catalogue status must match for %s", code)
                            .isEqualTo(expected.status());
                    assertThat(actual.defaultDetail())
                            .as("Catalogue default detail must match for %s", code)
                            .isEqualTo(expected.defaultDetail());
                });
    }

    @Test
    void parsesACommonMarkCompatibleCommonSectionAndStopsAtTheNextH2() {
        Map<String, CatalogueEntry> entries =
                parseCommonCatalogue(
                        List.of(
                                "\uFEFF  ## Common catalogue summary #   \r",
                                "### Context that remains inside the Common section\r",
                                " Code | HTTP status | Default detail \r",
                                " :--- | ---: | :---: \r",
                                " `EXAMPLE` | `400 Bad Request` | `A safe detail` \r",
                                "\r",
                                "## Identity catalogue summary\r",
                                "| Code | HTTP status | Default detail |\r",
                                "|---|---|---|\r",
                                "| `IDENTITY_ONLY` | `400 Bad Request` | `Must not be parsed` |\r"),
                        "in-memory CRLF catalogue");

        assertThat(entries)
                .containsOnlyKeys("EXAMPLE")
                .containsEntry(
                        "EXAMPLE",
                        new CatalogueEntry("EXAMPLE", HttpStatus.BAD_REQUEST, "A safe detail"));
    }

    @Test
    void ignoresHeadingLikeTextInFencesAndRejectsMissingOrDuplicateCommonSections() {
        Map<String, CatalogueEntry> entries =
                parseCommonCatalogue(
                        List.of(
                                "```text",
                                "## Common catalogue summary",
                                "```",
                                "## Common catalogue summary",
                                "| Code | HTTP status | Default detail |",
                                "|---|---|---|",
                                "| `EXAMPLE` | `400 Bad Request` | `Safe` |"),
                        "fenced heading");

        assertThat(entries).containsOnlyKeys("EXAMPLE");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of("### Common catalogue summary"), "missing section"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Missing Common catalogue summary heading");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "## Common catalogue summary"),
                                        "duplicate section"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Multiple Common catalogue summary headings");
    }

    @Test
    void requiresExactlyOneNonEmptyAuthoritativeTable() {
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of("## Common catalogue summary", "Narrative only"),
                                        "missing table"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Missing Common catalogue summary table");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "|---|---|---|",
                                                "",
                                                "Code | HTTP status | Default detail",
                                                "---|---|---"),
                                        "duplicate table"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Multiple Common catalogue summary tables");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "|---|---|---|"),
                                        "empty table"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Common catalogue summary table is empty");
    }

    @Test
    void validatesMarkdownSeparatorCellsAndColumnCounts() {
        assertThat(parseTableRow("| :--- | ---: | :---: |", "separator", 1).cells())
                .containsExactly(":---", "---:", ":---:");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "| -- | --- | --- |"),
                                        "short separator"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Malformed Common catalogue table separator");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "| ---x | --- | --- |"),
                                        "invalid separator"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Malformed Common catalogue table separator");
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "| --- | --- |"),
                                        "separator column mismatch"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Malformed Common catalogue table separator");
    }

    @Test
    void lexesEscapedPipesAndInlineCodeWithoutChangingUnrelatedCharacters() {
        assertThat(
                        parseTableRow("| `EXAMPLE` | `400 Bad Request` | `A \\| B` |", "escaped", 1)
                                .cells())
                .containsExactly("`EXAMPLE`", "`400 Bad Request`", "`A | B`");
        assertThat(parseTableRow("left\\\\|middle|right", "even slashes", 1).cells())
                .containsExactly("left\\", "middle", "right");
        assertThat(parseTableRow("left\\\\\\|middle|right", "odd slashes", 1).cells())
                .containsExactly("left\\|middle", "right");
        assertThat(parseTableRow("left|right\\|", "escaped terminal pipe", 1).cells())
                .containsExactly("left", "right|");
        assertThat(parseTableRow("left|`inline|code`|right", "inline code", 1).cells())
                .containsExactly("left", "`inline|code`", "right");
        assertThatThrownBy(() -> parseTableRow("left|`unterminated|right", "inline", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Unterminated inline-code span");
        assertThatThrownBy(() -> parseTableRow("left|right\\", "dangling escape", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Dangling escape");
    }

    @Test
    void rejectsMalformedDataRowsInsteadOfContinuingIntoAnotherTable() {
        assertThatThrownBy(
                        () ->
                                parseCommonCatalogue(
                                        List.of(
                                                "## Common catalogue summary",
                                                "| Code | HTTP status | Default detail |",
                                                "|---|---|---|",
                                                "| `EXAMPLE` | `400 Bad Request` | `Safe` |",
                                                "| malformed | table |",
                                                ""),
                                        "malformed contiguous row"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Malformed Common catalogue row");
    }

    private static Path cataloguePath() {
        Path path =
                Path.of(System.getProperty("user.dir"))
                        .toAbsolutePath()
                        .normalize()
                        .resolve("docs/design/error-catalogue.md");
        assertThat(path)
                .as("Expected error catalogue at Maven project root: %s", path)
                .isRegularFile();
        return path;
    }

    private static Map<String, CatalogueEntry> parseCommonCatalogue(Path path) throws IOException {
        return parseCommonCatalogue(Files.readAllLines(path), path.toString());
    }

    private static Map<String, CatalogueEntry> parseCommonCatalogue(
            List<String> sourceLines, String source) {
        List<String> lines = normalizeLines(sourceLines);
        Section commonSection = findCommonSection(lines, source);
        int headerIndex = findExactlyOneTableHeader(lines, commonSection, source);
        int separatorIndex = nextNonBlankLine(lines, headerIndex + 1, commonSection.endExclusive());
        if (separatorIndex < 0 || !isValidSeparator(lines.get(separatorIndex))) {
            throw new AssertionError("Malformed Common catalogue table separator in " + source);
        }

        Map<String, CatalogueEntry> entries = new LinkedHashMap<>();
        for (int index = separatorIndex + 1; index < commonSection.endExclusive(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                break;
            }

            TableRow row = parseTableRow(line, source, index + 1);
            if (!row.hasStructuralPipe()) {
                break;
            }

            CatalogueEntry entry = parseCatalogueEntry(row, source, index + 1);
            if (entries.putIfAbsent(entry.code(), entry) != null) {
                throw new AssertionError("Duplicate Common catalogue code " + entry.code());
            }
        }

        if (entries.isEmpty()) {
            throw new AssertionError("Common catalogue summary table is empty in " + source);
        }
        return entries;
    }

    private static List<String> normalizeLines(List<String> sourceLines) {
        List<String> lines = new ArrayList<>(sourceLines);
        if (!lines.isEmpty() && lines.getFirst().startsWith("\uFEFF")) {
            lines.set(0, lines.getFirst().substring(1));
        }
        lines.replaceAll(ErrorCatalogueConsistencyTest::stripTerminalCarriageReturn);
        return lines;
    }

    private static String stripTerminalCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static Section findCommonSection(List<String> lines, String source) {
        List<Integer> matchingHeadings = new ArrayList<>();
        Fence fence = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (fence != null) {
                if (closesFence(line, fence)) {
                    fence = null;
                }
                continue;
            }
            Fence openingFence = openingFence(line);
            if (openingFence != null) {
                fence = openingFence;
                continue;
            }

            AtxHeading heading = parseAtxHeading(line);
            if (heading != null
                    && heading.level() == 2
                    && heading.text().equals(SUMMARY_HEADING_TEXT)) {
                matchingHeadings.add(index);
            }
        }

        if (matchingHeadings.isEmpty()) {
            throw new AssertionError("Missing Common catalogue summary heading in " + source);
        }
        if (matchingHeadings.size() > 1) {
            throw new AssertionError("Multiple Common catalogue summary headings in " + source);
        }

        int headingIndex = matchingHeadings.getFirst();
        return new Section(headingIndex + 1, findSectionEnd(lines, headingIndex + 1));
    }

    private static int findSectionEnd(List<String> lines, int startIndex) {
        Fence fence = null;
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            if (fence != null) {
                if (closesFence(line, fence)) {
                    fence = null;
                }
                continue;
            }
            Fence openingFence = openingFence(line);
            if (openingFence != null) {
                fence = openingFence;
                continue;
            }

            AtxHeading heading = parseAtxHeading(line);
            if (heading != null && heading.level() <= 2) {
                return index;
            }
        }
        return lines.size();
    }

    private static int findExactlyOneTableHeader(
            List<String> lines, Section section, String source) {
        List<Integer> matchingHeaders = new ArrayList<>();
        Fence fence = null;
        for (int index = section.startInclusive(); index < section.endExclusive(); index++) {
            String line = lines.get(index);
            if (fence != null) {
                if (closesFence(line, fence)) {
                    fence = null;
                }
                continue;
            }
            Fence openingFence = openingFence(line);
            if (openingFence != null) {
                fence = openingFence;
                continue;
            }

            TableRow row = parseTableRow(line, source, index + 1);
            if (row.cells().equals(TABLE_HEADERS)) {
                matchingHeaders.add(index);
            }
        }

        if (matchingHeaders.isEmpty()) {
            throw new AssertionError("Missing Common catalogue summary table in " + source);
        }
        if (matchingHeaders.size() > 1) {
            throw new AssertionError("Multiple Common catalogue summary tables in " + source);
        }
        return matchingHeaders.getFirst();
    }

    private static int nextNonBlankLine(List<String> lines, int startIndex, int endExclusive) {
        for (int index = startIndex; index < endExclusive; index++) {
            if (!lines.get(index).isBlank()) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isValidSeparator(String line) {
        TableRow row = parseTableRow(line, "Common catalogue separator", 0);
        return row.cells().size() == TABLE_HEADERS.size()
                && row.cells().stream().allMatch(cell -> SEPARATOR_CELL.matcher(cell).matches());
    }

    /**
     * Lexes the limited Markdown-table constructs used by this catalogue: table pipes,
     * pipe/backslash escapes, and matching inline-code backtick spans. It is deliberately not a
     * general Markdown renderer.
     */
    private static TableRow parseTableRow(String line, String source, int lineNumber) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int inlineCodeDelimiterLength = 0;
        boolean hasStructuralPipe = false;
        boolean leadingStructuralPipe = false;
        int lastStructuralPipeIndex = -1;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '`') {
                int delimiterLength = runLength(line, index, '`');
                if (isEscapedByOddBackslashes(line, index)) {
                    cell.append(line, index, index + delimiterLength);
                    index += delimiterLength - 1;
                    continue;
                }
                cell.append(line, index, index + delimiterLength);
                if (inlineCodeDelimiterLength == 0) {
                    inlineCodeDelimiterLength = delimiterLength;
                } else if (inlineCodeDelimiterLength == delimiterLength) {
                    inlineCodeDelimiterLength = 0;
                }
                index += delimiterLength - 1;
                continue;
            }

            if (character == '|') {
                int backslashCount = trailingBackslashCount(cell);
                if (backslashCount > 0) {
                    cell.setLength(cell.length() - backslashCount);
                    cell.append("\\".repeat(backslashCount / 2));
                }
                if (backslashCount % 2 == 1) {
                    cell.append('|');
                    continue;
                }
                if (inlineCodeDelimiterLength != 0) {
                    cell.append('|');
                    continue;
                }

                if (!hasStructuralPipe && line.substring(0, index).isBlank()) {
                    leadingStructuralPipe = true;
                }
                cells.add(cell.toString().strip());
                cell.setLength(0);
                hasStructuralPipe = true;
                lastStructuralPipeIndex = index;
                continue;
            }

            cell.append(character);
        }

        if (inlineCodeDelimiterLength != 0) {
            throw malformed("Unterminated inline-code span", source, lineNumber);
        }
        int trailingBackslashes = trailingBackslashCount(cell);
        if (trailingBackslashes % 2 == 1) {
            throw malformed("Dangling escape", source, lineNumber);
        }
        if (trailingBackslashes > 0) {
            cell.setLength(cell.length() - trailingBackslashes);
            cell.append("\\".repeat(trailingBackslashes / 2));
        }
        cells.add(cell.toString().strip());

        if (leadingStructuralPipe) {
            cells.removeFirst();
        }
        if (lastStructuralPipeIndex >= 0 && line.substring(lastStructuralPipeIndex + 1).isBlank()) {
            cells.removeLast();
        }
        return new TableRow(List.copyOf(cells), hasStructuralPipe);
    }

    private static CatalogueEntry parseCatalogueEntry(TableRow row, String source, int lineNumber) {
        if (row.cells().size() != TABLE_HEADERS.size()) {
            throw malformed("Malformed Common catalogue row", source, lineNumber);
        }

        String code = unwrapOuterInlineCode(row.cells().get(0));
        String statusText = unwrapOuterInlineCode(row.cells().get(1));
        String defaultDetail = unwrapOuterInlineCode(row.cells().get(2));
        Matcher matcher = STATUS_CELL.matcher(statusText);
        if (code.isBlank() || defaultDetail.isBlank() || !matcher.matches()) {
            throw malformed("Malformed Common catalogue row for code " + code, source, lineNumber);
        }

        try {
            return new CatalogueEntry(
                    code, HttpStatus.valueOf(Integer.parseInt(matcher.group(1))), defaultDetail);
        } catch (IllegalArgumentException exception) {
            throw new AssertionError(
                    "Invalid Common catalogue HTTP status for code "
                            + code
                            + " at "
                            + source
                            + ":"
                            + lineNumber,
                    exception);
        }
    }

    private static String unwrapOuterInlineCode(String cell) {
        String value = cell.strip();
        if (value.isEmpty() || value.charAt(0) != '`') {
            return value;
        }
        int delimiterLength = runLength(value, 0, '`');
        String delimiter = "`".repeat(delimiterLength);
        if (value.length() >= delimiterLength * 2 && value.endsWith(delimiter)) {
            return value.substring(delimiterLength, value.length() - delimiterLength);
        }
        return value;
    }

    private static AtxHeading parseAtxHeading(String line) {
        Matcher matcher = ATX_HEADING_OPENING.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String text = matcher.group(2).stripTrailing();
        int closingHashStart = text.length();
        while (closingHashStart > 0 && text.charAt(closingHashStart - 1) == '#') {
            closingHashStart--;
        }
        if (closingHashStart < text.length()
                && closingHashStart > 0
                && Character.isWhitespace(text.charAt(closingHashStart - 1))) {
            text = text.substring(0, closingHashStart).stripTrailing();
        }
        return new AtxHeading(matcher.group(1).length(), text);
    }

    private static Fence openingFence(String line) {
        int contentStart = leadingSpaceCount(line);
        if (contentStart > 3 || contentStart >= line.length()) {
            return null;
        }
        char marker = line.charAt(contentStart);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int length = runLength(line, contentStart, marker);
        return length >= 3 ? new Fence(marker, length) : null;
    }

    private static boolean closesFence(String line, Fence fence) {
        int contentStart = leadingSpaceCount(line);
        if (contentStart > 3
                || contentStart >= line.length()
                || line.charAt(contentStart) != fence.marker()) {
            return false;
        }
        int length = runLength(line, contentStart, fence.marker());
        return length >= fence.length() && line.substring(contentStart + length).isBlank();
    }

    private static int leadingSpaceCount(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static int runLength(String value, int startIndex, char character) {
        int index = startIndex;
        while (index < value.length() && value.charAt(index) == character) {
            index++;
        }
        return index - startIndex;
    }

    private static boolean isEscapedByOddBackslashes(String value, int index) {
        int backslashCount = 0;
        for (int position = index - 1;
                position >= 0 && value.charAt(position) == '\\';
                position--) {
            backslashCount++;
        }
        return backslashCount % 2 == 1;
    }

    private static int trailingBackslashCount(StringBuilder value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
            count++;
        }
        return count;
    }

    private static AssertionError malformed(String message, String source, int lineNumber) {
        return new AssertionError(message + " at " + source + ":" + lineNumber);
    }

    private record AtxHeading(int level, String text) {}

    private record Fence(char marker, int length) {}

    private record Section(int startInclusive, int endExclusive) {}

    private record TableRow(List<String> cells, boolean hasStructuralPipe) {}

    private record CatalogueEntry(String code, HttpStatus status, String defaultDetail) {}
}
