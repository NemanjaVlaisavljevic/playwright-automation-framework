package dev.vlaisanem.automation.runner.service.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The set of GitHub numeric account IDs allowed to hold {@code ROLE_ADMIN} - compared exclusively
 * against this validated {@code Set<Long>}, never a raw string or a username. Parsed once at
 * startup from {@code RUNNER_SECURITY_ADMIN_GITHUB_ID} (a single ID today, comma-separated if more
 * than one is ever needed, without a future env-var rename).
 */
public record AdminGithubAllowlist(Set<Long> ids) {

  public AdminGithubAllowlist {
    ids = Set.copyOf(ids);
  }

  public boolean contains(long githubId) {
    return ids.contains(githubId);
  }

  /**
   * @throws IllegalArgumentException if {@code raw} is blank, or contains any entry that is blank
   *     or not a valid {@code long}.
   */
  public static AdminGithubAllowlist parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(
          "RUNNER_SECURITY_ADMIN_GITHUB_ID must not be blank when GitHub OAuth2 is enabled");
    }
    Set<Long> parsed = new LinkedHashSet<>();
    // limit -1: String.split() otherwise silently drops trailing empty strings, which would let a
    // trailing comma (e.g. "123,") through as if it were just "123" instead of a malformed entry.
    for (String entry : raw.split(",", -1)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException(
            "RUNNER_SECURITY_ADMIN_GITHUB_ID contains a blank entry: \"" + raw + "\"");
      }
      try {
        parsed.add(Long.parseLong(trimmed));
      } catch (NumberFormatException notNumeric) {
        throw new IllegalArgumentException(
            "RUNNER_SECURITY_ADMIN_GITHUB_ID contains a non-numeric entry: \"" + trimmed + "\"",
            notNumeric);
      }
    }
    return new AdminGithubAllowlist(parsed);
  }
}
