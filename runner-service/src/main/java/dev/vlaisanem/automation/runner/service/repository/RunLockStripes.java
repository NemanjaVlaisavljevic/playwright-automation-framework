package dev.vlaisanem.automation.runner.service.repository;

/**
 * Fixed-size array of monitor objects, striped by {@code runId.hashCode()} - the external per-run
 * lock {@code RunEventBroker} synchronizes on to coordinate a store commit with the live {@code
 * RunEventHub} publish, and {@code FakeRunLifecycleStore} uses to mirror {@code JdbcRunStore}'s
 * {@code SELECT ... FOR UPDATE} row-lock semantics.
 *
 * <p>Bounds memory instead of growing a per-run map forever (a lock object can never be safely
 * removed once a thread might still be synchronized on it). Two unrelated runs occasionally sharing
 * a stripe is a harmless false-positive serialization, never a false-negative.
 */
public final class RunLockStripes {

  private static final int STRIPE_COUNT = 256;

  private final Object[] stripes = new Object[STRIPE_COUNT];

  public RunLockStripes() {
    for (int i = 0; i < stripes.length; i++) {
      stripes[i] = new Object();
    }
  }

  public Object lockFor(String runId) {
    int index = (runId.hashCode() & Integer.MAX_VALUE) % stripes.length;
    return stripes[index];
  }
}
