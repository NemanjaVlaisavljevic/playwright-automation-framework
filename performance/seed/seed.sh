#!/bin/sh
# D4.4.1a - one-shot performance-baseline seed entrypoint. Never invoked directly against the real
# deployment; always via `docker compose run --rm performance-seed` under the isolated
# `runner-performance` Compose project - see deploy/docker-compose.performance.yml's own header
# comment for the full documented lifecycle. Idempotent/re-runnable: safe to invoke repeatedly
# against the same stack (e.g. once per D4.4.2 measurement pass) without a fresh `down -v` between
# runs.
set -eu

# 1. Fail-closed: refuse to run against anything but the dedicated performance database,
# regardless of how this container was actually invoked - a real safety net independent of
# whether the Compose project name/env wiring happens to be correct. Runs before anything else -
# no file or database write happens if this check fails.
actual_db=$(psql -Atqc "SELECT current_database();")
if [ "$actual_db" != "runner_performance" ]; then
  echo "performance-seed: refusing to run - current_database()=$actual_db, expected runner_performance" >&2
  exit 1
fi

# 2. Write the real artifact files BEFORE the database transaction below - a DB row referencing a
# file must never be committed before the file genuinely exists on disk. If the SQL step below
# then fails for any reason, only a harmless orphan file remains (deterministic path/content - the
# next, idempotent pass simply overwrites it), never a DB row pointing at nothing.
: "${ARTIFACTS_DIR:?ARTIFACTS_DIR must be set}"
echo "performance-seed: writing matching real artifact files under $ARTIFACTS_DIR..."
i=1
while [ "$i" -le 5 ]; do
  run_id=$(printf 'perf-run-%04d' "$i")
  dir="$ARTIFACTS_DIR/$run_id"
  mkdir -p "$dir"
  # A real, valid 1x1 PNG (not a zero-byte placeholder claiming to be one) - see assets/fixture.png
  # and this directory's own README for provenance.
  cp assets/fixture.png "$dir/screenshot-1.png"
  i=$((i + 1))
done
# Measured, never hardcoded - guards against silent drift if this fixture is ever replaced with a
# differently-sized one and a stale constant elsewhere is forgotten.
real_size=$(wc -c < assets/fixture.png | tr -d ' ')

# 3. The one seeding transaction: idempotently replaces every seed-owned row (never a global
# TRUNCATE - see seed.sql's own comment), then runs a runtime acceptance check on the seeded
# perf-replay-run event timeline before committing.
echo "performance-seed: seeding 500 terminal runs into $actual_db (artifact real_size=$real_size)..."
psql -v ON_ERROR_STOP=1 -v real_size="$real_size" -f seed.sql

echo "performance-seed: done."
