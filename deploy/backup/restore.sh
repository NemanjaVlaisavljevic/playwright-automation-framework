#!/usr/bin/env bash
#
# Restores one specific, explicitly-named backup into a target Postgres. Never accepts "latest"
# implicitly - the caller must name the exact object, and this script prints that identifier (and
# the real, database-confirmed target name - see below) before touching anything.
#
# The decrypted plaintext dump is never written to disk - age --decrypt pipes directly into
# pg_restore's stdin, mirroring backup.sh's "no plaintext ever persisted" guarantee. The restore
# runs under --single-transaction (which implies --exit-on-error per pg_restore's own docs) - either
# every object in the archive is applied, or none of them are.
#
# Usage: restore.sh <backup-identifier>
#   e.g. restore.sh runner-backup-20260910T120000Z-a1b2c3d4.dump.age
#
# Required env vars (never logged - see log() below):
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD   standard libpq connection variables identifying
#                              the target database to restore INTO. --clean --if-exists below is
#                              destructive against whatever schema already exists there - see the
#                              target-database checks below. Already present in this container's
#                              ambient environment (docker-compose.backup.yml's own service config,
#                              the same values backup.sh dumps FROM) for the common "restore into the
#                              same production database" case. A genuinely different target should
#                              override these five variables via `docker compose run
#                              --env-from-file <path>` instead (see docs/RUNBOOK.md section 10),
#                              never inline on the command line.
#   RESTORE_EXPECTED_DATABASE  the database name the caller believes PGHOST/PGPORT/etc. point at.
#                              Cross-checked against `SELECT current_database()` - what Postgres
#                              itself reports the connection landed on - and the restore refuses
#                              outright on any mismatch, before touching rclone/age/pg_restore.
#   BACKUP_AGE_IDENTITY_FILE  path to the PRIVATE age identity key file. Deliberately kept out of the
#                              standing deployment (see backup.sh's own header) - not present in
#                              docker-compose.backup.yml's config at all; supplied only here, mounted
#                              read-only for the duration of this one restore invocation, from
#                              wherever it is actually kept at rest (offline/a password manager). It
#                              is genuinely present on this host temporarily while a restore runs.
#   BACKUP_REMOTE, BACKUP_BUCKET   same rclone remote/bucket backup.sh uploaded to.
# Optional:
#   BACKUP_PREFIX     same default as backup.sh: "runner-backups".
#   BACKUP_LOCAL_DIR  local working directory for the downloaded (still-encrypted) file. Default:
#                     /backups.
#   RESTORE_CONFIRM_DESTRUCTIVE   required only when the target database already has a non-empty
#                     `runs` table - see the target-database check below. Must equal exactly
#                     "<real-database-name>:<backup-identifier>", never a generic
#                     RESTORE_CONFIRM_DESTRUCTIVE=yes, so it can't be copy-pasted and silently reused
#                     against a different target/backup. This script prints the exact string it
#                     expects in its own refusal message. Never required for an empty/disposable
#                     target.

set -euo pipefail

identifier="${1:-}"
if [ -z "$identifier" ]; then
  echo "usage: restore.sh <backup-identifier>  (e.g. runner-backup-20260910T120000Z-a1b2c3d4.dump.age)" >&2
  echo "refusing to guess 'latest' - list the bucket yourself and name the exact object." >&2
  exit 2
fi

# The identifier becomes part of both a local filesystem path and an rclone remote path below - an
# unvalidated argument there is an uncontrolled path, not just a display string. Validated against
# backup.sh's own naming convention before use; anything else is refused outright.
identifier_pattern='^runner-backup-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}\.dump\.age$'
if ! [[ "$identifier" =~ $identifier_pattern ]]; then
  echo "invalid backup identifier: '${identifier}'" >&2
  echo "must match ${identifier_pattern} (backup.sh's own naming convention) - refusing to build a local/remote path from an unvalidated value." >&2
  exit 2
fi

