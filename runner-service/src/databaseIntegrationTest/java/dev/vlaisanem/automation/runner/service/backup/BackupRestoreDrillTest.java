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
 * Builds the real {@code deploy/backup} Docker image and drives its real {@code backup.sh}/{@code
 * restore.sh} scripts end to end against real Testcontainers {@code postgres:17-alpine} instances -
 * real pg_dump, age encryption/decryption, rclone upload/download, and pg_restore, nothing mocked.
 * The negative tests below prove specific safety gaps (an unvalidated restore identifier, a
 * destructive restore with no target-emptiness check, an unretried "retry candidate") are closed,
 * not just that the happy path works.
 *
 * <p>{@code BACKUP_REMOTE} points at an rclone {@code type = local} remote standing in for the real
 * S3-compatible bucket - this test has no real cloud credentials, and none should ever be committed
 * to this repo. The real S3/B2 round trip is a separate D5 acceptance item (see
 * docs/RELEASE_EVIDENCE.md's D4.5 section).
 *
 * <p>{@code backup.sh}/{@code restore.sh} run as separate {@code docker run} invocations sharing
 * each Postgres container's own network namespace ({@code --network container:<id>}), reaching
 * Postgres at {@code 127.0.0.1:5432}. The image is built once for the whole class - its content
 * never changes between tests.
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
   * Proves a password containing URL-special characters ({@code @ : / %}) round-trips correctly.
   * PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD are passed as separate env vars, not a single
   * connection-string URL, so such characters in the password can't break parsing.
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
   * {@code identifier} becomes part of a local filesystem path and an rclone remote path, so
   * restore.sh must reject a path-traversal/absolute-path identifier before touching rclone/age at
   * all.
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
   * A destructive restore into a non-empty target requires an explicit {@code
   * RESTORE_CONFIRM_DESTRUCTIVE} tied to this restore's own database name and backup identifier - a
   * missing ack and a stale/wrong ack (from a different incident) are both refused, not just "some"
   * non-empty value.
   */
  @Test
  @Timeout(60)
  void refusesToRestoreDestructivelyIntoANonEmptyTargetWithoutExplicitAcknowledgment(
      @TempDir Path tempDir) throws Exception {
    try (PostgreSQLContainer<?> target = new PostgreSQLContainer<>("postgres:17-alpine")) {
      target.start();
      String runId = "already-here-" + UUID.randomUUID();
      migrateAndSeedFully(target, runId);

      // Content is irrelevant - the destructive-target check must refuse before age/rclone are
      // touched. Only needs to exist as a real host file so the bind mount succeeds.
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
   * {@code RESTORE_EXPECTED_DATABASE} is cross-checked against Postgres's own {@code
   * current_database()} - a wrong-database restore into a fresh, never-migrated database would
   * otherwise be indistinguishable from a legitimate disaster-recovery target.
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
   * Decrypting with the wrong identity must fail loudly (age refuses, exit code propagates via
   * {@code pipefail}), never silently hand pg_restore garbage that looks like success.
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
   * When upload verification fails, the local encrypted archive must survive on disk - untouched,
   * so the next run's retry pass can find and retry it.
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
   * Proves the survivor from a failed run is actually retried, not just left on disk: runs
   * backup.sh twice against the same local directory (failing bucket, then writable) and confirms
   * both the survivor and run 2's own fresh dump end up uploaded, with nothing left locally.
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
   * A concurrent backup.sh run must fail fast with a lock-held message, never block or race, while
   * another holds the same real {@code flock -n 9} lock this script itself uses.
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

      // Waits for the holder's "LOCK_ACQUIRED" announcement, not the lock file's existence: `exec
      // 9>...` creates the file before `flock` actually runs, so file-existence alone would race.
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
   * Blocks until {@code process}'s stdout prints a line equal to {@code marker}, or throws after
   * {@code timeout}. Reads line-by-line (never {@code readAllBytes()}, which blocks until exit) so
   * it can observe output while the process is still running.
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
   * <p>The backup/restore image runs as root, so on a real Linux Docker host a container-created
   * subdirectory inside this bind mount would be root-owned and undeletable by CI's non-root user
   * (directory-entry deletion needs write permission on the parent, not ownership of the entry).
   * Pre-creating {@code runner-backups} here, before any container runs, makes the CI user its
   * owner instead, so JUnit's {@code @TempDir} cleanup can still delete it even though files
   * written inside it afterward are root-owned.
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
   * Migrates and seeds one real row in every table the schema has, not just {@code runs} - these
   * are the tables a real restore is most likely to get subtly wrong (e.g. FK ordering under {@code
   * pg_restore --single-transaction}). Returns the real Flyway migration count read from the
   * database, not a hardcoded literal.
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
   * Runs {@code docker} with a bounded timeout and explicit cleanup. Killing this method's {@link
   * Process} handle only stops the local CLI client - the daemon keeps running the actual container
   * - so every {@code run} invocation gets an explicit {@code --name} and, on timeout, an explicit
   * {@code docker kill}/{@code docker rm -f}. Output is drained on a separate thread while waiting
   * with a real timeout, since {@code InputStream#readAllBytes()} has none.
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
