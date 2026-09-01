# SSE event stream contract (v1)

`GET /api/v1/runs/{runId}/events` is deliberately excluded from the OpenAPI document generated at
`/v3/api-docs` (see `RunEventStreamController`, `@Operation(hidden = true)`). OpenAPI 3 has no way
to describe a named-event `text/event-stream` payload without misrepresenting it - a naive schema
generator introspects the Java `SseEmitter` return type itself (its `timeout` field) rather than the
actual event payload, producing an actively wrong client. This document is the source of truth for
that endpoint instead; the frontend's SSE runtime schema (hand-written, not generated) is validated
against it.

## Request

```
GET /api/v1/runs/{runId}/events
Last-Event-ID: <sequence>   # optional; omit or send 0 for the full history
```

- `runId` must belong to a run the service still has a record of. An unknown `runId` returns `404`
  (`ProblemDetail`, same shape as the REST API) before any stream starts.
- `Last-Event-ID` must be a non-negative integer. A malformed value returns `400`. A value greater
  than the run's current highest sequence number also returns `400` - the client is claiming to
  have already seen an event this run's canonical journal never produced.
- Setting this header by hand is only available to non-browser HTTP clients (curl, a custom
  fetch-based SSE client). The native browser `EventSource` API has no way to set arbitrary request
  headers - it manages `Last-Event-ID` itself, automatically resending the last id it saw on its own
  reconnect. A client that wants to restart a run's stream from the beginning (not just resume)
  must not try to force `Last-Event-ID: 0` through `EventSource`; it should instead `close()` the
  existing instance and construct a brand-new one, which sends no `Last-Event-ID` header at all -
  equivalent to `0`.

## Response

`Content-Type: text/event-stream`. Each event is a standard SSE frame:

```
id:<sequence>
event:<EventType>
data:<JSON-encoded RunnerEvent>

```

`EventType` is one of: `RUN_QUEUED`, `RUN_STARTED`, `TEST_STARTED`, `TEST_PASSED`, `TEST_FAILED`,
`TEST_ABORTED`, `TEST_SKIPPED`, `RUN_FINISHED`. A client **must** register a listener per event
name (`addEventListener(type, ...)`) - the generic `onmessage` handler never fires for a named SSE
event.

`data` is the full canonical event, matching `runner-contract`'s `RunnerEvent` record. The service's
Jackson configuration is `NON_NULL` (see `JacksonConfig`, mirroring `spring.jackson.default-
property-inclusion: non_null`), so an inapplicable field is **omitted from the JSON entirely, not
present with a `null` value**. A client-side schema (e.g. Zod) generated from this contract must
model those fields as optional/absent, not nullable. A `TEST_STARTED` event actually looks like:

```json
{
  "schemaVersion": "1.0",
  "runId": "…",
  "sequence": 3,
  "timestamp": "2026-08-31T20:28:52.909407300Z",
  "type": "TEST_STARTED",
  "testId": "…",
  "testDisplayName": "…"
}
```

- `schemaVersion`, `runId`, `sequence`, `timestamp`, `type` are always present.
- `runOutcome` is present only on `RUN_FINISHED` (one of `SUCCEEDED`, `FAILED`, `TIMED_OUT`,
  `CANCELLED`, `ERROR`), absent everywhere else.
- `testId` / `testDisplayName` are present only for test-level event types, absent for the three
  run-level types (`RUN_QUEUED`, `RUN_STARTED`, `RUN_FINISHED`).
- `detail` is present only when there is a failure message / skip reason, absent otherwise.
- `sequence` is contiguous and gapless per `runId`, starting at 1. A client that ever observes a gap
  should treat it as a protocol error and reconnect from scratch, not try to patch around it - the
  server's ordered journal is the only source of truth. Forcing that from an `EventSource` means
  closing it and opening a new one (see the request-header note above), not setting a header.

## Replay and resume

- Connecting (or reconnecting) with `Last-Event-ID: N` replays every event with `sequence > N` in
  order, then continues with live events - atomically, with no possible gap or duplicate across the
  replay/live boundary.
- If `N` equals the run's current highest sequence **and** that event is `RUN_FINISHED`, there is
  nothing left to ever deliver: the response completes immediately (empty body) rather than being
  held open.
- The connection closes itself, cleanly, immediately after delivering `RUN_FINISHED` (replayed or
  live) - nothing is ever published for a runId after that event. A client must not assume the
  browser's own reconnect logic will stop on its own: per the WHATWG SSE standard, `EventSource`
  reconnects even after a clean server-side close unless the client calls `close()` itself. The
  client is responsible for calling `close()` once it observes `RUN_FINISHED`.
- A server-side alternative exists specifically for the *already-terminal reconnect* case (a client
  reconnects with `Last-Event-ID` already at, or past, the run's highest sequence and that event was
  `RUN_FINISHED`): responding `204 No Content` instead of today's empty `200` body would stop the
  browser's own auto-reconnect without any client-side bookkeeping. That is not yet implemented -
  today's behavior is the empty-`200`-body immediate completion described above, which still leaves
  a native `EventSource` free to reconnect again per the WHATWG default. See the runner-service SSE
  hardening backlog. This has no bearing on the live/in-progress case, where the connection is
  legitimately expected to stay open.

## Operational limits

- At most `runner.sse-max-subscribers` (default 100) concurrent connections across the whole
  service; beyond that, a new connection is rejected with `503`.
- A server-sent heartbeat comment (`:heartbeat`) is emitted every `runner.sse-heartbeat-interval`
  (default 15s) while a connection is idle, to keep intermediary proxies from timing it out.
- The service holds one canonical journal per run **in memory** (`RunRepository`, the REST-visible
  run history, is also in-memory) - a restart loses both, and a client reconnecting afterwards gets
  `404` from the REST layer before it would even reach the SSE endpoint. `FileBackedRunEventJournal`
  does flush every event to a per-run JSON Lines file under `runner.journal-dir`, and that file does
  survive a restart on disk - but nothing currently reads it back in on startup, so in practice nothing
  is recoverable from it without that follow-up work (rehydrating `RunRepository`/the journal's
  in-memory index from the on-disk files).
