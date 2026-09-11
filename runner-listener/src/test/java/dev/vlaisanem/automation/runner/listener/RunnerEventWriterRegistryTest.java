package dev.vlaisanem.automation.runner.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code runner.rawEventMaxBytes} resolution must fail closed on an implausible value (non-numeric,
 * non-positive, or too tiny for even one event), not silently overflow on the first write.
 */
class RunnerEventWriterRegistryTest {

  @AfterEach
  void clearSystemProperties() {
    System.clearProperty(RunnerEventWriterRegistry.RAW_EVENTS_DIR_PROPERTY);
    System.clearProperty("runner.rawEventMaxBytes");
  }

  @Test
  void rejectsANonNumericRawEventMaxBytes(@TempDir Path dir) {
    System.setProperty(RunnerEventWriterRegistry.RAW_EVENTS_DIR_PROPERTY, dir.toString());
    System.setProperty("runner.rawEventMaxBytes", "not-a-number");

    assertThatThrownBy(() -> RunnerEventWriterRegistry.writerFor(UUID.randomUUID().toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a whole number");
  }

  @Test
  void rejectsAZeroRawEventMaxBytes(@TempDir Path dir) {
    System.setProperty(RunnerEventWriterRegistry.RAW_EVENTS_DIR_PROPERTY, dir.toString());
    System.setProperty("runner.rawEventMaxBytes", "0");

    assertThatThrownBy(() -> RunnerEventWriterRegistry.writerFor(UUID.randomUUID().toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be at least");
  }

  @Test
  void rejectsANegativeRawEventMaxBytes(@TempDir Path dir) {
    System.setProperty(RunnerEventWriterRegistry.RAW_EVENTS_DIR_PROPERTY, dir.toString());
    System.setProperty("runner.rawEventMaxBytes", "-1");

    assertThatThrownBy(() -> RunnerEventWriterRegistry.writerFor(UUID.randomUUID().toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be at least");
  }
}
