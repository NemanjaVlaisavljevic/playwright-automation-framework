package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Run;

/**
 * One write's committed result: the {@link Run} snapshot together with the exact {@link
 * RunnerEvent} that was inserted for it, or a {@code null} event for a transition that deliberately
 * emits none (see {@code RunLifecycleStore#transitionIfNonTerminal}). Returning both - not just the
 * {@code Run} - is what lets a caller (see {@code RunEventBroker}) publish to live subscribers
 * using the exact committed event, without re-deriving or re-reading anything.
 */
public record CommittedRunChange(Run run, RunnerEvent event) {}
