#!/usr/bin/env bash
#
# D4.5 - restores one specific, explicitly-named backup into a target Postgres. Never accepts
# "latest" implicitly - the caller must name the exact object being restored, and this script prints
# that identifier (and the REAL, database-confirmed target name - see below) before touching
# anything, so a restore is never run against the wrong backup, or the wrong database, by accident
# (an operator in the middle of a real incident is exactly who this guards, per the D4.6 runbook this
# script is documented from).
#
# The decrypted plaintext dump is never written to disk, on this side either - age --decrypt pipes
# directly into pg_restore's own stdin, mirroring backup.sh's own "no plaintext ever persisted"
# guarantee in both directions. The restore itself is wrapped in --single-transaction (which
# pg_restore's own docs note implies --exit-on-error) - either every object in the archive is
# applied, or none of them are; there is no partially-restored state this script can leave a
# database in.
#
# Usage: restore.sh <backup-identifier>
#   e.g. restore.sh runner-backup-20260910T120000Z-a1b2c3d4.dump.age
#
# Required env vars (never logged - see log() below):
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD   standard libpq connection variables identifying
#                              the target database to restore INTO. --clean --if-exists below is
#                              destructive against whatever schema already exists there - see the
#                              target-database checks below for what this script requires before
#                              proceeding. Already present in this container's own ambient environment
#                              (docker-compose.backup.yml's own service config, the same values
#                              backup.sh dumps FROM) for the common "restore into the same production
#                              database" case - no override needed at all there. The one case that
#                              genuinely needs a *different* target should override these five
#                              variables via `docker compose run --env-from-file <path>` instead (a
#                              real Compose flag - see docs/RUNBOOK.md section 10), never inline on
#                              the command line. A review finding: an earlier version of this script
#                              instead accepted a single postgresql://user:password@host:port/dbname
#                              TARGET_DATABASE_URL and parsed it with a hand-rolled regex - a raw
#                              `@`/`:`/`/`/`%` character in a real password broke that parser outright
#                              (e.g. `p@ssword` was misparsed as password `p` and host
#                              `ssword@postgres`). Passing the five values directly, with no URL syntax
#                              to disambiguate, removes the parsing step - and the whole class of bug -
#                              entirely.
#   RESTORE_EXPECTED_DATABASE  the database name the caller BELIEVES PGHOST/PGPORT/etc. point at -
#                              a review finding: a database name merely parsed out of the URL string
#                              is not proof of anything (a typo/misdirected connection string would
#                              parse just as cleanly as a correct one). This is cross-checked against
#                              `SELECT current_database()` - what Postgres itself reports the
#                              connection actually landed on - and the restore refuses outright on any
#                              mismatch, before touching rclone/age/pg_restore at all.
#   BACKUP_AGE_IDENTITY_FILE  path to the PRIVATE age identity key file. This is the one secret this
#                              whole D4.5 design deliberately keeps out of the *standing* deployment
#                              (see backup.sh's own header) - it does not live in
#                              docker-compose.backup.yml's own config at all, and is supplied only
#                              here, mounted read-only for the duration of this one restore
#                              invocation, from wherever it is actually kept at rest (offline/a
#                              password manager, the user's own explicit D4.5 decision). It is
#                              genuinely present on this host, temporarily, while a restore runs -
#                              "never touches this host" would overclaim that; "never resides
#                              permanently in the standing deployment" is the accurate guarantee.
#   BACKUP_REMOTE, BACKUP_BUCKET   same rclone remote/bucket backup.sh uploaded to.
# Optional:
#   BACKUP_PREFIX     same default as backup.sh: "runner-backups".
#   BACKUP_LOCAL_DIR  local working directory for the downloaded (still-encrypted) file. Default:
#                     /backups.
#   RESTORE_CONFIRM_DESTRUCTIVE   required only when the target database already has a non-empty
#                     `runs` table - see the target-database check below. Must equal exactly
#                     "<real-database-name>:<backup-identifier>" (the two facts this specific restore
#                     is actually about) - a review finding: a generic RESTORE_CONFIRM_DESTRUCTIVE=yes
#                     could be scripted/copy-pasted once and then silently reused across a
#                     completely different target or backup without ever re-confirming intent. This
#                     script prints the exact string it expects in its own refusal message - never a
#                     value the caller has to compute themselves. Never required for a genuinely
#                     empty/disposable target.

