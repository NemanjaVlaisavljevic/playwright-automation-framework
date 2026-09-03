package dev.vlaisanem.automation.runner.service.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactManifestReaderTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final String RUN_ID = "run-1";

  private final ArtifactManifestReader reader = new ArtifactManifestReader(OBJECT_MAPPER);

  @Test
  void returnsAnEmptyListWhenTheManifestFileDoesNotExist(@TempDir Path dir) {
    List<ArtifactManifestEntry> entries = reader.read(dir.resolve("manifest.jsonl"), RUN_ID, false);

    assertThat(entries).isEmpty();
  }

  @Test
  void parsesEveryCompleteLine(@TempDir Path dir) throws IOException {
    Path manifest = writeLines(dir, line(entry("a")), line(entry("b")));

    List<ArtifactManifestEntry> entries = reader.read(manifest, RUN_ID, true);

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("a", "b");
  }

  @Test
  void skipsBlankLinesBetweenEntries(@TempDir Path dir) throws IOException {
    Path manifest = writeRaw(dir, line(entry("a")) + "\n" + line(entry("b")));

    List<ArtifactManifestEntry> entries = reader.read(manifest, RUN_ID, true);

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("a", "b");
  }

  @Test
  void toleratesAnUnterminatedTrailingLineWhileTheRunIsNotYetTerminal(@TempDir Path dir)
      throws IOException {
    // No trailing '\n' after the second entry - simulates reading mid-append.
    Path manifest = writeRaw(dir, line(entry("a")) + toJson(entry("b")));

    List<ArtifactManifestEntry> entries = reader.read(manifest, RUN_ID, false);

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("a");
  }

  @Test
  void reportsAnUnterminatedTrailingLineAsCorruptOnceTheRunIsTerminal(@TempDir Path dir)
      throws IOException {
    Path manifest = writeRaw(dir, line(entry("a")) + toJson(entry("b")));

    assertCorruptWithDiagnosticContaining(
        () -> reader.read(manifest, RUN_ID, true), "unterminated trailing line");
  }

  @Test
  void reportsAMalformedCompleteLineAsCorruptEvenWhileTheRunIsStillRunning(@TempDir Path dir)
      throws IOException {
    Path manifest = writeRaw(dir, "{not valid json\n");

    assertCorruptWithDiagnosticContaining(
        () -> reader.read(manifest, RUN_ID, false), "malformed entry");
  }

  @Test
  void reportsAContractViolatingCompleteLineAsCorrupt(@TempDir Path dir) throws IOException {
    // Valid JSON shape, but sizeBytes is negative - ArtifactManifestEntry's own compact constructor
    // rejects this, and Jackson wraps that rejection while deserializing.
    String badJson =
        "{\"schemaVersion\":\"1.0\",\"artifactId\":\"a\",\"runId\":\"run-1\",\"testId\":\"t\","
            + "\"testDisplayName\":\"t\",\"type\":\"SCREENSHOT\",\"relativePath\":\"a.png\","
            + "\"mediaType\":\"image/png\",\"sizeBytes\":-1,\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    Path manifest = writeRaw(dir, badJson + "\n");

    assertCorruptWithDiagnosticContaining(
        () -> reader.read(manifest, RUN_ID, true), "malformed entry");
  }

  @Test
  void reportsARunIdMismatchAsCorruptRegardlessOfRunStatus(@TempDir Path dir) throws IOException {
    ArtifactManifestEntry wrongRun =
        new ArtifactManifestEntry(
            ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
            "a",
            "some-other-run",
            "t",
            "t",
            null,
            ArtifactType.SCREENSHOT,
            "a.png",
            "image/png",
            1,
            Instant.parse("2026-01-01T00:00:00Z"));
    Path manifest = writeLines(dir, line(wrongRun));

    assertCorruptWithDiagnosticContaining(() -> reader.read(manifest, RUN_ID, false), "runId");
  }

  @Test
  void reportsInvalidUtf8InsideACompleteLineAsCorrupt(@TempDir Path dir) throws IOException {
    // A lone continuation byte (0x80) is never valid at the start of a UTF-8 sequence - embedded
    // inside an otherwise well-formed, newline-terminated JSON line so only the strict decode step
    // is what catches it, not the JSON parser itself.
    byte[] validPrefix =
        "{\"schemaVersion\":\"1.0\",\"artifactId\":\"a\",\"runId\":\"run-1\",\"testId\":\"t"
            .getBytes(StandardCharsets.UTF_8);
    byte[] invalidByte = {(byte) 0x80};
    byte[] validSuffix =
        ("\",\"testDisplayName\":\"t\",\"type\":\"SCREENSHOT\",\"relativePath\":\"a.png\","
                + "\"mediaType\":\"image/png\",\"sizeBytes\":1,\"createdAt\":\"2026-01-01T00:00:00Z\"}\n")
            .getBytes(StandardCharsets.UTF_8);
    Path manifest = dir.resolve("manifest.jsonl");
    try (var out = Files.newOutputStream(manifest, StandardOpenOption.CREATE)) {
      out.write(validPrefix);
      out.write(invalidByte);
      out.write(validSuffix);
    }

    assertCorruptWithDiagnosticContaining(
        () -> reader.read(manifest, RUN_ID, false), "invalid UTF-8");
  }

  @Test
  void reportsADuplicateArtifactIdAsCorrupt(@TempDir Path dir) throws IOException {
    Path manifest = writeLines(dir, line(entry("a")), line(entry("a")));

    assertCorruptWithDiagnosticContaining(
        () -> reader.read(manifest, RUN_ID, false), "duplicate artifactId");
  }

  /**
   * {@code getMessage()} is deliberately generic and client-safe (see {@link
   * ArtifactManifestCorruptException}'s own Javadoc) - every diagnostic detail these tests actually
   * care about lives in {@link ArtifactManifestCorruptException#diagnosticReason()} instead.
   */
  private static void assertCorruptWithDiagnosticContaining(
      ThrowingCallable callable, String expectedSubstring) {
    assertThatThrownBy(callable)
        .isInstanceOfSatisfying(
            ArtifactManifestCorruptException.class,
            exception -> assertThat(exception.diagnosticReason()).contains(expectedSubstring));
  }

  private static ArtifactManifestEntry entry(String artifactId) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        RUN_ID,
        "test-" + artifactId,
        "test " + artifactId,
        null,
        ArtifactType.SCREENSHOT,
        artifactId + ".png",
        "image/png",
        1024,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static String toJson(ArtifactManifestEntry entry) {
    try {
      return OBJECT_MAPPER.writeValueAsString(entry);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String line(ArtifactManifestEntry entry) {
    return toJson(entry) + "\n";
  }

  private static Path writeLines(Path dir, String... lines) throws IOException {
    StringBuilder content = new StringBuilder();
    for (String line : lines) {
      content.append(line);
    }
    return writeRaw(dir, content.toString());
  }

  private static Path writeRaw(Path dir, String content) throws IOException {
    Path manifest = dir.resolve("manifest.jsonl");
    Files.writeString(manifest, content, StandardCharsets.UTF_8);
    return manifest;
  }
}
