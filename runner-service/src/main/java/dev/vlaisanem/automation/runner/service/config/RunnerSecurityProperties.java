package dev.vlaisanem.automation.runner.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code runner.security.*}, which only {@link
 * dev.vlaisanem.automation.runner.service.security.RunnerSecurityEnvironmentPostProcessor} ever
 * sets - never configured directly in {@code application.yml}. {@code oauth2Enabled} reflects
 * whether both {@code RUNNER_SECURITY_GITHUB_CLIENT_ID}/{@code _SECRET} were present at startup;
 * {@code adminGithubId} carries the raw, possibly comma-separated value of {@code
 * RUNNER_SECURITY_ADMIN_GITHUB_ID} unparsed - see {@code
 * dev.vlaisanem.automation.runner.service.security.AdminGithubAllowlist#parse(String)} for the
 * validated {@code Set<Long>} this is turned into.
 *
 * @param oauth2Enabled whether GitHub OAuth2 credentials were configured at startup.
 * @param adminGithubId the raw, unparsed {@code RUNNER_SECURITY_ADMIN_GITHUB_ID} value (blank when
 *     {@code oauth2Enabled} is {@code false}).
 */
@ConfigurationProperties(prefix = "runner.security")
public record RunnerSecurityProperties(
    @DefaultValue("false") boolean oauth2Enabled, @DefaultValue("") String adminGithubId) {}
