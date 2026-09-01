package dev.vlaisanem.automation.runner.service.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Creates and immediately starts one {@link ListenerEventIngestor} per run. A thin factory rather
 * than exposing {@link ListenerEventIngestor}'s constructor directly: it is the sole holder of the
 * shared {@link RunEventAppender}/{@link ObjectMapper}/{@code rawEventsDir}/poll-interval wiring,
 * so a caller (see {@code RunService}) only ever needs a runId.
 */
@Component
public class ListenerEventIngestorFactory {

  private final RunEventAppender eventAppender;
  private final ObjectMapper objectMapper;
  private final Path rawEventsDir;
  private final Duration pollInterval;

  public ListenerEventIngestorFactory(
      RunEventAppender eventAppender, ObjectMapper objectMapper, RunnerProperties properties) {
    this.eventAppender = eventAppender;
    this.objectMapper = objectMapper;
    this.rawEventsDir = Path.of(properties.rawEventsDir()).toAbsolutePath().normalize();
    this.pollInterval = properties.ingestionPollInterval();
  }

  public ListenerEventIngestor start(String runId) {
    return new ListenerEventIngestor(
        runId,
        rawEventsDir.resolve(runId + ".tests.jsonl"),
        rawEventsDir.resolve(runId + ".tests.complete"),
        eventAppender,
        objectMapper,
        pollInterval);
  }
}
