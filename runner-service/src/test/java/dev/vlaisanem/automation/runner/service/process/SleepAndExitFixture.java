package dev.vlaisanem.automation.runner.service.process;

/**
 * Tiny standalone program launched as a real child process by {@link GradleProcessRunnerTest} -
 * sleeps for {@code args[0]} milliseconds then exits with code {@code args[1]}. Exists so those
 * tests exercise a genuine OS process (start, wait, forced kill) without depending on Gradle
 * itself, which would be slow and non-portable across environments.
 */
public final class SleepAndExitFixture {

  private SleepAndExitFixture() {}

  public static void main(String[] args) throws InterruptedException {
    long sleepMillis = Long.parseLong(args[0]);
    int exitCode = Integer.parseInt(args[1]);
    if (args.length == 2) {
      System.out.println("fixture stdout");
      System.err.println("fixture stderr");
    } else {
      int outputBytes = Integer.parseInt(args[2]);
      System.out.print("x".repeat(outputBytes));
    }
    Thread.sleep(sleepMillis);
    System.exit(exitCode);
  }
}
