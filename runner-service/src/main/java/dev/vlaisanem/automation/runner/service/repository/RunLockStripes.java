package dev.vlaisanem.automation.runner.service.repository;

/**
 * Fixed-size array of monitor objects, striped by {@code runId.hashCode()} - the external per-run
 * lock {@code RunEventBroker} synchronizes on to coordinate a store commit with the live {@code
 * RunEventHub} publish, and {@code FakeRunLifecycleStore} synchronizes on to mirror {@code
 * JdbcRunStore}'s {@code SELECT ... FOR UPDATE} row-lock semantics.
 *
 * <p>Replaces a per-run {@code ConcurrentHashMap<String, Object>} that grew forever - one entry per
 * run ever seen, never removed, since removing an entry once a run reaches a terminal status is
 * itself unsafe (a lock object must never be replaced or removed while another thread might still
 * be synchronized on it, and there is no safe point at which nothing could be). Striping over a
 * fixed number of locks bounds memory instead: two unrelated runs occasionally share a stripe (a
 * false-positive serialization - a lock held for one run occasionally, harmlessly, blocks an
 * unrelated one too), never a false-negative, which is a correctness-preserving trade nothing here
 * relies on avoiding.
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
