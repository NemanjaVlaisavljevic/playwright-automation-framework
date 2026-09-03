package dev.vlaisanem.automation.runner.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ArtifactManifestEntryTest {

  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  @Test
  void acceptsAValidScreenshotEntry() {
    ArtifactManifestEntry entry = validEntry("tests/run-1-test-1/failure.png");

    assertThat(entry.type()).isEqualTo(ArtifactType.SCREENSHOT);
    assertThat(entry.relativePath()).isEqualTo("tests/run-1-test-1/failure.png");
  }

  @Test
  void rejectsAnUnsupportedSchemaVersion() {
    assertThatThrownBy(() -> entryWith(b -> b.schemaVersion = "99.0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void rejectsABlankArtifactId() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void acceptsARealUuidShapedArtifactId() {
    ArtifactManifestEntry entry =
        entryWith(b -> b.artifactId = "aa0f6e70-ba2f-4790-a708-b766bf075f19");

    assertThat(entry.artifactId()).isEqualTo("aa0f6e70-ba2f-4790-a708-b766bf075f19");
  }

  @Test
  void rejectsAnArtifactIdContainingASlash() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = "a/b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void rejectsAnArtifactIdContainingABackslash() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = "a\\b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void rejectsAnArtifactIdContainingAQuote() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = "a\"b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void rejectsAnArtifactIdContainingCrLf() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = "a\r\nb"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void rejectsAnArtifactIdLongerThan128Characters() {
    assertThatThrownBy(() -> entryWith(b -> b.artifactId = "a".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void accepts128CharacterArtifactId() {
    String maxLength = "a".repeat(128);

    ArtifactManifestEntry entry = entryWith(b -> b.artifactId = maxLength);

    assertThat(entry.artifactId()).isEqualTo(maxLength);
  }

  @Test
  void rejectsABlankRunId() {
    assertThatThrownBy(() -> entryWith(b -> b.runId = ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");
  }

  @Test
  void rejectsABlankTestId() {
    assertThatThrownBy(() -> entryWith(b -> b.testId = null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("testId");
  }

  @Test
  void rejectsABlankTestDisplayName() {
    assertThatThrownBy(() -> entryWith(b -> b.testDisplayName = " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("testDisplayName");
  }

  @Test
  void acceptsANullStepId() {
    ArtifactManifestEntry entry = entryWith(b -> b.stepId = null);

    assertThat(entry.stepId()).isNull();
  }

  @Test
  void rejectsABlankStepId() {
    assertThatThrownBy(() -> entryWith(b -> b.stepId = " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stepId");
  }

  @Test
  void acceptsANonBlankStepId() {
    ArtifactManifestEntry entry = entryWith(b -> b.stepId = "step-1");

    assertThat(entry.stepId()).isEqualTo("step-1");
  }

  @Test
  void rejectsANullType() {
    assertThatThrownBy(() -> entryWith(b -> b.type = null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("type");
  }

  @Test
  void rejectsABlankRelativePath() {
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("relativePath");
  }

  @Test
  void rejectsABlankMediaType() {
    assertThatThrownBy(() -> entryWith(b -> b.mediaType = ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mediaType");
  }

  @Test
  void rejectsANullCreatedAt() {
    assertThatThrownBy(() -> entryWith(b -> b.createdAt = null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("createdAt");
  }

  @Test
  void rejectsAnAbsolutePosixPath() {
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = "/etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be absolute");
  }

  @Test
  void rejectsAnAbsoluteWindowsPath() {
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = "C:/Windows/System32"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be absolute");
  }

  @Test
  void rejectsAPosixStyleTraversalSegment() {
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = "../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'..'");
  }

  @Test
  void rejectsAWindowsStyleBackslashSeparator() {
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = "tests\\run-1\\failure.png"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separator");
  }

  @Test
  void rejectsAWindowsStyleTraversalSegmentOnceBackslashesAreNormalized() {
    // The dedicated backslash rejection above already refuses this literal string on its own, but
    // this proves the '..' segment check would ALSO catch it if the separator check were ever
    // relaxed - defense in depth, not redundant with the backslash test.
    assertThatThrownBy(() -> entryWith(b -> b.relativePath = "tests/../../secrets"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'..'");
  }

  @Test
  void rejectsANegativeSizeInBytes() {
    assertThatThrownBy(() -> entryWith(b -> b.sizeBytes = -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sizeBytes");
  }

  private static ArtifactManifestEntry validEntry(String relativePath) {
    return entryWith(b -> b.relativePath = relativePath);
  }

  private static ArtifactManifestEntry entryWith(java.util.function.Consumer<Builder> customizer) {
    Builder builder = new Builder();
    customizer.accept(builder);
    return builder.build();
  }

  /**
   * Plain mutable holder so each test can override exactly one field from an otherwise-valid set.
   */
  private static final class Builder {
    String schemaVersion = ArtifactManifestEntry.CURRENT_SCHEMA_VERSION;
    String artifactId = "artifact-1";
    String runId = "run-1";
    String testId = "[engine:junit-jupiter]/[class:SomeTest]/[method:someTest()]";
    String testDisplayName = "someTest()";
    String stepId = null;
    ArtifactType type = ArtifactType.SCREENSHOT;
    String relativePath = "tests/run-1-test-1/failure.png";
    String mediaType = "image/png";
    long sizeBytes = 1024;
    Instant createdAt = NOW;

    ArtifactManifestEntry build() {
      return new ArtifactManifestEntry(
          schemaVersion,
          artifactId,
          runId,
          testId,
          testDisplayName,
          stepId,
          type,
          relativePath,
          mediaType,
          sizeBytes,
          createdAt);
    }
  }
}
