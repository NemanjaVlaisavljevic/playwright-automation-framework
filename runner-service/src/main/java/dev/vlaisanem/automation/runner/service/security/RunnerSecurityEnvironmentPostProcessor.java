package dev.vlaisanem.automation.runner.service.security;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Bridges {@code RUNNER_SECURITY_GITHUB_CLIENT_ID}/{@code _SECRET} into the {@code
 * spring.security.oauth2.client.registration.github.*} keys Spring Boot's own {@code
 * OAuth2ClientAutoConfiguration} expects - but only when both are genuinely present - and, in the
 * same pass, enforces the two fail-closed rules that cannot be expressed as a bean-creation-time
 * failure (see {@link SecurityConfig#adminGithubAllowlist} for the one that can): under {@code
 * runner.deployment-profile=PORTFOLIO}, GitHub OAuth2 must be configured at all, and the session
 * cookie must be {@code Secure}.
 *
 * <p><strong>Why this check lives here, not in an {@code ApplicationRunner}</strong> (a review
 * finding): an {@code ApplicationRunner} only executes after the application context has fully
 * refreshed, which is <em>after</em> the embedded Tomcat has already started listening - a
 * misconfigured {@code PORTFOLIO} instance would briefly serve real anonymous traffic through the
 * permissive chain on every restart before that runner got a chance to throw. Throwing here, from
 * {@link #postProcessEnvironment}, aborts {@link SpringApplication#run} before the {@code
 * ApplicationContext} is even created - no bean, no web server factory, no listening socket ever
 * exists. Confirmed empirically (see {@code RunnerSecurityFailFastTest}): a {@code ServerSocket}
 * can bind the exact port a misconfigured instance was told to use, immediately after the expected
 * exception propagates.
 *
 * <p><strong>Binds {@code runner.deployment-profile} via {@link Binder}, not a raw string
 * comparison</strong> (a review finding): comparing the raw property value against the literal
 * {@code "PORTFOLIO"} string could disagree with {@code RunAvailabilityConfig}'s own
 * {@code @Value}-based enum conversion - a lowercase {@code portfolio} (Spring's enum conversion is
 * case-insensitive) or incidental whitespace would silently bypass this entire check while still
 * resolving to {@link DeploymentProfile#PORTFOLIO} later, once the real bean is created. Using the
 * same {@link Binder} mechanism Spring Boot's own configuration binding uses elsewhere guarantees
 * this early check can never disagree with what the application actually ends up running as - and a
 * value that fails to bind at all (neither profile name) aborts startup here for that reason alone,
 * exactly as the later {@code @Value} binding would have failed anyway.
 *
 * <p>Binding {@code registration.github.client-id} to any value at all, including an empty default,
 * makes {@code OAuth2ClientAutoConfiguration} eagerly build a {@code ClientRegistration} at
 * context-refresh time; {@code ClientRegistration.Builder.build()} throws on a blank client id.
 * That would break every credential-less {@code bootRun}/test/CI run with an unrelated-looking
 * startup crash. Setting the property only when both values are real - and leaving it genuinely
 * absent otherwise, never present-with-blank - avoids that entirely.
 *
 * <p>Sets an explicit {@code scope} of {@code read:user} - GitHub's own documented default (no
 * scope requested at all) would already suffice for the three public-profile fields this service
 * reads ({@code id}/{@code login}/{@code avatar_url}), and removing this property entirely was
 * tried first, but a real OAuth round trip against a real GitHub OAuth App showed the resulting
 * `redirect_uri` still requested {@code read:user} regardless - traced to {@code
 * CommonOAuth2Provider.GITHUB}'s own preset default, which {@code
 * ClientRegistration.Builder.scope()} cannot be overridden down to empty for (an empty/absent scope
 * array is a deliberate no-op in that builder, not a clear). So this line changes nothing about
 * what is actually requested; it is kept only as self-documentation of that already-minimal,
 * GitHub-confirmed-safe (read-only, non-sensitive) default, not as an addition on top of it.
 *
 * <p>Registered via {@code META-INF/spring.factories}, not a {@code @Component}: this must run
 * before {@code OAuth2ClientAutoConfiguration} evaluates its own conditions, which happens before
 * the application context (and therefore component scanning) exists at all. Ordered to run
 * immediately after {@link ConfigDataEnvironmentPostProcessor} so {@code application.yml}'s own
 * {@code runner.deployment-profile: LOCAL_DEV} default is already loaded into the environment by
 * the time this reads it - still far earlier than any bean is created.
 */
