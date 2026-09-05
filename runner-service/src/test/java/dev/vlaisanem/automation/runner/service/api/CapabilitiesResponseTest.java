package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilitiesResponse#current()} must mirror {@code RunRequestValidator}'s real allowlist,
 * not a hand-copied duplicate of it - these assertions are deliberately written against the same
 * combinations {@code RunCatalog} actually allows, so a future allowlist change that forgets to
 * also touch this class fails here.
 */
class CapabilitiesResponseTest {

  @Test
  void reflectsTheRealAllowlistForPublicAndLocal() {
    CapabilitiesResponse response = CapabilitiesResponse.current();

    assertThat(response.apiVersion()).isEqualTo("v1");
    assertThat(response.eventSchemaVersion()).isEqualTo(RunnerEvent.CURRENT_SCHEMA_VERSION);
    // Environment.PUBLIC before Environment.LOCAL - enum declaration order, which
    // CapabilitiesResponse.current() deliberately sorts by (see its own compareTo call).
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
    // LOCAL only ever runs mutation-safe against a manually-started local stack for JOURNEY today -
    // see RunCatalog and Environment.LOCAL's own Javadoc for why this is deliberately narrow.
    assertThat(response.environments().get(1).suites()).containsExactly(Suite.JOURNEY);
  }
}
