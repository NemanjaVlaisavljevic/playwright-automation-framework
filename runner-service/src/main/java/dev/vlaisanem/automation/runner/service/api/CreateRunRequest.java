package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/v1/runs} - domain-level names only, never a Gradle task. */
public record CreateRunRequest(@NotNull Environment environment, @NotNull Suite suite) {}
