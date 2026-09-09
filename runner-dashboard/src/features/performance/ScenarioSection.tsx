import { StatusBadge } from "../../components/ui/StatusBadge";
import { cx } from "../../components/ui/cx";
import type { Scenario } from "../../domain/performance-baseline";
import { formatMs, formatUtilization, resultStatus } from "./format";
import styles from "./PerformancePage.module.css";

export interface ScenarioSectionProps {
  scenario: Scenario;
}

export function ScenarioSection({ scenario }: ScenarioSectionProps) {
  return (
    <div className={styles.scenarioCard}>
      <div className={styles.scenarioHeader}>
        <h2 className={styles.sectionTitle}>{scenario.displayName}</h2>
        <span className={styles.statusValue}>
          <StatusBadge status={scenario.status} />
        </span>
      </div>

      {scenario.latencies.length > 0 && (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption className="visually-hidden">
              Latency metrics for {scenario.displayName}
            </caption>
            <thead>
              <tr>
                <th>Metric</th>
                <th>Samples</th>
                <th>p50</th>
                <th>p95</th>
                <th>p99</th>
                <th>Limit (p95)</th>
                <th>Utilization</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {scenario.latencies.map((metric) => (
                <tr
                  key={metric.metricId}
                  className={cx(metric.passed === false && styles.regressedRow)}
                >
                  <td>{metric.displayName}</td>
                  <td className={styles.numeric}>{metric.sampleCount}</td>
                  <td className={styles.numeric}>{formatMs(metric.p50Ms)}</td>
                  <td className={styles.numeric}>{formatMs(metric.p95Ms)}</td>
                  <td className={styles.numeric}>{formatMs(metric.p99Ms)}</td>
                  <td className={styles.numeric}>
                    {metric.p95LimitMs === null ? (
                      <span className={styles.muted}>—</span>
                    ) : (
                      formatMs(metric.p95LimitMs)
                    )}
                  </td>
                  <td className={styles.numeric}>
                    {formatUtilization(metric.p95Ms, metric.p95LimitMs)}
                  </td>
                  <td>
                    <StatusBadge status={resultStatus(metric.passed)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {scenario.signals.length > 0 && (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption className="visually-hidden">
              Signal metrics for {scenario.displayName}
            </caption>
            <thead>
              <tr>
                <th>Signal</th>
                <th>Value</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {scenario.signals.map((metric) => (
                <tr
                  key={metric.metricId}
                  className={cx(metric.passed === false && styles.regressedRow)}
                >
                  <td>{metric.displayName}</td>
                  <td className={styles.numeric}>
                    {metric.unit === "rate"
                      ? `${(metric.value * 100).toFixed(1)}%`
                      : metric.value}
                  </td>
                  <td>
                    <StatusBadge status={resultStatus(metric.passed)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
