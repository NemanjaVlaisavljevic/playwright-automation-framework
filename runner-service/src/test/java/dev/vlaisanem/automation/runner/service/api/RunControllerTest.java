package dev.vlaisanem.automation.runner.service.api;

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

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.exception.RunLogNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

  @Test
  void createReturns202WithTheQueuedRun() throws Exception {
    Run queued =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-08-30T00:00:00Z"));
    when(runService.submit(Environment.PUBLIC, Suite.SMOKE)).thenReturn(queued);

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

  @Test
  void createReturns400ForAnUnsupportedCombination() throws Exception {
    when(runService.submit(eq(Environment.PUBLIC), any()))
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

  @Test
  void deleteOnTheRunsCollectionReturns405() throws Exception {
    assertProblemDetailShape(mockMvc.perform(delete("/api/v1/runs")), 405, "Method Not Allowed");
  }

  /**
   * Regression test for the review's requirement: an exception the service layer never anticipated
   * must still surface as 500 with a generic message, never {@code exception.getMessage()} - see
   * {@link RunExceptionHandlerTest} for the handler-level proof that the original exception is
   * still logged, not just swallowed.
   */
  @Test
  void createReturns500WithAGenericMessageForAnUnexpectedException() throws Exception {
    when(runService.submit(any(), any()))
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
    when(runService.submit(any(), any())).thenThrow(new RunQueueFullException(5));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void createReturns503WhenTheCanonicalEventJournalIsUnavailable() throws Exception {
    when(runService.submit(any(), any()))
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
   * Regression test for the review's finding: a test that only checks the HTTP status stays green
   * even if {@code spring.mvc.problemdetails.enabled} were removed, silently regressing the
   * framework-triggered error shape back to Spring's classic {@code {timestamp,status,error,path}}
   * body - a completely different shape from our own {@code ProblemDetail} responses that the
   * frontend would then have to special-case. Locks the actual contract instead: {@code
   * application/problem+json} content type plus the {@code status}/{@code title}/{@code
   * detail}/{@code instance} fields.
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
