package com.devops.validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocValidatorTest {

    // Fixtures live in docs/fixtures/, one level above the Maven project root.
    private static final Path FIXTURES = Path.of("..", "docs", "fixtures");

    @Test
    void validDocPasses() throws IOException {
        var result = DocValidator.validate(FIXTURES.resolve("valid-doc.md"));
        assertTrue(result.valid(), "expected valid-doc.md to pass, got: " + result.reason());
    }

    @Test
    void missingFrontmatterFieldFails() throws IOException {
        var result = DocValidator.validate(FIXTURES.resolve("missing-frontmatter.md"));
        assertFalse(result.valid(), "expected missing-frontmatter.md to be rejected");
        assertTrue(result.reason().toLowerCase().contains("version"),
                "expected rejection reason to mention the missing 'version' field, got: " + result.reason());
    }

    @Test
    void leakedSecretFails() throws IOException {
        var result = DocValidator.validate(FIXTURES.resolve("leaked-secret.md"));
        assertFalse(result.valid(), "expected leaked-secret.md to be rejected");
        assertTrue(result.reason().toLowerCase().contains("secret")
                        || result.reason().toLowerCase().contains("pii"),
                "expected rejection reason to mention secret/PII, got: " + result.reason());
    }

    @TempDir
    Path tempDir;

    @Test
    void oversizeFileFails() throws IOException {
        String hugeBody = "x".repeat((int) DocValidator.MAX_FILE_SIZE_BYTES + 1);
        String content = """
                ---
                title: "Too Big"
                source: "generated"
                version: "1.0"
                ---

                %s
                """.formatted(hugeBody);
        Path big = tempDir.resolve("too-big.md");
        Files.writeString(big, content, StandardCharsets.UTF_8);

        var result = DocValidator.validate(big);
        assertFalse(result.valid(), "expected an oversize file to be rejected");
        assertTrue(result.reason().toLowerCase().contains("size"),
                "expected rejection reason to mention size, got: " + result.reason());
    }

    @Test
    void packagesValidDocIntoZipWithManifest() throws Exception {
        Path out = tempDir.resolve("artifact.zip");
        DocValidator.packageArtifact(FIXTURES.resolve("valid-doc.md"), out);
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }
}
