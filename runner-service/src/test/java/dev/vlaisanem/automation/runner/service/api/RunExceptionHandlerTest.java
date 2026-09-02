package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
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
   * Regression test for the review's requirement: an unmapped exception must return a generic
   * client-facing message - never {@link Throwable#getMessage()}, which can carry internal detail
   * never meant to reach a client - while the original exception is still fully logged server-side,
   * so nothing is lost for diagnosis.
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
   * Regression test for a review's finding: an absolute filesystem path (or any other internal
   * detail) in {@link ArtifactManifestCorruptException#diagnosticReason()} must reach the server
   * log for diagnosis but never the client-facing {@link ProblemDetail#getDetail()}.
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
}
