package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Run;

/**
 * One write's committed result: the {@link Run} snapshot plus the exact {@link RunnerEvent}
 * inserted for it, or {@code null} for a transition that emits none. Lets a caller (e.g. {@code
 * RunEventBroker}) publish to live subscribers using the exact committed event.
 */
public record CommittedRunChange(Run run, RunnerEvent event) {}
