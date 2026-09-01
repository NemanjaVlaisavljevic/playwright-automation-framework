package dev.vlaisanem.automation.runner.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract only - see {@link CapabilitiesResponse} for where the allowlist actually comes
 * from.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {

  @Operation(operationId = "getRunnerCapabilities")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CapabilitiesResponse.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping
  public CapabilitiesResponse get() {
    return CapabilitiesResponse.current();
  }
}
