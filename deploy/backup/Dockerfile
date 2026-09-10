# D4.5 - the backup/restore image. `postgres:17-alpine`, not a separate base, so pg_dump/pg_restore
# always match the exact server major version this deployment actually runs (deploy/docker-compose.yml
# pins the same tag for the `postgres` service) - a version-mismatched client is the classic pg_dump
# footgun this sidesteps entirely rather than pinning two independent version numbers that could
# silently drift apart. `age`/`rclone` are both real Alpine 3.24 packages (confirmed present in this
# base image's own apk repos), so no third-party script/binary download is needed.
#
# Deliberately a separate image from runner-service's own Dockerfile, not a JVM-embedded
# `@Scheduled` job: the whole backup/restore mechanism (see backup.sh/restore.sh) is a plain shell
# pipeline with no Java involved, and shipping pg_dump/age/rclone inside the application's own JVM
# image would coupled backup failures to the app's own container lifecycle for no benefit - this
# image is invoked on demand (`docker compose -f docker-compose.yml -f docker-compose.backup.yml
# run --rm backup`), normally from a host-level systemd timer documented in the D4.6 operational
# runbook, never as a long-running service.
FROM postgres:17-alpine

RUN apk add --no-cache age rclone

WORKDIR /scripts
COPY backup.sh restore.sh ./
RUN chmod +x backup.sh restore.sh

# No CMD/ENTRYPOINT - deploy/docker-compose.backup.yml's `backup` service (a separate overlay file,
# never merged into the base docker-compose.yml or gated by a profile there - see that overlay's own
# header comment for why) supplies the real command (`./backup.sh` or `./restore.sh <identifier>`)
# per invocation, and the databaseIntegrationTest restore drill (BackupRestoreDrillTest) invokes this
# same image directly via `docker run` with its own explicit command too - one image, two entry
# points, matching the fact that a production host runs backup.sh on a schedule and restore.sh only
# ever by hand, deliberately.
