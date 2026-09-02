package dev.vlaisanem.automation.runner.service.process;

/**
 * Tiny standalone program launched as a real child process by {@link GradleProcessRunnerTest} -
 * prints the value of the environment variable named by {@code args[0]}, or the literal {@code
 * <unset>} if it is not present, then exits 0. Proves environment variables passed to {@link
 * GradleProcessRunner#start} actually reach the spawned process, not just that the call compiles.
 */
public final class EnvPrintingFixture {

  private EnvPrintingFixture() {}

  public static void main(String[] args) {
    String value = System.getenv(args[0]);
    System.out.println(value == null ? "<unset>" : value);
  }
}
