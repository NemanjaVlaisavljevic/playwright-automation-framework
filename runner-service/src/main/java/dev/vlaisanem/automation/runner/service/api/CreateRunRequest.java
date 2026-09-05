package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/runs} - domain-level names only, never a Gradle task. {@code
 * testKeys} is only meaningful for {@link Suite#CUSTOM} - {@code null}/absent for every other
 * suite. Never trusted as-is: {@code RunService} validates every key against the current
 * server-side catalog (see {@code CustomTestSelectionValidator}) before it can influence anything.
 */
public record CreateRunRequest(
    @NotNull Environment environment, @NotNull Suite suite, List<String> testKeys) {}
