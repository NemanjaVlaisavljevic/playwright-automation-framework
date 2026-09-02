package dev.vlaisanem.automation.runner.service.artifacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a run's {@code manifest.jsonl} - a file the main automation framework's {@code
 * ArtifactManifestWriter} may still be actively appending to while this run is non-terminal - as a
 * one-shot snapshot for an HTTP request, not a live tail.
 *
 * <p>Mirrors {@code ListenerEventIngestor}'s own philosophy for a concurrently-written JSONL
 * stream: only a byte range actually terminated by {@code '\n'} is ever attempted as JSON at all.
 * This sidesteps any ambiguity between "malformed" and "still being written" entirely, rather than
 * parsing optimistically and trying to guess which one a failure means - a trailing, not-yet-
 * newline-terminated tail is simply never parsed, full stop.
 *
 * <p>That trailing tail is tolerated silently while {@code runTerminal} is {@code false} (the
 * writer may genuinely still be mid-append) and reported as {@link
 * ArtifactManifestCorruptException} once {@code runTerminal} is {@code true} - no further writes
 * are ever expected once a run reaches a terminal status, so a permanently unterminated line at
 * that point can only mean the writer crashed mid-write and never will finish it.
 *
 * <p>A syntactically <em>complete</em> line that fails to decode as strict UTF-8, fails to parse,
 * fails {@link ArtifactManifestEntry}'s own compact-constructor validation, whose {@code runId}
 * does not match {@code expectedRunId}, or whose {@code artifactId} repeats one already seen in
 * this same manifest, is never explainable by "still being written" and is always reported as
 * corruption regardless of {@code runTerminal} - the manifest is untrusted input (hence the runId
 * cross-check at all), not a file this reader already knows to be internally self-consistent just
 * because the run isn't finished yet. Strict UTF-8 (not the JDK's default lossy
 * replacement-character decoding) matters here specifically: a line with invalid bytes could
 * otherwise still decode into syntactically valid JSON, silently hiding raw corruption behind a
 * value that merely looks a little odd rather than failing loudly.
 */
final class ArtifactManifestReader {

  private final ObjectMapper objectMapper;

  ArtifactManifestReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  List<ArtifactManifestEntry> read(Path manifestFile, String expectedRunId, boolean runTerminal) {
    if (!Files.exists(manifestFile)) {
      return List.of();
    }
    byte[] content;
    try {
      content = Files.readAllBytes(manifestFile);
    } catch (IOException e) {
      throw new ArtifactManifestCorruptException(
          expectedRunId, "could not read " + manifestFile + ": " + e.getMessage());
    }

    List<ArtifactManifestEntry> entries = new ArrayList<>();
    Set<String> seenArtifactIds = new HashSet<>();
    int start = 0;
    int lineNumber = 0;
    for (int i = 0; i < content.length; i++) {
      if (content[i] == '\n') {
        lineNumber++;
        int lineLength = i - start;
        if (lineLength > 0) {
          ArtifactManifestEntry entry =
              parseAndValidate(content, start, lineLength, lineNumber, expectedRunId, manifestFile);
          if (!seenArtifactIds.add(entry.artifactId())) {
            throw new ArtifactManifestCorruptException(
                expectedRunId,
                "duplicate artifactId "
                    + entry.artifactId()
                    + " at line "
                    + lineNumber
                    + " of "
                    + manifestFile);
          }
          entries.add(entry);
        }
        start = i + 1;
      }
    }
    if (start < content.length && runTerminal) {
      throw new ArtifactManifestCorruptException(
          expectedRunId,
          manifestFile + " ends with an unterminated trailing line after the run completed");
    }
    return List.copyOf(entries);
  }

  private ArtifactManifestEntry parseAndValidate(
      byte[] content,
      int start,
      int length,
      int lineNumber,
      String expectedRunId,
      Path manifestFile) {
    String line;
    try {
      line = decodeStrictUtf8(content, start, length);
    } catch (CharacterCodingException invalidUtf8) {
      throw new ArtifactManifestCorruptException(
          expectedRunId,
          "invalid UTF-8 byte sequence at line "
              + lineNumber
              + " of "
              + manifestFile
              + ": "
              + invalidUtf8.getMessage());
    }
    ArtifactManifestEntry entry;
    try {
      entry = objectMapper.readValue(line, ArtifactManifestEntry.class);
    } catch (IOException malformed) {
      // IOException also catches Jackson wrapping ArtifactManifestEntry's own compact-constructor
      // validation (a ValueInstantiationException) - a line that is syntactically valid JSON but
      // fails the contract's own invariants is exactly as untrustworthy as one that isn't JSON at
      // all.
      throw new ArtifactManifestCorruptException(
          expectedRunId,
          "malformed entry at line " + lineNumber + " of " + manifestFile + ": " + malformed);
    }
    if (!expectedRunId.equals(entry.runId())) {
      throw new ArtifactManifestCorruptException(
          expectedRunId,
          "entry at line "
              + lineNumber
              + " of "
              + manifestFile
              + " has runId "
              + entry.runId()
              + ", expected "
              + expectedRunId);
    }
    return entry;
  }

  /**
   * Deliberately {@link CharsetDecoder} with {@link CodingErrorAction#REPORT}, not {@code new
   * String(bytes, UTF_8)} - the latter silently replaces an invalid byte sequence with U+FFFD,
   * which could then still decode into syntactically valid (if slightly odd-looking) JSON, hiding
   * genuine raw corruption instead of surfacing it. Mirrors {@code
   * ListenerEventIngestor#decodeStrictUtf8} exactly, for the same reason.
   */
  private static String decodeStrictUtf8(byte[] bytes, int offset, int length)
      throws CharacterCodingException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
  }
}
