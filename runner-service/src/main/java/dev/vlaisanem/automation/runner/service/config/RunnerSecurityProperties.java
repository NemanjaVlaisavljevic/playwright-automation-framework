package dev.vlaisanem.automation.runner.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code runner.security.*}, set only by {@link
 * dev.vlaisanem.automation.runner.service.security.RunnerSecurityEnvironmentPostProcessor} - never
 * configured directly in {@code application.yml}.
 *
 * @param oauth2Enabled whether GitHub OAuth2 credentials were configured at startup.
 * @param adminGithubId the raw, unparsed {@code RUNNER_SECURITY_ADMIN_GITHUB_ID} value (blank when
 *     {@code oauth2Enabled} is {@code false}) - see {@code AdminGithubAllowlist#parse(String)} for
 *     the validated {@code Set<Long>} this is turned into.
 */
@ConfigurationProperties(prefix = "runner.security")
public record RunnerSecurityProperties(
    @DefaultValue("false") boolean oauth2Enabled, @DefaultValue("") String adminGithubId) {}
