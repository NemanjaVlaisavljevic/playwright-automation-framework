package dev.vlaisanem.automation.runner.service.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactDownload;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactService;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ArtifactController.class, RunExceptionHandler.class})
class ArtifactControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ArtifactService artifactService;

  @Test
  void listReturnsEverySummaryForTheRun() throws Exception {
    when(artifactService.listForRun(eq("run-1"), isNull()))
        .thenReturn(List.of(screenshotEntry("a"), traceEntry("b")));

    mockMvc
        .perform(get("/api/v1/runs/run-1/artifacts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$[0].artifactId").value("a"))
        .andExpect(jsonPath("$[0].type").value("SCREENSHOT"))
        .andExpect(jsonPath("$[0].downloadUrl").value("/api/v1/runs/run-1/artifacts/a"))
        .andExpect(jsonPath("$[0].relativePath").doesNotExist())
        .andExpect(jsonPath("$[1].artifactId").value("b"))
        .andExpect(jsonPath("$[1].type").value("TRACE"));
  }

  @Test
  void listPassesTheTestIdQueryParameterThrough() throws Exception {
    when(artifactService.listForRun("run-1", "test-1")).thenReturn(List.of(screenshotEntry("a")));

    mockMvc
        .perform(get("/api/v1/runs/run-1/artifacts").queryParam("testId", "test-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].artifactId").value("a"));
  }

  @Test
  void listReturns404WhenTheRunDoesNotExist() throws Exception {
    when(artifactService.listForRun(eq("missing"), any()))
        .thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing/artifacts")).andExpect(status().isNotFound());
  }

  @Test
  void listReturns500WithASpecificButGenericDetailWhenTheManifestIsCorrupt() throws Exception {
    // "simulated corruption" here is the internal diagnostic reason - see
    // RunExceptionHandlerTest for the precise proof that it never reaches the client.
    when(artifactService.listForRun(eq("run-1"), any()))
        .thenThrow(new ArtifactManifestCorruptException("run-1", "simulated corruption"));

    mockMvc
        .perform(get("/api/v1/runs/run-1/artifacts"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.detail")
                .value("Artifact data for run run-1 is corrupt and cannot be served."))
        .andExpect(
            jsonPath(
                "$.detail",
                org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("simulated corruption"))));
  }

  @Test
  void downloadServesAScreenshotInlineWithThePngContentType(@TempDir Path tempDir)
      throws Exception {
    Path file = tempDir.resolve("failure.png");
    Files.writeString(file, "fake png bytes");
    when(artifactService.download("run-1", "a"))
        .thenReturn(new ArtifactDownload(screenshotEntry("a"), file));

    mockMvc
        .perform(get("/api/v1/runs/run-1/artifacts/a"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(header().string("Content-Disposition", "inline; filename=\"a.png\""))
        .andExpect(content().string("fake png bytes"));
  }

  @Test
  void downloadServesATraceAsAnAttachmentWithTheZipContentType(@TempDir Path tempDir)
      throws Exception {
    Path file = tempDir.resolve("trace.zip");
    Files.writeString(file, "fake zip bytes");
    when(artifactService.download("run-1", "b"))
        .thenReturn(new ArtifactDownload(traceEntry("b"), file));

    mockMvc
        .perform(get("/api/v1/runs/run-1/artifacts/b"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/zip"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"b.zip\""));
  }

  @Test
  void downloadReturns404ForAnUnknownArtifactId() throws Exception {
    when(artifactService.download("run-1", "missing"))
        .thenThrow(new ArtifactNotFoundException("run-1", "missing"));

    mockMvc.perform(get("/api/v1/runs/run-1/artifacts/missing")).andExpect(status().isNotFound());
  }

  @Test
  void downloadReturns404WhenTheRunDoesNotExist() throws Exception {
    when(artifactService.download("missing", "a")).thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing/artifacts/a")).andExpect(status().isNotFound());
  }

  private static ArtifactManifestEntry screenshotEntry(String artifactId) throws IOException {
    return entry(artifactId, ArtifactType.SCREENSHOT, artifactId + ".png", "image/png");
  }

  private static ArtifactManifestEntry traceEntry(String artifactId) throws IOException {
    return entry(artifactId, ArtifactType.TRACE, artifactId + ".zip", "application/zip");
  }

  private static ArtifactManifestEntry entry(
      String artifactId, ArtifactType type, String relativePath, String mediaType) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        "run-1",
        "test-1",
        "some test",
        null,
        type,
        relativePath,
        mediaType,
        1024,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
