package dev.vlaisanem.automation.runner.service.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.exception.RunLogNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = {RunController.class, RunExceptionHandler.class})
class RunControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;
  @MockitoBean private ArtifactRepository artifactRepository;

  @Test
  void createReturns202WithTheQueuedRun() throws Exception {
    Run queued =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-08-30T00:00:00Z"));
    when(runService.submit(Environment.PUBLIC, Suite.SMOKE, null)).thenReturn(queued);

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run-1"))
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.processLogUrl").value("/api/v1/runs/run-1/log"))
        .andExpect(jsonPath("$.startedAt").doesNotExist());
  }

  /**
   * Proves the request body's {@code testKeys} actually reach {@link RunService#submit}, and that
   * the response's {@code selectedTests} reflects what the service returns rather than a hardcoded
   * shape.
   */
  @Test
  void createPassesTestKeysThroughAndReturnsTheMatchingSelectionSnapshot() throws Exception {
    List<SelectedTestSnapshot> selectedTests =
        List.of(new SelectedTestSnapshot("some.Test#method", "Some test", TestLayer.API));
    Run queued =
        Run.queued(
            "run-1",
            Environment.PUBLIC,
            Suite.CUSTOM,
            Instant.parse("2026-08-30T00:00:00Z"),
            selectedTests);
    when(runService.submit(Environment.PUBLIC, Suite.CUSTOM, List.of("some.Test#method")))
        .thenReturn(queued);

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content(
                    "{\"environment\":\"PUBLIC\",\"suite\":\"CUSTOM\","
                        + "\"testKeys\":[\"some.Test#method\"]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run-1"))
        .andExpect(jsonPath("$.selectedTests", hasSize(1)))
        .andExpect(jsonPath("$.selectedTests[0].testKey").value("some.Test#method"))
        .andExpect(jsonPath("$.selectedTests[0].displayName").value("Some test"))
        .andExpect(jsonPath("$.selectedTests[0].layer").value("API"));
  }

  @Test
  void createReturns400ForAnUnsupportedCombination() throws Exception {
    when(runService.submit(eq(Environment.PUBLIC), any(), any()))
        .thenThrow(new UnsupportedRunCombinationException(Environment.PUBLIC, Suite.SMOKE));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createReturns400WhenTheRequestBodyIsMissingFields() throws Exception {
    assertProblemDetailShape(
        mockMvc.perform(post("/api/v1/runs").contentType("application/json").content("{}")),
        400,
        "Bad Request");
  }

  @Test
  void createReturns400WhenEnvironmentIsMissing() throws Exception {
    assertProblemDetailShape(
        mockMvc.perform(
            post("/api/v1/runs").contentType("application/json").content("{\"suite\":\"SMOKE\"}")),
        400,
        "Bad Request");
  }

  @Test
  void createReturns400ForAnInvalidEnumValue() throws Exception {
    assertProblemDetailShape(
        mockMvc.perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"NOT_A_REAL_SUITE\"}")),
        400,
        "Bad Request");
  }

  @Test
  void createReturns400ForMalformedJson() throws Exception {
    assertProblemDetailShape(
        mockMvc.perform(post("/api/v1/runs").contentType("application/json").content("not-json")),
        400,
        "Bad Request");
  }

  /**
   * {@code @Size(max = 25)} on {@code testKeys} is defense-in-depth, independent of {@code
   * CustomTestSelectionValidator}'s identical cap - proves Bean Validation rejects an oversized
   * list with {@code 400} before the service layer's live-catalog lookup ever runs.
   */
  @Test
  void createReturns400ForMoreThan25TestKeys() throws Exception {
    String oversizedTestKeys =
        java.util.stream.IntStream.range(0, 26)
            .mapToObj(i -> "\"key-" + i + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));

    assertProblemDetailShape(
        mockMvc.perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content(
                    "{\"environment\":\"PUBLIC\",\"suite\":\"CUSTOM\",\"testKeys\":"
                        + oversizedTestKeys
                        + "}")),
        400,
        "Bad Request");
  }

  /**
   * The per-key length cap guards against a single absurdly long string inflating the request body
   * without tripping the list-size check above.
   */
  @Test
  void createReturns400ForATestKeyLongerThan200Characters() throws Exception {
    String tooLongKey = "k".repeat(201);

    assertProblemDetailShape(
        mockMvc.perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content(
                    "{\"environment\":\"PUBLIC\",\"suite\":\"CUSTOM\",\"testKeys\":[\""
                        + tooLongKey
                        + "\"]}")),
        400,
        "Bad Request");
  }

  /**
   * Confirms Jackson's default behavior already rejects an unknown JSON field with {@code 400},
   * rather than silently ignoring it - no code change needed here.
   */
  @Test
  void createReturns400ForAnUnknownJsonField() throws Exception {
    assertProblemDetailShape(
        mockMvc.perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content(
                    "{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\",\"notARealField\":true}")),
        400,
        "Bad Request");
  }

  @Test
  void deleteOnTheRunsCollectionReturns405() throws Exception {
    assertProblemDetailShape(mockMvc.perform(delete("/api/v1/runs")), 405, "Method Not Allowed");
  }

  /**
   * An unanticipated service-layer exception must surface as 500 with a generic message, never
   * {@code exception.getMessage()}. See {@link RunExceptionHandlerTest} for the handler-level proof
   * it's still logged, not swallowed.
   */
  @Test
  void createReturns500WithAGenericMessageForAnUnexpectedException() throws Exception {
    when(runService.submit(any(), any(), any()))
        .thenThrow(new IllegalStateException("internal detail: should never reach a client"));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred."));
  }

  @Test
  void createReturns503WhenTheQueueIsFull() throws Exception {
    when(runService.submit(any(), any(), any())).thenThrow(new RunQueueFullException(5));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void createReturns503WhenTheRunnerIsStillRecoveringFromARestart() throws Exception {
    when(runService.submit(any(), any(), any())).thenThrow(new RunnerRecoveringException());

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void createReturns503WhenTheCanonicalEventJournalIsUnavailable() throws Exception {
    when(runService.submit(any(), any(), any()))
        .thenThrow(
            new RunEventPersistenceException(
                "run-1", new IllegalStateException("simulated journal failure")));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("journal")));
  }

  @Test
  void getReturns200WithTheRun() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-08-30T00:00:00Z"))
            .transitionTo(RunStatus.STARTING, Instant.parse("2026-08-30T00:00:01Z"));
    when(runService.find("run-1")).thenReturn(run);

    mockMvc
        .perform(get("/api/v1/runs/run-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("STARTING"));
  }

  @Test
  void getReturns404ForAnUnknownRunId() throws Exception {
    when(runService.find("missing")).thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing")).andExpect(status().isNotFound());
  }

  @Test
  void processLogReturnsThePreservedCombinedOutput(@TempDir Path tempDir) throws Exception {
    Path logFile = tempDir.resolve("run-1.log");
    Files.writeString(logFile, "gradle diagnostic output");
    when(runService.processLog("run-1")).thenReturn(logFile);

    mockMvc
        .perform(get("/api/v1/runs/run-1/log"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", "attachment; filename=\"run-1-process.log\""))
        .andExpect(content().string("gradle diagnostic output"));
  }

  @Test
  void processLogReturns404BeforeALogIsAvailable() throws Exception {
    when(runService.processLog("run-1")).thenThrow(new RunLogNotFoundException("run-1"));

    mockMvc.perform(get("/api/v1/runs/run-1/log")).andExpect(status().isNotFound());
  }

  /**
   * {@code cancel()} must be gated by {@link
   * dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService}: a cancel request
   * arriving while the runner is still recovering non-terminal runs from a restart could otherwise
   * reach a stale, no-longer-tracked run and surface as raw {@code 500} instead of a clear {@code
   * 503}.
   */
  @Test
  void cancelReturns503WhenTheRunnerIsStillRecoveringFromARestart() throws Exception {
    when(runService.cancel("run-1")).thenThrow(new RunnerRecoveringException());

    mockMvc.perform(post("/api/v1/runs/run-1/cancel")).andExpect(status().isServiceUnavailable());
  }

  /**
   * Locks the actual contract ({@code application/problem+json} content type plus {@code
   * status}/{@code title}/{@code detail}/{@code instance}), not just HTTP status - a bare status
   * check would stay green even if {@code spring.mvc.problemdetails.enabled} were removed, silently
   * reverting to Spring's classic {@code {timestamp,status,error,path}} shape.
   */
  private static ResultActions assertProblemDetailShape(
      ResultActions actions, int status, String title) throws Exception {
    return actions
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").value(title))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.instance").value("/api/v1/runs"));
  }
}
