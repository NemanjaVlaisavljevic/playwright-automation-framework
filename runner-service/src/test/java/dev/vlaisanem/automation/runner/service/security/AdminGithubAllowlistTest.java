package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminGithubAllowlistTest {

  @Test
  void parsesASingleId() {
    assertThat(AdminGithubAllowlist.parse("123456").ids()).containsExactly(123456L);
  }

  @Test
  void parsesMultipleCommaSeparatedIdsAndTrimsWhitespace() {
    assertThat(AdminGithubAllowlist.parse(" 123456 , 789012 ").ids())
        .containsExactlyInAnyOrder(123456L, 789012L);
  }

  @Test
  void containsReflectsTheParsedSet() {
    AdminGithubAllowlist allowlist = AdminGithubAllowlist.parse("123456");
    assertThat(allowlist.contains(123456L)).isTrue();
    assertThat(allowlist.contains(999L)).isFalse();
  }

  @Test
  void rejectsANullValue() {
    assertThatThrownBy(() -> AdminGithubAllowlist.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void rejectsABlankValue() {
    assertThatThrownBy(() -> AdminGithubAllowlist.parse("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void rejectsANonNumericEntry() {
    assertThatThrownBy(() -> AdminGithubAllowlist.parse("123,not-a-number"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-numeric entry");
  }

  @Test
  void rejectsATrailingCommaLeavingABlankEntry() {
    assertThatThrownBy(() -> AdminGithubAllowlist.parse("123,"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank entry");
  }
}