: "${PGHOST:?PGHOST is required (already present in the ambient environment for a same-database restore; override via a separate --env-from-file for a cross-target restore - see docs/RUNBOOK.md section 10)}"
: "${PGPORT:?PGPORT is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${RESTORE_EXPECTED_DATABASE:?RESTORE_EXPECTED_DATABASE is required}"
: "${BACKUP_AGE_IDENTITY_FILE:?BACKUP_AGE_IDENTITY_FILE is required}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE is required}"
: "${BACKUP_BUCKET:?BACKUP_BUCKET is required}"
BACKUP_PREFIX="${BACKUP_PREFIX:-runner-backups}"
BACKUP_LOCAL_DIR="${BACKUP_LOCAL_DIR:-/backups}"

log() {
  printf '[restore] %s\n' "$1"
}

# psql/pg_restore take no connection-string argument - PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD
# are picked up automatically by libpq, so the password never appears in `ps`. pg_restore's --dbname
# below gets only the plain database name.

if [ ! -f "$BACKUP_AGE_IDENTITY_FILE" ]; then
  echo "BACKUP_AGE_IDENTITY_FILE does not point at a real file: $BACKUP_AGE_IDENTITY_FILE" >&2
  exit 1
fi

# The real, database-confirmed target name - never just the one PGDATABASE names (a mistyped or
# misdirected PGDATABASE parses just as cleanly as a correct one; only Postgres itself can say which
# database a connection actually landed on).
actual_database="$(psql -tAc 'SELECT current_database()')"
if [ "$actual_database" != "$RESTORE_EXPECTED_DATABASE" ]; then
  echo "PGHOST/PGPORT/PGDATABASE actually connect to database '${actual_database}', but RESTORE_EXPECTED_DATABASE='${RESTORE_EXPECTED_DATABASE}' - refusing." >&2
  echo "this guards against a mistyped or misdirected target; double-check PGHOST/PGPORT/PGDATABASE before retrying." >&2
  exit 4
fi

# Refuses a destructive restore (--clean below) into a target that is not genuinely empty/disposable,
# unless the caller explicitly acknowledges it with a confirmation tied to this specific database and
# backup (see RESTORE_CONFIRM_DESTRUCTIVE's own header comment). A missing `runs` table (a fresh,
# never-migrated database - the normal disaster-recovery case) is treated as empty and needs no
# acknowledgment.
existing_table_count="$(psql -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'runs'")"
if [ "$existing_table_count" != "0" ]; then
  existing_row_count="$(psql -tAc "SELECT count(*) FROM runs")"
  required_ack="${actual_database}:${identifier}"
  if [ "${RESTORE_CONFIRM_DESTRUCTIVE:-}" != "$required_ack" ]; then
    echo "target database '${actual_database}' already has a 'runs' table with ${existing_row_count} row(s) - refusing to run a destructive restore (--clean --if-exists)." >&2
    echo "use a genuinely empty/disposable target database instead, or set RESTORE_CONFIRM_DESTRUCTIVE=${required_ack} if you really mean to overwrite it." >&2
    exit 3
  fi
  log "WARNING: proceeding with a destructive restore into a NON-empty target '${actual_database}' (RESTORE_CONFIRM_DESTRUCTIVE matched) - ${existing_row_count} existing run row(s) will be dropped"
fi

remote_path="${BACKUP_REMOTE}:${BACKUP_BUCKET}/${BACKUP_PREFIX}/${identifier}"
local_encrypted="${BACKUP_LOCAL_DIR}/${identifier}"

mkdir -p "$BACKUP_LOCAL_DIR"

# Printed before anything else runs - the two facts (which backup, which database, both
# database-confirmed) an operator mid-incident needs to be certain about before this proceeds to a
# destructive pg_restore.
log "about to restore ${remote_path} into database '${actual_database}' - Ctrl-C now to abort"
log "downloading ${remote_path}"
rclone copyto "$remote_path" "$local_encrypted"

# --single-transaction (implies --exit-on-error per pg_restore's docs): the whole archive is applied
# atomically, or none of it is. --no-owner --no-privileges: ownership/ACLs are deliberately not
# restored, since the target's roles/grants may not match the source's.
log "decrypting and restoring (--single-transaction --clean --if-exists --no-owner --no-privileges)"
age --decrypt --identity "$BACKUP_AGE_IDENTITY_FILE" "$local_encrypted" \
  | pg_restore --single-transaction --clean --if-exists --no-owner --no-privileges \
      --dbname "$PGDATABASE"

rm -f "$local_encrypted"

log "restore complete: ${identifier}"
