package dev.vlaisanem.automation.runner.service.config;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deployment profile from {@code runner.deployment-profile} (defaults to {@code
 * LOCAL_DEV}, today's unrestricted behavior) into a single {@link RunAvailabilityPolicy} bean - the
 * portfolio Compose deployment sets {@code RUNNER_DEPLOYMENTPROFILE=PORTFOLIO} (see {@code
 * deploy/docker-compose.yml}) to hide {@link
 * dev.vlaisanem.automation.runner.service.domain.Environment#LOCAL}, everything else (local
 * development, every CI workflow) leaves it unset.
 */
@Configuration
public class RunAvailabilityConfig {

  @Bean
  public RunAvailabilityPolicy runAvailabilityPolicy(
      @Value("${runner.deployment-profile:LOCAL_DEV}") DeploymentProfile profile) {
    return new RunAvailabilityPolicy(profile);
  }
}
