package dev.vlaisanem.automation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Covers {@link TestConfig#targetsSharedEnvironment()}, the origin-normalization check the mutation
 * guard in {@code AutomationExtension} relies on to refuse writes against the shared public host. A
 * false negative here (two URLs that are really the same origin but compare unequal) would let a
 * mutation test slip through unguarded.
 */
class TestConfigTest {

  private static TestConfig configFor(String baseUrl, String sharedTargetBaseUrl) {
    return new TestConfig(
        baseUrl,
        BrowserName.CHROMIUM,
        true,
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        true,
        false,
        Path.of("build/artifacts"),
        "test-run-id",
        "admin",
        "password",
        sharedTargetBaseUrl,
        false);
  }

  @ParameterizedTest(name = "baseUrl={0} sharedTarget={1} -> shared")
  @CsvSource({
    // exact match
    "https://automationintesting.online, https://automationintesting.online",
    // scheme/host case differences
    "HTTPS://AutomationIntesting.Online, https://automationintesting.online",
    // explicit default port spelled out on one side only
    "https://automationintesting.online:443, https://automationintesting.online",
    "https://automationintesting.online, https://automationintesting.online:443",
    // trailing slash on either side
    "https://automationintesting.online/, https://automationintesting.online",
    // path/query beyond the origin is ignored — only scheme+host+port are compared
    "https://automationintesting.online/room, https://automationintesting.online",
  })
  void treatsEquivalentOriginsAsTheSharedTarget(String baseUrl, String sharedTargetBaseUrl) {
    assertThat(configFor(baseUrl, sharedTargetBaseUrl).targetsSharedEnvironment()).isTrue();
  }

  @ParameterizedTest(name = "baseUrl={0} sharedTarget={1} -> not shared")
  @CsvSource({
    // different host entirely (e.g. local Docker SUT vs. public host)
    "http://localhost, https://automationintesting.online",
    // different scheme, same host
    "http://automationintesting.online, https://automationintesting.online",
    // different explicit port on an otherwise identical origin
    "https://automationintesting.online:8443, https://automationintesting.online",
    // subdomain is a different host, not a variant spelling
    "https://staging.automationintesting.online, https://automationintesting.online",
  })
  void treatsDifferentOriginsAsNotShared(String baseUrl, String sharedTargetBaseUrl) {
    assertThat(configFor(baseUrl, sharedTargetBaseUrl).targetsSharedEnvironment()).isFalse();
  }

  @Test
  void localDockerTargetOnDefaultHttpPortIsNotTheSharedHttpsTarget() {
    TestConfig config = configFor("http://localhost:80", "https://automationintesting.online");

    assertThat(config.targetsSharedEnvironment()).isFalse();
  }
}
