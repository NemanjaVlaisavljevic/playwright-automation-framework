package dev.vlaisanem.automation.runner.service.domain;

import java.util.Objects;

/**
 * One test as it was selected for a {@code CUSTOM} run, captured at launch time. A test can later
 * be renamed or deleted from the suite entirely, so a run's history carries its own copy of {@code
 * displayName}/{@code layer} rather than re-resolving {@code testKey} against today's catalog.
 */
public record SelectedTestSnapshot(String testKey, String displayName, TestLayer layer) {

  public SelectedTestSnapshot {
    if (testKey == null || testKey.isBlank()) {
      throw new IllegalArgumentException("testKey must not be blank");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(layer, "layer must not be null");
  }
}
