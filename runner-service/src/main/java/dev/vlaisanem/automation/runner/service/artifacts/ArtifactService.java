package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads and safely resolves a run's artifacts via {@link ArtifactRepository}; the manifest file
 * itself is only read by {@link ArtifactIngestionService}. An {@link ArtifactManifestEntry} read
 * back from the database still originated from the manifest file, so its {@code relativePath}
 * remains untrusted - see {@link ArtifactFileResolver}.
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

  /** {@code testIdFilter} narrows the result to one test's own artifacts when non-blank. */
  public List<ArtifactManifestEntry> listForRun(String runId, String testIdFilter) {
    // find() alone is what 404s for an unknown runId.
    runService.find(runId);
    return repository.findForRun(runId, testIdFilter);
  }

  /**
   * Whether {@code runId}'s artifact metadata may be incomplete because its final ingestion drain
   * failed and hasn't yet been recovered by background reconciliation.
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
