import { useState, type ReactNode } from 'react';
import { AlertTriangle, BarChart3, Download, Table2 } from 'lucide-react';
import type { MetricOutcome, MetricWidget } from '@dsp/shared';
import { cx } from '../ui';
import { downloadCsv } from '../../lib/csv';
import { MetricView, resultTable } from './MetricView';
import { compact } from '../charts';

const WIDTH_CLASS: Record<string, string> = { third: 'w-third', half: 'w-half', 'two-thirds': 'w-two-thirds', full: 'w-full' };

const time = (iso: string) => new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });

/**
 * A widget on a dashboard: title, the chart (or its table view), CSV export and what the numbers
 * are based on. While results reload, the previous render stays at reduced opacity.
 */
export function MetricCard({
  widget,
  outcome,
  loading,
  actions,
  sourceName,
}: {
  widget: MetricWidget;
  outcome?: MetricOutcome;
  loading?: boolean;
  actions?: ReactNode;
  sourceName?: string;
}) {
  const [table, setTable] = useState(false);
  const r = outcome?.result;
  const canTable = !!r && widget.kind !== 'count' && widget.chart !== 'table';
  return (
    <section className={cx('card metric-card', WIDTH_CLASS[widget.width ?? 'half'], loading && 'is-loading')} aria-busy={loading}>
      <header className="metric-head">
        <div className="metric-titles">
          <h2 className="card-title">{widget.title}</h2>
          {widget.description && <p className="card-subtitle">{widget.description}</p>}
        </div>
        <div className="metric-actions">
          {canTable && (
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              onClick={() => setTable((t) => !t)}
              aria-pressed={table}
              title={table ? 'Show chart' : 'Show table'}
              aria-label={table ? 'Show chart' : 'Show table'}
            >
              {table ? <BarChart3 size={16} /> : <Table2 size={16} />}
            </button>
          )}
          {r && (
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              title="Download CSV"
              aria-label="Download CSV"
              onClick={() => {
                const t = resultTable(widget, r);
                downloadCsv(`${widget.title}.csv`, [t.head, ...t.rows]);
              }}
            >
              <Download size={16} />
            </button>
          )}
          {actions}
        </div>
      </header>
      <div className="metric-body">
        {!outcome && loading ? <div className="viz-placeholder" /> : <MetricView widget={widget} outcome={outcome} table={table} />}
      </div>
      {r && (
        <footer className="metric-foot">
          <span>
            {widget.kind === 'count' ? (sourceName ?? '') : `${compact(r.total)} entries${sourceName ? ` in ${sourceName}` : ''}`}
            {widget.filter ? ' (filtered)' : ''}
            {widget.kind !== 'count' || sourceName || widget.filter ? ' · ' : ''}
            {time(r.computedAt)}
          </span>
          {r.warnings?.map((w) => (
            <span key={w} className="metric-warning">
              <AlertTriangle size={13} aria-hidden /> {w}
            </span>
          ))}
        </footer>
      )}
    </section>
  );
}
