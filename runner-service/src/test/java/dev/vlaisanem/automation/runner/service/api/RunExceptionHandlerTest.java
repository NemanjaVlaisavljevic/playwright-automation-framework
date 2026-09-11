package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vlaisanem.automation.runner.service.catalog.TestCatalogUnavailableException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class RunExceptionHandlerTest {

  private final RunExceptionHandler handler = new RunExceptionHandler();
  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogAppender() {
    logger = (Logger) LoggerFactory.getLogger(RunExceptionHandler.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    logger.detachAppender(logAppender);
  }

  /**
   * An unmapped exception must return a generic client-facing message, never {@link
   * Throwable#getMessage()} (which can carry internal detail), while still being fully logged
   * server-side for diagnosis.
   */
  @Test
  void unexpectedExceptionNeverLeaksItsMessageToTheClientButIsFullyLogged() {
    RuntimeException original = new RuntimeException("internal detail: connection string leaked");

    ProblemDetail response = handler.handleUnexpected(original);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(response.getDetail()).isEqualTo("An unexpected internal error occurred.");
    assertThat(response.getDetail()).doesNotContain("internal detail");

    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getThrowableProxy().getMessage())
                  .isEqualTo("internal detail: connection string leaked");
            });
  }

  /**
   * An absolute filesystem path (or other internal detail) in {@link
   * ArtifactManifestCorruptException#diagnosticReason()} must reach the server log for diagnosis
   * but never the client-facing {@link ProblemDetail#getDetail()}.
   */
  @Test
  void artifactManifestCorruptExceptionNeverLeaksTheDiagnosticReasonButLogsIt() {
    ArtifactManifestCorruptException original =
        new ArtifactManifestCorruptException(
            "run-1", "could not read C:\\secret\\path\\manifest.jsonl: access denied");

    ProblemDetail response = handler.handleArtifactManifestCorrupt(original);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(response.getDetail())
        .isEqualTo("Artifact data for run run-1 is corrupt and cannot be served.");
    assertThat(response.getDetail()).doesNotContain("C:\\secret\\path");

    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage()).contains("C:\\secret\\path");
            });
  }

  /**
   * {@code TestCatalogService} resolves the catalog file to an absolute path; that path must reach
   * the server log for diagnosis but never leak into the client-facing 503 response, which would
   * otherwise expose internal filesystem structure.
   */
  @Test
  void testCatalogUnavailableExceptionNeverLeaksTheAbsolutePathButLogsIt() {
    TestCatalogUnavailableException original =
        new TestCatalogUnavailableException(
            Path.of("C:\\secret\\container\\path\\catalog.json"), new IOException("disk error"));

    ProblemDetail response = handler.handleTestCatalogUnavailable(original);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(response.getDetail()).isEqualTo("Test catalog is unavailable.");
    assertThat(response.getDetail()).doesNotContain("C:\\secret\\container\\path");

    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage()).contains("C:\\secret\\container\\path");
            });
  }
}
