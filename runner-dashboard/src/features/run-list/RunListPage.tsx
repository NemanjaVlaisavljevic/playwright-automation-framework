import { PageHeader } from "../../components/ui/PageHeader";
import { RunLaunchForm } from "../run-launch/RunLaunchForm";
import { RunsTable } from "./RunsTable";

/**
 * The runner-service health indicator used to live here, but now lives in `AppShell` instead - it
 * needs to be visible from every page (including `/runs/:runId`), not just this one.
 */
export function RunListPage() {
  return (
    <>
      <PageHeader title="Runs" />
      <RunLaunchForm />
      <RunsTable />
    </>
  );
}
