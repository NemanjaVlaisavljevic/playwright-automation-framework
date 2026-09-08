import sse from 'k6/x/sse';
import { Counter, Rate, Trend } from 'k6/metrics';
import { check } from 'k6';
import { handleSummary as sharedHandleSummary } from './lib/summary.js';

// D4.4.1c - sse-replay.js: connection-establishment time, time-to-first-event, and full-replay
// duration/correctness against `perf-replay-run` (a terminal, SUCCEEDED run with 399 seeded
// run_events - see performance/seed/seed.sql). A terminal run's own SSE subscription replays its
// full history then completes on its own (RunEventBroker never expects a terminal run to receive
// any further event) - no client-side timeout needed here, unlike sse-connection-cap.js's
// still-`RUNNING` fixture.
//
// Requires the custom k6-sse image (performance/k6-sse/Dockerfile) - stock k6 has no SSE support.
const BASE_URL = __ENV.BASE_URL || 'http://web';
const EXPECTED_RUN_ID = 'perf-replay-run';
const EXPECTED_SCHEMA_VERSION = '1.1';
const EXPECTED_EVENT_COUNT = 399;

export const sseConnectionEstablishMs = new Trend('sse_connection_establish_ms', true);
export const sseTimeToFirstEventMs = new Trend('sse_time_to_first_event_ms', true);
export const sseReplayDurationMs = new Trend('sse_replay_duration_ms', true);
export const sseEventsReceived = new Counter('sse_events_received');
export const sseTransportErrors = new Counter('sse_transport_errors');
// A dedicated correctness signal, independent of k6's generic `checks` (a review finding: `check()`
// alone only records a result - without a real threshold referencing it, a regression here would
// still exit 0). True only when the *whole* replay satisfies every contract invariant: exactly
// EXPECTED_EVENT_COUNT events, strictly consecutive sequence numbers starting at 1 (never a
// duplicate/gap/reorder), every event's own runId/schemaVersion correct, the first event is
// RUN_QUEUED and the last is RUN_FINISHED. A JSON-parse failure on any event's data is itself a
// correctness failure, never silently skipped.
export const sseReplayCorrectness = new Rate('sse_replay_correctness');

export const options = {
  vus: 1,
  iterations: 5,
  thresholds: {
    // Permissive placeholders only - real empirical latency thresholds are locked in D4.4.2.
    sse_connection_establish_ms: ['p(95)<100000'],
    sse_time_to_first_event_ms: ['p(95)<100000'],
    sse_replay_duration_ms: ['p(95)<100000'],
    // Real, failing correctness gates - never just a reported check.
    sse_replay_correctness: ['rate==1'],
    sse_transport_errors: ['count==0'],
    checks: ['rate==1'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('sse-replay', [], data);
}

export default function () {
  const startedAt = Date.now();
  let openAt = null;
  let firstEventAt = null;
  const events = [];

  const response = sse.open(`${BASE_URL}/api/v1/runs/${EXPECTED_RUN_ID}/events`, function (client) {
    client.on('open', function () {
      openAt = Date.now();
      sseConnectionEstablishMs.add(openAt - startedAt);
    });
    client.on('event', function (event) {
      if (firstEventAt === null && openAt !== null) {
        firstEventAt = Date.now();
        sseTimeToFirstEventMs.add(firstEventAt - openAt);
      }
      sseEventsReceived.add(1);
      let parsed;
      try {
        parsed = JSON.parse(event.data);
      } catch (parseError) {
        parsed = null;
      }
      events.push(parsed);
    });
    client.on('error', function () {
      // A real transport error - never expected for a clean terminal-run replay. Closing here is
      // what actually unblocks sse.open() (it otherwise waits indefinitely for the next channel
      // signal - see the extension's own source, sse.go's main select loop).
      sseTransportErrors.add(1);
      client.close();
    });
  });

  sseReplayDurationMs.add(Date.now() - startedAt);

  const correct = validateReplay(response, events);
  sseReplayCorrectness.add(correct);

  check(response, { 'sse-replay: response status is 200': (r) => r.status === 200 });
  check(events, {
    'sse-replay: exactly the expected event count, no more, no less': (e) =>
      e.length === EXPECTED_EVENT_COUNT,
  });
  check(correct, {
    'sse-replay: full event-timeline contract validation passed': (c) => c === true,
  });
}

/**
 * Every invariant a real replay of `perf-replay-run` must satisfy - accepting anything less (a
 * minimum-count check alone) would let duplicates, extra events, wrong ordering, or malformed
 * frames through undetected.
 */
function validateReplay(response, events) {
  if (response.status !== 200) {
    return false;
  }
  if (events.length !== EXPECTED_EVENT_COUNT) {
    return false;
  }
  for (let i = 0; i < events.length; i++) {
    const event = events[i];
    if (event === null || typeof event !== 'object') {
      return false; // JSON parse failure - never silently ignored.
    }
    if (event.sequence !== i + 1) {
      return false; // strictly consecutive, starting at 1 - never a gap, duplicate, or reorder.
    }
    if (event.runId !== EXPECTED_RUN_ID) {
      return false;
    }
    if (event.schemaVersion !== EXPECTED_SCHEMA_VERSION) {
      return false;
    }
  }
  if (events[0].type !== 'RUN_QUEUED') {
    return false;
  }
  if (events[events.length - 1].type !== 'RUN_FINISHED') {
    return false;
  }
  return true;
}
