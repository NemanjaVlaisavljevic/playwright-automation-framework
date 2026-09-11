package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of {@link RunCatalog}'s combinations this deployment actually allows. {@link RunCatalog}
 * still knows every combination the codebase can run at all; this class additionally asks whether
 * the current deployment wants to advertise/accept it. {@link
 * dev.vlaisanem.automation.runner.service.orchestration.RunRequestValidator} and {@link
 * dev.vlaisanem.automation.runner.service.api.CapabilitiesResponse} both filter through it.
 *
 * @param profile which combinations are currently allowed, beyond what {@link RunCatalog} alone
 *     permits - see {@link DeploymentProfile}.
 */
public record RunAvailabilityPolicy(DeploymentProfile profile) {

  /**
   * {@link #LOCAL_DEV} - everything {@link RunCatalog} allows. {@link #PORTFOLIO} - {@link
   * Environment#PUBLIC} only; {@link Environment#LOCAL} needs the separate seven-container stack
   * this deployment's resource budget doesn't carry.
   */
  public enum DeploymentProfile {
    LOCAL_DEV,
    PORTFOLIO
  }

  public RunAvailabilityPolicy {
    Objects.requireNonNull(profile, "profile must not be null");
  }

  /** Today's unrestricted default - every combination {@link RunCatalog} allows. */
  public static RunAvailabilityPolicy localDev() {
    return new RunAvailabilityPolicy(DeploymentProfile.LOCAL_DEV);
  }

  /**
   * Whether {@code environment}+{@code suite} is both known to {@link RunCatalog} and allowed under
   * this profile.
   */
  public boolean allows(Environment environment, Suite suite) {
    if (RunCatalog.gradleTaskFor(environment, suite).isEmpty()) {
      return false;
    }
    return profile == DeploymentProfile.LOCAL_DEV || environment == Environment.PUBLIC;
  }

  /** {@link RunCatalog#allowedKeys()}, filtered down to what this profile actually allows. */
  public Set<RunCatalog.Key> allowedKeys() {
    return RunCatalog.allowedKeys().stream()
        .filter(key -> allows(key.environment(), key.suite()))
        .collect(Collectors.toUnmodifiableSet());
  }
}
