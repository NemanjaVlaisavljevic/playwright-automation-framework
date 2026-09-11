package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ArtifactRepository} for fast unit tests - no real Postgres needed. Mirrors
 * {@code JdbcArtifactRepository}'s exact semantics (same conflict-on-different-metadata check, same
 * ingestion-status tracking) rather than a simplified approximation, so a fake accepting something
 * the real repository would reject - or vice versa - can't happen by construction.
 *
 * <p><strong>Two exceptions</strong>, both proven only against real Postgres (see the
 * databaseIntegrationTest / {@code RetentionServiceTest} suites), not here: {@link
 * #completePurge}'s atomic ingest-vs-purge guard is a single cross-table SQL statement in the real
 * repository and isn't reproducible in a plain in-memory map; and the real {@link
 * #isArtifactsPurged}/{@link #findForRun} reflect the instant a purge <em>starts</em>, not just
 * once {@link #completePurge} runs - this fake has no reference to that in-between state.
 */
public final class FakeArtifactRepository implements ArtifactRepository {

  private final Map<String, ArtifactManifestEntry> byArtifactId = new ConcurrentHashMap<>();
  private final Set<String> incompleteRunIds = ConcurrentHashMap.newKeySet();
  private final Set<String> purgedRunIds = ConcurrentHashMap.newKeySet();

  @Override
  public void ingest(List<ArtifactManifestEntry> entries) {
    for (ArtifactManifestEntry entry : entries) {
      ArtifactManifestEntry existing = byArtifactId.putIfAbsent(entry.artifactId(), entry);
      if (existing != null && !existing.equals(entry)) {
        throw new ArtifactIngestionConflictException(entry.artifactId(), existing, entry);
      }
    }
  }

  @Override
  public List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter) {
    return byArtifactId.values().stream()
        .filter(entry -> runId.equals(entry.runId()))
        .filter(
            entry ->
                testIdFilter == null
                    || testIdFilter.isBlank()
                    || testIdFilter.equals(entry.testId()))
        .sorted(
            Comparator.comparing(ArtifactManifestEntry::createdAt)
                .thenComparing(ArtifactManifestEntry::artifactId))
        .toList();
  }

  @Override
  public void markIngestionIncomplete(String runId) {
    incompleteRunIds.add(runId);
  }

  @Override
  public void markIngestionComplete(String runId) {
    incompleteRunIds.remove(runId);
  }

  @Override
  public List<String> findRunIdsWithIncompleteIngestion() {
    return List.copyOf(incompleteRunIds);
  }

  @Override
  public boolean isIngestionIncomplete(String runId) {
    return incompleteRunIds.contains(runId);
  }

  @Override
  public boolean isArtifactsPurged(String runId) {
    return purgedRunIds.contains(runId);
  }

  @Override
  public void completePurge(String runId) {
    byArtifactId.entrySet().removeIf(e -> runId.equals(e.getValue().runId()));
    purgedRunIds.add(runId);
  }
}
