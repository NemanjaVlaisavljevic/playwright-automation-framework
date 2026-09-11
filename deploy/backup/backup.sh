#!/usr/bin/env bash
#
# Runs one backup cycle: any previously-uploaded-but-unverified local archive is retried first, then
# a fresh pg_dump streams directly into age encryption (no plaintext dump ever touches disk), the
# encrypted file is uploaded off-site via rclone, the upload is verified by an explicit checksum
# comparison, and the local copy is deleted only after that verification succeeds. Fails closed
# (refuses to start a new dump) if local disk is too tight rather than discarding unconfirmed data -
# see check_local_disk_budget() below. Runs under a non-blocking flock so concurrent invocations
# never share a filename, race an in-flight partial, or double-upload.
#
# Remote retention is primarily a bucket lifecycle policy (configured on the bucket itself - see
# docs/DEPLOYMENT_ARCHITECTURE.md section 7); BACKUP_RETENTION_DAYS below is a second, independent
# bound this script also enforces.
#
# Required env vars (never logged - see log() below):
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD   standard libpq connection variables identifying
#                             the source database to dump - psql/pg_dump pick these up automatically,
#                             so the password never appears on any command line (ps-safe). See
#                             docker-compose.backup.yml's own service config for where these are set.
#   BACKUP_AGE_RECIPIENT     a public age1... recipient string. NOT a secret - the matching private
#                             identity key never resides permanently in the standing deployment (see
#                             restore.sh's own header - it is mounted only transiently, for the
#                             duration of an actual restore).
#   BACKUP_REMOTE             rclone remote name (from RCLONE_CONFIG), e.g. "b2".
#   BACKUP_BUCKET             bucket/container name within that remote.
# Optional:
#   BACKUP_PREFIX               path prefix within the bucket. Default: "runner-backups".
#   BACKUP_RETENTION_DAYS       remote objects older than this are deleted after a successful upload.
#                                Default: unset (rely on the bucket's own lifecycle policy alone).
#   BACKUP_LOCAL_DIR             local working directory. Default: /backups (see
#                                docker-compose.backup.yml's own bind mount for this service).
#   BACKUP_MIN_FREE_BYTES        flat floor check_local_disk_budget() always requires, on top of the
#                                actual database size below. Default: 104857600 (100 MiB).
#   BACKUP_DUMP_RESERVE_BYTES    additional flat safety margin added on top of the source database's
#                                own real, live-measured size. Default: 104857600 (100 MiB).
#   RCLONE_CONFIG                path to the rclone config file holding BACKUP_REMOTE's own real
#                                credentials. Never baked into this image - mount it read-only. Real
#                                rclone env var name, not invented here.

set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${BACKUP_AGE_RECIPIENT:?BACKUP_AGE_RECIPIENT is required}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE is required}"
: "${BACKUP_BUCKET:?BACKUP_BUCKET is required}"
BACKUP_PREFIX="${BACKUP_PREFIX:-runner-backups}"
BACKUP_LOCAL_DIR="${BACKUP_LOCAL_DIR:-/backups}"
BACKUP_MIN_FREE_BYTES="${BACKUP_MIN_FREE_BYTES:-104857600}"
BACKUP_DUMP_RESERVE_BYTES="${BACKUP_DUMP_RESERVE_BYTES:-104857600}"

# Never echo PGPASSWORD, RCLONE_CONFIG contents, or BACKUP_AGE_RECIPIENT anywhere that could be
# confused with the private identity - only filenames, sizes, timestamps, and the remote path are
# safe to log.
log() {
  printf '[backup] %s\n' "$1"
}

remote_dir="${BACKUP_REMOTE}:${BACKUP_BUCKET}/${BACKUP_PREFIX}"
mkdir -p "$BACKUP_LOCAL_DIR"

# Single-flight: held for the script's duration, released automatically on exit. Non-blocking - a
# genuinely concurrent invocation must fail immediately, never queue up and race a filename or an
# in-flight *.partial.
exec 9>"${BACKUP_LOCAL_DIR}/.backup.lock"
if ! flock -n 9; then
  log "another backup run already holds the lock - exiting rather than racing it"
  exit 1
fi

# Streams pg_dump's stdout directly into age; the pipe never touches disk unencrypted. `set -o
# pipefail` fails the whole pipeline on either stage's failure, and the partial is removed rather
# than promoted to a final path.
dump_and_encrypt() {
  local_path="$1"
  partial_path="${local_path}.partial"
  if ! pg_dump --format=custom \
      | age --encrypt --recipient "$BACKUP_AGE_RECIPIENT" --output "$partial_path"; then
    log "dump or encrypt failed - removing incomplete partial, not attempting upload"
    rm -f "$partial_path"
    return 1
  fi
  # Atomic on the same filesystem (both paths are under BACKUP_LOCAL_DIR).
  mv "$partial_path" "$local_path"
  return 0
}

