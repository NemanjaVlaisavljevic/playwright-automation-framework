# Operational runbook (D4.6)

Real, exercised procedures for operating this deployment - not a theoretical checklist. Every command
below is either taken directly from a file already in this repo (`deploy/docker-compose.yml`,
`deploy/backup/*.sh`, `deploy/systemd/*`) or was run for real during this phase's own verification
pass (see `docs/RELEASE_EVIDENCE.md`'s D4.6 section). For *why* a mechanism is built the way it is,
see `docs/DEPLOYMENT_ARCHITECTURE.md` - this document is deliberately just the *what to do*.

All commands below assume a shell on the production host, in the repo's `deploy/` directory, with a
real `deploy/.env` present (copied from `deploy/.env.example` and filled in - never committed). A
handful of commands (sections 6.A, 7, 10, 12) need `POSTGRES_USER`/`POSTGRES_DB` - **never `source
deploy/.env` into the operator's own shell to get them**: a Compose `.env` file is not guaranteed to
be safe, valid shell syntax, and sourcing it would needlessly export every other secret in that file
(the GitHub OAuth client secret, the DB password) into the operator's own shell history/environment
for no reason. Those two values already exist as real environment variables *inside* the `postgres`
container itself (`docker-compose.yml`'s own `POSTGRES_USER`/`POSTGRES_DB` service config) - every
command below that needs them resolves them there instead, via `exec postgres sh -c '...'` with the
variable references left unexpanded until they reach that inner shell.

## 0. Authenticating as admin for `curl` (prerequisite for several sections below)

There is **no API token/bearer-credential mechanism in this deployment at all** - `ROLE_ADMIN` is
reachable only via a real GitHub OAuth2 browser login that produces a session cookie
(`docs/DEPLOYMENT_ARCHITECTURE.md` section 4). A `curl` command against an admin-only endpoint (disk
usage, retention) needs that same session, extracted from a real logged-in browser.

**Never put the session cookie or CSRF token in a `-b`/`-H` command-line argument** - either would
sit in the operator's own shell history, and briefly in the process list, for the duration of a real
admin session. Use `curl`'s own `-K`/`--config` file instead:

1. Log in through the dashboard in a real browser ("Login with GitHub" control).
2. Open browser devtools -> Application/Storage -> Cookies for the site, and copy the values of two
   cookies: `JSESSIONID` (the session) and `XSRF-TOKEN` (the CSRF token - `SecurityConfig`'s own
   `CookieCsrfTokenRepository.withHttpOnlyFalse()`, deliberately readable from script so the SPA
   itself can prime it the same way).
3. Write them into a locked-down, temporary curl config file - with an editor, not a shell command
   that would itself put the values on a command line or in history. Paste this whole block as one
   unit into an interactive shell - it runs in a **subshell** (`( ... )`) with `set -euo pipefail`, an
   `EXIT`-only cleanup function, and separate `HUP`/`INT`/`TERM` handlers that actually `exit` (never a
   single `trap '...' EXIT HUP INT TERM`, which would catch those signals and let execution continue
   with the next command instead of stopping - risking, e.g., a Ctrl-C mid-`read -rsp` deleting the
   file and then continuing into recreating it with an empty value). `set -euo pipefail` is also
   required - without it, `curl -K ... | jq`'s exit status is `jq`'s, not `curl`'s, so a failed `curl`
   inside a pipeline can be masked by a successful `jq`:
   ```bash
   (
     set -euo pipefail

     curl_config="$(mktemp)"
     chmod 600 "$curl_config"
     cleanup() {
       rm -f "$curl_config"
     }
     trap cleanup EXIT
     trap 'exit 129' HUP
     trap 'exit 130' INT
     trap 'exit 143' TERM

     "${EDITOR:-vi}" "$curl_config"
     # contents for a GET (e.g. section 8's disk-usage check):
     #   url = "https://<domain>/api/v1/disk/usage"
     #   cookie = "JSESSIONID=<value>"
     # ...or for a state-changing POST (e.g. section 9's real retention sweep - Spring Security only
     # guards unsafe HTTP methods, so a plain GET never needs the CSRF header at all):
     #   url = "https://<domain>/api/v1/retention/run"
     #   request = "POST"
     #   cookie = "JSESSIONID=<value>; XSRF-TOKEN=<value>"
     #   header = "X-XSRF-TOKEN: <value>"
     curl -K "$curl_config"
   )
   ```
   `"${EDITOR:-vi}" "$curl_config"` pauses the subshell for the operator to type the config contents
   above and save; `curl` then runs automatically once the editor exits. `HUP`/`INT`/`TERM` each
   `exit` with the signal's own conventional exit code (128+signal, e.g. `130` for `SIGINT`), which
   still triggers the `EXIT` trap so `cleanup()` runs, but stops the script instead of continuing.
   Every `curl -K ...` example elsewhere in this runbook (sections 8, 9) follows this same subshell
   pattern, abbreviated there to just the two relevant config lines.

Cookie values expire with the session (4h idle timeout, `application.yml`'s own
`server.servlet.session.timeout`) - repeat the login/copy step if a command starts returning `401`.

## 1. Deploy (fresh)

```bash
git clone <repo-url> && cd playwright-automation-framework
cp deploy/.env.example deploy/.env
# fill in deploy/.env: POSTGRES_PASSWORD, RUNNER_SECURITY_GITHUB_CLIENT_ID/_SECRET,
# RUNNER_SECURITY_ADMIN_GITHUB_ID at minimum - see that file's own comments for every var and where
# each value comes from (e.g. the GitHub OAuth App registration).
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up --build -d
```
Flyway migrations run automatically at `runner-service` startup (see section 14) - no manual DB setup
step. Confirm the stack is actually healthy before considering the deploy done (section 5).

## 2. Upgrade / redeploy (after a code or config change)

```bash
git pull
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up --build -d
```
Rebuilds and recreates only the images/containers whose content actually changed. Flyway migrations
re-run automatically (a no-op for any migration already applied - safe every time). Confirm health
after (section 5).

## 3. Restarting the service

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env restart runner-service
```
Safe at any time, including mid-run: any run left non-terminal by the restart is recovered to
`ERROR` by D2.5's own startup recovery pass (see section 6 for what it looks like when *that* itself
fails). A run's own client (the dashboard) surfaces the terminal `ERROR` status and the SSE stream's
own reconnect-and-replay behavior, not a silent hang.

## 4. Rollback - a real, currently-unclosed gap

**Stated honestly, not papered over**: this deployment has no image versioning or registry today.
`APP_VERSION` (`deploy/.env.example`) only labels the `service.version` field on structured log
lines (`deploy/runner-service/Dockerfile`) - it does not tag the built image itself, and no built
image is ever pushed anywhere. There is no fast image-swap rollback, and the current image is not
preserved anywhere once replaced - `git checkout` + rebuild (below) is the only mechanism that exists,
and it is only ever safe under the condition in the next paragraph.

**A `git checkout` + rebuild rollback is only safe when the schema the new code already applied is
backward-compatible with the older code being rolled back to.** Every Flyway
migration a bad deploy already ran (section 14) stays applied to the real database regardless of
which application version is running afterward; Flyway has no notion of "roll a migration back," and
an older application binary was never written against columns/constraints a later migration added. A
schema-changing upgrade that turns out to need a rollback is therefore **not** a case for a plain
code-only rollback unless the migration was deliberately written to be backward-compatible (additive,
nullable, no renamed/dropped column the old code still reads/writes).

**Before any upgrade that changes the schema** (section 2, whenever the commit being deployed adds a
new file under `runner-service/src/main/resources/db/migration/`): take a confirmed, verified backup
first (section 10) and record the exact commit SHA being deployed (`git rev-parse HEAD` - keep this
noted somewhere outside the repo itself, e.g. the change/incident log this runbook doesn't own) before
running the upgrade. If that upgrade then needs to be rolled back and its own migration was **not**
backward-compatible, a code-only rollback is not an option - the safe path is a controlled restore of
the pre-upgrade backup (section 10) during a real maintenance window, not a live schema downgrade
attempt (Flyway has no supported mechanism for one).

## 5. Health checks

- `GET /actuator/health/liveness` - JVM/process alive only, never composed with anything
  self-resolving (must never flap to `DOWN` over a transient Postgres/disk issue, or Docker's own
  healthcheck-driven restart policy would restart a perfectly recoverable process).
- `GET /actuator/health/readiness` - composed of four sub-checks, each with its own precise failure
  semantics (`application.yml`'s own `management.endpoint.health.group.readiness.include`):
  - `db` - Spring Boot's own DataSource health indicator. `DOWN` on a real Postgres outage.
  - `recovery` - `OUT_OF_SERVICE` while D2.5's own startup recovery pass hasn't finished yet (should
    be brief; see section 6 if it never clears).
  - `disk` - `OUT_OF_SERVICE` when free disk has dropped below the configured threshold; `DOWN` only
    if the probe itself cannot even determine free space (a genuinely broken filesystem, not just a
    full one).
  - `runnerAvailability` - `OUT_OF_SERVICE` while the runner is `DEGRADED` (a process tree that could
    not be killed - self-clears once the reaper confirms it actually exited).

Both are public through Caddy (`deploy/web/Caddyfile`); every other `/actuator/*` path 404s. **Neither
endpoint ever reveals which sub-check failed or why, to anyone** - `application.yml` sets both
`management.endpoint.health.show-details: never` and `show-components: never` explicitly, so even a
`DOWN`/`OUT_OF_SERVICE` response body carries only the bare aggregate status, no per-component
breakdown at all (this is not role-gated - `never` means never, not "never to anonymous callers"):
```bash
curl -s https://<domain>/actuator/health/readiness | jq
# {"status":"DOWN"} - and nothing more specific than that, from this endpoint alone.
```
Finding *which* sub-check is actually responsible always means going elsewhere: the disk-usage
endpoint (section 8) for `disk`, `docker compose logs runner-service`/`docker compose logs postgres`
for `db`/`recovery`, and direct Postgres diagnostics (section 12) to confirm reachability.

## 6. Recovering from a crash-loop

Two structurally different fail-closed startup failures can both look like the same symptom
(`docker compose ps` shows `runner-service` endlessly restarting) - distinguish them from the
container's own logs before doing anything else:
```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs runner-service --tail 200
```

**A. D2.5 startup recovery failure** (`RunRecoveryService`) - look for `Startup run-recovery pass
could not load the non-terminal runs to recover` (the initial DB read itself failed - almost always a
Postgres reachability problem, see section 12) or `Startup run-recovery pass failed to recover run(s)
[...]` (the DB read succeeded, but writing at least one specific run's own recovery failed). Either
throws out of the startup `ApplicationRunner`, which fails the whole Spring Boot context before any
listening socket opens - `restart: unless-stopped` then retries the entire pass on the next restart.
This is **idempotent per run**: an already-recovered run is already terminal and is never
re-attempted, so each restart only ever retries the run(s) that are genuinely still failing - a loop
here means a *specific*, persistent problem, not random flakiness.
- If the first message appears: fix Postgres reachability first (section 12) - nothing else about
  this failure mode is actionable until the DB read itself can succeed.
- If the second message appears: it names the failing run id(s). **Never hand-write a `SQL UPDATE`
  against `runs` to force it terminal.** The recovery write
  (`RunRecoveryService`/`RunLifecycleCoordinator#finishIfLive`) is one atomic transaction that also
  inserts the run's `RUN_FINISHED` event, advances `next_event_sequence`, and bumps the
  optimistic-lock `version` column together. A raw `UPDATE runs SET status = 'ERROR' ...` skips all
  three: the row becomes terminal so retries stop, but no `RUN_FINISHED` event ever exists, so any SSE
  client replaying that run's history waits forever for a terminal event that will never arrive -
  trading one visible failure (the crash-loop) for a quieter, worse one.

  The recovery write is a normal, constraint-checked transition - a failure here almost always means
  a genuine, specific data problem with that one row (get the full exception first, not just the
  aggregate message):
  ```bash
  docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs runner-service \
    | grep -A 30 "Failed to recover run <run-id>"
  ```
  Then open a real interactive `psql` session to inspect the row (a plain interactive prompt, not a
  one-liner `-c` string, sidesteps having to quote a run id through two nested layers of shell):
  ```bash
  docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -it postgres \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
  ```
  ```sql
  SELECT run_id, status, next_event_sequence, version FROM runs WHERE run_id = '<run-id>';
  ```
  The correct fix is always to resolve *that specific* underlying problem (e.g. a corrupted
  `next_event_sequence`, an orphaned/duplicate event row already occupying the sequence number the
  transition needs next) - a minimal, targeted correction of the actual invariant violation the
  exception names, never a jump straight to `status = 'ERROR'` - then simply restart
  `runner-service` again (section 3): `RunRecoveryService` retries the identical atomic transition
  automatically for any run still non-terminal, and this time it succeeds through the normal,
  correct path (real `RUN_FINISHED` event, real sequence/version bump).

  **If the specific problem cannot be identified and fixed this way, this is a genuine, currently
  unclosed gap**: there is no supported "break-glass" tool today that can force a stuck run terminal
  through the same atomic `RunLifecycleStore` path outside of a real, successful `finishIfLive` call.
  Building one (an admin-only operation that performs the identical transaction) is a legitimate
  future improvement; until it exists, a run stuck this way needs the underlying data problem actually
  understood and fixed, not bypassed.

**B. D3.2 fail-closed security startup failure** (`RunnerSecurityEnvironmentPostProcessor`,
`AdminGithubAllowlist`) - look for `refusing to start` in the logs. Three distinct causes, all fixed
the same way (correct `deploy/.env`, then redeploy - section 2):
- Missing `RUNNER_SECURITY_GITHUB_CLIENT_ID`/`_SECRET` under `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`.
- A non-`Secure` session cookie under `PORTFOLIO` (`SESSION_COOKIE_SECURE=false` left set, or unset
  with no real TLS in front) - see `deploy/.env.example`'s own comment on when this override is
  actually appropriate (never in real production).
- `RUNNER_SECURITY_ADMIN_GITHUB_ID` blank or not a real numeric GitHub account id.

Both failure classes abort **before any listening socket opens** - this is deliberate (see
`docs/DEPLOYMENT_ARCHITECTURE.md` section 4), not a bug to work around.

## 7. Credential/secret rotation

- **`POSTGRES_PASSWORD`** - the Postgres image only reads this env var to set up the superuser on a
  *brand-new, empty* data directory; on the real, already-initialized `pgdata` volume it is silently
  ignored on every later start. Rotating it for real needs an actual password change inside Postgres
  itself, not just an env var edit - and **never a plain `ALTER USER ... WITH PASSWORD '<value>'`
  one-liner**: typing the real new password into a shell command leaves it sitting in that shell's own
  history file and briefly visible in the process list (`ps`), and a special character in the password
  would break the SQL string entirely. `psql`'s own `\password` meta-command avoids both problems - it
  prompts twice, locally, and sends only the resulting hash, never the plaintext, as a SQL literal:
  1. Stop `runner-service` first (its own pooled connections stay valid through a password change,
     but a stopped app avoids any window where it might open a *new* connection with the
     about-to-be-stale password mid-rotation):
     ```bash
     docker compose -f deploy/docker-compose.yml --env-file deploy/.env stop runner-service
     ```
  2. Open a real interactive session (again resolving `POSTGRES_USER` inside the container, never
     the operator's own shell):
     ```bash
     docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -it postgres \
       sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
     ```
  3. At the `psql` prompt:
     ```
     \password
     ```
     Enter and confirm the new password when prompted - never typed as a command-line argument.
  4. Update `POSTGRES_PASSWORD` in `deploy/.env` to the same new value.
  5. Recreate `runner-service` so its own connection string picks up the change:
     ```bash
     docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d runner-service
     ```
  6. Confirm it actually worked - the `db` sub-indicator itself is never directly visible (section
     5's own `show-details: never`/`show-components: never`), so check three things instead: the
     *aggregate* readiness status is `UP`, `runner-service`'s own logs show no new
     DB/HikariCP connection errors since the restart, and a direct connectivity check against
     Postgres itself succeeds:
     ```bash
     curl -s https://<domain>/actuator/health/readiness
     docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs runner-service --tail 50
     docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec postgres \
       sh -c 'pg_isready -U "$POSTGRES_USER"'
     ```
- **`RUNNER_SECURITY_GITHUB_CLIENT_SECRET`** - rotate in the GitHub OAuth App's own settings first,
  then update `deploy/.env` and redeploy. A stale/wrong value is not caught at startup (only
  *presence* is fail-closed-checked, section 6.B) - it surfaces as OAuth login failures instead; a
  real login attempt right after rotating is the actual verification.
- **`RUNNER_SECURITY_ADMIN_GITHUB_ID`** - update `deploy/.env`, redeploy. Comma-separated for more
  than one admin.
- **The backup `age` keypair** (`BACKUP_AGE_RECIPIENT` + the offline private identity - see
  `docs/DEPLOYMENT_ARCHITECTURE.md` section 7): generate a fresh keypair offline (`age-keygen`),
  update `BACKUP_AGE_RECIPIENT` in `deploy/.env` to the new public key, redeploy the backup overlay.
  **Every backup already uploaded under the OLD recipient can only ever be decrypted with the OLD
  identity** - keep the old identity file safely archived (never delete it) until every backup
  encrypted under it has aged out of the bucket's own retention policy, or those backups become
  permanently unrestorable.
- **rclone/B2 credentials** (`BACKUP_RCLONE_CONFIG_FILE`) - rotate in the B2 web console first, then
  update the real rclone config file that path points at on the host (never regenerate it from a
  template in this repo - real credentials never touch version control).

## 8. Checking free disk

Run the full subshell block from section 0 (`$curl_config` only exists for the lifetime of that one
block - there is nothing left to reuse from an earlier run), with these config contents:
`url = "https://<domain>/api/v1/disk/usage"` + `cookie = "JSESSIONID=<value>"`:
```bash
(
  set -euo pipefail
  curl_config="$(mktemp)"
  chmod 600 "$curl_config"
  cleanup() { rm -f "$curl_config"; }
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  "${EDITOR:-vi}" "$curl_config"
  curl -K "$curl_config" | jq
)
```
Returns `runnerDataBytes`/`usableFreeBytes`/`databaseBytes`/`diskMinFreeBytes`/`runMaxDiskBytes`/
`belowThreshold` - the same accounting `POST /api/v1/runs`'s own D4.2 submit-time guard checks before
accepting a new run. Readiness's own `disk` sub-indicator (section 5) is the automated version of
this same check - reach for this endpoint when you need the real numbers behind that status, not just
whether it is `OUT_OF_SERVICE`.

## 9. Manual retention triggers

Same subshell block as section 0, run once per call with its own config contents (each run's
`$curl_config` is cleaned up the moment that block exits - nothing persists between the two calls
below):
```bash
# Dry run - what WOULD be deleted, touches nothing. Config:
#   url = "https://<domain>/api/v1/retention/preview"
#   cookie = "JSESSIONID=<value>"
(
  set -euo pipefail
  curl_config="$(mktemp)"
  chmod 600 "$curl_config"
  cleanup() { rm -f "$curl_config"; }
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  "${EDITOR:-vi}" "$curl_config"
  curl -K "$curl_config" | jq
)

# The real sweep - needs the CSRF header too. Config:
#   url = "https://<domain>/api/v1/retention/run"
#   request = "POST"
#   cookie = "JSESSIONID=<value>; XSRF-TOKEN=<value>"
#   header = "X-XSRF-TOKEN: <value>"
(
  set -euo pipefail
  curl_config="$(mktemp)"
  chmod 600 "$curl_config"
  cleanup() { rm -f "$curl_config"; }
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  "${EDITOR:-vi}" "$curl_config"
  curl -K "$curl_config" | jq
)
```
Both endpoints carry their own independent rate limit (`runner.retention-rate-limit`, 10/hour default
- `docs/DEPLOYMENT_ARCHITECTURE.md` section 6) - a `429` here means exactly that, not a real failure.
The scheduled sweep (`RetentionScheduler`, hourly by default) already runs this automatically; reach
for a manual trigger only to force an out-of-cycle cleanup or to inspect what the next automatic sweep
would do.

## 10. Backup and restore

Full design in `docs/DEPLOYMENT_ARCHITECTURE.md` section 7 - this section is only the *procedure*.

**Routine backups** run automatically via a systemd timer, not cron: `Persistent=true` catches a run
missed while the host was asleep/down, plus a real per-unit timeout, an unambiguous unit exit status,
and journald evidence for every run with no separate log file to manage. Three unit files, not two -
`runner-backup-alert@.service` fires only
once the main unit's own bounded retries are genuinely exhausted (see below). Install once, on the
real production host:
```bash
sudo cp deploy/systemd/runner-backup.service deploy/systemd/runner-backup.timer \
  'deploy/systemd/runner-backup-alert@.service' /etc/systemd/system/
# Edit /etc/systemd/system/runner-backup.service's own WorkingDirectory first - it must point at
# this host's real deploy/ checkout path, not the placeholder the committed file ships with.
sudo systemctl daemon-reload
sudo systemctl enable --now runner-backup.timer
```
Check it's actually scheduled, and inspect any run's own output:
```bash
systemctl list-timers runner-backup.timer
journalctl -u runner-backup.service --since "1 day ago"
```

**A missed-run retry, and how failure actually gets noticed.** `Persistent=true` above fires a missed
backup right after the host boots, but Postgres itself may not be reachable at that exact moment yet;
`runner-backup.service` allows up to 5 total attempts (the initial run plus up to 4 restarts) within
any rolling hour, 5 minutes apart (`Restart=on-failure`/`RestartSec=300`/`StartLimitIntervalSec`/
`StartLimitBurst`) before giving up, rather than a single failed attempt silently waiting a full day
for the next scheduled run. **journald alone is not an alert** - nothing pages an operator just
because a log line exists. Once retries are genuinely exhausted,
`OnFailure=runner-backup-alert@%n.service` fires a companion unit that today only logs at `crit`
priority (`journalctl -p crit` surfaces it distinctly) - deliberately minimal; there's no paging
infrastructure in this project today. `runner-backup-alert@.service`'s own header names two
close-to-zero-infrastructure options worth adding if real paging matters: a dead-man's-switch ping
service, or a webhook curl into whatever chat channel is already used day to day.

**A manual, one-off backup** (the identical command the timer itself runs):
```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.backup.yml \
  --env-file deploy/.env run --rm backup
```

**Restore against the real production database - a full, ordered procedure, never just the one
`restore.sh` command in isolation.** Running the restore alone, with `runner-service` still up, lets
its own live traffic - HikariCP connections, an in-flight run write - race the destructive
`pg_restore --clean` and corrupt the outcome. Always a deliberate, one-off invocation, never a
standing service (the private `age` identity is mounted only for this one command, from wherever it
is actually kept at rest - see section 7 above).

1. **Take a safety backup of the current (about-to-be-overwritten) state first** - even a restore
   being done *because* of a real incident should not destroy the last-known state without a way
   back:
   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.backup.yml \
     --env-file deploy/.env run --rm backup
   ```
2. **Stop `runner-service`** - Postgres itself stays up (the restore connects to it):
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env stop runner-service
   ```
3. **Run the restore.** `restore.sh` refuses to guess "latest" (name the real object, e.g. via
   `rclone lsf` against the configured remote) and refuses a destructive restore into a non-empty
   target without an acknowledgment tied to the exact database and backup being restored. `restore.sh`
   connects via the standard libpq `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` variables,
   never a connection-string argument - a raw `@`/`:`/`/`/`%` character in a real password would break
   URL parsing (e.g. `p@ssword` misparsed as password `p`, host `ssword@postgres`); passing the five
   values directly removes that whole class of bug. Two cases:
   - **Restoring into the same production database** (the ordinary disaster-recovery case) - no
     override needed at all: `restore.sh` picks up `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/
     `PGPASSWORD` directly from the `backup` service's own ambient environment (the identical values
     `backup.sh` dumps *from*, defined once in `docker-compose.backup.yml`, never typed here):
     ```bash
     docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.backup.yml \
       --env-file deploy/.env run --rm \
       -e RESTORE_EXPECTED_DATABASE=runner \
       -e BACKUP_AGE_IDENTITY_FILE=/secrets/identity.txt \
       -e RESTORE_CONFIRM_DESTRUCTIVE=runner:<backup-identifier> \
       -v /path/to/offline/identity.txt:/secrets/identity.txt:ro \
       backup ./restore.sh <backup-identifier>
     ```
   - **Restoring into a genuinely different target** (a different host/database, e.g. a
     disaster-recovery drill into a scratch instance) - override all five `PG*` variables at once via
     Compose's own `run --env-from-file` (a real flag, not `-e`), never as a literal `-e` argument (the
     password would sit in the operator's own shell history and briefly in the process list). The real
     password still must never even be *typed* as one on a visible line: read it with `read -rsp` (a
     silent, non-echoing prompt - only the `read` invocation itself, never the typed value, ever
     becomes a history line). Run the whole procedure inside a **subshell** (`( ... )`) with
     `set -euo pipefail`, an `EXIT`-only cleanup function, and separate `HUP`/`INT`/`TERM` handlers that
     `exit` - never a single `trap '...' EXIT HUP INT TERM`, which would catch those signals and let
     execution continue with the next command instead of stopping (e.g. a Ctrl-C landing mid-`read -rsp`
     could delete `$target_env`, then continue into recreating it with an **empty** `PGPASSWORD` and
     attempting the restore anyway):
     ```bash
     (
       set -euo pipefail

       target_env="$(mktemp)"
       chmod 600 "$target_env"
       cleanup() {
         unset target_pgpassword
         rm -f "$target_env"
       }
       trap cleanup EXIT
       trap 'exit 129' HUP
       trap 'exit 130' INT
       trap 'exit 143' TERM

       read -rsp 'Enter the target PGPASSWORD: ' target_pgpassword
       echo
       {
         echo 'PGHOST=scratch-postgres.internal'   # edit to the real target host
         echo 'PGPORT=5432'
         echo 'PGDATABASE=scratch_db'               # edit to the real target database
         echo 'PGUSER=runner'                        # edit to the real target user
         printf 'PGPASSWORD=%s\n' "$target_pgpassword"
       } > "$target_env"
       docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.backup.yml \
         --env-file deploy/.env run --rm --env-from-file "$target_env" \
         -e RESTORE_EXPECTED_DATABASE=scratch_db \
         -e BACKUP_AGE_IDENTITY_FILE=/secrets/identity.txt \
         -e RESTORE_CONFIRM_DESTRUCTIVE=scratch_db:<backup-identifier> \
         -v /path/to/offline/identity.txt:/secrets/identity.txt:ro \
         backup ./restore.sh <backup-identifier>
     )
     ```
     Because `PGPASSWORD` is written to `$target_env` as a literal `KEY=VALUE` line (`printf '%s'`,
     never URL-assembled), any password - including one containing `@`, `:`, `/`, or `%` - passes
     through unchanged, with no encoding step to get wrong.
   If the target already has data and `RESTORE_CONFIRM_DESTRUCTIVE` above is omitted or wrong,
   `restore.sh` prints the exact value it requires in its own refusal message - never something to
   guess or reuse from a previous incident.
4. **Restart `runner-service`**:
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env start runner-service
   ```
5. **Wait for Flyway and readiness.** The restored `flyway_schema_history` reflects whatever
   application version the *backup itself* came from - not necessarily the version currently
   deployed. Flyway's own normal startup behavior handles this correctly, it just needs to actually
   be watched, not assumed: it (1) checks the restored `flyway_schema_history`, (2) applies any
   migration newer than what the restore brought back that the currently-deployed code already has
   (a real, valid outcome - the backup predating the running code by even one migration is not
   itself an error), and (3) must complete successfully before the service reports ready at all.
   Confirm all of that actually happened, not just that the container is running:
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs runner-service --tail 50
   # look for real "Migrating schema..."/"Successfully applied N migrations" lines if any new
   # migration was applied, and confirm no Flyway error appears.
   curl -s https://<domain>/actuator/health/readiness
   ```
6. **Verify real content, not just a clean startup** - a specific known run (one that existed in the
   restored backup) and its event count, plus a couple of ordinary public endpoints:
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -it postgres \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
   ```
   ```sql
   SELECT run_id, status FROM runs WHERE run_id = '<a-known-run-id>';
   SELECT count(*) FROM run_events WHERE run_id = '<a-known-run-id>';
   ```
   ```bash
   curl -s https://<domain>/api/v1/capabilities
   curl -s https://<domain>/api/v1/runs | head -c 200
   ```

**RPO/RTO**: RPO is bounded by the backup schedule above (~24h, daily). RTO has not yet been measured
against a real production host or a real off-site round trip - see
`docs/DEPLOYMENT_ARCHITECTURE.md` section 7's own "Required for the D5 acceptance pass" list; this
runbook's own final acceptance checklist (section 13) includes measuring it for real.

## 11. Diagnosing a failed run

1. The dashboard's own run details page (`/runs/<id>`) - status, per-step failure detail, and (for a
   completed run) a download-log link and per-test artifact links, all already built for exactly this
   (see the main `runner-dashboard` UI, not a separate diagnostic tool).
2. Backend-side context the dashboard doesn't show directly - structured JSON logs (`PORTFOLIO`
   profile), correlated by `runId`:
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs runner-service \
     | grep '"runId":"<run-id>"'
   ```
3. Raw files on the `runner-data` volume, if a download link itself is failing:
   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec runner-service \
     ls -la /data/runner-artifacts /data/runner-logs /data/runner-events/raw
   ```

## 12. Postgres unavailable

Readiness's own `db` check contributes to the aggregate status - it does **not** surface as its own
visible component (section 5's own `show-details: never`/`show-components: never`). A real Postgres outage means readiness
goes `DOWN` overall, with no component name attached - confirm the cause is really Postgres via logs
and a direct connectivity check, not the readiness response body itself:
```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs postgres --tail 100
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec postgres \
  sh -c 'pg_isready -U "$POSTGRES_USER"'
```
`runner-service` itself keeps retrying its own connection pool (HikariCP) rather than crashing outright
for a *transient* outage reached after startup - a genuine, sustained outage instead surfaces as
readiness staying `DOWN` and every run-submission request failing. Once Postgres recovers, any run
that was genuinely mid-execution when the outage began is picked up by the same D2.5 recovery pass
described in section 6.A on `runner-service`'s own next restart if one was needed - a transient outage
`runner-service` itself survived without restarting needs no separate recovery step at all.

## 13. Final production Compose acceptance checklist

The D5 acceptance pass this whole D4 series has been building toward - every item below must be
checked against the *real* production host and domain, not the local dev stack, before calling a
deployment genuinely done (mirrors the same round-based, real-command-and-real-output discipline
`docs/DEPLOYMENT_ARCHITECTURE.md`'s own "## Verified" section already established for D0/D1):

- [ ] Real HTTPS via Caddy's own ACME issuance against the real domain, plus the D3.3 security headers
      (CSP/Permissions-Policy) present on a real response.
- [ ] Login -> launch a run -> cancel it -> logout, through the real GitHub OAuth App (not the
      WireMock-stubbed `OAuthFlowE2eTest`).
- [ ] Anonymous read access to run history/results/SSE, with no session at all.
- [ ] A real, authenticated admin launch and cancel.
- [ ] Live SSE streaming through Caddy (not buffered/delayed) during a real run.
- [ ] Artifact download for a real failed test's screenshot/trace.
- [ ] A real hard-kill (`docker kill -s SIGKILL`) of `runner-service` mid-run, followed by the D2.5
      recovery pass actually reaching `ERROR` for that run on restart (the same scenario
      `docs/RELEASE_EVIDENCE.md`'s D2.6 section already proved locally - now against the real host).
- [ ] A real Postgres restart while `runner-service` is up, confirming section 12's own recovery
      behavior for real.
- [ ] A real backup -> restore drill against the real bucket (see section 10's own RPO/RTO note, and
      `docs/DEPLOYMENT_ARCHITECTURE.md` section 7's own "Required for the D5 acceptance pass" list) -
      not the local `rclone type=local` stand-in `BackupRestoreDrillTest` uses.
- [ ] **The real systemd layer itself, not just the Compose command it runs.** D4.6's verification ran
      `runner-backup.service`'s exact `ExecStart`/`ExecStopPost` command lines directly (proving the
      backup mechanism works), but Docker-Desktop-on-Windows has no nested-container access to the
      real Docker socket, so `systemctl` itself was never exercised (see `docs/RELEASE_EVIDENCE.md`'s
      D4.6 section). This item is that missing piece, on the real Linux host where it is finally
      possible:
      1. Install all three unit files (`runner-backup.service`, `runner-backup.timer`,
         `runner-backup-alert@.service` - section 10) for real via `systemctl enable --now`.
      2. `systemctl start runner-backup.service` directly (not waiting for the timer).
      3. Confirm a real object landed in the real bucket (`rclone lsf` against the real remote).
      4. Deliberately cause a controlled failure (e.g. a temporarily wrong `BACKUP_REMOTE` in
         `deploy/.env`) and re-run.
      5. Confirm the real retry behavior (`systemctl status`/`journalctl -u runner-backup.service`
         showing the bounded restarts) and that the final `crit`-priority alert actually fires once
         retries are exhausted (`journalctl -p crit`).
      6. Interrupt a real, in-progress backup run (`systemctl stop runner-backup.service` mid-run, or
         a real host reboot mid-run).
      7. Confirm the `runner-backup-scheduled` container did not survive the interruption
         (`docker ps -a` - `ExecStopPost` should have cleaned it up).
      8. Confirm `systemctl list-timers runner-backup.timer` shows a real, correct next-scheduled
         run afterward.
- [ ] A real retention sweep (section 9) against real aged-out data.
- [ ] A real disk-low rejection - temporarily lower `RUNNER_DISK_MIN_FREE_BYTES` and confirm
      `POST /api/v1/runs` actually returns `503` (the same D4.2 submit-time guard section 8's own
      `GET /api/v1/disk/usage` endpoint reports on; D4.2 itself has no dedicated write-up in either
      `docs/DEPLOYMENT_ARCHITECTURE.md` or `docs/RELEASE_EVIDENCE.md` today - a pre-existing gap, not
      one this phase introduced).
- [ ] A real browser smoke test of the whole dashboard from the actual public domain, not
      `localhost`.

## 14. Flyway migration procedure

Fully automatic - Spring Boot's own default Flyway auto-configuration runs every not-yet-applied
migration in `runner-service/src/main/resources/db/migration/` at `runner-service` startup, against
whatever `spring.datasource.*` resolves to. No manual step, ever, for a normal deploy or restart
(sections 1-3 above already cover this).

**Adding a new migration**: create a new file named `V<next-integer>__<snake_case_description>.sql`
in that same directory - the next sequential version number after whichever `V<N>` is currently the
highest one actually committed there (check the directory itself, not a number written here that
would go stale the moment another migration is added). **Never edit an already-applied migration
file** - Flyway checksums every
migration it has already run and refuses to start if a previously-applied file's content changed
underneath it; a mistake needs a new, corrective migration instead, never a rewrite of history.

**If a migration itself fails to apply** (a genuine SQL error, not yet exercised for real against
this schema): Flyway marks it failed in `flyway_schema_history` and refuses to proceed past it on any
later restart until resolved - there is no established, tested repair procedure in this repo yet.
`flyway repair` (removing the failed row so a corrected migration can be reapplied) is the documented
Flyway-provided mechanism for this; treat it as an untested, high-caution last resort, verify against
a real backup restored to a scratch database first (section 10) rather than attempting it directly
against production.
