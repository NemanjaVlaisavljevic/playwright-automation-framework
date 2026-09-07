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
 *
 * <p><strong>One deliberate exception</strong>: {@link #completePurge}'s D4.1 atomic
 * ingest-vs-purge guard is not mirrored here. The real guard lives inside one SQL statement
 * spanning both the {@code artifacts} and {@code runs} tables (see {@code
 * JdbcArtifactRepository#ingest}'s own {@code WHERE EXISTS} clause) - a single-database cross-table
 * atomicity this fake, a plain in-memory map with no reference to a {@code
 * RunLifecycleStore}/{@code runs} table at all, cannot reproduce. That specific race is proven only
 * against a real Postgres (see the databaseIntegrationTest suite) - this fake's {@link
 * #completePurge} only removes the run's own artifact rows, the same effect {@code
 * JdbcArtifactRepository}'s own {@code DELETE FROM artifacts} step has.
 *
 * <p><strong>A second, related exception (D4.1 review round)</strong>: the real repository's {@link
 * #isArtifactsPurged}/{@link #findForRun} now reflect the instant purge <em>starts</em> ({@code
 * runs.artifacts_purge_started_at}), not only once {@link #completePurge} runs - a distinction this
 * fake cannot model at all, since "purge started" is state {@code claimForArtifactPurge} sets on
 * {@code RunLifecycleStore}/{@code FakeRunLifecycleStore}, a different fake this one holds no
 * reference to. No unit test relies on that in-between window through this fake; it is proven only
 * against the real repository (see {@code RetentionServiceTest}).
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
