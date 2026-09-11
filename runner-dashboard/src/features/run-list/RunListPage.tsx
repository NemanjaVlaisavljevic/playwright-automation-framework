import { PageHeader } from "../../components/ui/PageHeader";
import { RunLaunchForm } from "../run-launch/RunLaunchForm";
import { RunsTable } from "./RunsTable";

export function RunListPage() {
  return (
    <>
      <PageHeader title="Runs" />
      <RunLaunchForm />
      <RunsTable />
    </>
  );
}
