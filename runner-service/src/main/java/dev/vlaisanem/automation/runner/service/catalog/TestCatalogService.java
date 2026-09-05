package dev.vlaisanem.automation.runner.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads the committed, JUnit-discovery-generated {@code CUSTOM}-suite catalog off disk - the same
 * file {@code TestCatalogGenerator} (main suite, {@code tooling} package) writes and {@code
 * testCatalogCheck} (root {@code build.gradle}) keeps free of drift in CI. Read fresh on every call
 * rather than cached: this is a small, infrequently-hit file, and re-reading avoids any
 * cache-invalidation reasoning for what is, in production, a file nothing rewrites while the
 * process is running.
 *
 * <p>The deserialized content is re-validated on every read via {@link TestCatalogContentValidator}
 * - the file is a build artifact on disk in a deployed container, not something this service can
 * assume stayed byte-for-byte as generated.
 */
@Service
public class TestCatalogService {

  private final Path catalogFile;
  private final ObjectMapper objectMapper;

  public TestCatalogService(RunnerProperties properties, ObjectMapper objectMapper) {
    this.catalogFile =
        Path.of(properties.repoRoot())
            .toAbsolutePath()
            .normalize()
            .resolve(properties.testCatalogPath());
    this.objectMapper = objectMapper;
  }

  public List<TestCatalogEntry> current() {
    if (!Files.isRegularFile(catalogFile)) {
      throw new TestCatalogUnavailableException(catalogFile);
    }
    List<TestCatalogEntry> entries;
    try {
      byte[] bytes = Files.readAllBytes(catalogFile);
      entries = objectMapper.readValue(bytes, TestCatalogResponse.class).tests();
    } catch (IOException exception) {
      throw new TestCatalogUnavailableException(catalogFile, exception);
    }
    try {
      TestCatalogContentValidator.validate(entries);
    } catch (IllegalStateException exception) {
      throw new TestCatalogUnavailableException(catalogFile, exception);
    }
    return entries;
  }
}
