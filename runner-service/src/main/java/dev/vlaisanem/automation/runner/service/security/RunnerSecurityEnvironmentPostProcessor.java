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
 * spring.security.oauth2.client.registration.github.*} keys {@code OAuth2ClientAutoConfiguration}
 * expects, only when both are present, and enforces the two fail-closed rules that can't be
 * expressed as a bean-creation-time failure (see {@link SecurityConfig#adminGithubAllowlist} for
 * the one that can): under {@code runner.deployment-profile=PORTFOLIO}, GitHub OAuth2 must be
 * configured, and the session cookie must be {@code Secure}.
 *
 * <p>Lives in an {@code EnvironmentPostProcessor}, not an {@code ApplicationRunner}: a runner only
 * executes after the context has fully refreshed, which is after Tomcat has already started
 * listening - a misconfigured {@code PORTFOLIO} instance would briefly serve real traffic through
 * the permissive chain on every restart first. Throwing from {@link #postProcessEnvironment}
 * instead aborts {@link SpringApplication#run} before any bean, web server, or listening socket
 * exists.
 *
 * <p>Binds {@code runner.deployment-profile} via {@link Binder}, not a raw string comparison:
 * comparing against the literal {@code "PORTFOLIO"} could disagree with the real {@code @Value}
 * -based enum conversion elsewhere (case, whitespace), silently bypassing this check while still
 * resolving to {@link DeploymentProfile#PORTFOLIO} later. The same {@link Binder} mechanism
 * guarantees this check can never disagree with what the application actually runs as.
 *
 * <p>Binding {@code registration.github.client-id} to any value, including empty, makes {@code
 * OAuth2ClientAutoConfiguration} eagerly build a {@code ClientRegistration} that throws on a blank
 * id - breaking every credential-less {@code bootRun}/test/CI run. Setting the property only when
 * both values are real, and leaving it genuinely absent otherwise, avoids that.
 *
 * <p>The explicit {@code scope} of {@code read:user} changes nothing about what's actually
 * requested (GitHub's {@code CommonOAuth2Provider.GITHUB} preset already defaults to it and can't
 * be overridden down to empty) - kept only as self-documentation of that already-minimal scope.
 *
 * <p>Registered via {@code META-INF/spring.factories}, not a {@code @Component}: this must run
 * before {@code OAuth2ClientAutoConfiguration} evaluates its conditions, before the application
 * context exists. Ordered right after {@link ConfigDataEnvironmentPostProcessor} so {@code
 * application.yml}'s defaults are already loaded by the time this reads them.
 */
public class RunnerSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String PROPERTY_SOURCE_NAME = "runnerSecurityGithubOAuth2";
  private static final String REDIRECT_URI_TEMPLATE =
      "{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}";
  // Restates, rather than adds to, Spring Security's CommonOAuth2Provider.GITHUB preset default:
  // ClientRegistration.Builder.scope() treats an empty/absent scope as a no-op, so this can't be
  // overridden down to zero through the standard property path. read:user is GitHub's own
  // read-only, non-sensitive scope (public profile info only) - set explicitly for clarity, not
  // because leaving it unset would grant less access.
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
      // Absent defaults to false here (conservative), even though Tomcat's own runtime default is
      // request-scheme auto-detection - deliberate, since the Dockerfile always sets this
      // explicitly and this check exists to catch a local-only override left in place by mistake.
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

    // Only ever adds properties in the "credentials present" branch, never a "false"/"" default
    // otherwise: RunnerSecurityProperties' own @DefaultValue already supplies the disabled case,
    // and setting it explicitly here would make this bridge unconditionally win over a later,
    // legitimate override (e.g. @TestPropertySource) in Spring Boot Test's context bootstrap.
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
