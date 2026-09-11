package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/runs} - domain-level names only, never a Gradle task. {@code
 * testKeys} is only meaningful for {@link Suite#CUSTOM}; {@code RunService} validates every key
 * against the server-side catalog before it can influence anything.
 *
 * <p>The {@code @Size} caps here are defense in depth, not a replacement for {@code
 * CustomTestSelectionValidator}'s own 25-key cap: they reject an oversized/malformed payload with a
 * plain {@code 400} before the service layer's catalog lookup runs.
 */
public record CreateRunRequest(
    @NotNull Environment environment,
    @NotNull Suite suite,
    @Size(max = 25) List<@Size(max = 200) String> testKeys) {}
