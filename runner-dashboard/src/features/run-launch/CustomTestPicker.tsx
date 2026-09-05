import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listPublicTests, type TestCatalogEntry } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import type { Environment } from "../../domain/run";
import styles from "./CustomTestPicker.module.css";

type LayerFilter = "ALL" | TestCatalogEntry["category"];

const LAYER_OPTIONS: ReadonlyArray<{ value: LayerFilter; label: string }> = [
  { value: "ALL", label: "All" },
  { value: "API", label: "API" },
  { value: "UI", label: "UI" },
  { value: "JOURNEY", label: "JOURNEY" },
];

export interface CustomTestPickerProps {
  environment: Environment;
  selectedKeys: ReadonlySet<string>;
  onChange: (keys: ReadonlySet<string>) => void;
  /** Overridable for tests - see CustomTestPicker.test.tsx. */
  catalogRetryIntervalMs?: number;
}

/**
 * The `CUSTOM`-suite picker: a searchable, filterable list of the server's own catalog
 * (`GET /api/v1/tests`) with checkboxes. Deliberately never lets the caller type a class/method
 * name by hand - every selectable entry comes from {@link listPublicTests}, and what gets
 * submitted is exactly the `testKey` values checked here (see `RunLaunchForm`'s own submit
 * handler) - the same allowlist-only contract `CustomTestSelectionValidator` enforces server-side.
 *
 * `smoke` is deliberately a separate checkbox, not a fourth layer option: a test can be both `UI`
 * and `smoke` at once (see the catalog's own `tags`), so folding it into the layer dropdown would
 * misrepresent it as mutually exclusive with API/UI/JOURNEY.
 */
export function CustomTestPicker({
  environment,
  selectedKeys,
  onChange,
  catalogRetryIntervalMs = 5_000,
}: CustomTestPickerProps) {
  const [search, setSearch] = useState("");
  const [layer, setLayer] = useState<LayerFilter>("ALL");
  const [smokeOnly, setSmokeOnly] = useState(false);

  const catalog = useQuery({
    queryKey: queryKeys.publicTestCatalog(environment),
    queryFn: () => listPublicTests(environment),
    // Mirrors RunLaunchForm's own capabilities query: the catalog itself doesn't change mid-session,
    // so this only ever matters while erroring, and refetchIntervalInBackground matters for the same
    // reason - a tab left open, unfocused, across a backend restart must still recover on its own.
    refetchInterval: (query) =>
      query.state.status === "error" ? catalogRetryIntervalMs : false,
    refetchIntervalInBackground: true,
  });

  const visible = useMemo(() => {
    const tests = catalog.data ?? [];
    const needle = search.trim().toLowerCase();
    return tests.filter((test) => {
      if (layer !== "ALL" && test.category !== layer) {
        return false;
      }
      if (smokeOnly && !test.tags.includes("smoke")) {
        return false;
      }
      if (needle.length > 0) {
        return (
          test.displayName.toLowerCase().includes(needle) ||
          test.testKey.toLowerCase().includes(needle)
        );
      }
      return true;
    });
  }, [catalog.data, search, layer, smokeOnly]);

  if (catalog.isPending) {
    return <p>Loading tests…</p>;
  }
  if (catalog.isError) {
    return (
      <p>
        Could not load the test catalog:{" "}
        {catalog.error instanceof RunnerApiError
          ? catalog.error.message
          : "Unknown error"}
      </p>
    );
  }

  function toggle(testKey: string) {
    const next = new Set(selectedKeys);
    if (next.has(testKey)) {
      next.delete(testKey);
    } else {
      next.add(testKey);
    }
    onChange(next);
  }

  function selectAllVisible() {
    const next = new Set(selectedKeys);
    visible.forEach((test) => next.add(test.testKey));
    onChange(next);
  }

  function clearSelection() {
    onChange(new Set());
  }

  return (
    <div className={styles.picker}>
      <div className={styles.toolbar}>
        <label className={styles.field}>
          Search tests
          <input
            type="text"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <label className={styles.field}>
          Layer
          <select
            value={layer}
            onChange={(event) => setLayer(event.target.value as LayerFilter)}
          >
            {LAYER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.checkboxField}>
          <input
            type="checkbox"
            checked={smokeOnly}
            onChange={(event) => setSmokeOnly(event.target.checked)}
          />
          Smoke only
        </label>
        <button type="button" onClick={selectAllVisible}>
          Select all visible
        </button>
        <button type="button" onClick={clearSelection}>
          Clear selection
        </button>
      </div>
      <p aria-live="polite" className={styles.count}>
        {selectedKeys.size} test{selectedKeys.size === 1 ? "" : "s"} selected
      </p>
      <ul className={styles.list}>
        {visible.map((test) => (
          <li key={test.testKey} className={styles.item}>
            <label>
              <input
                type="checkbox"
                checked={selectedKeys.has(test.testKey)}
                onChange={() => toggle(test.testKey)}
              />
              {test.displayName}
              <span className={styles.badge}>{test.category}</span>
            </label>
          </li>
        ))}
        {visible.length === 0 && (
          <li className={styles.empty}>No tests match.</li>
        )}
      </ul>
    </div>
  );
}
