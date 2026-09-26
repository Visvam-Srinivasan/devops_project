package com.devops.validator;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Quality/security gate for markdown docs headed into the RAG knowledge
 * base. Checks, in order:
 *   1. File size is under the max.
 *   2. YAML frontmatter is present and has non-blank title/source/version.
 *   3. The document body contains no secret/PII-shaped strings.
 *
 * On success, packages the doc + a manifest.json (filename, sha256,
 * timestamp) into a zip artifact that Ansible later ships to the
 * ingestion worker.
 */
public class DocValidator {

    static final long MAX_FILE_SIZE_BYTES = 100_000; // 100 KB — plenty for a demo doc
    static final List<String> REQUIRED_FRONTMATTER_FIELDS = List.of("title", "source", "version");

    // --- secret / PII patterns -------------------------------------------------
    // These are deliberately simple, shape-based checks (not an exhaustive
    // secret-scanning engine) — good enough to reject the kind of accidental
    // leak a course-project demo needs to catch.

    static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");

    static final Pattern GENERIC_API_KEY =
            Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password)\\b\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?");

    static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // 13-19 digits, optionally grouped with spaces/dashes — card-number shaped
    static final Pattern CARD_LIKE_DIGITS =
            Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            AWS_ACCESS_KEY, GENERIC_API_KEY, EMAIL_ADDRESS, CARD_LIKE_DIGITS
    );

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, "OK");
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /** Validates a markdown file against the size / frontmatter / secret-scan rules. */
    public static ValidationResult validate(Path file) throws IOException {
        if (!Files.exists(file)) {
            return ValidationResult.fail("File not found: " + file);
        }

        long size = Files.size(file);
        if (size > MAX_FILE_SIZE_BYTES) {
            return ValidationResult.fail(
                    "File size " + size + " bytes exceeds max of " + MAX_FILE_SIZE_BYTES + " bytes");
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);

        Frontmatter fm = Frontmatter.parse(content);
        if (fm == null) {
            return ValidationResult.fail("Missing or malformed YAML frontmatter (no --- ... --- block)");
        }

        for (String field : REQUIRED_FRONTMATTER_FIELDS) {
            Object value = fm.fields().get(field);
            if (value == null || value.toString().isBlank()) {
                return ValidationResult.fail("Missing required frontmatter field: \"" + field + "\"");
            }
        }

        // Only scan the body — frontmatter is structured metadata, not free text.
        Matcher awsMatch = AWS_ACCESS_KEY.matcher(fm.body());
        if (awsMatch.find()) {
            return ValidationResult.fail("Secret detected: AWS access key-shaped string near \"" + snippet(fm.body(), awsMatch.start()) + "\"");
        }
        Matcher apiKeyMatch = GENERIC_API_KEY.matcher(fm.body());
        if (apiKeyMatch.find()) {
            return ValidationResult.fail("Secret detected: API key/token/password-shaped string near \"" + snippet(fm.body(), apiKeyMatch.start()) + "\"");
        }
        Matcher emailMatch = EMAIL_ADDRESS.matcher(fm.body());
        if (emailMatch.find()) {
            return ValidationResult.fail("PII detected: email address \"" + emailMatch.group() + "\"");
        }
        Matcher cardMatch = CARD_LIKE_DIGITS.matcher(fm.body());
        if (cardMatch.find()) {
            return ValidationResult.fail("PII detected: card-number-shaped digit run near \"" + snippet(fm.body(), cardMatch.start()) + "\"");
        }

        return ValidationResult.ok();
    }

    private static String snippet(String text, int index) {
        int start = Math.max(0, index - 10);
        int end = Math.min(text.length(), index + 15);
        return text.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    /** A parsed "---\n...yaml...\n---\nbody" markdown file. */
    private record Frontmatter(Map<String, Object> fields, String body) {
        static Frontmatter parse(String content) {
            String normalized = content.stripLeading();
            if (!normalized.startsWith("---")) {
                return null;
            }
            // Skip the opening delimiter line.
            int firstNewline = normalized.indexOf('\n');
            if (firstNewline < 0) {
                return null;
            }
            String rest = normalized.substring(firstNewline + 1);

            int closingIndex = findClosingDelimiter(rest);
            if (closingIndex < 0) {
                return null;
            }

            String yamlBlock = rest.substring(0, closingIndex);
            // Everything after the closing "---" line is the body.
            int bodyStart = rest.indexOf('\n', closingIndex);
            String body = bodyStart >= 0 ? rest.substring(bodyStart + 1) : "";

            Map<String, Object> parsed;
            try {
                Object loaded = new Yaml().load(yamlBlock);
                if (loaded instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) map;
                    parsed = casted;
                } else {
                    parsed = Map.of();
                }
            } catch (Exception e) {
                parsed = Map.of();
            }

            return new Frontmatter(parsed, body);
        }

        /** Finds the index (within {@code rest}) where a line equal to "---" starts. */
        private static int findClosingDelimiter(String rest) {
            String[] lines = rest.split("\n", -1);
            int offset = 0;
            for (String line : lines) {
                if (line.strip().equals("---")) {
                    return offset;
                }
                offset += line.length() + 1;
            }
            return -1;
        }
    }

    /** Packages the doc + a manifest.json describing it into a zip artifact. */
    public static void packageArtifact(Path docFile, Path outputZip) throws IOException, NoSuchAlgorithmException {
        byte[] docBytes = Files.readAllBytes(docFile);
        String sha256 = sha256Hex(docBytes);
        String timestamp = Instant.now().toString();
        String filename = docFile.getFileName().toString();

        String manifestJson = """
                {
                  "filename": "%s",
                  "sha256": "%s",
                  "timestamp": "%s"
                }
                """.formatted(filename, sha256, timestamp);

        Files.createDirectories(outputZip.toAbsolutePath().getParent());
        try (OutputStream fos = Files.newOutputStream(outputZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(filename));
            zos.write(docBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: DocValidator <doc-file> <output-zip>");
            System.exit(2);
        }
        Path docFile = Path.of(args[0]);
        Path outputZip = Path.of(args[1]);

        try {
            ValidationResult result = validate(docFile);
            if (!result.valid()) {
                System.err.println("REJECTED: " + docFile + " -> " + result.reason());
                System.exit(1);
            }
            packageArtifact(docFile, outputZip);
            System.out.println("ACCEPTED: " + docFile + " -> packaged " + outputZip);
        } catch (Exception e) {
            System.err.println("ERROR validating " + docFile + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
