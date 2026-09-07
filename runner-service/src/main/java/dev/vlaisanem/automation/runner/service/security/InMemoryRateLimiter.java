package dev.vlaisanem.automation.runner.service.security;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, dependency-free fixed-window rate limiter - no bucket4j/resilience4j, matching this
 * codebase's existing style of plain in-memory capacity ceilings ({@code
 * RunnerProperties#queueCapacity}/{@code #sseMaxSubscribers}). Single-instance, in-memory, resets
 * on restart - deliberate, the same rationale as the session store's own D3.1 decision (no Redis
 * for a single-instance portfolio deployment).
 *
 * <p>Each {@code (namespace, key)} pair (a typed {@link WindowKey}, never a hand-concatenated
 * string with a separator character - a review finding: an earlier version used a literal character
 * between the two parts that a file-write mishap turned into an actual embedded NUL byte, making
 * the whole source file look like binary content to `git`/`grep`/review tooling) gets its own
 * {@link Window}: a start timestamp, the rule's own configured window duration (kept alongside it
 * specifically so a background sweep can tell a window is safe to evict without needing the
 * original {@link RateLimitRule} again), and a count - reset in place whenever the duration has
 * elapsed since that window started, so a key that keeps coming back never grows the map.
 *
 * <p><strong>Bounded memory, not just a documented trade-off (a review finding)</strong>: a real
 * attacker (internet scanners, botnet clients, IPv6 address rotation) can present many genuinely
 * distinct source IPs, each opening one permanent entry - the reverse-proxy trust boundary prevents
 * *spoofing* a single request's origin, it does not prevent a real flood of distinct real origins.
 * Two independent safeguards, both required: (1) an opportunistic sweep, every {@value
 * #SWEEP_EVERY_N_CALLS} calls, removes every window whose own configured duration has already
 * elapsed since it last started; (2) a hard ceiling ({@value #DEFAULT_MAX_TRACKED_KEYS} distinct
 * keys by default) - if inserting a genuinely new key would exceed it, an immediate synchronous
 * sweep runs first, and if the map is still at capacity afterward (meaning that many keys are all
 * genuinely active right now), the single oldest window is evicted to make room. This is a safety
 * valve against unbounded growth, not a strict fairness guarantee under sustained flooding.
 *
 * <p><strong>Multi-rule checks are atomic (a review finding)</strong>: {@link #tryAcquire(String,
 * List)} evaluates every rule for one logical attempt (e.g. create-run's per-minute *and* per-hour
 * caps) before mutating any of their counters - a request that would be rejected by the second rule
 * never silently consumes the first rule's budget. On rejection, the returned {@code retryAfter} is
 * the *largest* remaining time among every rule that would have rejected (never the shortest), so a
 * caller blocked mainly by a hard hourly cap is never told to retry in a few seconds just because a
 * shorter-window rule also happened to be exhausted. Concurrent multi-rule callers lock every
 * involved {@link Window} in one fixed, global order (by identity hash) before evaluating any of
 * them, so two callers can never deadlock waiting on each other's windows in opposite order.
 */
public class InMemoryRateLimiter {

  private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;
  private static final int SWEEP_EVERY_N_CALLS = 256;

  private final int maxTrackedKeys;
  private final ConcurrentHashMap<WindowKey, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong callCount = new AtomicLong();

  public InMemoryRateLimiter() {
    this(DEFAULT_MAX_TRACKED_KEYS);
  }

  /** Package-private: lets tests exercise the bounded-growth safety valve with a small cap. */
  InMemoryRateLimiter(int maxTrackedKeys) {
    if (maxTrackedKeys < 1) {
      throw new IllegalArgumentException("maxTrackedKeys must be at least 1");
    }
    this.maxTrackedKeys = maxTrackedKeys;
  }

  /** Convenience for the common single-rule case - see {@link #tryAcquire(String, List)}. */
  public Result tryAcquire(String namespace, String key, RateLimitRule rule) {
    return tryAcquire(key, List.of(new NamedRule(namespace, rule)));
  }

  /**
   * Evaluates every rule for the same logical attempt as one atomic operation - either all of them
   * pass (and all of their counters are incremented) or none of them are mutated at all.
   *
   * @param key the caller identity this limit is counted per (client IP or GitHub numeric id).
   * @param rules every rule that must independently pass for this attempt, each under its own
   *     namespace (so the same key is tracked separately per rule).
   * @return {@link Result#allow()} if every rule allowed this attempt (now counted against all of
   *     them); otherwise {@link Result#reject(Duration)} carrying the *largest* remaining time
   *     among every rule that rejected - none of the rules were mutated in this case.
   */
  public Result tryAcquire(String key, List<NamedRule> rules) {
    Instant now = Instant.now();
    maybeSweepExpired(now);

    List<Window> windowsInOrder = new ArrayList<>(rules.size());
    for (NamedRule namedRule : rules) {
      windowsInOrder.add(
          windowFor(new WindowKey(namedRule.namespace(), key), namedRule.rule(), now));
    }

    List<Window> lockOrder = new ArrayList<>(windowsInOrder);
    lockOrder.sort(Comparator.comparingInt(System::identityHashCode));
    return acquireLocksThenEvaluate(lockOrder, 0, windowsInOrder, rules, now);
  }

  private Window windowFor(WindowKey windowKey, RateLimitRule rule, Instant now) {
    Window existing = windows.get(windowKey);
    if (existing != null) {
      return existing;
    }
    if (windows.size() >= maxTrackedKeys) {
      evictExpiredOrOldest(now);
    }
    return windows.computeIfAbsent(windowKey, ignored -> new Window(now, rule.window()));
  }

  private void maybeSweepExpired(Instant now) {
    if (callCount.incrementAndGet() % SWEEP_EVERY_N_CALLS == 0) {
      windows.entrySet().removeIf(entry -> entry.getValue().hasExpired(now));
    }
  }

  private void evictExpiredOrOldest(Instant now) {
    boolean removedAny = windows.entrySet().removeIf(entry -> entry.getValue().hasExpired(now));
    if (removedAny) {
      return;
    }
    windows.entrySet().stream()
        .min(Comparator.comparing(entry -> entry.getValue().start))
        .ifPresent(oldest -> windows.remove(oldest.getKey(), oldest.getValue()));
  }

  /**
   * Recursively synchronizes on every window in {@code lockOrder} (a fixed, globally-consistent
   * order every caller uses, preventing deadlock) before evaluating - by the time {@code
   * evaluateAndCommit} runs, every window this attempt touches is held.
   */
  private Result acquireLocksThenEvaluate(
      List<Window> lockOrder,
      int index,
      List<Window> windowsInOrder,
      List<NamedRule> rules,
      Instant now) {
    if (index == lockOrder.size()) {
      return evaluateAndCommit(windowsInOrder, rules, now);
    }
    synchronized (lockOrder.get(index)) {
      return acquireLocksThenEvaluate(lockOrder, index + 1, windowsInOrder, rules, now);
    }
  }

  private Result evaluateAndCommit(
      List<Window> windowsInOrder, List<NamedRule> rules, Instant now) {
    Duration maxRetryAfter = null;
    for (int i = 0; i < windowsInOrder.size(); i++) {
      Window window = windowsInOrder.get(i);
      RateLimitRule rule = rules.get(i).rule();
      Duration elapsed = Duration.between(window.start, now);
      if (elapsed.compareTo(rule.window()) >= 0) {
        window.start = now;
        window.windowDuration = rule.window();
        window.count.set(0);
        elapsed = Duration.ZERO;
      }
      if (window.count.get() >= rule.maxAttempts()) {
        Duration remaining = rule.window().minus(elapsed);
        if (remaining.isNegative()) {
          remaining = Duration.ZERO;
        }
        if (maxRetryAfter == null || remaining.compareTo(maxRetryAfter) > 0) {
          maxRetryAfter = remaining;
        }
      }
    }
    if (maxRetryAfter != null) {
      return Result.reject(maxRetryAfter);
    }
    for (Window window : windowsInOrder) {
      window.count.incrementAndGet();
    }
    return Result.allow();
  }

  /** Test-only visibility into current memory usage, for the bounded-growth regression test. */
  int trackedKeyCount() {
    return windows.size();
  }

  private record WindowKey(String namespace, String key) {}

  /** One rule under its own namespace - see {@link #tryAcquire(String, List)}. */
  public record NamedRule(String namespace, RateLimitRule rule) {}

  private static final class Window {
    private volatile Instant start;
    private volatile Duration windowDuration;
    private final AtomicInteger count = new AtomicInteger(0);

    private Window(Instant start, Duration windowDuration) {
      this.start = start;
      this.windowDuration = windowDuration;
    }

    /** Safe to evict once its own configured window has elapsed since it last started. */
    private boolean hasExpired(Instant now) {
      return Duration.between(start, now).compareTo(windowDuration) >= 0;
    }
  }

  /** Never {@code null} - either allowed, or rejected with a real remaining-time estimate. */
  public record Result(boolean allowed, Duration retryAfter) {

    private static final Result ALLOW = new Result(true, Duration.ZERO);

    public static Result allow() {
      return ALLOW;
    }

    public static Result reject(Duration retryAfter) {
      return new Result(false, retryAfter);
    }
  }
}
