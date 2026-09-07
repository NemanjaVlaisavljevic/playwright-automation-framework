package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/runs} - domain-level names only, never a Gradle task. {@code
 * testKeys} is only meaningful for {@link Suite#CUSTOM} - {@code null}/absent for every other
 * suite. Never trusted as-is: {@code RunService} validates every key against the current
 * server-side catalog (see {@code CustomTestSelectionValidator}) before it can influence anything.
 *
 * <p>{@code @Size} on {@code testKeys} (D3.3) is a defense-in-depth belt-and-braces layer, not a
 * replacement for {@code CustomTestSelectionValidator}'s own 25-key cap: Bean Validation runs here,
 * against the raw deserialized list, before the service layer (and its live-catalog lookup) ever
 * sees it - catching an oversized/malformed payload with a plain {@code 400} before any of that
 * later work is attempted. The per-key length cap guards against a single absurdly long string
 * being used to inflate the request body without tripping the list-size check.
 */
public record CreateRunRequest(
    @NotNull Environment environment,
    @NotNull Suite suite,
    @Size(max = 25) List<@Size(max = 200) String> testKeys) {}
