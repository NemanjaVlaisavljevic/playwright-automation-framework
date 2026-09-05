package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code CUSTOM}-suite catalog a client picks {@code testKeys} from. {@code environment} is a
 * required query parameter, not a path segment, so this endpoint's shape reads naturally as "tests
 * available for this environment" - today that can only ever be {@code PUBLIC} (see {@code
 * RunCatalog}: {@code CUSTOM} has no {@code LOCAL} mapping), so any other value is rejected rather
 * than silently returning an empty list.
 */
@RestController
@RequestMapping("/api/v1/tests")
public class TestCatalogController {

  private final TestCatalogService testCatalogService;

  public TestCatalogController(TestCatalogService testCatalogService) {
    this.testCatalogService = testCatalogService;
  }

  @Operation(operationId = "listPublicTests")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = TestCatalogResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "environment is missing or not PUBLIC.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "The committed test catalog file is missing or unreadable.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping
  public TestCatalogResponse list(
      @Parameter(required = true) @RequestParam Environment environment) {
    if (environment != Environment.PUBLIC) {
      throw new UnsupportedTestCatalogEnvironmentException(environment);
    }
    return new TestCatalogResponse(testCatalogService.current());
  }
}