# Shared by both the retry-existing-files pass and the fresh-dump pass, so neither path can diverge
# on what "uploaded and verified" means.
upload_and_verify() {
  local_path="$1"
  name="$(basename "$local_path")"
  log "uploading ${name}"
  if ! rclone copyto "$local_path" "${remote_dir}/${name}"; then
    log "upload failed for ${name} - keeping local copy for the next run to retry"
    return 1
  fi
  # Explicit post-upload verification, not just copyto's own retry/checksum behavior - `rclone
  # check` independently re-reads both sides and compares hashes/sizes.
  log "verifying upload (checksum): ${name}"
  if ! rclone check "$local_path" "${remote_dir}/" --one-way; then
    log "upload verification FAILED for ${name} - keeping local copy for the next run to retry"
    return 1
  fi
  log "upload verified - deleting local copy: ${name}"
  rm -f "$local_path"
  return 0
}

# A *.partial here can only be from a crashed prior run - the flock above excludes a genuinely
# concurrent one. Always safe to remove unconditionally.
for stale in "$BACKUP_LOCAL_DIR"/*.dump.age.partial; do
  [ -e "$stale" ] || continue
  log "removed a partial left over from a crashed previous run: $(basename "$stale")"
  rm -f "$stale"
done

# Retry every existing, unverified archive first, before attempting a new dump. A file leaves this
# loop only once upload_and_verify() confirms it is safely off-site; a repeatedly-failing one is
# left in place indefinitely (see check_local_disk_budget() below for what happens if that starts to
# threaten local disk space).
for existing in "$BACKUP_LOCAL_DIR"/*.dump.age; do
  [ -e "$existing" ] || continue
  log "retrying a previously-unverified local backup: $(basename "$existing")"
  upload_and_verify "$existing" || true
done

# Fail-closed guard, not a cleanup: refuses to start a new dump unless local disk has room for more
# than a flat floor - also for the dump actually about to be attempted. `pg_database_size()` is
# Postgres's own real, live-measured size of the database - a conservative estimate (pg_dump's
# compressed output is typically smaller than this raw on-disk figure). Required = flat floor
# (headroom for whatever else shares this filesystem) + real database size + an additional flat
# safety margin. A failure here most likely means uploads have been failing repeatedly and unverified
# archives are piling up, or the database has outgrown its old defaults; either way this refuses to
# start the dump rather than risk disk exhaustion mid-dump or silently deleting an unconfirmed
# backup to make room. Deliberately loud - a human needs to investigate.
check_local_disk_budget() {
  available="$(df -k "$BACKUP_LOCAL_DIR" | tail -n1 | awk '{print $4}')"
  available_bytes=$((available * 1024))
  database_bytes="$(psql -tAc 'SELECT pg_database_size(current_database())')"
  required_bytes=$((BACKUP_MIN_FREE_BYTES + database_bytes + BACKUP_DUMP_RESERVE_BYTES))
  if [ "$available_bytes" -lt "$required_bytes" ]; then
    log "FAIL-CLOSED: only ${available_bytes} bytes free under ${BACKUP_LOCAL_DIR}, but need at least ${required_bytes} (floor ${BACKUP_MIN_FREE_BYTES} + real database size ${database_bytes} + reserve ${BACKUP_DUMP_RESERVE_BYTES}) after retrying every existing local backup - refusing to start a new dump. This may mean uploads have been failing repeatedly (investigate the off-site path - network/credentials/bucket) or that the database has genuinely outgrown the local volume; either way, a human needs to look before this volume fills completely."
    exit 1
  fi
}
check_local_disk_budget

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
suffix="$(printf '%04x%04x' "$RANDOM" "$RANDOM")"
backup_name="runner-backup-${timestamp}-${suffix}.dump.age"
final_path="${BACKUP_LOCAL_DIR}/${backup_name}"

log "starting dump -> encrypt: ${backup_name}"
if ! dump_and_encrypt "$final_path"; then
  exit 1
fi
dump_bytes="$(stat -c%s "$final_path" 2>/dev/null || stat -f%z "$final_path")"
log "encrypted archive ready: ${backup_name} (${dump_bytes} bytes)"

if ! upload_and_verify "$final_path"; then
  exit 1
fi

if [ -n "${BACKUP_RETENTION_DAYS:-}" ]; then
  log "enforcing secondary remote retention: deleting objects under ${remote_dir}/ older than ${BACKUP_RETENTION_DAYS} day(s)"
  # Deliberately non-fatal: the dump is already safely uploaded and verified by this point - a
  # transient failure in this secondary cleanup (the bucket's own lifecycle policy is the primary
  # retention mechanism) must never make a successful backup report as failed.
  if ! rclone delete "${remote_dir}/" --min-age "${BACKUP_RETENTION_DAYS}d"; then
    log "WARNING: secondary remote retention cleanup failed - today's backup is still safely uploaded and verified; relying on the bucket's own lifecycle policy this cycle"
  fi
fi

log "backup complete: ${backup_name}"
