package dev.vlaisanem.automation.runner.service.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunFilePathsTest {

  @Test
  void buildsDirectChildrenForProductionAndPerformanceRunIds(@TempDir Path root) {
    String uuid = "11111111-1111-4111-8111-111111111111";

    assertThat(RunFilePaths.artifactsDirectory(root, uuid))
        .isEqualTo(root.toAbsolutePath().normalize().resolve(uuid));
    assertThat(RunFilePaths.processLog(root, uuid))
        .isEqualTo(root.toAbsolutePath().normalize().resolve(uuid + ".log"));
    assertThat(RunFilePaths.artifactManifest(root, "perf-run-0001"))
        .isEqualTo(
            root.toAbsolutePath().normalize().resolve("perf-run-0001").resolve("manifest.jsonl"));
  }

  @Test
  void rejectsTraversalAbsolutePathsHeaderInjectionAndDotSegments(@TempDir Path root) {
    for (String unsafe :
        new String[] {
          "../outside",
          "..\\outside",
          "/absolute",
          "C:\\absolute",
          "11111111-1111-4111-8111-111111111111/../outside",
          "run-id\r\nX-Evil: true",
          "run.id",
          ""
        }) {
      assertThatThrownBy(() -> RunFilePaths.artifactsDirectory(root, unsafe))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("safe path segment");
    }
  }
}
