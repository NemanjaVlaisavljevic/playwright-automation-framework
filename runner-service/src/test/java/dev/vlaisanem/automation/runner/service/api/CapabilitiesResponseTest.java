package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilitiesResponse#current()} must mirror {@code RunRequestValidator}'s real allowlist,
 * not a hand-copied duplicate of it - these assertions are deliberately written against the same
 * suites the validator actually allows for {@code PUBLIC}, so a future allowlist change that
 * forgets to also touch this class fails here.
 */
class CapabilitiesResponseTest {

  @Test
  void reflectsTheRealAllowlistForPublic() {
    CapabilitiesResponse response = CapabilitiesResponse.current();

    assertThat(response.apiVersion()).isEqualTo("v1");
    assertThat(response.eventSchemaVersion()).isEqualTo(RunnerEvent.CURRENT_SCHEMA_VERSION);
    assertThat(response.environments())
        .extracting(CapabilitiesResponse.EnvironmentCapabilities::name)
        .containsExactly(Environment.PUBLIC);
    assertThat(response.environments().get(0).suites())
        .containsExactly(Suite.SMOKE, Suite.API, Suite.UI, Suite.JOURNEY, Suite.REGRESSION);
  }
}
