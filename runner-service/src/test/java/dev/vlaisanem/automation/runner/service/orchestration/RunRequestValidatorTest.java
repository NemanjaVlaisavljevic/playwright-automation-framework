package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunRequestValidatorTest {

  // No "rejects an unsupported combination" test exists yet: Environment has exactly one value
  // (PUBLIC) today, so there is no real enum literal that would exercise the rejection branch -
  // adding one just to test it would mean testing a scenario that cannot currently happen. That
  // branch becomes testable the moment a second Environment value is introduced.
  @ParameterizedTest
  @EnumSource(Suite.class)
  void allowsEverySuiteUnderPublic(Suite suite) {
    assertThatCode(() -> RunRequestValidator.validate(Environment.PUBLIC, suite))
        .doesNotThrowAnyException();
  }
}
