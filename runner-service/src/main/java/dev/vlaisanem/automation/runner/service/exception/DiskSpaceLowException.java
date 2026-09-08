package dev.vlaisanem.automation.runner.service.exception;

import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;

/**
 * Thrown when the runner refuses a new submission, or refuses to actually launch an already-queued
 * one, because free disk space is below the configured safety threshold - starting (or continuing
 * to run) work now risks driving the shared {@code runner-data} volume to zero.
 */
public class DiskSpaceLowException extends RuntimeException {

  public DiskSpaceLowException(DiskUsageSnapshot usage) {
    super(
        "Runner is temporarily unable to accept new runs: available disk space ("
            + usage.usableFreeBytes()
            + " bytes usable) is below the configured safety threshold ("
            + (usage.diskMinFreeBytes() + usage.runMaxDiskBytes())
            + " bytes required).");
  }
}
