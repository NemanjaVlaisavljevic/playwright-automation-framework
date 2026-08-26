package dev.vlaisanem.automation.core;

import dev.vlaisanem.automation.config.TestConfig;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

final class RuntimeRegistry implements AutoCloseable {
  private final Queue<BrowserEngine> engines = new ConcurrentLinkedQueue<>();
  private final ThreadLocal<BrowserEngine> workerEngine = new ThreadLocal<>();

  BrowserEngine engine(TestConfig config) {
    BrowserEngine current = workerEngine.get();
    if (current == null) {
      current = new BrowserEngine(config);
      engines.add(current);
      workerEngine.set(current);
    }
    return current;
  }

  @Override
  public void close() {
    workerEngine.remove();
    RuntimeException firstFailure = null;
    for (BrowserEngine engine : engines) {
      try {
        engine.close();
      } catch (RuntimeException exception) {
        if (firstFailure == null) {
          firstFailure = exception;
        }
      }
    }
    engines.clear();
    if (firstFailure != null) {
      throw firstFailure;
    }
  }
}
