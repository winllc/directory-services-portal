import type { ReactNode } from 'react';
import { AlertOctagon, AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight } from 'lucide-react';
import type { Severity } from '@dsp/shared';
import { Button } from '../ui';

export function Kpi({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'good' | 'warning' | 'critical' }) {
  return (
    <div className={`stat kpi${tone ? ` kpi-${tone}` : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

/** Status is never colour alone: icon + label. */
export function SeverityBadge({ severity }: { severity: Severity }) {
  return severity === 'warning' ? (
    <span className="status status-warning">
      <AlertTriangle size={13} aria-hidden /> Warning
    </span>
  ) : (
    <span className="status status-critical">
      <AlertOctagon size={13} aria-hidden /> Error
    </span>
  );
}

export function OkBadge({ children = 'OK' }: { children?: ReactNode }) {
  return (
    <span className="status status-good">
      <CheckCircle2 size={13} aria-hidden /> {children}
    </span>
  );
}

/** A meter: the filled share on a lighter track of the same ramp. */
export function Meter({ value, label, tone = 'good' }: { value: number; label: string; tone?: 'good' | 'warning' | 'critical' }) {
  const pctValue = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div className={`meter meter-${tone}`} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pctValue)} aria-label={label}>
      <div className="meter-fill" style={{ width: `${pctValue}%` }} />
    </div>
  );
}

export function Values({ values }: { values: string[] | null | undefined }) {
  if (!values) return <span className="muted">not present</span>;
  if (!values.length) return <span className="muted">—</span>;
  return (
    <ul className="plain-list">
      {values.map((v, i) => (
        <li key={i} className="audit-value">
          {v}
        </li>
      ))}
    </ul>
  );
}

export const PAGE_SIZE = 50;

export function Pager({ page, total, onPage }: { page: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (pages <= 1) return null;
  return (
    <div className="pagination">
      <Button size="sm" variant="ghost" icon={<ChevronLeft size={16} />} disabled={page <= 1} onClick={() => onPage(page - 1)}>
        Previous
      </Button>
      <span className="muted small">
        Page {page} of {pages}
      </span>
      <Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => onPage(page + 1)}>
        Next <ChevronRight size={16} />
      </Button>
    </div>
  );
}
