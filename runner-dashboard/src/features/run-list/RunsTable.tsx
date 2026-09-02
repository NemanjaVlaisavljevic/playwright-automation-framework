import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { cancelRun, listRuns, type RunResponse } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import { Button } from "../../components/ui/Button";
import { EmptyState } from "../../components/ui/EmptyState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatLocalDateTime } from "../../domain/datetime";
import { formatDuration, runDurationMs } from "../../domain/duration";
import { isTerminalRunStatus } from "../../domain/run";
import styles from "./RunsTable.module.css";

interface RunsTableProps {
  /** Overridable for tests - see RunsTable.test.tsx. */
  pollIntervalMs?: number;
}

type SortKey = "requestedAt" | "status" | "suite" | "environment";
type SortDirection = "asc" | "desc";

const ALL = "ALL";

export function RunsTable({ pollIntervalMs = 2000 }: RunsTableProps = {}) {
  const runs = useQuery({
    queryKey: queryKeys.runs,
    queryFn: listRuns,
    // Stops on its own once nothing is left to watch - no separate "am I still needed" check, and
    // no per-row subscription of any kind (an SSE connection per row would not scale). Also keeps
    // retrying on `pollIntervalMs` while the query has never succeeded at all (`data` is still
    // `undefined`): `data?.some(...)` alone would be `undefined` (falsy) for that case, silently
    // stopping the poll forever on the very first failure instead of recovering once the backend
    // comes back - unlike health/capabilities, which have their own explicit error-state check.
    refetchInterval: (query) => {
      if (query.state.status === "error") {
        return pollIntervalMs;
      }
      const data = query.state.data;
      return data?.some((run) => !isTerminalRunStatus(run.status))
        ? pollIntervalMs
        : false;
    },
    refetchIntervalInBackground: true,
  });

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>(ALL);
  const [suiteFilter, setSuiteFilter] = useState<string>(ALL);
  const [sort, setSort] = useState<{
    key: SortKey;
    direction: SortDirection;
  } | null>(null);

  if (runs.isPending) {
    return <p>Loading runs…</p>;
  }
  if (runs.isError) {
    return <p>Could not load runs: {describeError(runs.error)}</p>;
  }
  if (runs.data.length === 0) {
    return <EmptyState title="No runs yet." />;
  }

  // The currently-selected filter value is always included even if it no longer appears in the
  // live data (e.g. the one "RUNNING" run just finished) - the <select> must keep showing what is
  // actually being filtered on. A native <select> can't display a value with no matching <option>,
  // so dropping a stale value from this list (falling back to a masked "All" display instead of
  // keeping the option) would make the <select> lie about the filter that's still in effect - and
  // worse, silently "re-arm" that exact same filter with no user action the moment a run matching
  // it reappears later, which is exactly the surprising behavior this avoids.
  const statuses = uniqueSorted([
    ...runs.data.map((run) => run.status),
    ...(statusFilter === ALL ? [] : [statusFilter]),
  ]);
  const suites = uniqueSorted([
    ...runs.data.map((run) => run.suite),
    ...(suiteFilter === ALL ? [] : [suiteFilter]),
  ]);

  const filtered = runs.data.filter(
    (run) =>
      (statusFilter === ALL || run.status === statusFilter) &&
      (suiteFilter === ALL || run.suite === suiteFilter) &&
      matchesSearch(run.runId, search),
  );
  const visible = sort ? [...filtered].sort(compareRuns(sort)) : filtered;

  function toggleSort(key: SortKey) {
    setSort((current) =>
      current?.key === key
        ? { key, direction: current.direction === "asc" ? "desc" : "asc" }
        : { key, direction: "asc" },
    );
  }

  return (
    <>
      <div className={styles.filters}>
        <label className={styles.filterField}>
          Search by run ID
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="run-…"
          />
        </label>
        <label className={styles.filterField}>
          Status
          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
          >
            <option value={ALL}>All</option>
            {statuses.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.filterField}>
          Suite
          <select
            value={suiteFilter}
            onChange={(event) => setSuiteFilter(event.target.value)}
          >
            <option value={ALL}>All</option>
            {suites.map((suite) => (
              <option key={suite} value={suite}>
                {suite}
              </option>
            ))}
          </select>
        </label>
      </div>

      {visible.length === 0 ? (
        <EmptyState title="No runs match the current filters.">
          Try clearing the search or filters above.
        </EmptyState>
      ) : (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption className="visually-hidden">Runs</caption>
            <thead>
              <tr>
                <SortableHeader
                  label="Status"
                  sortKey="status"
                  sort={sort}
                  onSort={toggleSort}
                />
                <SortableHeader
                  label="Suite"
                  sortKey="suite"
                  sort={sort}
                  onSort={toggleSort}
                />
                <SortableHeader
                  label="Environment"
                  sortKey="environment"
                  sort={sort}
                  onSort={toggleSort}
                />
                <SortableHeader
                  label="Requested"
                  sortKey="requestedAt"
                  sort={sort}
                  onSort={toggleSort}
                />
                <th>Duration</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((run) => (
                <RunTableRow key={run.runId} run={run} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function SortableHeader({
  label,
  sortKey,
  sort,
  onSort,
}: {
  label: string;
  sortKey: SortKey;
  sort: { key: SortKey; direction: SortDirection } | null;
  onSort: (key: SortKey) => void;
}) {
  const active = sort?.key === sortKey;
  const ariaSort = active
    ? sort.direction === "asc"
      ? "ascending"
      : "descending"
    : "none";
  return (
    <th aria-sort={ariaSort}>
      <button
        type="button"
        className={styles.sortButton}
        onClick={() => onSort(sortKey)}
      >
        {label}
        {active && (
          <span aria-hidden="true">
            {sort.direction === "asc" ? " ▲" : " ▼"}
          </span>
        )}
      </button>
    </th>
  );
}

function uniqueSorted(values: string[]): string[] {
  return Array.from(new Set(values)).sort();
}

function matchesSearch(runId: string, search: string): boolean {
  const trimmed = search.trim().toLowerCase();
  return trimmed === "" || runId.toLowerCase().includes(trimmed);
}

function compareRuns(sort: { key: SortKey; direction: SortDirection }) {
  const factor = sort.direction === "asc" ? 1 : -1;
  return (a: RunResponse, b: RunResponse) =>
    factor * a[sort.key].localeCompare(b[sort.key]);
}

/**
 * Its own component specifically so `cancel` is its own `useMutation` instance, scoped to this one
 * row: a single mutation shared across the whole table only ever remembers the *last* `mutate()`
 * call's state, so cancelling run B while run A's cancel is still in flight would silently re-enable
 * A's button (`variables` now points at B) and lose A's own pending/error state entirely - a real
 * bug found by a concurrent-cancel scenario no test happened to cover yet.
 */
function RunTableRow({ run }: { run: RunResponse }) {
  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => cancelRun(run.runId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.runs }),
  });

  const durationMs = runDurationMs(run);
  // `run.processLogUrl` (from the backend, not recomputed here) is present the moment a run is
  // accepted, but the file behind it only exists once the process has actually launched - `STARTING`
  // is not enough (the backend can sit in STARTING while waiting out a DEGRADED runner, before ever
  // calling ProcessLauncher.start()); `startedAt` is only ever populated once RUNNING is reached
  // (Run's own constructor forbids it on QUEUED/STARTING), so it's the one field that's actually
  // safe to gate on.
  const logAvailable = run.startedAt !== undefined;

  return (
    <tr>
      <td>
        <StatusBadge status={run.status} />
      </td>
      <td>{run.suite}</td>
      <td>{run.environment}</td>
      <td>{formatLocalDateTime(run.requestedAt)}</td>
      <td>{durationMs !== undefined ? formatDuration(durationMs) : "—"}</td>
      <td>
        <div className={styles.actions}>
          <Link to={`/runs/${run.runId}`}>View</Link>
          {!isTerminalRunStatus(run.status) && (
            <Button
              variant="secondary"
              size="compact"
              onClick={() => cancel.mutate()}
              disabled={cancel.isPending}
            >
              Cancel
            </Button>
          )}
          {logAvailable && <a href={run.processLogUrl}>Download log</a>}
          {cancel.isError && (
            <span role="alert">
              Could not cancel: {describeError(cancel.error)}
            </span>
          )}
        </div>
      </td>
    </tr>
  );
}

function describeError(error: unknown): string {
  return error instanceof RunnerApiError ? error.message : "Unknown error";
}
