package dev.vlaisanem.automation.runner.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class RunnerEventTestExecutionListenerTest {

  private static final ObjectMapper OBJECT_MAPPER = RunnerEventObjectMapper.create();

  @Test
  void emitsOneJsonlLinePerTestLifecycleSignal(@TempDir Path tempDir) throws IOException {
    String runId = "test-run";

    // Auto-registration disabled: this module's own META-INF/services file would otherwise
    // double-register a listener, double-writing every event under a clashing sequence.
    Launcher launcher =
        LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build());
    launcher.registerTestExecutionListeners(new RunnerEventTestExecutionListener(runId));

    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request().selectors(selectClass(Fixture.class)).build();
    withRawEventsDir(tempDir, () -> launcher.execute(request));

    assertThat(Files.exists(tempDir.resolve(runId + ".tests.complete")))
        .as("completion marker should exist once the listener has closed the writer")
        .isTrue();

    List<RunnerEvent> events = readEvents(tempDir.resolve(runId + ".tests.jsonl"));
    assertThat(events).hasSize(5);
    assertThat(events)
        .extracting(RunnerEvent::sequence)
        .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    assertThat(events).allSatisfy(event -> assertThat(event.runId()).isEqualTo(runId));

    Map<String, List<RunnerEvent>> byDisplayName =
        events.stream().collect(Collectors.groupingBy(RunnerEvent::testDisplayName));

    assertThat(byDisplayName.get("passing()"))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.TEST_STARTED, EventType.TEST_PASSED);

    List<RunnerEvent> failing = byDisplayName.get("failing()");
    assertThat(failing)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.TEST_STARTED, EventType.TEST_FAILED);
    // FailureDetailFormatter's shape (class + redacted message + stack frames), not bare message.
    assertThat(failing.get(1).detail()).contains("AssertionFailedError").contains("boom");

    List<RunnerEvent> skipped = byDisplayName.get("skipped()");
    assertThat(skipped).extracting(RunnerEvent::type).containsExactly(EventType.TEST_SKIPPED);
    assertThat(skipped.get(0).detail()).isEqualTo("not ready yet");
  }

  @Test
  void emitsSkippedForEveryMethodInADisabledClass(@TempDir Path tempDir) throws IOException {
    String runId = "disabled-class-run";
    Launcher launcher =
        LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build());
    launcher.registerTestExecutionListeners(new RunnerEventTestExecutionListener(runId));

    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(DisabledFixture.class))
            .build();
    withRawEventsDir(tempDir, () -> launcher.execute(request));

    List<RunnerEvent> events = readEvents(tempDir.resolve(runId + ".tests.jsonl"));

    // JUnit Platform never calls executionStarted/Skipped for a skipped container's descendants -
    // the listener has to walk TestPlan.getDescendants() itself, or these methods are invisible.
    assertThat(events).extracting(RunnerEvent::type).containsOnly(EventType.TEST_SKIPPED);
    assertThat(events).extracting(RunnerEvent::detail).containsOnly("suite paused");
    assertThat(events)
        .extracting(RunnerEvent::testDisplayName)
        .containsExactlyInAnyOrder("neverRuns()", "alsoNeverRuns()");
  }

  /**
   * Redirects {@link RunnerEventWriterRegistry}'s raw-events directory to {@code tempDir} for
   * {@code action}, restoring the previous value afterward.
   */
  private void withRawEventsDir(Path tempDir, Runnable action) {
    String property = RunnerEventWriterRegistry.RAW_EVENTS_DIR_PROPERTY;
    String previous = System.getProperty(property);
    System.setProperty(property, tempDir.toString());
    try {
      action.run();
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  private List<RunnerEvent> readEvents(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file);
    return lines.stream()
        .map(
            line -> {
              try {
                return OBJECT_MAPPER.readValue(line, RunnerEvent.class);
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            })
        .toList();
  }

  static class Fixture {

    @Test
    void passing() {}

    @Test
    void failing() {
      Assertions.fail("boom");
    }

    @Test
    @Disabled("not ready yet")
    void skipped() {}
  }

  @Disabled("suite paused")
  static class DisabledFixture {

    @Test
    void neverRuns() {}

    @Test
    void alsoNeverRuns() {}
  }
}
