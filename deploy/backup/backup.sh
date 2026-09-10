#!/usr/bin/env bash
#
# D4.5 - one backup cycle: any previously-uploaded-but-unverified local archive is retried FIRST,
# then a fresh pg_dump streams directly into recipient-based age encryption (no plaintext database
# dump is ever persisted to disk - see the pipe below), the encrypted file is uploaded off-site via
# rclone, the upload is verified by an explicit checksum comparison, and the local copy is deleted
# only after that verification succeeds - never on age alone (a review finding: an earlier version of
# this script called a leftover local file a "retry candidate" but never actually retried it, then
# deleted it purely for being old, directly contradicting its own "delete only after verified"
# guarantee). If local disk pressure becomes critical while unverified backups are still sitting
# around, this script fails closed and refuses to start a new dump rather than silently discarding
# unconfirmed data - see check_local_disk_budget() below. The whole cycle runs under a non-blocking
# flock, so two concurrent invocations (a cron/systemd overlap, or a manual run racing a scheduled
# one) can never share a filename, race an in-flight partial, or double-upload.
#
# Remote retention is primarily a bucket lifecycle policy (configured on the bucket itself, not here
# - see docs/DEPLOYMENT_ARCHITECTURE.md section 7); BACKUP_RETENTION_DAYS below is a second,
# independent bound this script also enforces, defense in depth against a lifecycle policy that was
# never configured or was misconfigured.
#
# Required env vars (never logged - see log() below):
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD   standard libpq connection variables identifying
#                             the source database to dump - psql/pg_dump pick these up automatically,
#                             with no connection-string argument ever passed on any command line
#                             (ps/argv-safe) and no URL-encoding ambiguity for special characters in
#                             the password. A review finding: an earlier version of this script instead
#                             accepted a single postgresql://user:password@host:port/dbname DATABASE_URL
#                             and parsed it with a hand-rolled regex - a raw `@`/`:`/`/`/`%` character
#                             in a real, strong production password broke that parser outright (e.g.
#                             `p@ssword` was misparsed as password `p` and host `ssword@postgres`, and
#                             percent-encoded forms like `p%40ssword` were never decoded at all).
#                             Passing the five values directly, as separate environment variables with
#                             no URL syntax to disambiguate, removes the parsing step - and the whole
#                             class of bug - entirely. See docker-compose.backup.yml's own service
#                             config for where these are actually set.
#   BACKUP_AGE_RECIPIENT     a public age1... recipient string. NOT a secret - the matching private
#                             identity key never resides permanently in the standing deployment (see
#                             restore.sh's own header - it is mounted only transiently, for the
#                             duration of an actual restore).
#   BACKUP_REMOTE             rclone remote name (from RCLONE_CONFIG), e.g. "b2".
#   BACKUP_BUCKET             bucket/container name within that remote.
# Optional:
#   BACKUP_PREFIX               path prefix within the bucket. Default: "runner-backups".
#   BACKUP_RETENTION_DAYS       remote objects older than this are deleted after a successful upload.
#                                Default: unset (no script-side remote deletion - rely on the bucket's
#                                own lifecycle policy alone).
#   BACKUP_LOCAL_DIR             local working directory. Default: /backups (see
#                                docker-compose.backup.yml's own bind mount for this service).
#   BACKUP_MIN_FREE_BYTES        a flat floor check_local_disk_budget() always requires, on top of the
#                                actual database size below - covers the encrypted archive's own
#                                bookkeeping/metadata and leaves headroom for whatever else shares this
#                                filesystem. Default: 104857600 (100 MiB).
#   BACKUP_DUMP_RESERVE_BYTES    an additional flat safety margin added on top of the source
#                                database's own real, live-measured size (a review finding: a flat
#                                100 MiB floor alone says nothing about how big the dump about to be
#                                attempted actually is - 101 MiB free with a several-hundred-MiB
#                                database would have passed the old check and then exhausted the
#                                filesystem mid-dump). Default: 104857600 (100 MiB).
#   RCLONE_CONFIG                path to the rclone config file holding BACKUP_REMOTE's own real
#                                credentials. Never baked into this image - mount it read-only. rclone
#                                itself reads this exact env var name; not a name invented here.

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

# Never echo PGPASSWORD, RCLONE_CONFIG's own contents, or BACKUP_AGE_RECIPIENT in a context that
# could be confused with the private identity - only filenames, sizes, timestamps, and the remote
# *path* (never remote credentials) are safe to log. pg_dump/psql below take no connection-string
# argument at all - PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD are picked up automatically by libpq,
# so the password never appears in this container's own process list (`ps`) either.
log() {
  printf '[backup] %s\n' "$1"
}

remote_dir="${BACKUP_REMOTE}:${BACKUP_BUCKET}/${BACKUP_PREFIX}"
mkdir -p "$BACKUP_LOCAL_DIR"

