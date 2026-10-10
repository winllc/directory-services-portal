import type { MetricOutcome, MetricResult, MetricWidget } from '@dsp/shared';
import { Lock } from 'lucide-react';
import { BarChart, ColumnChart, DonutChart, LineChart, StackedBarChart, compact, exact, pct } from '../charts';
import { Alert } from '../ui';

/** Rows of the result as CSV-able cells, matching the table view. */
export function resultTable(widget: MetricWidget, r: MetricResult): { head: string[]; rows: (string | number)[][] } {
  if (widget.kind === 'count') return { head: ['Entries'], rows: [[r.total]] };
  if (r.series && r.matrix) {
    return {
      head: [labelFor(widget), ...r.series.map((s) => s.label), 'Entries'],
      rows: (r.rows ?? []).map((row, i) => [row.label, ...(r.matrix?.[i] ?? []), row.value]),
    };
  }
  if (widget.kind === 'numeric' && r.stats) {
    return { head: [widget.measure === 'valueCount' ? 'Values per entry' : 'Range', 'Entries'], rows: (r.rows ?? []).map((row) => [row.label, row.value]) };
  }
  const shareHead = widget.kind === 'completeness' ? 'Filled in' : 'Share';
  return {
    head: [labelFor(widget), widget.kind === 'completeness' ? 'Entries with a value' : 'Entries', shareHead],
    rows: (r.rows ?? []).map((row) => [row.label, row.value, pct(row.share)]),
  };
}

function labelFor(w: MetricWidget) {
  if (w.kind === 'completeness') return 'Attribute';
  if (w.kind === 'timeline') return 'Period';
  return w.attribute === 'dn' ? 'Entry' : (w.attribute ?? 'Value');
}

export function MetricTable({ widget, result }: { widget: MetricWidget; result: MetricResult }) {
  const t = resultTable(widget, result);
  return (
    <div className="table-wrap viz-table">
      <table className="table table-compact">
        <thead>
          <tr>
            {t.head.map((h, i) => (
              <th key={i} className={i > 0 ? 'num' : undefined}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {t.rows.map((row, i) => (
            <tr key={i}>
              {row.map((c, j) => (
                <td key={j} className={j > 0 ? 'num' : undefined}>
                  {typeof c === 'number' ? exact(c) : c}
                </td>
              ))}
            </tr>
          ))}
          {t.rows.length === 0 && (
            <tr>
              <td colSpan={t.head.length} className="muted">
                No data
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

function NumericStats({ widget, r }: { widget: MetricWidget; r: MetricResult }) {
  const s = r.stats;
  if (!s || !s.count) return <p className="muted">No numeric values.</p>;
  const f = (v?: number) => (v === undefined ? '—' : compact(Math.round(v * 100) / 100));
  return (
    <div className="stat-row">
      <Stat label={widget.measure === 'valueCount' ? 'Entries' : 'Values'} value={compact(s.count)} />
      <Stat label="Average" value={f(s.mean)} />
      <Stat label="Median" value={f(s.median)} />
      <Stat label="Lowest" value={f(s.min)} />
      <Stat label="Highest" value={f(s.max)} />
    </div>
  );
}

/** Draws a computed widget with the chart it asks for (falling back to one that fits the data). */
export function MetricView({ widget, outcome, table }: { widget: MetricWidget; outcome?: MetricOutcome; table?: boolean }) {
  if (!outcome) return null;
  if (outcome.forbidden) {
    return (
      <div className="viz-empty">
        <Lock size={16} aria-hidden /> You don't have access to this chart's directory.
      </div>
    );
  }
  if (outcome.error || !outcome.result) {
    return (
      <Alert tone="danger">
        {outcome.error ?? 'Could not compute this chart'}
        {outcome.details && Object.keys(outcome.details).length > 0 && (
          <ul className="alert-list">
            {Object.entries(outcome.details).map(([k, v]) => (
              <li key={k}>{v}</li>
            ))}
          </ul>
        )}
      </Alert>
    );
  }
  const r = outcome.result;
  const label = widget.title;
  if (widget.kind === 'count') {
    return <Stat label="Entries" value={compact(r.total)} sub={widget.filter ? 'matching the filter' : undefined} />;
  }
  if (table || widget.chart === 'table') return <MetricTable widget={widget} result={r} />;
  if (widget.kind === 'numeric' && widget.chart === 'stat') return <NumericStats widget={widget} r={r} />;
  const rows = r.rows ?? [];
  if (rows.length === 0 || rows.every((x) => x.value === 0 && widget.kind !== 'completeness')) {
    return <div className="viz-empty">Nothing to show: no entry has a value here.</div>;
  }
  switch (widget.kind) {
    case 'distribution':
      if (r.series && r.matrix) return <StackedBarChart rows={rows} series={r.series} matrix={r.matrix} label={label} />;
      if (widget.chart === 'donut') return <DonutChart rows={rows} total={r.total} label={label} />;
      if (widget.chart === 'column') return <ColumnChart rows={rows} label={label} />;
      return <BarChart rows={rows} label={label} />;
    case 'completeness':
      return <BarChart rows={rows} percent label={label} />;
    case 'timeline':
      if (widget.chart === 'line' || widget.chart === 'area') return <LineChart rows={rows} label={label} area={widget.chart === 'area'} />;
      return <ColumnChart rows={rows} label={label} />;
    case 'numeric':
      return (
        <>
          <NumericStats widget={widget} r={r} />
          <ColumnChart
            rows={rows}
            label={label}
            valueLabel={widget.measure === 'valueCount' ? 'entries' : 'values'}
            xLabel={widget.measure === 'valueCount' ? `Number of ${widget.attribute} values per entry` : widget.attribute}
          />
        </>
      );
    default:
      return <MetricTable widget={widget} result={r} />;
  }
}
