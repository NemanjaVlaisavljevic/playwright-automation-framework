package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactDownload;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract only - all path/symlink safety and manifest trust decisions live in {@link
 * ArtifactService}. {@code testId} is a query parameter, not a path variable: a real {@code testId}
 * (JUnit's own unique-ID format, e.g. {@code [engine:junit-jupiter]/[class:Foo]/[method:bar()]})
 * contains {@code /} characters, which would make it an unusable REST path segment.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class ArtifactController {

  private final ArtifactService artifactService;

  public ArtifactController(ArtifactService artifactService) {
    this.artifactService = artifactService;
  }

  @Operation(operationId = "listRunArtifacts")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                array =
                    @ArraySchema(
                        schema = @Schema(implementation = ArtifactSummaryResponse.class)))),
    @ApiResponse(
        responseCode = "404",
        description = "No run exists for the given runId.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error, or the run's artifact manifest is corrupt.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{runId}/artifacts")
  public List<ArtifactSummaryResponse> list(
      @PathVariable String runId,
      @Parameter(description = "Narrows the result to one test's own artifacts, if given.")
          @RequestParam(required = false)
          String testId) {
    return artifactService.listForRun(runId, testId).stream()
        .map(ArtifactSummaryResponse::from)
        .toList();
  }

  @Operation(operationId = "downloadRunArtifact")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = {
          @Content(
              mediaType = MediaType.IMAGE_PNG_VALUE,
              schema = @Schema(type = "string", format = "binary")),
          @Content(
              mediaType = "application/zip",
              schema = @Schema(type = "string", format = "binary")),
          @Content(mediaType = "video/webm", schema = @Schema(type = "string", format = "binary"))
        }),
    @ApiResponse(
        responseCode = "404",
        description =
            "No run exists for the given runId, or no artifact exists for the given artifactId.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error, or the run's artifact manifest is corrupt.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{runId}/artifacts/{artifactId}")
  public ResponseEntity<Resource> download(
      @PathVariable String runId, @PathVariable String artifactId) {
    ArtifactDownload download = artifactService.download(runId, artifactId);
    ArtifactManifestEntry entry = download.entry();
    Resource resource = new FileSystemResource(download.file());
    String filename = artifactId + extensionFor(entry.type());
    ContentDisposition disposition =
        (entry.type() == ArtifactType.SCREENSHOT
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
            .filename(filename)
            .build();
    return ResponseEntity.ok()
        .contentType(contentTypeFor(entry.type()))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(resource);
  }

  /**
   * Never {@code entry.mediaType()} - a value that came from the manifest file, which {@link
   * ArtifactService}'s own Javadoc already treats as untrusted input. {@link ArtifactType} is a
   * fixed, closed enum this controller fully controls, so switching on it (rather than trusting any
   * string value, however it was validated) is what actually keeps the response's real {@code
   * Content-Type} header safe.
   */
  private static MediaType contentTypeFor(ArtifactType type) {
    return switch (type) {
      case SCREENSHOT -> MediaType.IMAGE_PNG;
      case TRACE -> MediaType.parseMediaType("application/zip");
      case VIDEO -> MediaType.parseMediaType("video/webm");
    };
  }

  private static String extensionFor(ArtifactType type) {
    return switch (type) {
      case SCREENSHOT -> ".png";
      case TRACE -> ".zip";
      case VIDEO -> ".webm";
    };
  }
}
