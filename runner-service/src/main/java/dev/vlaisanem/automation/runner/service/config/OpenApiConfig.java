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
   * {@code org.springframework.http.ProblemDetail} - used as the error-response schema across every
   * 4xx/5xx response in this API - carries no Bean Validation / {@code @Schema} annotations of its
   * own, so springdoc infers no {@code required} array for it at all, unlike {@link
   * dev.vlaisanem.automation.runner.service.api.RunResponse} and {@link
   * dev.vlaisanem.automation.runner.service.api.CapabilitiesResponse}, where that same gap was
   * closed with an explicit {@code @Schema(requiredMode = REQUIRED)} directly on the record. There
   * is no record to annotate here - {@code ProblemDetail} is a third-party framework class - so
   * this customizer corrects the generated document after the fact instead.
   *
   * <p>{@code title}/{@code status}/{@code detail}/{@code instance} are marked required: every
   * {@code RunExceptionHandler} branch builds its response via {@code
   * ProblemDetail.forStatusAndDetail(status, detail)}, which always populates {@code title} (from
   * the {@code HttpStatus} reason phrase) and {@code status}/{@code detail} explicitly; {@code
   * instance} is populated by Spring MVC's own {@code ProblemDetail} handling from the request URI
   * even though nothing in this codebase sets it explicitly - confirmed empirically against a real
   * {@code 404} response (body: {@code {"detail":"...","instance":"/api/v1/runs/...
   * ","status":404,"title":"Not Found"}}). {@code type} and {@code properties} are deliberately
   * left optional: that same response has no {@code type} key at all (Spring omits the {@code
   * about:blank} default rather than serializing it) and no {@code properties} key (empty extension
   * map is omitted, not emitted as {@code {}}).
   *
   * <p>Also fixes {@code instance}'s format: springdoc maps the field's Java {@code java.net.URI}
   * type to {@code format: uri} (an absolute-URI format), but RFC 7807 defines {@code instance} as
   * a URI *reference*, and the value above ({@code /api/v1/runs/does-not-exist}) is relative.
   * typed-openapi maps {@code format: uri} to Zod's {@code z.url()}, which rejects a relative path
   * outright - output-validating a real error response would then throw. Clearing the format leaves
   * a plain string, matching the value's actual URI-reference (not absolute-URI) semantics.
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
