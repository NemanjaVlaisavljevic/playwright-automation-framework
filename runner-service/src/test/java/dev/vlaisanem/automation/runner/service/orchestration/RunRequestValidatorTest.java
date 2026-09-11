package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunRequestValidatorTest {

  private static final RunAvailabilityPolicy LOCAL_DEV = RunAvailabilityPolicy.localDev();
  private static final RunAvailabilityPolicy PORTFOLIO =
      new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO);

  @ParameterizedTest
  @EnumSource(Suite.class)
  void allowsEverySuiteUnderPublicRegardlessOfProfile(Suite suite) {
    assertThatCode(() -> RunRequestValidator.validate(LOCAL_DEV, Environment.PUBLIC, suite))
        .doesNotThrowAnyException();
    assertThatCode(() -> RunRequestValidator.validate(PORTFOLIO, Environment.PUBLIC, suite))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsOnlyJourneyUnderLocalWhenLocalDev() {
    assertThatCode(() -> RunRequestValidator.validate(LOCAL_DEV, Environment.LOCAL, Suite.JOURNEY))
        .doesNotThrowAnyException();
  }

  /**
   * {@link Environment#LOCAL} is the first enum literal that exercises the rejection branch, since
   * unlike {@link Environment#PUBLIC} it does not allow every {@link Suite}.
   */
  @ParameterizedTest
  @EnumSource(value = Suite.class, names = "JOURNEY", mode = EnumSource.Mode.EXCLUDE)
  void rejectsEverySuiteOtherThanJourneyUnderLocalWhenLocalDev(Suite suite) {
    assertThatThrownBy(() -> RunRequestValidator.validate(LOCAL_DEV, Environment.LOCAL, suite))
        .isInstanceOf(UnsupportedRunCombinationException.class);
  }

  /**
   * The portfolio profile's whole point (see {@code docs/DEPLOYMENT_ARCHITECTURE.md}, "LOCAL is out
   * of scope for the portfolio deployment"): rejects even the one combination {@link
   * RunAvailabilityPolicy.DeploymentProfile#LOCAL_DEV} allows.
   */
  @ParameterizedTest
  @EnumSource(Suite.class)
  void rejectsEverySuiteUnderLocalWhenPortfolio(Suite suite) {
    assertThatThrownBy(() -> RunRequestValidator.validate(PORTFOLIO, Environment.LOCAL, suite))
        .isInstanceOf(UnsupportedRunCombinationException.class);
  }

  @Test
  void allowedCombinationsReflectsExactlyWhatValidateAcceptsForLocalDev() {
    assertAllowedCombinationsMatchesValidate(LOCAL_DEV);
  }

  @Test
  void allowedCombinationsReflectsExactlyWhatValidateAcceptsForPortfolio() {
    assertAllowedCombinationsMatchesValidate(PORTFOLIO);
  }

  @Test
  void portfolioAllowedCombinationsOmitsLocalEntirely() {
    Map<Environment, Set<Suite>> allowed = RunRequestValidator.allowedCombinations(PORTFOLIO);

    assertThatCode(
            () -> {
              if (allowed.containsKey(Environment.LOCAL)) {
                throw new AssertionError(
                    "PORTFOLIO must not advertise Environment.LOCAL at all, but found: "
                        + allowed.get(Environment.LOCAL));
              }
            })
        .doesNotThrowAnyException();
  }

  private static void assertAllowedCombinationsMatchesValidate(RunAvailabilityPolicy policy) {
    Map<Environment, Set<Suite>> allowed = RunRequestValidator.allowedCombinations(policy);

    assertThatCode(
            () -> {
              for (Environment environment : Environment.values()) {
                for (Suite suite : Suite.values()) {
                  boolean listedAsAllowed =
                      allowed.getOrDefault(environment, Set.of()).contains(suite);
                  boolean actuallyValidates = doesValidate(policy, environment, suite);
                  if (listedAsAllowed != actuallyValidates) {
                    throw new AssertionError(
                        environment
                            + " + "
                            + suite
                            + ": allowedCombinations() says "
                            + listedAsAllowed
                            + " but validate() says "
                            + actuallyValidates);
                  }
                }
              }
            })
        .doesNotThrowAnyException();
  }

  private static boolean doesValidate(
      RunAvailabilityPolicy policy, Environment environment, Suite suite) {
    try {
      RunRequestValidator.validate(policy, environment, suite);
      return true;
    } catch (UnsupportedRunCombinationException expected) {
      return false;
    }
  }
}
