# The backup/restore image. `postgres:17-alpine`, not a separate base, so pg_dump/pg_restore always
# match the exact server major version this deployment runs (docker-compose.yml pins the same tag
# for the `postgres` service) - avoids the classic pg_dump version-mismatch footgun. `age`/`rclone`
# are both real Alpine packages, so no third-party script/binary download is needed.
#
# A separate image from runner-service's Dockerfile, not a JVM-embedded `@Scheduled` job: the
# backup/restore mechanism (see backup.sh/restore.sh) is a plain shell pipeline with no Java
# involved. Invoked on demand (`docker compose -f docker-compose.yml -f docker-compose.backup.yml
# run --rm backup`), normally from a host-level systemd timer (see the D4.6 runbook), never as a
# long-running service.
FROM postgres:17-alpine

RUN apk add --no-cache age rclone

WORKDIR /scripts
COPY backup.sh restore.sh ./
RUN chmod +x backup.sh restore.sh

# No CMD/ENTRYPOINT - deploy/docker-compose.backup.yml's `backup` service supplies the real command
# (`./backup.sh` or `./restore.sh <identifier>`) per invocation, and BackupRestoreDrillTest invokes
# this same image directly via `docker run` with its own explicit command - one image, two entry
# points, matching that a production host runs backup.sh on a schedule and restore.sh only by hand.
