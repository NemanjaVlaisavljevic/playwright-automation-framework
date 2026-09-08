#!/bin/sh
# D4.4.1c - inserts or deletes perf-hold-open-run (the RUNNING fixture sse.js's connection-cap
# scenario needs). Never invoked directly against the real deployment; always through
# deploy/docker-compose.performance.yml's `performance-seed-live` service, under the isolated
# `runner-performance` Compose project, gated on `runner-service: condition: service_healthy` -
# see seed-live-run.sql's own header comment for why that ordering matters.
#
#   docker compose ... run --rm performance-seed-live ./seed-live-run.sh insert
#   docker compose ... run --rm performance-seed-live ./seed-live-run.sh delete
set -eu

action="${1:?usage: seed-live-run.sh <insert|delete>}"

# Fail-closed, same as seed.sh's own check - never trust the Compose project/env wiring alone.
actual_db=$(psql -Atqc "SELECT current_database();")
if [ "$actual_db" != "runner_performance" ]; then
  echo "seed-live-run: refusing to run - current_database()=$actual_db, expected runner_performance" >&2
  exit 1
fi

case "$action" in
  insert)
    psql -v ON_ERROR_STOP=1 -f seed-live-run.sql
    ;;
  delete)
    # perf-hold-open-run was never a real run RunService orchestrated (no real process, no
    # RUN_FINISHED it will ever emit on its own) - nothing else in the application ever cleans it
    # up, so the SSE scenario's own teardown does so explicitly.
    psql -v ON_ERROR_STOP=1 -c "DELETE FROM runs WHERE run_id = 'perf-hold-open-run';"
    ;;
  *)
    echo "seed-live-run: unknown action '$action' - expected insert|delete" >&2
    exit 1
    ;;
esac
