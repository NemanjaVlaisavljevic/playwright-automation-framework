package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of {@link RunCatalog}'s combinations <em>this deployment</em> actually allows - the
 * intersection {@code docs/DEPLOYMENT_ARCHITECTURE.md}'s "LOCAL is out of scope for the portfolio
 * deployment" section calls for. {@link RunCatalog} stays unchanged: it still knows every
 * combination this codebase is capable of running at all (local development, CI, and the portfolio
 * deployment alike). This class is the one place that additionally asks "and does the
 * currently-running deployment want to advertise/accept that combination?" - {@link
 * dev.vlaisanem.automation.runner.service.orchestration.RunRequestValidator} and {@link
 * dev.vlaisanem.automation.runner.service.api.CapabilitiesResponse} both filter through it, so a
 * portfolio deployment's {@code /api/v1/capabilities} never advertises {@link Environment#LOCAL}
 * and its validator rejects it outright, without either of them hand-duplicating the profile check.
 *
 * @param profile which combinations are currently allowed, beyond what {@link RunCatalog} alone
 *     permits - see {@link DeploymentProfile}.
 */
public record RunAvailabilityPolicy(DeploymentProfile profile) {

  /**
   * {@link #LOCAL_DEV} - everything {@link RunCatalog} allows, unchanged (today's behavior: local
   * development, CI, and any environment that has not opted into the narrower portfolio profile).
   * {@link #PORTFOLIO} - {@link Environment#PUBLIC} only; {@link Environment#LOCAL} needs the
   * separate seven-container Restful Booker Platform stack running alongside it, which this
   * deployment's RAM/disk/attack-surface budget does not carry (see {@code
   * docs/DEPLOYMENT_ARCHITECTURE.md} section 2).
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
