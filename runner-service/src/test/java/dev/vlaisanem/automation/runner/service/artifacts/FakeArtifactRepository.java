package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ArtifactRepository} for fast unit tests - no real Postgres needed. Deliberately
 * mirrors {@code JdbcArtifactRepository}'s exact semantics rather than a simplified approximation
 * (the same conflict-on-different-metadata check, the same ingestion-status tracking), so a fake
 * accepting something the real repository would reject - or vice versa - is impossible by
 * construction, not just by convention.
 */
public final class FakeArtifactRepository implements ArtifactRepository {

  private final Map<String, ArtifactManifestEntry> byArtifactId = new ConcurrentHashMap<>();
  private final Set<String> incompleteRunIds = ConcurrentHashMap.newKeySet();

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
}
