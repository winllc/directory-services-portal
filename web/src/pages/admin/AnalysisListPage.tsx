import { Link } from 'react-router-dom';
import { GitCompareArrows, Play, Plus, ShieldCheck } from 'lucide-react';
import type { AnalysisDefinition } from '@dsp/shared';
import { jobActive } from '@dsp/shared';
import { useAdminDefinitions, useAnalyses, useAnalysisMutations, useJobs } from '../../api/hooks';
import { Badge, Button, EmptyState, ErrorAlert, PageHeader, Spinner, useToast } from '../../components/ui';
import { ProgressBar, jobDetail } from '../../components/jobs/JobProgress';

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function AnalysisTypeIcon({ type, size = 18 }: { type: AnalysisDefinition['type']; size?: number }) {
  return type === 'compare' ? <GitCompareArrows size={size} aria-hidden /> : <ShieldCheck size={size} aria-hidden />;
}

export function AnalysisListPage() {
  const analyses = useAnalyses();
  const defs = useAdminDefinitions();
  const jobs = useJobs();
  const { run } = useAnalysisMutations();
  const toast = useToast();
  const name = (id: string) => defs.data?.find((d) => d.id === id)?.name ?? id;
  const runningFor = (id: string) => jobs.data?.find((j) => j.target?.type === 'analysis' && j.target.id === id && jobActive(j));
  const start = async (a: AnalysisDefinition) => {
    try {
      await run.mutateAsync({ id: a.id });
      toast.info(`“${a.name}” is running on the server. You'll be notified when it's ready.`);
    } catch (e) {
      toast.error(e);
    }
  };

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
                <th />
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
                    {runningFor(a.id) ? (
                      <>
                        <div>Running · {jobDetail(runningFor(a.id)!)}</div>
                        <ProgressBar job={runningFor(a.id)!} />
                      </>
                    ) : a.lastRun ? (
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
                  <td className="cell-actions">
                    <Button size="sm" icon={<Play size={14} />} disabled={!!runningFor(a.id)} onClick={() => start(a)}>
                      Run
                    </Button>
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
