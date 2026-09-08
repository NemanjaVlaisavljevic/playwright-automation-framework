package dev.vlaisanem.automation.runner.service.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcScopeTest {

  private static final String KEY = "runId";

  @AfterEach
  void clearMdc() {
    MDC.remove(KEY);
  }

  @Test
  void setsTheKeyDuringTheActionAndRemovesItAfterwardWhenThereWasNoPreviousValue() {
    assertThat(MDC.get(KEY)).isNull();

    withMdcCapturingDuring("run-1");

    assertThat(MDC.get(KEY)).isNull();
  }

  @Test
  void restoresThePreviousValueRatherThanRemovingItWhenOneAlreadyExisted() {
    MDC.put(KEY, "outer-run");

    withMdcCapturingDuring("inner-run");

    assertThat(MDC.get(KEY)).isEqualTo("outer-run");
  }

  @Test
  void restoresThePreviousValueEvenWhenTheActionThrows() {
    MDC.put(KEY, "outer-run");

    assertThatThrownBy(
            () ->
                MdcScope.withMdc(
                    KEY,
                    "inner-run",
                    () -> {
                      throw new RuntimeException("boom");
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("boom");

    assertThat(MDC.get(KEY)).isEqualTo("outer-run");
  }

  @Test
  void removesTheKeyWhenTheActionThrowsAndThereWasNoPreviousValue() {
    assertThatThrownBy(
            () ->
                MdcScope.withMdc(
                    KEY,
                    "run-1",
                    () -> {
                      throw new RuntimeException("boom");
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(MDC.get(KEY)).isNull();
  }

  private void withMdcCapturingDuring(String value) {
    String[] duringAction = new String[1];
    MdcScope.withMdc(KEY, value, () -> duringAction[0] = MDC.get(KEY));
    assertThat(duringAction[0]).isEqualTo(value);
  }
}