public class RunnerSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String PROPERTY_SOURCE_NAME = "runnerSecurityGithubOAuth2";
  private static final String REDIRECT_URI_TEMPLATE =
      "{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}";
  // Restates, rather than adds to, Spring Security's own CommonOAuth2Provider.GITHUB preset
  // default - confirmed by reading ClientRegistration.Builder.scope() directly:
  // "if (scope != null && scope.length > 0)" makes an empty/absent scope a deliberate no-op, so
  // there is no way to override this preset down to zero scope through the standard
  // spring.security.oauth2.client.registration.* property path - only a fully custom (non-
  // "github") provider block with hand-specified authorization/token/user-info URIs could bypass
  // it, which trades meaningful config complexity/risk for a purely cosmetic reduction in GitHub's
  // consent screen. read:user itself is GitHub's own documented read-only, non-sensitive scope
  // (public profile info only), so this is set explicitly here for clarity, not because leaving
  // it unset would grant less access.
  private static final String MINIMAL_SCOPE = "read:user";

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String clientId = environment.getProperty("RUNNER_SECURITY_GITHUB_CLIENT_ID", "").trim();
    String clientSecret =
        environment.getProperty("RUNNER_SECURITY_GITHUB_CLIENT_SECRET", "").trim();
    String adminGithubId = environment.getProperty("RUNNER_SECURITY_ADMIN_GITHUB_ID", "").trim();
    boolean credentialsPresent = !clientId.isEmpty() && !clientSecret.isEmpty();

    Binder binder = Binder.get(environment);
    DeploymentProfile profile =
        binder
            .bind("runner.deployment-profile", DeploymentProfile.class)
            .orElse(DeploymentProfile.LOCAL_DEV);
    if (profile == DeploymentProfile.PORTFOLIO) {
      if (!credentialsPresent) {
        throw new IllegalStateException(
            "runner.deployment-profile=PORTFOLIO requires RUNNER_SECURITY_GITHUB_CLIENT_ID and"
                + " RUNNER_SECURITY_GITHUB_CLIENT_SECRET to be set - refusing to start before any"
                + " listening socket is opened; the process will exit so the container"
                + " orchestrator restarts it once they are configured.");
      }
      // Absent entirely defaults to false here (a conservative "not explicitly Secure" reading)
      // even though Tomcat's own runtime default for this property is request-scheme auto-
      // detection, not a hardcoded false - deliberate, since deploy/runner-service/Dockerfile
      // always sets this explicitly (true by default, only ever false via a documented local-only
      // override in deploy/docker-compose.yml) and this check exists specifically to catch that
      // override being left in place by mistake, not to second-guess Tomcat's own auto-detection
      // in some other launch context.
      boolean cookieSecure =
          binder.bind("server.servlet.session.cookie.secure", Boolean.class).orElse(false);
      if (!cookieSecure) {
        throw new IllegalStateException(
            "runner.deployment-profile=PORTFOLIO requires a Secure session cookie"
                + " (SERVER_SERVLET_SESSION_COOKIE_SECURE=true) - refusing to start with a"
                + " non-Secure session cookie in production; the process will exit so the"
                + " container orchestrator restarts it once this is corrected.");
      }
    }

    // Only ever adds properties in the "credentials present" branch. Deliberately adds nothing at
    // all otherwise - RunnerSecurityProperties' own @DefaultValue("false")/@DefaultValue("")
    // already supplies the disabled case, and forcing an explicit "false"/"" here (even via
    // addFirst) would make this bridge unconditionally win over any later, legitimate override -
    // e.g. a test's own @TestPropertySource - since this post-processor's synthetic source ends up
    // evaluated after such overrides are already in place during Spring Boot Test's context
    // bootstrap, not before. Confirmed empirically: an earlier version that always set both keys
    // silently defeated @TestPropertySource("runner.security.oauth2-enabled=true") in a
    // @WebMvcTest, always resolving to the permissive chain regardless of the test's own override.
    if (credentialsPresent) {
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("spring.security.oauth2.client.registration.github.client-id", clientId);
      properties.put(
          "spring.security.oauth2.client.registration.github.client-secret", clientSecret);
      properties.put(
          "spring.security.oauth2.client.registration.github.redirect-uri", REDIRECT_URI_TEMPLATE);
      properties.put("spring.security.oauth2.client.registration.github.scope", MINIMAL_SCOPE);
      properties.put("runner.security.oauth2-enabled", "true");
      properties.put("runner.security.admin-github-id", adminGithubId);
      environment
          .getPropertySources()
          .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
  }
}
