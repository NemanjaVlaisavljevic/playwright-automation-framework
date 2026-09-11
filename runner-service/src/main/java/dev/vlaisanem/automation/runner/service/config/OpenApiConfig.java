package dev.vlaisanem.automation.runner.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata only - the actual paths/schemas are discovered by springdoc from the controllers
 * themselves. {@code version} is the REST contract version ({@code v1}, matching the {@code
 * /api/v1} URL prefix everywhere), deliberately not the build's own {@code 1.0.0-SNAPSHOT} version
 * - the two change independently.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI runnerServiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Playwright Automation Runner API")
                .version("v1")
                .description(
                    "REST control plane for launching and observing allowlisted automation"
                        + " suites. The SSE event stream (GET /api/v1/runs/{runId}/events) is"
                        + " intentionally not part of this document - see"
                        + " docs/SSE_CONTRACT_V1.md."));
  }

  /**
   * {@code org.springframework.http.ProblemDetail}, the error-response schema for every 4xx/5xx
   * response, carries no Bean Validation/{@code @Schema} annotations of its own (it's a third-party
   * class, so nothing to annotate), so springdoc infers no {@code required} array; this customizer
   * corrects the generated document after the fact.
   *
   * <p>{@code title}/{@code status}/{@code detail}/{@code instance} are marked required since every
   * {@code RunExceptionHandler} branch populates them via {@code ProblemDetail.forStatusAndDetail}.
   * {@code type}/{@code properties} stay optional since Spring omits both keys rather than
   * serializing a default/empty value.
   *
   * <p>Also clears {@code instance}'s {@code format}: springdoc maps its {@code java.net.URI} type
   * to {@code format: uri} (absolute-URI), but the actual value is a relative URI reference per RFC
   * 7807, and typed-openapi maps {@code format: uri} to Zod's {@code z.url()}, which rejects a
   * relative path - so output-validating a real error response would throw.
   */
  @Bean
  public OpenApiCustomizer problemDetailContractCustomizer() {
    return openApi -> {
      Schema<?> problemDetail = openApi.getComponents().getSchemas().get("ProblemDetail");
      if (problemDetail == null) {
        return;
      }
      problemDetail.setRequired(List.of("title", "status", "detail", "instance"));
      Object instanceProperty = problemDetail.getProperties().get("instance");
      if (instanceProperty instanceof Schema<?> instanceSchema) {
        instanceSchema.setFormat(null);
      }
    };
  }
}
