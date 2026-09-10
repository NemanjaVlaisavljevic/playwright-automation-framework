package dev.vlaisanem.automation.runner.service.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D4.5 - the automated proof the original spec required: "a backup with no proven restore is not a
 * finished backup." Builds the real {@code deploy/backup} Docker image and drives its real {@code
 * backup.sh}/{@code restore.sh} scripts end to end - real {@code pg_dump}, real {@code age}
 * encryption/decryption (a genuine, disposable {@code age-keygen}-generated recipient/identity
 * keypair, never the production one), real {@code rclone} upload/download, and a real {@code
 * pg_restore} into a second, empty Postgres - against real Testcontainers {@code
 * postgres:17-alpine} instances, not mocked at any layer. A first review round found the original
 * happy-path-only version of this class could not have caught several real safety gaps (an
 * unvalidated restore identifier, a destructive restore with no target-emptiness check, an
 * unretried "retry candidate") - the negative tests below exist specifically to prove those gaps
 * are now closed, not just that the golden path works.
 *
 * <p>The one real difference from production: {@code BACKUP_REMOTE} points at an rclone {@code type
 * = local} remote (a plain host directory standing in for the S3-compatible bucket), not a real
 * Backblaze B2 account - this test has no real cloud credentials to exercise, and none should ever
 * be committed to this repo. Every other step (the encrypt-pipe-into-age, the atomic rename, the
 * {@code rclone check} verification, the explicit-identifier restore, the destructive-restore
 * guard) runs for real. The real S3/B2 round trip, against real production data, is the D5
 * acceptance item this test does not replace (see docs/RELEASE_EVIDENCE.md's D4.5 section).
 *
 * <p>{@code backup.sh}/{@code restore.sh} run as real, separate, one-shot {@code docker run}
 * invocations (via {@link ProcessBuilder}, mirroring the same pattern root {@code build.gradle}'s
 * own {@code localPostgresRun}/{@code rbpRun} helpers and {@code DashboardProcess} already use in
 * this repo) sharing each Postgres container's own network namespace ({@code --network
 * container:<id>}) - the identical mechanism a production host uses to reach {@code postgres} by
 * Compose DNS, just addressed as {@code 127.0.0.1:5432} instead. The image is built once for the
 * whole class ({@link #buildBackupImage()}), not per test - its content never changes between
 * tests.
 */
@Testcontainers
class BackupRestoreDrillTest {

  private static final Pattern PUBLIC_KEY_LINE = Pattern.compile("(?m)^# public key: (age1\\S+)$");
  private static final String IMAGE_TAG = "runner-backup-drill-test:latest";

  @BeforeAll
  static void buildBackupImage() throws Exception {
    Path repoRoot = Path.of(System.getProperty("databaseIntegrationTest.repoRoot"));
    dockerOrThrow(List.of("build", "-t", IMAGE_TAG, repoRoot.resolve("deploy/backup").toString()));
  }

  @Test
  @Timeout(180)
  void aRealDumpEncryptUploadDownloadDecryptRestoreRoundTripPreservesRealData(@TempDir Path tempDir)
      throws Exception {
    try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine");
        PostgreSQLContainer<?> target = new PostgreSQLContainer<>("postgres:17-alpine")) {
      source.start();
      target.start();

      String runId = "backup-drill-" + UUID.randomUUID();
      int sourceMigrationCount = migrateAndSeedFully(source, runId);

      TestKeypair keypair = generateTestKeypair(tempDir);
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);

      dockerOrThrow(backupRunArgs(source, keypair.recipient(), bucket, "/backups", null));

      String backupIdentifier = onlyUploadedObjectName(bucket);
      assertThat(backupIdentifier).matches("runner-backup-\\d{8}T\\d{6}Z-[0-9a-f]{8}\\.dump\\.age");

      dockerOrThrow(
          restoreRunArgs(
              target, keypair.identityFile(), bucket, "/backups", backupIdentifier, null));

      assertRestoredContentMatches(source, target, runId, sourceMigrationCount);
    }
  }

  /**
   * A review finding: an earlier version of {@code backup.sh}/{@code restore.sh} built a single
   * {@code postgresql://user:password@host:port/dbname} connection string and parsed it with a
   * hand-rolled regex - a raw {@code @}, {@code :}, {@code /}, or {@code %} in the password broke
   * that parser outright (e.g. {@code p@ssword} was misparsed as password {@code p} and host {@code
   * ssword@postgres}, and percent-encoded forms were never decoded at all), which could have
   * silently disabled the very first production backup the moment a normally-generated strong
   * password was used. {@code PGHOST}/{@code PGPORT}/{@code PGDATABASE}/{@code PGUSER}/{@code
   * PGPASSWORD} are now passed as separate, literal environment variables with no URL syntax to
   * disambiguate - this proves a password containing every one of those characters at once
   * round-trips through a real dump, encrypt, upload, download, decrypt, and restore unchanged.
   */
  @Test
  @Timeout(180)
  void aPasswordContainingUrlSpecialCharactersRoundTripsCorrectly(@TempDir Path tempDir)
      throws Exception {
    String specialPassword = "p@ss:word/with%special";
    try (PostgreSQLContainer<?> source =
            new PostgreSQLContainer<>("postgres:17-alpine").withPassword(specialPassword);
        PostgreSQLContainer<?> target =
            new PostgreSQLContainer<>("postgres:17-alpine").withPassword(specialPassword)) {
      source.start();
      target.start();

      String runId = "special-char-password-" + UUID.randomUUID();
      migrateAndSeedFully(source, runId);

      TestKeypair keypair = generateTestKeypair(tempDir);
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);

      dockerOrThrow(backupRunArgs(source, keypair.recipient(), bucket, "/backups", null));
      String backupIdentifier = onlyUploadedObjectName(bucket);

      dockerOrThrow(
          restoreRunArgs(
              target, keypair.identityFile(), bucket, "/backups", backupIdentifier, null));

      assertThat(hasRunsTable(target)).isTrue();
      assertThat(countRuns(target, runId)).isEqualTo(1);
    }
  }

  /**
   * A review finding: {@code identifier} becomes part of both a local filesystem path and an rclone
   * remote path inside restore.sh - an unvalidated argument there is an uncontrolled path (path
   * traversal, absolute-path escape), not just a display string. This never needs a real uploaded
   * backup, a source database, or even valid credentials for anything else - restore.sh must refuse
   * before touching any of that.
   */
  @Test
  @Timeout(60)
  void refusesAnInvalidBackupIdentifier() throws Exception {
    DockerResult result =
        docker(List.of("run", "--rm", IMAGE_TAG, "./restore.sh", "../../etc/passwd"));

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.output()).contains("invalid backup identifier");
  }

  /**
   * A review finding: the original restore.sh went straight to a destructive {@code --clean}
   * restore with no check that the target was actually empty/disposable. This check runs (and must
   * refuse) before restore.sh ever touches rclone/age - a syntactically-valid but nonexistent
   * identifier is enough to prove it, no real uploaded backup needed. A second review finding
   * pushed this further: {@code RESTORE_CONFIRM_DESTRUCTIVE} must be tied to the real database name
   * and the real backup identifier, not a generic "yes" that could be scripted once and silently
   * reused across a completely different incident - this test proves both a missing ack AND a
   * present-but-wrong one (the previous target's own database name/identifier pair, not this one's)
   * are equally refused, not just that *some* non-empty value is required.
   */
  @Test
  @Timeout(60)
  void refusesToRestoreDestructivelyIntoANonEmptyTargetWithoutExplicitAcknowledgment(
      @TempDir Path tempDir) throws Exception {
    try (PostgreSQLContainer<?> target = new PostgreSQLContainer<>("postgres:17-alpine")) {
      target.start();
      String runId = "already-here-" + UUID.randomUUID();
      migrateAndSeedFully(target, runId);

      // Content is irrelevant - the destructive-target check must refuse before restore.sh ever
      // reads this file's content (i.e. before age/rclone are touched at all). Only needs to exist
      // as a real host file so the bind mount itself succeeds.
      Path unusedIdentityFile = tempDir.resolve("unused-identity.txt");
      Files.writeString(
          unusedIdentityFile, "AGE-SECRET-KEY-1UNUSEDPLACEHOLDERPLACEHOLDERPLACEHOLDERPLACEHOLD\n");
      String identifier = "runner-backup-20260101T000000Z-deadbeef.dump.age";

      DockerResult noAck =
          docker(restoreRunArgs(target, unusedIdentityFile, null, "/backups", identifier, null));
      assertThat(noAck.exitCode()).isEqualTo(3);
      assertThat(noAck.output()).contains("refusing to run a destructive restore");

      DockerResult wrongAck =
          docker(
              restoreRunArgs(
                  target,
                  unusedIdentityFile,
                  null,
                  "/backups",
                  identifier,
                  "some-other-database:some-other-identifier.dump.age"));
      assertThat(wrongAck.exitCode()).isEqualTo(3);
      assertThat(wrongAck.output()).contains("refusing to run a destructive restore");

      // Neither refusal ran anything else - the pre-existing row must still be untouched.
      assertThat(countRuns(target, runId)).isEqualTo(1);
    }
  }

  /**
   * A review finding: {@code target_db} was only ever parsed out of {@code TARGET_DATABASE_URL}'s
   * own string - a mistyped or misdirected connection string parses just as "successfully" as a
   * correct one, so a wrong-database restore with no `runs` table at all (a fresh, never-migrated
   * database - indistinguishable from the normal disaster-recovery case by the destructive-target
   * check alone) would have been silently accepted. {@code RESTORE_EXPECTED_DATABASE} is now
   * cross-checked against Postgres's own {@code current_database()}; this test deliberately
   * supplies a wrong expectation and confirms restore.sh refuses before touching
   * rclone/age/pg_restore at all.
   */
  @Test
  @Timeout(60)
  void refusesWhenTargetDatabaseNameDoesNotMatchExpectation(@TempDir Path tempDir)
      throws Exception {
    try (PostgreSQLContainer<?> target = new PostgreSQLContainer<>("postgres:17-alpine")) {
      target.start();

      Path unusedIdentityFile = tempDir.resolve("unused-identity.txt");
      Files.writeString(
          unusedIdentityFile, "AGE-SECRET-KEY-1UNUSEDPLACEHOLDERPLACEHOLDERPLACEHOLDERPLACEHOLD\n");

      DockerResult result =
          docker(
              restoreRunArgs(
                  target,
                  unusedIdentityFile,
                  null,
                  "/backups",
                  "runner-backup-20260101T000000Z-deadbeef.dump.age",
                  "definitely-not-the-real-database-name",
                  null));

      assertThat(result.exitCode()).isEqualTo(4);
      assertThat(result.output()).contains("but RESTORE_EXPECTED_DATABASE=");
      assertThat(hasRunsTable(target)).isFalse();
    }
  }

  /**
   * A review finding pushed this scenario into scope: decrypting with the wrong identity must fail
   * loudly (age itself refuses, the pipeline's exit code propagates via {@code pipefail}), never
   * silently hand pg_restore garbage that happens to look like success.
   */
  @Test
  @Timeout(180)
  void decryptionFailsWithTheWrongIdentityKey(@TempDir Path tempDir) throws Exception {
    try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine");
        PostgreSQLContainer<?> target = new PostgreSQLContainer<>("postgres:17-alpine")) {
      source.start();
      target.start();
      migrateOnly(source);

      TestKeypair realKeypair = generateTestKeypair(tempDir.resolve("real"));
      TestKeypair wrongKeypair = generateTestKeypair(tempDir.resolve("wrong"));
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);

      dockerOrThrow(backupRunArgs(source, realKeypair.recipient(), bucket, "/backups", null));
      String backupIdentifier = onlyUploadedObjectName(bucket);

      DockerResult result =
          docker(
              restoreRunArgs(
                  target, wrongKeypair.identityFile(), bucket, "/backups", backupIdentifier, null));

      assertThat(result.exitCode()).isNotZero();
      // The target never had anything restored into it - still no runs table at all.
      assertThat(hasRunsTable(target)).isFalse();
    }
  }

  /**
   * A review finding: an earlier version of backup.sh called a leftover local archive a "retry
   * candidate" in a comment but never actually retried it, and deleted it purely by age - directly
   * contradicting its own "delete only after verified" guarantee. This proves the real invariant:
   * when the upload genuinely fails (here, a read-only destination bucket directory), the local
   * encrypted archive survives - a real file, still on disk, still a valid encrypted backup,
   * exactly where the next run's retry pass would find and retry it.
   */
  @Test
  @Timeout(180)
  void backupKeepsTheLocalCopyWhenUploadVerificationFails(@TempDir Path tempDir) throws Exception {
    try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine")) {
      source.start();
      migrateOnly(source);

      TestKeypair keypair = generateTestKeypair(tempDir);
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);
      Path localBackupsDir = tempDir.resolve("local-backups");
      Files.createDirectories(localBackupsDir);

      DockerResult result =
          docker(
              backupRunArgs(
                  source, keypair.recipient(), bucket, "/backups", null, localBackupsDir, true));

      assertThat(result.exitCode()).isNotZero();
      assertThat(survivingLocalArchives(localBackupsDir)).hasSize(1);
    }
  }

  /**
   * A review finding: the previous test proved a failed upload leaves the local archive on disk,
   * but never proved anything actually retries it - "survives" and "gets retried" are different
   * claims. This runs backup.sh twice against the SAME local directory: once with a read-only
   * (failing) bucket mount, once with the bucket made writable again - and confirms the survivor
   * from run 1 is genuinely uploaded on run 2, alongside run 2's own fresh dump (two real objects
   * in the bucket afterward, zero files left locally).
   */
  @Test
  @Timeout(180)
  void backupActuallyRetriesAndUploadsASurvivedLocalCopyOnTheNextRun(@TempDir Path tempDir)
      throws Exception {
    try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine")) {
      source.start();
      migrateOnly(source);

      TestKeypair keypair = generateTestKeypair(tempDir);
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);
      Path localBackupsDir = tempDir.resolve("local-backups");
      Files.createDirectories(localBackupsDir);

      DockerResult firstRun =
          docker(
              backupRunArgs(
                  source, keypair.recipient(), bucket, "/backups", null, localBackupsDir, true));
      assertThat(firstRun.exitCode()).isNotZero();
      List<Path> survivorsAfterFirstRun = survivingLocalArchives(localBackupsDir);
      assertThat(survivorsAfterFirstRun).hasSize(1);
      String survivorName = survivorsAfterFirstRun.get(0).getFileName().toString();

      DockerResult secondRun =
          docker(
              backupRunArgs(
                  source, keypair.recipient(), bucket, "/backups", null, localBackupsDir));
      assertThat(secondRun.exitCode()).isZero();
      assertThat(secondRun.output())
          .as("the second run's own log should show it retrying the exact survivor from run 1")
          .contains("retrying a previously-unverified local backup: " + survivorName);

      List<Path> uploadedObjects;
      try (Stream<Path> files = Files.list(bucket.bucketDir().resolve("runner-backups"))) {
        uploadedObjects = files.toList();
      }
      assertThat(uploadedObjects)
          .as("both the retried run-1 archive and run 2's own fresh dump should be off-site")
          .hasSize(2);
      assertThat(survivingLocalArchives(localBackupsDir))
          .as("nothing should remain locally once both are confirmed uploaded")
          .isEmpty();
    }
  }

  /**
   * A review finding: {@code flock}/the random filename suffix closed a real collision path, but
   * nothing proved the lock itself actually excludes a genuinely concurrent invocation. A
   * background container holds the exact same lock file backup.sh itself uses (real {@code flock -n
   * 9} against {@code /backups/.backup.lock}, the identical mechanism, not a simulation) for a
   * fixed window; backup.sh, invoked against the same shared local directory while that window is
   * still open, must fail immediately with the lock-held message rather than blocking, racing, or
   * corrupting anything.
   */
  @Test
  @Timeout(120)
  void aConcurrentBackupRunFailsFastInsteadOfWaitingOrRacing(@TempDir Path tempDir)
      throws Exception {
    try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine")) {
      source.start();
      migrateOnly(source);

      TestKeypair keypair = generateTestKeypair(tempDir);
      RemoteBucket bucket = prepareLocalRcloneRemote(tempDir);
      Path localBackupsDir = tempDir.resolve("local-backups");
      Files.createDirectories(localBackupsDir);

      // A fixed, generous hold window (the same idiom this repo's own CancelDuringStepFixtureTest
      // uses elsewhere for a deterministic concurrency race) - deliberately waited on via the
      // holder's own "LOCK_ACQUIRED" announcement below, not merely the lock *file*'s existence:
      // the
      // shell's own `exec 9>...` redirection creates that file immediately, before `flock` itself
      // has
      // necessarily run, so file-existence alone would be a real race, not proof the lock is held.
      Process holder =
          new ProcessBuilder(
                  "docker",
                  "run",
                  "--rm",
                  "-v",
                  localBackupsDir.toAbsolutePath() + ":/backups",
                  IMAGE_TAG,
                  "sh",
                  "-c",
                  "exec 9>/backups/.backup.lock; flock -n 9; echo LOCK_ACQUIRED; sleep 20")
              .redirectErrorStream(true)
              .start();
      try {
        waitForLine(holder, "LOCK_ACQUIRED", Duration.ofSeconds(60));

        DockerResult second =
            docker(
                backupRunArgs(
                    source, keypair.recipient(), bucket, "/backups", null, localBackupsDir));

        assertThat(second.exitCode()).isNotZero();
        assertThat(second.output()).contains("another backup run already holds the lock");
      } finally {
        holder.destroyForcibly();
        holder.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  private static List<Path> survivingLocalArchives(Path localBackupsDir) throws IOException {
    try (Stream<Path> files = Files.list(localBackupsDir)) {
      return files.filter(p -> p.toString().endsWith(".dump.age")).toList();
    }
  }

  /**
   * Blocks until {@code process}'s own stdout (merged with stderr, per {@code
   * redirectErrorStream(true)} at every call site) prints a line equal to {@code marker}, or throws
   * once {@code timeout} elapses. Reading line-by-line while the process is still running (never
   * {@code readAllBytes()}, which blocks until the process exits) is what makes this usable as a
   * genuine "has the holder actually reached this point yet" signal, not just an existence check on
   * a side effect that can happen before the thing it is supposed to indicate.
   */
  private static void waitForLine(Process process, String marker, Duration timeout)
      throws IOException, InterruptedException {
    process.getOutputStream().close();
    long deadline = System.nanoTime() + timeout.toNanos();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      while (System.nanoTime() < deadline) {
        if (reader.ready()) {
          String line = reader.readLine();
          if (line == null) {
            break;
          }
          if (marker.equals(line.trim())) {
            return;
          }
        } else {
          Thread.sleep(50);
        }
      }
    }
    throw new IllegalStateException(
        "'"
            + marker
            + "' was not printed within "
            + timeout
            + " - process alive: "
            + process.isAlive());
  }

  // --- shared infrastructure -----------------------------------------------------------------

  private record TestKeypair(String recipient, Path identityFile) {}

  private record RemoteBucket(Path rcloneConfig, Path bucketDir) {}

  private record DockerResult(int exitCode, String output) {}

  private static TestKeypair generateTestKeypair(Path dir) throws Exception {
    Files.createDirectories(dir);
    String keygenOutput = dockerOrThrow(List.of("run", "--rm", IMAGE_TAG, "age-keygen"));
    Matcher publicKeyMatcher = PUBLIC_KEY_LINE.matcher(keygenOutput);
    assertThat(publicKeyMatcher.find())
        .as("age-keygen output should contain a '# public key: age1...' line:\n" + keygenOutput)
        .isTrue();
    String recipient = publicKeyMatcher.group(1);
    String identity =
        keygenOutput
            .lines()
            .filter(line -> line.startsWith("AGE-SECRET-KEY-1"))
            .findFirst()
            .orElseThrow();
    Path identityFile = dir.resolve("identity.txt");
    Files.writeString(identityFile, identity + System.lineSeparator());
    return new TestKeypair(recipient, identityFile);
  }

  /**
   * rclone's own {@code local} backend, standing in for the real S3-compatible bucket.
   *
   * <p>A CI finding: the {@code backup}/{@code restore} image runs as root (the base {@code
   * postgres:17-alpine} image sets no {@code USER}, confirmed via {@code docker inspect}), so on a
   * real Linux Docker host - unlike this project's own Windows/Docker-Desktop dev machine, where a
   * bind-mounted directory's ownership is transparently translated to the host user - a file {@code
   * rclone copyto} writes into a bind-mounted host directory is genuinely owned by {@code root} on
   * the host side. If the {@code runner-backups} prefix subdirectory itself does not already exist
   * before the first container write, the container (running as root) creates it too, so the
   * directory ends up root-owned with default permissions that the CI runner's own non-root user
   * cannot write to - and deleting a directory entry requires write permission on its *parent*, not
   * ownership of the entry itself, so JUnit's own {@code @TempDir} cleanup then fails outright
   * ({@code Failed to delete temp directory ... Permission denied}), even though the files inside
   * are otherwise perfectly readable. Pre-creating {@code runner-backups} here, before any
   * container ever runs, makes the CI runner's own user its owner instead - later root-owned files
   * written *inside* it are still deletable, since Unix directory-entry deletion is governed by the
   * parent directory's own permissions, not the individual file's owner. Verified for real against
   * a Linux container/volume (not just reasoned about): reproduced the exact CI failure with a
   * container-root-created subdirectory (a non-root cleanup `rm -rf` failed with `Permission
   * denied`), then confirmed a subdirectory pre-created by the non-root identity itself remains
   * fully deletable by that same identity afterward, even after a root-owned file is written into
   * it and a second root process reads it back - the exact sequence this test class's own
   * backup-then-restore scenarios exercise.
   */
  private static RemoteBucket prepareLocalRcloneRemote(Path tempDir) throws IOException {
    Path rcloneConfig = tempDir.resolve("rclone.conf");
    Files.writeString(rcloneConfig, "[testlocal]\ntype = local\n");
    Path bucketDir = tempDir.resolve("remote-bucket");
    Files.createDirectories(bucketDir.resolve("runner-backups"));
    return new RemoteBucket(rcloneConfig, bucketDir);
  }

  private static List<String> backupRunArgs(
      PostgreSQLContainer<?> source,
      String recipient,
      RemoteBucket bucket,
      String backupLocalDir,
      String retentionDays) {
    return backupRunArgs(source, recipient, bucket, backupLocalDir, retentionDays, null, false);
  }

  private static List<String> backupRunArgs(
      PostgreSQLContainer<?> source,
      String recipient,
      RemoteBucket bucket,
      String backupLocalDir,
      String retentionDays,
      Path hostBackupsDir) {
    return backupRunArgs(
        source, recipient, bucket, backupLocalDir, retentionDays, hostBackupsDir, false);
  }

  /**
   * @param hostBackupsDir when non-null, bind-mounted at {@code backupLocalDir} instead of relying
   *     on the container's own ephemeral filesystem - needed whenever a test cares about local
   *     state surviving (or being shared with) a second, separate {@code docker run} invocation,
   *     e.g. proving a genuine retry-on-the-next-run or a real cross-invocation {@code flock}.
   * @param bucketReadOnly when true, mounts the bucket directory {@code :ro} - a portable way to
   *     simulate a real upload failure (rclone's local-backend write fails with a permission error)
   *     without depending on host-OS-specific filesystem permissions.
   */
  private static List<String> backupRunArgs(
      PostgreSQLContainer<?> source,
      String recipient,
      RemoteBucket bucket,
      String backupLocalDir,
      String retentionDays,
      Path hostBackupsDir,
      boolean bucketReadOnly) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "run",
                "--rm",
                "--network",
                // Sharing source's own network namespace means Postgres is reachable at the
                // container-internal port 5432 on the loopback address, never the Testcontainers-
                // assigned host-mapped port getJdbcUrl() itself uses.
                "container:" + source.getContainerId(),
                "-e",
                "PGHOST=127.0.0.1",
                "-e",
                "PGPORT=5432",
                "-e",
                "PGDATABASE=" + source.getDatabaseName(),
                "-e",
                "PGUSER=" + source.getUsername(),
                "-e",
                "PGPASSWORD=" + source.getPassword(),
                "-e",
                "BACKUP_AGE_RECIPIENT=" + recipient,
                "-e",
                "BACKUP_REMOTE=testlocal",
                "-e",
                "BACKUP_BUCKET=/remote-bucket",
                "-e",
                "BACKUP_LOCAL_DIR=" + backupLocalDir,
                "-e",
                "RCLONE_CONFIG=/secrets/rclone.conf"));
    if (retentionDays != null) {
      args.addAll(List.of("-e", "BACKUP_RETENTION_DAYS=" + retentionDays));
    }
    args.addAll(
        List.of(
            "-v",
            bucket.bucketDir().toAbsolutePath() + ":/remote-bucket" + (bucketReadOnly ? ":ro" : ""),
            "-v",
            bucket.rcloneConfig().toAbsolutePath() + ":/secrets/rclone.conf:ro"));
    if (hostBackupsDir != null) {
      args.addAll(List.of("-v", hostBackupsDir.toAbsolutePath() + ":" + backupLocalDir));
    }
    args.addAll(List.of(IMAGE_TAG, "./backup.sh"));
    return args;
  }

  private static List<String> restoreRunArgs(
      PostgreSQLContainer<?> target,
      Path identityFile,
      RemoteBucket bucket,
      String backupLocalDir,
      String identifier,
      String confirmDestructive) {
    return restoreRunArgs(
        target, identityFile, bucket, backupLocalDir, identifier, null, confirmDestructive);
  }

  /**
   * @param expectedDatabaseOverride when null, {@code RESTORE_EXPECTED_DATABASE} is set to {@code
   *     target}'s own real database name (the correct value in every scenario except the one test
   *     specifically proving a mismatch is refused - see {@link
   *     #refusesWhenTargetDatabaseNameDoesNotMatchExpectation}).
   */
  private static List<String> restoreRunArgs(
      PostgreSQLContainer<?> target,
      Path identityFile,
      RemoteBucket bucket,
      String backupLocalDir,
      String identifier,
      String expectedDatabaseOverride,
      String confirmDestructive) {
    String expectedDatabase =
        expectedDatabaseOverride != null ? expectedDatabaseOverride : target.getDatabaseName();
    List<String> args =
        new ArrayList<>(
            List.of(
                "run",
                "--rm",
                "--network",
                "container:" + target.getContainerId(),
                "-e",
                "PGHOST=127.0.0.1",
                "-e",
                "PGPORT=5432",
                "-e",
                "PGDATABASE=" + target.getDatabaseName(),
                "-e",
                "PGUSER=" + target.getUsername(),
                "-e",
                "PGPASSWORD=" + target.getPassword(),
                "-e",
                "RESTORE_EXPECTED_DATABASE=" + expectedDatabase,
                "-e",
                "BACKUP_AGE_IDENTITY_FILE=/secrets/identity.txt",
                "-e",
                "BACKUP_REMOTE=testlocal",
                "-e",
                "BACKUP_BUCKET=/remote-bucket",
                "-e",
                "BACKUP_LOCAL_DIR=" + backupLocalDir));
    if (confirmDestructive != null) {
      args.addAll(List.of("-e", "RESTORE_CONFIRM_DESTRUCTIVE=" + confirmDestructive));
    }
    args.addAll(List.of("-e", "RCLONE_CONFIG=/secrets/rclone.conf"));
    if (bucket != null) {
      args.addAll(
          List.of(
              "-v",
              bucket.bucketDir().toAbsolutePath() + ":/remote-bucket",
              "-v",
              bucket.rcloneConfig().toAbsolutePath() + ":/secrets/rclone.conf:ro"));
    }
    args.addAll(
        List.of(
            "-v",
            identityFile.toAbsolutePath() + ":/secrets/identity.txt:ro",
            IMAGE_TAG,
            "./restore.sh",
            identifier));
    return args;
  }

  private static String onlyUploadedObjectName(RemoteBucket bucket) throws IOException {
    Path uploadedDir = bucket.bucketDir().resolve("runner-backups");
    List<Path> uploaded;
    try (Stream<Path> files = Files.list(uploadedDir)) {
      uploaded = files.toList();
    }
    assertThat(uploaded)
        .as("exactly one encrypted backup object should have been uploaded")
        .hasSize(1);
    return uploaded.get(0).getFileName().toString();
  }

  private static void migrateOnly(PostgreSQLContainer<?> container) {
    Flyway.configure()
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .load()
        .migrate();
  }

  /**
   * Migrates and seeds one real row in every table the schema actually has - not just {@code runs}
   * (a review finding: the original version of this test only ever proved a bare run row survived,
   * never {@code run_events}/{@code run_selected_tests}/{@code artifacts}, which are exactly the
   * tables a real restore is most likely to get subtly wrong, e.g. a foreign-key ordering issue
   * {@code pg_restore} handles differently under {@code --single-transaction}). Returns the real
   * Flyway migration count, read from the database itself rather than a hardcoded literal that
   * would silently go stale the next time a migration is added.
   */
  private static int migrateAndSeedFully(PostgreSQLContainer<?> container, String runId)
      throws Exception {
    migrateOnly(container);
    try (Connection connection =
        DriverManager.getConnection(
            container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
      OffsetDateTime requestedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              INSERT INTO runs
                (run_id, environment, suite, status, requested_at, started_at, finished_at,
                 exit_code, detail, next_event_sequence, version)
              VALUES (?, 'PUBLIC', 'SMOKE', 'SUCCEEDED', ?, ?, ?, 0, NULL, 2, 0)
              """)) {
        statement.setString(1, runId);
        statement.setObject(2, requestedAt);
        statement.setObject(3, requestedAt.plusSeconds(1));
        statement.setObject(4, requestedAt.plusSeconds(10));
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              INSERT INTO run_selected_tests (run_id, ordinal, test_key, display_name, layer)
              VALUES (?, 0, 'some.Test#methodOne', 'Some test', 'API')
              """)) {
        statement.setString(1, runId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
              VALUES (?, 1, 'RUN_QUEUED', now(), '{}'::jsonb)
              """)) {
        statement.setString(1, runId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              INSERT INTO artifacts
                (artifact_id, run_id, test_id, test_display_name, schema_version, artifact_type,
                 relative_path, size_bytes, media_type, created_at)
              VALUES (?, ?, 'some-test-id', 'Some test', '1.1', 'SCREENSHOT', 'shot.png', 100,
                      'image/png', ?)
              """)) {
        statement.setString(1, "artifact-" + runId);
        statement.setString(2, runId);
        statement.setObject(3, OffsetDateTime.now());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
              connection.prepareStatement("SELECT count(*) FROM flyway_schema_history");
          ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static boolean hasRunsTable(PostgreSQLContainer<?> container) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = 'runs'");
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getInt(1) > 0;
    }
  }

  private static int countRuns(PostgreSQLContainer<?> container, String runId) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM runs WHERE run_id = ?")) {
      statement.setString(1, runId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /**
   * The real proof of the whole drill: the target Postgres - which received nothing but an
   * encrypted, off-site-round-tripped {@code pg_dump} archive - now carries the exact same Flyway
   * migration history (compared dynamically against the source's own real count, never a hardcoded
   * literal) and one real row in every one of the four tables this schema has.
   */
  private static void assertRestoredContentMatches(
      PostgreSQLContainer<?> source,
      PostgreSQLContainer<?> target,
      String runId,
      int sourceMigrationCount)
      throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            target.getJdbcUrl(), target.getUsername(), target.getPassword())) {
      try (PreparedStatement statement =
              connection.prepareStatement("SELECT count(*) FROM flyway_schema_history");
          ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        assertThat(resultSet.getInt(1))
            .as("restored schema_history should carry exactly the source's own migration count")
            .isEqualTo(sourceMigrationCount);
      }
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT status, exit_code FROM runs WHERE run_id = ?")) {
        statement.setString(1, runId);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertThat(resultSet.next())
              .as("the real run row should have survived the round trip")
              .isTrue();
          assertThat(resultSet.getString("status")).isEqualTo("SUCCEEDED");
          assertThat(resultSet.getInt("exit_code")).isZero();
        }
      }
      assertOneRowSurvived(connection, "run_selected_tests", runId);
      assertOneRowSurvived(connection, "run_events", runId);
      assertOneRowSurvived(connection, "artifacts", runId);
    }
  }

  private static void assertOneRowSurvived(Connection connection, String table, String runId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE run_id = ?")) {
      statement.setString(1, runId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        assertThat(resultSet.getInt(1))
            .as(table + " row should have survived the round trip")
            .isEqualTo(1);
      }
    }
  }

  private static String dockerOrThrow(List<String> args) throws IOException, InterruptedException {
    DockerResult result = docker(args);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "docker "
              + args.get(0)
              + " failed (exit "
              + result.exitCode()
              + "):\n"
              + result.output());
    }
    return result.output();
  }

  private static final Duration DOCKER_COMMAND_TIMEOUT = Duration.ofMinutes(2);

  /**
   * A review finding: the original version of this helper had no bounded cleanup path at all - a
   * hung {@code docker} command (or, since JUnit 5's default {@code @Timeout} mode does not
   * actually interrupt a blocking {@link Process#waitFor()} on the same thread, a test that simply
   * never returns) could leak both the local {@code docker} CLI client process and, for a {@code
   * run} invocation, the actual container the daemon keeps executing regardless of what happens to
   * that local client - the same class of failure-path gap {@code DashboardProcess}'s own bounded
   * termination logic already guards against elsewhere in this repo, adapted here to Docker's own
   * execution model (killing this method's {@link Process} handle only ever stops the local CLI
   * client, never the daemon-managed container - a named, explicit {@code docker kill}/{@code
   * docker rm -f} is the only mechanism that reaches the actual container).
   *
   * <p>Every {@code run} invocation gets an explicit, unique {@code --name} inserted for exactly
   * this reason. Output is drained on a separate thread while the main thread waits with a real
   * timeout, rather than blocking on {@code InputStream#readAllBytes()} first (which itself has no
   * timeout and would already be stuck against a hung process before {@link Process#waitFor(long,
   * TimeUnit)} is ever reached).
   */
  private static DockerResult docker(List<String> rawArgs)
      throws IOException, InterruptedException {
    List<String> args = new ArrayList<>(rawArgs);
    String containerName = null;
    if (!args.isEmpty() && "run".equals(args.get(0))) {
      containerName = "backup-drill-" + UUID.randomUUID();
      args.add(1, "--name");
      args.add(2, containerName);
    }

    List<String> command = new ArrayList<>();
    command.add("docker");
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();

    StringBuilder output = new StringBuilder();
    Thread outputReader =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                int character;
                while ((character = reader.read()) != -1) {
                  output.append((char) character);
                }
              } catch (IOException e) {
                // Stream closed underneath a forcibly-destroyed process below - expected, not an
                // error worth surfacing.
              }
            },
            "docker-output-reader");
    outputReader.setDaemon(true);
    outputReader.start();

    boolean finished = process.waitFor(DOCKER_COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      if (containerName != null) {
        killAndRemoveContainer(containerName);
      }
      outputReader.join(Duration.ofSeconds(5).toMillis());
      throw new IllegalStateException(
          "docker "
              + (rawArgs.isEmpty() ? "" : rawArgs.get(0))
              + " timed out after "
              + DOCKER_COMMAND_TIMEOUT
              + " - forcibly killed"
              + (containerName != null ? " (container " + containerName + ")" : "")
              + ". Output so far:\n"
              + output);
    }
    outputReader.join(Duration.ofSeconds(5).toMillis());
    return new DockerResult(process.exitValue(), output.toString());
  }

  private static void killAndRemoveContainer(String containerName) {
    try {
      new ProcessBuilder("docker", "kill", containerName).start().waitFor(10, TimeUnit.SECONDS);
      new ProcessBuilder("docker", "rm", "-f", containerName).start().waitFor(10, TimeUnit.SECONDS);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Best-effort cleanup on an already-exceptional path - the timeout exception this is called
      // from is the actionable failure; a cleanup command that itself fails to run must not replace
      // or hide it.
    }
  }
}
