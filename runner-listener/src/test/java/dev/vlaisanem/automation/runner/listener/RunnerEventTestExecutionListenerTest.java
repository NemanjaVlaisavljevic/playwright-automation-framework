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

    // Auto-registration is disabled deliberately: this module's own META-INF/services file would
    // otherwise ALSO auto-register a second listener instance from the classpath, double-writing
    // every event under a clashing sequence. Real consumers (the main test suite) want the default
    // auto-registration on - see the services file next to the listener class.
    Launcher launcher =
        LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build());
    launcher.registerTestExecutionListeners(new RunnerEventTestExecutionListener(runId, tempDir));

    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request().selectors(selectClass(Fixture.class)).build();
    launcher.execute(request);

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
    assertThat(failing.get(1).detail()).isEqualTo("boom");

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
    launcher.registerTestExecutionListeners(new RunnerEventTestExecutionListener(runId, tempDir));

    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(DisabledFixture.class))
            .build();
    launcher.execute(request);

    List<RunnerEvent> events = readEvents(tempDir.resolve(runId + ".tests.jsonl"));

    // JUnit Platform never calls executionStarted/executionSkipped for the descendants of a
    // skipped container (a class-level @Disabled skips the whole class in one callback) - the
    // listener has to walk TestPlan.getDescendants() itself, or these methods are invisible.
    assertThat(events).extracting(RunnerEvent::type).containsOnly(EventType.TEST_SKIPPED);
    assertThat(events).extracting(RunnerEvent::detail).containsOnly("suite paused");
    assertThat(events)
        .extracting(RunnerEvent::testDisplayName)
        .containsExactlyInAnyOrder("neverRuns()", "alsoNeverRuns()");
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
