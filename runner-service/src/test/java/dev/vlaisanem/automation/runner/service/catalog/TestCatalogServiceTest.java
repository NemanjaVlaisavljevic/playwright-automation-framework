package dev.vlaisanem.automation.runner.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCatalogServiceTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void readsAndParsesARealCatalogFile(@TempDir Path repoRoot) throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "Some test",
              "category": "API",
              "tags": ["regression", "read-only", "api"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    var entries = service.current();

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).testKey()).isEqualTo("some.Test#method");
    assertThat(entries.get(0).tags()).containsExactlyInAnyOrder("regression", "read-only", "api");
  }

  @Test
  void throwsAClientSafeExceptionWhenTheFileIsMissing(@TempDir Path repoRoot) {
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "does-not-exist.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenTheFileIsMalformed(@TempDir Path repoRoot) throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(catalogFile, "not json");
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  /**
   * Regression test for a review's finding: {@code TestCatalogService} used to deserialize the file
   * and hand its entries straight to callers with no content validation - a corrupted or
   * hand-edited catalog on disk in a deployed container could silently change what {@code
   * CustomTestSelectionValidator} treats as a legal selection. The file is syntactically valid JSON
   * here (so this exercises {@link TestCatalogContentValidator}, not the Jackson-parse-failure path
   * above) but has two entries sharing one {@code testKey} - exactly the overloaded-method
   * collision scenario the reviewer was worried about.
   */
  @Test
  void throwsAClientSafeExceptionWhenTheCatalogHasADuplicateTestKey(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "First",
              "category": "API",
              "tags": ["regression", "read-only", "api"]
            },
            {
              "testKey": "some.Test#method",
              "displayName": "Second",
              "category": "API",
              "tags": ["regression", "read-only", "api"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenAnEntryIsMissingTheReadOnlyTag(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "Some test",
              "category": "API",
              "tags": ["regression", "api"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenAnEntryCarriesTheMutationTag(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "Some test",
              "category": "API",
              "tags": ["regression", "read-only", "api", "mutation"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenAnEntryHasNoLayerTag(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "Some test",
              "category": "API",
              "tags": ["regression", "read-only"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenAnEntryHasTwoLayerTags(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.Test#method",
              "displayName": "Some test",
              "category": "API",
              "tags": ["regression", "read-only", "api", "ui"]
            }
          ]
        }
        """);
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  @Test
  void throwsAClientSafeExceptionWhenTheCatalogHasNoEntries(@TempDir Path repoRoot)
      throws IOException {
    Path catalogFile = repoRoot.resolve("catalog.json");
    Files.writeString(catalogFile, "{ \"tests\": [] }");
    TestCatalogService service =
        new TestCatalogService(properties(repoRoot, "catalog.json"), OBJECT_MAPPER);

    assertThatThrownBy(service::current).isInstanceOf(TestCatalogUnavailableException.class);
  }

  private static RunnerProperties properties(Path repoRoot, String testCatalogPath) {
    return new RunnerProperties(
        repoRoot.toString(),
        Duration.ofMinutes(10),
        "raw",
        "journal",
        "logs",
        testCatalogPath,
        "artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10));
  }
}
