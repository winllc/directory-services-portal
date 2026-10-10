import { Link } from 'react-router-dom';
import { GitCompareArrows, Plus, ShieldCheck } from 'lucide-react';
import type { AnalysisDefinition } from '@dsp/shared';
import { useAdminDefinitions, useAnalyses } from '../../api/hooks';
import { Badge, EmptyState, ErrorAlert, PageHeader, Spinner } from '../../components/ui';

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function AnalysisTypeIcon({ type, size = 18 }: { type: AnalysisDefinition['type']; size?: number }) {
  return type === 'compare' ? <GitCompareArrows size={size} aria-hidden /> : <ShieldCheck size={size} aria-hidden />;
}

export function AnalysisListPage() {
  const analyses = useAnalyses();
  const defs = useAdminDefinitions();
  const name = (id: string) => defs.data?.find((d) => d.id === id)?.name ?? id;

  return (
    <div className="page">
      <PageHeader
        icon={<ShieldCheck size={22} />}
        title="Data analysis"
        subtitle="Compare directories with each other, and check them for correct and unique values."
        actions={
          <>
            <Link to="/admin/analysis/new?type=compare" className="btn btn-secondary">
              <GitCompareArrows size={16} /> New comparison
            </Link>
            <Link to="/admin/analysis/new?type=quality" className="btn btn-primary">
              <Plus size={16} /> New quality check
            </Link>
          </>
        }
      />
      <ErrorAlert error={analyses.error} />
      {analyses.isLoading ? (
        <Spinner />
      ) : !analyses.data?.length ? (
        <EmptyState icon={<ShieldCheck size={22} />} title="No analyses yet">
          A comparison matches entries across directories by a key attribute (e.g. uid) and reports what is missing or differs. A quality check finds
          missing, malformed and duplicate values.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Analysis</th>
                <th>Sources</th>
                <th>Last run</th>
              </tr>
            </thead>
            <tbody>
              {analyses.data.map((a) => (
                <tr key={a.id}>
                  <td>
                    <Link to={`/admin/analysis/${a.id}`} className="cell-with-icon row-title">
                      <AnalysisTypeIcon type={a.type} />
                      {a.name}
                    </Link>
                    <div className="muted small">
                      <Badge tone={a.type === 'compare' ? 'info' : 'accent'}>{a.type === 'compare' ? 'Comparison' : 'Quality check'}</Badge> {a.description}
                    </div>
                  </td>
                  <td className="small">{a.sources.map((s) => s.label || name(s.definitionId)).join(a.type === 'compare' ? ' ↔ ' : ', ')}</td>
                  <td className="small">
                    {a.lastRun ? (
                      <>
                        <div>{a.lastRun.summary}</div>
                        <div className="muted">
                          {when(a.lastRun.at)} by {a.lastRun.by}
                        </div>
                      </>
                    ) : (
                      <span className="muted">Never</span>
                    )}
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
