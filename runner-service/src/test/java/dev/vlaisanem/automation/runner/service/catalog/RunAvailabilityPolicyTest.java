package dev.vlaisanem.automation.runner.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunAvailabilityPolicyTest {

  @Test
  void rejectsANullProfile() {
    assertThatThrownBy(() -> new RunAvailabilityPolicy(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void localDevAllowsExactlyWhatTheCatalogAllows() {
    RunAvailabilityPolicy policy = RunAvailabilityPolicy.localDev();

    assertThat(policy.allowedKeys()).isEqualTo(RunCatalog.allowedKeys());
  }

  @Test
  void localDevAllowsLocalJourney() {
    assertThat(RunAvailabilityPolicy.localDev().allows(Environment.LOCAL, Suite.JOURNEY)).isTrue();
  }

  @Test
  void portfolioAllowsNoLocalCombinationEvenOnesTheCatalogKnows() {
    RunAvailabilityPolicy portfolio = new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO);

    for (Suite suite : Suite.values()) {
      assertThat(portfolio.allows(Environment.LOCAL, suite))
          .as("LOCAL + %s under PORTFOLIO", suite)
          .isFalse();
    }
  }

  @Test
  void portfolioStillAllowsEveryPublicCombinationTheCatalogKnows() {
    RunAvailabilityPolicy portfolio = new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO);

    for (Suite suite : Suite.values()) {
      boolean catalogAllowsIt = RunCatalog.gradleTaskFor(Environment.PUBLIC, suite).isPresent();
      assertThat(portfolio.allows(Environment.PUBLIC, suite))
          .as("PUBLIC + %s under PORTFOLIO", suite)
          .isEqualTo(catalogAllowsIt);
    }
  }

  @Test
  void neitherProfileAllowsACombinationTheCatalogDoesNotKnowAtAll() {
    // Environment.LOCAL + Suite.SMOKE is not in RunCatalog today - neither profile can turn on a
    // combination the catalog itself never mapped to a Gradle task.
    assertThat(RunCatalog.gradleTaskFor(Environment.LOCAL, Suite.SMOKE)).isEmpty();
    assertThat(RunAvailabilityPolicy.localDev().allows(Environment.LOCAL, Suite.SMOKE)).isFalse();
    assertThat(
            new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO)
                .allows(Environment.LOCAL, Suite.SMOKE))
        .isFalse();
  }

  @Test
  void allowedKeysIsUnmodifiable() {
    Set<RunCatalog.Key> keys = RunAvailabilityPolicy.localDev().allowedKeys();

    assertThatThrownBy(() -> keys.add(new RunCatalog.Key(Environment.PUBLIC, Suite.SMOKE)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