# Single-flight: held for this whole script's duration (the fd stays open until the process exits,
# which releases the lock automatically - no explicit unlock needed). Non-blocking - a second,
# genuinely concurrent invocation (an overlapping cron/systemd run, or a manual run racing a
# scheduled one) must fail loudly and immediately, never queue up and silently share a filename or
# race an in-flight *.partial with the first invocation.
exec 9>"${BACKUP_LOCAL_DIR}/.backup.lock"
if ! flock -n 9; then
  log "another backup run already holds the lock - exiting rather than racing it"
  exit 1
fi

# Streams pg_dump's stdout directly into age; the pipe never touches disk unencrypted at any point.
# On failure at either stage, `set -o pipefail` (via `set -euo pipefail` above) fails the whole
# pipeline, and the partial is removed rather than ever being renamed into a final, upload-candidate
# path.
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

# Shared by both the retry-existing-files pass and the fresh-dump pass below, so neither path can
# silently diverge on what "uploaded and verified" actually means.
upload_and_verify() {
  local_path="$1"
  name="$(basename "$local_path")"
  log "uploading ${name}"
  if ! rclone copyto "$local_path" "${remote_dir}/${name}"; then
    log "upload failed for ${name} - keeping local copy for the next run to retry"
    return 1
  fi
  # Explicit post-upload verification, not just trusting copyto's own internal retry/checksum
  # behavior - `rclone check` independently re-reads both sides and compares hashes/sizes.
  log "verifying upload (checksum): ${name}"
  if ! rclone check "$local_path" "${remote_dir}/" --one-way; then
    log "upload verification FAILED for ${name} - keeping local copy for the next run to retry"
    return 1
  fi
  log "upload verified - deleting local copy: ${name}"
  rm -f "$local_path"
  return 0
}

# A *.partial left over here can only be from a crashed prior run, never a genuinely concurrent one
# - the flock above already excludes that. Always safe to remove unconditionally, no age check
# needed.
for stale in "$BACKUP_LOCAL_DIR"/*.dump.age.partial; do
  [ -e "$stale" ] || continue
  log "removed a partial left over from a crashed previous run: $(basename "$stale")"
  rm -f "$stale"
done

# Retry every existing, still-unverified final archive FIRST, before attempting a new dump - a
# review finding: an earlier version of this script only ever called these "retry candidates" in a
# comment and then deleted them by age, which directly contradicted the "delete only after verified"
# guarantee. A file only leaves this loop once upload_and_verify() has actually confirmed it is
# safely off-site; a repeatedly-failing one is left in place indefinitely (see
# check_local_disk_budget() below for what happens if that starts to threaten local disk space).
for existing in "$BACKUP_LOCAL_DIR"/*.dump.age; do
  [ -e "$existing" ] || continue
  log "retrying a previously-unverified local backup: $(basename "$existing")"
  upload_and_verify "$existing" || true
done

# A fail-closed guard, not a cleanup: refuses to start a new dump unless local disk has room not just
# for a flat floor, but for the dump actually about to be attempted - a review finding: a flat-floor-
# only check (the original version of this function) would have happily started a several-hundred-MiB
# dump with only 101 MiB free, exhausting the filesystem mid-dump instead of ever catching the problem
# up front. `pg_database_size()` is Postgres's own real, live-measured size of the database about to
# be dumped - a conservative estimate (pg_dump's own compressed custom-format output is typically
# *smaller* than the raw on-disk size this reports, so requiring room for the larger, uncompressed
# figure is the safe direction to round). Required = the flat floor (headroom for everything else that
# might share this filesystem) + the real database size + an additional flat safety margin. If that
# fails - most plausibly because uploads have been failing repeatedly (a broken network path,
# revoked/expired credentials, a misconfigured bucket) and unverified archives have been piling up
# above, though it could just as well mean the database itself has genuinely outgrown its old
# defaults - this script refuses to start the dump at all, rather than risk either failing mid-dump
# from real disk exhaustion or (the far worse alternative an earlier version of this script actually
# did) silently deleting a real, unconfirmed backup just to make room. A human needs to see this and
# investigate; this is deliberately loud, not a quiet skip.
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
  # Deliberately non-fatal: today's dump is already safely uploaded and verified by this point - a
  # transient failure in this purely secondary, defense-in-depth cleanup (the bucket's own lifecycle
  # policy is the primary retention mechanism) must never make a genuinely successful backup report
  # as failed, which could otherwise trigger a false alert or an unnecessary re-run of an
  # already-successful dump.
  if ! rclone delete "${remote_dir}/" --min-age "${BACKUP_RETENTION_DAYS}d"; then
    log "WARNING: secondary remote retention cleanup failed - today's backup is still safely uploaded and verified; relying on the bucket's own lifecycle policy this cycle"
  fi
fi

log "backup complete: ${backup_name}"
