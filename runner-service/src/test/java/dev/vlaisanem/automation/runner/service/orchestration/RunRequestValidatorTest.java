package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunRequestValidatorTest {

  @ParameterizedTest
  @EnumSource(Suite.class)
  void allowsEverySuiteUnderPublic(Suite suite) {
    assertThatCode(() -> RunRequestValidator.validate(Environment.PUBLIC, suite))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsOnlyJourneyUnderLocal() {
    assertThatCode(() -> RunRequestValidator.validate(Environment.LOCAL, Suite.JOURNEY))
        .doesNotThrowAnyException();
  }

  /**
   * Now testable for real (see the git history of this file): {@link Environment#LOCAL} is the
   * first enum literal that actually exercises the rejection branch, since it does not allow every
   * {@link Suite} the way {@link Environment#PUBLIC} does.
   */
  @ParameterizedTest
  @EnumSource(value = Suite.class, names = "JOURNEY", mode = EnumSource.Mode.EXCLUDE)
  void rejectsEverySuiteOtherThanJourneyUnderLocal(Suite suite) {
    assertThatThrownBy(() -> RunRequestValidator.validate(Environment.LOCAL, suite))
        .isInstanceOf(UnsupportedRunCombinationException.class);
  }

  @Test
  void allowedCombinationsReflectsExactlyWhatValidateAccepts() {
    Map<Environment, Set<Suite>> allowed = RunRequestValidator.allowedCombinations();

    assertThatCode(
            () -> {
              for (Environment environment : Environment.values()) {
                for (Suite suite : Suite.values()) {
                  boolean listedAsAllowed =
                      allowed.getOrDefault(environment, Set.of()).contains(suite);
                  boolean actuallyValidates = doesValidate(environment, suite);
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

  private static boolean doesValidate(Environment environment, Suite suite) {
    try {
      RunRequestValidator.validate(environment, suite);
      return true;
    } catch (UnsupportedRunCombinationException expected) {
      return false;
    }
  }
}
