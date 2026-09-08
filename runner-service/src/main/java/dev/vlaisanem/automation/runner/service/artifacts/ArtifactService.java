package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads and safely resolves a run's artifacts. D2.4 - {@link #listForRun}/{@link #download} query
 * the {@code artifacts} table via {@link ArtifactRepository} exclusively; the manifest file itself
 * is no longer read here at all - {@link ArtifactIngestionService} is the sole component that still
 * reads it, to keep {@code artifacts} populated. Path resolution itself is shared with that class
 * via {@link ArtifactFileResolver} - see its own Javadoc for this class's remaining trust boundary:
 * an {@link ArtifactManifestEntry} read back from the database still originated from the manifest
 * file, so its {@code relativePath} is treated exactly as untrusted as it always was.
 */
@Service
public class ArtifactService {

  private final RunService runService;
  private final ArtifactRepository repository;
  private final Path artifactsRootDir;

  public ArtifactService(
      RunService runService, ArtifactRepository repository, RunnerProperties properties) {
    this.runService = runService;
    this.repository = repository;
    this.artifactsRootDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
  }

  /**
   * {@code testIdFilter} narrows the result to one test's own artifacts when non-blank - a query
   * parameter, not a path variable: a real {@code testId} (JUnit's own unique-ID format) contains
   * {@code /} characters, which would make it an unusable REST path segment.
   */
  public List<ArtifactManifestEntry> listForRun(String runId, String testIdFilter) {
    // find() alone is what 404s for an unknown runId - the returned Run itself is otherwise unused
    // now that artifacts are read from Postgres directly, which needs no "is this run terminal"
    // tolerance the way reading a still-being-appended manifest file directly used to.
    runService.find(runId);
    return repository.findForRun(runId, testIdFilter);
  }

  /**
   * Whether {@code runId}'s artifact metadata may be incomplete because its final ingestion drain
   * failed and has not yet been recovered by {@code ArtifactIngestionService}'s own bounded
   * background reconciliation - lets {@link
   * dev.vlaisanem.automation.runner.service.api.ArtifactController} distinguish "this run genuinely
   * has zero artifacts" from "ingestion for this run has not finished yet" instead of conflating
   * the two (a review finding).
   */
  public boolean isIngestionIncomplete(String runId) {
    return repository.isIngestionIncomplete(runId);
  }

  public ArtifactDownload download(String runId, String artifactId) {
    ArtifactManifestEntry entry =
        listForRun(runId, null).stream()
            .filter(candidate -> artifactId.equals(candidate.artifactId()))
            .findFirst()
            .orElseThrow(() -> new ArtifactNotFoundException(runId, artifactId));
    return new ArtifactDownload(
        entry, ArtifactFileResolver.resolve(artifactsRootDir, runId, entry));
  }
}
