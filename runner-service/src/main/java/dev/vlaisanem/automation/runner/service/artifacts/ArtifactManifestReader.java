package dev.vlaisanem.automation.runner.service.artifacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a run's {@code manifest.jsonl} - which the automation framework's writer may still be
 * actively appending to while the run is non-terminal - as a one-shot snapshot, not a live tail.
 * Only a byte range terminated by {@code '\n'} is ever parsed; a trailing unterminated tail is
 * tolerated silently unless {@code runTerminal} is {@code true} (no further writes are expected
 * past that point, so it can only mean the writer crashed mid-write).
 *
 * <p>A syntactically complete line that fails strict UTF-8 decoding, JSON parsing, {@link
 * ArtifactManifestEntry} validation, an {@code expectedRunId} mismatch, or a duplicate {@code
 * artifactId} is always reported as corruption regardless of {@code runTerminal} - the manifest is
 * untrusted input. Strict UTF-8 (not the JDK's default lossy decoding) matters because a line with
 * invalid bytes could otherwise still decode into syntactically valid JSON, hiding corruption.
 */
final class ArtifactManifestReader {

  private final ObjectMapper objectMapper;

  ArtifactManifestReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  List<ArtifactManifestEntry> read(
      Path manifestFile, String expectedRunId, boolean runTerminal, long manifestMaxBytes) {
    if (!Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    byte[] content = boundedRead(manifestFile, expectedRunId, manifestMaxBytes);
    return parseLines(content, manifestFile, expectedRunId, runTerminal);
  }

  /**
   * A single bounded channel session, not a separate size check followed by an unbounded read: the
   * latter has a TOCTOU gap on a file the writer can still be appending to between the two calls.
   * One {@code channel.size()} snapshot decides both whether to reject and how much to read.
   */
  private static byte[] boundedRead(
      Path manifestFile, String expectedRunId, long manifestMaxBytes) {
    try (FileChannel channel =
        FileChannel.open(manifestFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long size = channel.size();
      if (size > manifestMaxBytes) {
        throw new ArtifactManifestCorruptException(
            expectedRunId,
            manifestFile
                + " is "
                + size
                + " bytes, exceeding the configured "
                + manifestMaxBytes
                + "-byte manifest limit");
      }
      ByteBuffer buffer = ByteBuffer.allocate((int) size);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          break;
        }
      }
      buffer.flip();
      byte[] content = new byte[buffer.remaining()];
      buffer.get(content);
      return content;
    } catch (IOException e) {
      throw new ArtifactManifestCorruptException(
          expectedRunId, "could not read " + manifestFile + ": " + e.getMessage());
    }
  }

  private List<ArtifactManifestEntry> parseLines(
      byte[] content, Path manifestFile, String expectedRunId, boolean runTerminal) {
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
      // Also catches Jackson wrapping ArtifactManifestEntry's compact-constructor validation: a
      // line that's syntactically valid JSON but fails the contract's invariants is just as
      // untrustworthy as one that isn't JSON at all.
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
   * {@link CharsetDecoder} with {@link CodingErrorAction#REPORT}, not {@code new String(bytes,
   * UTF_8)}: the latter silently replaces invalid bytes with U+FFFD, which could still decode as
   * valid JSON and hide real corruption.
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
