import {
  createBrowserRouter,
  Navigate,
  type RouteObject,
} from "react-router-dom";
import { RunDetailsPage } from "../features/run-details/RunDetailsPage";
import { RunListPage } from "../features/run-list/RunListPage";
import { PerformancePage } from "../features/performance/PerformancePage";
import { AppShell } from "./AppShell";

export const appRoutes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { path: "/", element: <Navigate to="/runs" replace /> },
      { path: "/runs", element: <RunListPage /> },
      { path: "/runs/:runId", element: <RunDetailsPage /> },
      { path: "/performance", element: <PerformancePage /> },
    ],
  },
];

export const router = createBrowserRouter(appRoutes);
