import type { ReactNode } from 'react';
import type { MetricOutcome, MetricSource, MetricWidget } from '@dsp/shared';
import { MetricCard } from './MetricCard';

/** Widgets laid out on a six-column grid (third, half, two-thirds or full width). */
export function MetricGrid({
  widgets,
  results,
  loading,
  sources,
  actions,
}: {
  widgets: MetricWidget[];
  results?: Record<string, MetricOutcome>;
  loading?: boolean;
  sources?: MetricSource[];
  actions?: (w: MetricWidget, index: number) => ReactNode;
}) {
  return (
    <div className="metric-grid">
      {widgets.map((w, i) => (
        <MetricCard
          key={w.id ?? i}
          widget={w}
          outcome={results?.[w.id ?? String(i)]}
          loading={loading}
          sourceName={sources?.find((s) => s.id === w.definitionId)?.name}
          actions={actions?.(w, i)}
        />
      ))}
    </div>
  );
}
