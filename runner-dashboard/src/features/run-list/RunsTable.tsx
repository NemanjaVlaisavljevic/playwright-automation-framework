import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { cancelRun, listRuns, type RunResponse } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import {
  RunnerApiError,
  describePermissionError,
} from "../../api/problem-detail";
import { Button } from "../../components/ui/Button";
import { EmptyState } from "../../components/ui/EmptyState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { useCanManageRuns } from "../auth/useCanManageRuns";
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
  const canManageRuns = useCanManageRuns();
  const runs = useQuery({
    queryKey: queryKeys.runs,
    queryFn: listRuns,
    // Stops once nothing is left to watch. Also keeps retrying on an error status, since
    // `data?.some(...)` alone would be falsy (undefined data) and silently stop polling forever.
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

  // Keeps the current filter value even if it no longer matches live data, so the <select>
  // doesn't silently fall back to "All" or re-arm the filter later with no user action.
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
                <RunTableRow
                  key={run.runId}
                  run={run}
                  canManageRuns={canManageRuns}
                />
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

/** Its own component so `cancel` is a per-row `useMutation` instance - a shared one would mix up state between concurrent cancels. */
function RunTableRow({
  run,
  canManageRuns,
}: {
  run: RunResponse;
  canManageRuns: boolean;
}) {
  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => cancelRun(run.runId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.runs }),
  });

  const durationMs = runDurationMs(run);
  // The log file only exists once the process has launched; `startedAt` (populated only at
  // RUNNING) is the one field safe to gate on - STARTING alone isn't enough.
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
              disabled={cancel.isPending || !canManageRuns}
              title={canManageRuns ? undefined : "Admin login required"}
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
  return (
    describePermissionError(error) ??
    (error instanceof RunnerApiError ? error.message : "Unknown error")
  );
}
