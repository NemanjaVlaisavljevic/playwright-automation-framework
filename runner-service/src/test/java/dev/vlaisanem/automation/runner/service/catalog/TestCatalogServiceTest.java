package dev.vlaisanem.automation.runner.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
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
   * A syntactically valid catalog with two entries sharing one {@code testKey} must still be
   * rejected (exercises {@link TestCatalogContentValidator}, not the Jackson-parse-failure path
   * above) - an unvalidated duplicate could let {@code CustomTestSelectionValidator} silently treat
   * it as a legal selection.
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
        Duration.ofMinutes(10),
        new RateLimitRule(5, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(3, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofHours(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(120, Duration.ofMinutes(1)),
        new RateLimitRule(30, Duration.ofMinutes(1)),
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        new RateLimitRule(10, Duration.ofHours(1)),
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        new RateLimitRule(10, Duration.ofHours(1)),
        Duration.ofSeconds(60));
  }
}
