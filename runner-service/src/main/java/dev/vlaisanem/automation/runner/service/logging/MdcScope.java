package dev.vlaisanem.automation.runner.service.logging;

import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Runs {@code action} with {@code key}={@code value} set in the current thread's MDC, restoring
 * whatever value {@code key} held before (not a blind remove) once {@code action} completes -
 * correct even when nested or already set by an outer scope.
 */
public final class MdcScope {

  private MdcScope() {}

  public static void withMdc(String key, String value, Runnable action) {
    withMdc(
        key,
        value,
        () -> {
          action.run();
          return null;
        });
  }

  /** Same as {@link #withMdc(String, String, Runnable)}, for an {@code action} with a result. */
  public static <T> T withMdc(String key, String value, Supplier<T> action) {
    try (Handle ignored = open(key, value)) {
      return action.get();
    }
  }

  /**
   * Same restore-not-remove semantics as {@link #withMdc(String, String, Runnable)}, but as a
   * try-with-resources {@link Handle} for call sites that must interleave with checked-exception
   * code (e.g. a servlet filter's {@code doFilter}).
   */
  public static Handle open(String key, String value) {
    String previous = MDC.get(key);
    MDC.put(key, value);
    return () -> {
      if (previous == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, previous);
      }
    };
  }

  /**
   * An {@link AutoCloseable} whose {@link #close()} declares no checked exception - safe to use in
   * a try-with-resources with no surrounding {@code catch}, unlike the plain {@code AutoCloseable}
   * contract.
   */
  public interface Handle extends AutoCloseable {
    @Override
    void close();
  }
}
