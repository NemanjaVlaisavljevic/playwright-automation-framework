package dev.vlaisanem.automation.runner.service.logging;

import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * D4.3.3 - runs {@code action} with {@code key} set to {@code value} in the current thread's MDC,
 * restoring whatever value {@code key} held before (not a blind {@code remove}) once {@code action}
 * completes, whether normally or by throwing. A blind remove would be wrong the moment this is ever
 * nested, or ever used on a thread some other scope already set the same key on; restoring the
 * exact previous value (including "absent" when there was none) is correct in every case, not just
 * the common one.
 *
 * <p>Used at every per-run background thread boundary this service has - {@code RunService}'s own
 * single-worker executor task, {@code GradleProcessRunner}'s output-drainer thread, {@code
 * ListenerEventIngestor}'s own per-run executor, and the HTTP-thread cancel path - so {@code runId}
 * is a real, structured MDC field (and so appears in the real ECS JSON output) on every log line
 * any of them ever produce, not just the ones on whichever single thread happened to be wrapped
 * first.
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
   * Opens {@code key} = {@code value} in the current thread's MDC and returns a {@link Handle}
   * whose {@link Handle#close()} restores whatever value {@code key} held before (the same
   * restore-not-remove semantics as {@link #withMdc(String, String, Runnable)}) - a try-with-
   * resources-friendly equivalent for a call site that must interleave with checked-exception-
   * throwing code a plain {@code Runnable}/{@code Supplier} cannot express, e.g. a servlet filter's
   * own {@code doFilter}, which declares {@code ServletException}/{@code IOException}.
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
