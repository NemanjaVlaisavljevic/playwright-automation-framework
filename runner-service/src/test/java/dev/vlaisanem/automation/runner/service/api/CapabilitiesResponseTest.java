package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import org.junit.jupiter.api.Test;

/**
 * Assertions mirror the real allowlist {@code RunCatalog}/{@link RunAvailabilityPolicy} enforce,
 * not a hand-copied duplicate, so a future allowlist change that forgets to touch this class fails
 * here.
 */
class CapabilitiesResponseTest {

  @Test
  void reflectsTheRealAllowlistForPublicAndLocalUnderLocalDev() {
    CapabilitiesResponse response = CapabilitiesResponse.current(RunAvailabilityPolicy.localDev());

    assertThat(response.apiVersion()).isEqualTo("v1");
    assertThat(response.eventSchemaVersion()).isEqualTo(RunnerEvent.CURRENT_SCHEMA_VERSION);
    // PUBLIC before LOCAL: enum declaration order, which CapabilitiesResponse.current()
    // deliberately sorts by.
    assertThat(response.environments())
        .extracting(CapabilitiesResponse.EnvironmentCapabilities::name)
        .containsExactly(Environment.PUBLIC, Environment.LOCAL);
    assertThat(response.environments().get(0).suites())
        .containsExactly(
            Suite.SMOKE,
            Suite.API,
            Suite.UI,
            Suite.JOURNEY,
            Suite.REGRESSION,
            Suite.FIXTURE,
            Suite.CUSTOM);
    // LOCAL only ever runs JOURNEY (mutation-safe, manually-started local stack) - see
    // RunCatalog/Environment.LOCAL for why this is deliberately narrow.
    assertThat(response.environments().get(1).suites()).containsExactly(Suite.JOURNEY);
  }

  /**
   * {@code /api/v1/capabilities} must never advertise {@link Environment#LOCAL} at all - not just
   * with an empty suite list - since PORTFOLIO deployments are out of scope for LOCAL (see {@code
   * docs/DEPLOYMENT_ARCHITECTURE.md}).
   */
  @Test
  void omitsLocalEntirelyUnderPortfolio() {
    CapabilitiesResponse response =
        CapabilitiesResponse.current(new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO));

    assertThat(response.environments())
        .extracting(CapabilitiesResponse.EnvironmentCapabilities::name)
        .containsExactly(Environment.PUBLIC);
    assertThat(response.environments().get(0).suites())
        .containsExactly(
            Suite.SMOKE,
            Suite.API,
            Suite.UI,
            Suite.JOURNEY,
            Suite.REGRESSION,
            Suite.FIXTURE,
            Suite.CUSTOM);
  }
}