set -euo pipefail

identifier="${1:-}"
if [ -z "$identifier" ]; then
  echo "usage: restore.sh <backup-identifier>  (e.g. runner-backup-20260910T120000Z-a1b2c3d4.dump.age)" >&2
  echo "refusing to guess 'latest' - list the bucket yourself and name the exact object." >&2
  exit 2
fi

# The identifier becomes part of both a local filesystem path and an rclone remote path below - a
# review finding: an unvalidated argument there is an uncontrolled path, not just a display string.
# Validated against exactly backup.sh's own naming convention (see its timestamp/suffix generation)
# before it is used anywhere; anything else is refused outright rather than passed through.
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

# psql/pg_restore below take no connection-string argument at all - PGHOST/PGPORT/PGDATABASE/PGUSER/
# PGPASSWORD are picked up automatically by libpq, so the password never appears in this container's
# own process list (`ps`) either. pg_restore's own --dbname below gets only the plain database name
# (never a password), with the rest of the connection resolved from these same env vars.

if [ ! -f "$BACKUP_AGE_IDENTITY_FILE" ]; then
  echo "BACKUP_AGE_IDENTITY_FILE does not point at a real file: $BACKUP_AGE_IDENTITY_FILE" >&2
  exit 1
fi

# The real, database-confirmed target name - never the one merely named by PGDATABASE itself (a
# review finding: a mistyped/misdirected PGDATABASE parses just as cleanly as a correct one; only
# Postgres itself can say which database a connection actually landed on).
actual_database="$(psql -tAc 'SELECT current_database()')"
if [ "$actual_database" != "$RESTORE_EXPECTED_DATABASE" ]; then
  echo "PGHOST/PGPORT/PGDATABASE actually connect to database '${actual_database}', but RESTORE_EXPECTED_DATABASE='${RESTORE_EXPECTED_DATABASE}' - refusing." >&2
  echo "this guards against a mistyped or misdirected target; double-check PGHOST/PGPORT/PGDATABASE before retrying." >&2
  exit 4
fi

# Refuses a destructive restore (--clean below) into a target that is not genuinely empty/disposable,
# unless the caller explicitly acknowledges it with a confirmation tied to THIS specific database and
# THIS specific backup - never a generic "yes" (see RESTORE_CONFIRM_DESTRUCTIVE's own header comment
# for why). A missing `runs` table (a fresh, never-migrated database - the normal disaster-recovery
# case) is treated as empty and needs no acknowledgment.
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

# Printed before anything else runs - the two facts (which backup, which database - both now
# database-confirmed, never just parsed) an operator mid-incident needs to be certain about before
# this proceeds to a destructive pg_restore.
log "about to restore ${remote_path} into database '${actual_database}' - Ctrl-C now to abort"
log "downloading ${remote_path}"
rclone copyto "$remote_path" "$local_encrypted"

# --single-transaction (which itself implies --exit-on-error, per pg_restore's own docs): the whole
# archive is applied atomically, or none of it is - never a partially-restored database if a later
# statement fails. --no-owner --no-privileges: the target's own roles/grants are never assumed to
# match the source's; ownership/ACLs are deliberately not restored.
log "decrypting and restoring (--single-transaction --clean --if-exists --no-owner --no-privileges)"
age --decrypt --identity "$BACKUP_AGE_IDENTITY_FILE" "$local_encrypted" \
  | pg_restore --single-transaction --clean --if-exists --no-owner --no-privileges \
      --dbname "$PGDATABASE"

rm -f "$local_encrypted"

log "restore complete: ${identifier}"
