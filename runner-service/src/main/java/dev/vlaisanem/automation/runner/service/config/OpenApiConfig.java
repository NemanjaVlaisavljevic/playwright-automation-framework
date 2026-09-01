package dev.vlaisanem.automation.runner.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
}
