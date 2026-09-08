package dev.vlaisanem.automation.runner.service.disk;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * D4.2 - the single source of truth for how much disk {@code runner-service} itself is using and
 * has available, reused by {@link dev.vlaisanem.automation.runner.service.orchestration.RunService}
 * (D4.2's own submit/pre-launch guards), the D4.2 admin diagnostic endpoint, and - unchanged, later
 * - D4.3's readiness probe and Micrometer gauges.
 *
 * <p>{@link #snapshot()} takes one live read and returns a single, internally consistent {@link
 * DiskUsageSnapshot} - every caller must derive its decision from that one snapshot, never call
 * {@link DiskUsageSnapshot#belowThreshold()} and a separate live byte count independently, since
 * the two could then disagree about the same fluctuating resource.
 */
@Component
public class DiskUsageService {

  private static final Logger log = LoggerFactory.getLogger(DiskUsageService.class);
  private static final String WRITABILITY_PROBE_PREFIX = ".disk-usage-writability-probe-";

  private final RunnerProperties properties;
  private final JdbcTemplate jdbcTemplate;
  private final Path rawEventsDir;
  private final Path logsDir;
  private final Path artifactsDir;

  public DiskUsageService(RunnerProperties properties, JdbcTemplate jdbcTemplate) {
    this.properties = properties;
    this.jdbcTemplate = jdbcTemplate;
    this.rawEventsDir = Path.of(properties.rawEventsDir()).toAbsolutePath().normalize();
    this.logsDir = Path.of(properties.logsDir()).toAbsolutePath().normalize();
    this.artifactsDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
  }

  /**
   * Creates every root directory this service depends on and verifies each is writable - a
   * freshly-provisioned volume does not necessarily have these subdirectories yet (they are
   * normally created lazily by the first real run), so this must run once at startup, before the
   * first submission can ever race a missing directory. Also fails closed if any two of the three
   * configured roots are nested inside one another, since {@link #runnerDataBytes()} sums each
   * independently and would otherwise double-count.
   */
  @PostConstruct
  public void initializeStorage() {
    List<Path> roots = List.of(rawEventsDir, logsDir, artifactsDir);
    for (Path root : roots) {
      try {
        Files.createDirectories(root);
        // A unique, generated name (not one fixed name) - two instances/threads initializing
        // concurrently, or a stale leftover from a crashed previous attempt, must never collide.
        Path probe = Files.createTempFile(root, WRITABILITY_PROBE_PREFIX, "");
        Files.delete(probe);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Disk protection storage initialization failed: " + root + " is not writable", e);
      }
    }
    for (int i = 0; i < roots.size(); i++) {
      Path realA = realPathOf(roots.get(i));
      for (int j = i + 1; j < roots.size(); j++) {
        Path realB = realPathOf(roots.get(j));
        if (realA.startsWith(realB) || realB.startsWith(realA)) {
          throw new IllegalStateException(
              "Disk protection storage initialization failed: configured directories "
                  + roots.get(i)
                  + " and "
                  + roots.get(j)
                  + " are nested inside one another - runnerDataBytes() would double-count them");
        }
      }
    }
  }

  private static Path realPathOf(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Disk protection storage initialization failed: could not resolve real path of " + path,
          e);
    }
  }

  /** One consistent read of every disk-usage figure this service tracks. */
  public DiskUsageSnapshot snapshot() {
    return new DiskUsageSnapshot(
        usableFreeBytes(),
        properties.diskMinFreeBytes(),
        properties.runMaxDiskBytes(),
        Instant.now());
  }

  /**
   * The minimum {@code getUsableSpace()} across the distinct {@link FileStore}s backing {@link
   * #rawEventsDir}/{@link #logsDir}/{@link #artifactsDir} (deduplicated by equality) - all three
   * share one Docker volume today, but this must not assume that topology holds forever.
   */
  private long usableFreeBytes() {
    Set<FileStore> stores = new LinkedHashSet<>();
    for (Path dir : List.of(rawEventsDir, logsDir, artifactsDir)) {
      try {
        stores.add(Files.getFileStore(dir));
      } catch (IOException e) {
        throw new DiskUsageUnavailableException(
            "could not resolve the filesystem backing " + dir + ": " + e.getMessage(), e);
      }
    }
    long minUsable = Long.MAX_VALUE;
    for (FileStore store : stores) {
      try {
        minUsable = Math.min(minUsable, store.getUsableSpace());
      } catch (IOException e) {
        throw new DiskUsageUnavailableException(
            "could not read usable space for filesystem " + store + ": " + e.getMessage(), e);
      }
    }
    return minUsable;
  }

  /**
   * Sum of regular-file sizes under each of the three configured roots, walked independently (they
   * are validated at startup to never be nested inside one another). A file that disappears
   * mid-walk (D4.1's retention sweep can legitimately delete one concurrently) is tolerated as a
   * benign race, not a failure.
   */
  public long runnerDataBytes() {
    long total = 0;
    for (Path dir : List.of(rawEventsDir, logsDir, artifactsDir)) {
      total += directorySize(dir);
    }
    return total;
  }

  /** One database's own size - see this class's own Javadoc for what this does not measure. */
  public long databaseBytes() {
    Long size =
        jdbcTemplate.queryForObject("SELECT pg_database_size(current_database())", Long.class);
    return size == null ? 0L : size;
  }

  static long directorySize(Path dir) {
    if (!Files.exists(dir)) {
      return 0L;
    }
    AtomicLong total = new AtomicLong();
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              total.addAndGet(attrs.size());
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              if (exc instanceof NoSuchFileException) {
                // Benign race with a concurrent deleter (e.g. D4.1's retention sweep) - the file
                // is gone, so it no longer counts toward this total either way.
                return FileVisitResult.CONTINUE;
              }
              log.warn(
                  "Could not read size of {} while computing disk usage: {}",
                  file,
                  exc.getMessage());
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return total.get();
  }

  /**
   * @param usableFreeBytes live usable space at the moment this snapshot was taken.
   * @param diskMinFreeBytes the configured floor that must remain free even after a run starts.
   * @param runMaxDiskBytes the worst-case total disk a single starting run can still consume (see
   *     {@link RunnerProperties#runMaxDiskBytes()}).
   */
  public record DiskUsageSnapshot(
      long usableFreeBytes, long diskMinFreeBytes, long runMaxDiskBytes, Instant takenAt) {

    /**
     * {@code true} once usable space can no longer guarantee {@link #diskMinFreeBytes} remains free
     * even after a newly-starting run consumes up to {@link #runMaxDiskBytes} more - not just
     * whether space exists right now.
     */
    public boolean belowThreshold() {
      return usableFreeBytes < diskMinFreeBytes + runMaxDiskBytes;
    }
  }
}
