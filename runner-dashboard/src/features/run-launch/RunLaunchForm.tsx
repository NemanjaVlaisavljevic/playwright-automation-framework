import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createRun, getCapabilities } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import { Alert } from "../../components/ui/Alert";
import { Button } from "../../components/ui/Button";
import type { Environment, Suite } from "../../domain/run";
import styles from "./RunLaunchForm.module.css";

interface RunLaunchFormProps {
  /** Overridable for tests - see RunLaunchForm.test.tsx. */
  capabilitiesRetryIntervalMs?: number;
}

export function RunLaunchForm({
  capabilitiesRetryIntervalMs = 5_000,
}: RunLaunchFormProps = {}) {
  const capabilities = useQuery({
    queryKey: queryKeys.capabilities,
    queryFn: getCapabilities,
    // The allowlist itself doesn't change mid-session, so this only ever matters while erroring -
    // see RunListPage's health query for why refetchIntervalInBackground matters here too (a tab
    // left open, unfocused, across a backend restart must still recover on its own).
    refetchInterval: (query) =>
      query.state.status === "error" ? capabilitiesRetryIntervalMs : false,
    refetchIntervalInBackground: true,
  });
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  // Empty string means "no explicit user choice yet" - the actual selected value used for
  // rendering/submission always falls back to the first available option (see below), so the
  // dropdown has a sensible default the moment capabilities load without a useEffect to sync it.
  const [environmentChoice, setEnvironmentChoice] = useState<Environment | "">(
    "",
  );
  const [suiteChoice, setSuiteChoice] = useState<Suite | "">("");

  const launch = useMutation({
    mutationFn: (request: { environment: Environment; suite: Suite }) =>
      createRun(request),
    onSuccess: async (run) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.runs });
      navigate(`/runs/${run.runId}`);
    },
  });

  if (capabilities.isPending) {
    return <p>Loading capabilities…</p>;
  }
  if (capabilities.isError) {
    return (
      <p>Could not load capabilities: {describeError(capabilities.error)}</p>
    );
  }

  const environments = capabilities.data.environments;
  const selectedEnvironment =
    environments.find((candidate) => candidate.name === environmentChoice) ??
    environments[0];
  const suites = selectedEnvironment?.suites ?? [];
  const selectedSuite =
    suites.find((candidate) => candidate === suiteChoice) ?? suites[0];

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      launch.isPending ||
      selectedEnvironment === undefined ||
      selectedSuite === undefined
    ) {
      return;
    }
    launch.mutate({
      environment: selectedEnvironment.name,
      suite: selectedSuite,
    });
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      <h2 className={styles.title}>Start a run</h2>
      <div className={styles.fields}>
        <label className={styles.field}>
          Environment
          <select
            value={selectedEnvironment?.name ?? ""}
            onChange={(event) => {
              setEnvironmentChoice(event.target.value as Environment);
              setSuiteChoice(""); // a different environment may allow a different suite set
            }}
          >
            {environments.map((candidate) => (
              <option key={candidate.name} value={candidate.name}>
                {candidate.name}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.field}>
          Suite
          <select
            value={selectedSuite ?? ""}
            onChange={(event) => setSuiteChoice(event.target.value as Suite)}
          >
            {suites.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit" variant="primary" disabled={launch.isPending}>
          {launch.isPending ? "Starting…" : "Run"}
        </Button>
      </div>
      {launch.isError && <Alert>{describeLaunchError(launch.error)}</Alert>}
    </form>
  );
}

function describeError(error: unknown): string {
  return error instanceof RunnerApiError ? error.message : "Unknown error";
}

function describeLaunchError(error: unknown): string {
  if (!(error instanceof RunnerApiError)) {
    return "An unexpected error occurred while starting the run.";
  }
  if (error.kind === "http" && error.status === 503) {
    return "The runner is busy or temporarily unavailable. Try again shortly.";
  }
  if (error.kind === "http" && error.status === 400) {
    return error.problem?.detail ?? "The request was rejected.";
  }
  if (error.kind === "http") {
    return "An unexpected error occurred while starting the run.";
  }
  return error.message;
}
