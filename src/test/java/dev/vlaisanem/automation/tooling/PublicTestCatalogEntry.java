package dev.vlaisanem.automation.tooling;

import java.util.Set;

/**
 * One selectable test in the {@code CUSTOM}-suite catalog. {@code testKey} is deliberately not
 * called {@code testId} - the SSE/event contract's {@code testId} is a JUnit {@code UniqueId}
 * (engine/class/method segments); this is a much simpler {@code ClassName#methodName} string, a
 * separate identity that must never be confused with it.
 */
public record PublicTestCatalogEntry(
    String testKey, String displayName, String category, Set<String> tags) {}
