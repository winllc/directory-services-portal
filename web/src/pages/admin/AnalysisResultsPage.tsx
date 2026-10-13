import { Link, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import type { AnalysisResult } from '@dsp/shared';
import { jobActive } from '@dsp/shared';
import { runFindings, useAdminDefinitions, useJob, useJobResult } from '../../api/hooks';
import { Alert, EmptyState, ErrorAlert, PageHeader, Spinner } from '../../components/ui';
import { JobProgress } from '../../components/jobs/JobProgress';
import { CompareResults } from '../../components/analysis/CompareResults';
import { QualityResults } from '../../components/analysis/QualityResults';
import { AnalysisTypeIcon } from './AnalysisListPage';

/** The result of an unsaved analysis run, kept with its background job. */
export function AnalysisResultsPage() {
  const { jobId } = useParams();
  const job = useJob(jobId);
  const result = useJobResult<AnalysisResult>(jobId, job.data?.status === 'succeeded');
  const defs = useAdminDefinitions();

  if (job.isLoading) return <Spinner label="Loading…" />;
  if (!job.data) {
    return (
      <EmptyState title="Result not available" action={<Link to="/admin/analysis">Data analysis</Link>}>
        Results of unsaved analyses are kept for a day. Save an analysis to keep its latest result.
      </EmptyState>
    );
  }
  const r = result.data?.result;
  return (
    <div className="page page-wide">
      <Link to="/admin/analysis" className="back-link">
        <ArrowLeft size={14} /> Data analysis
      </Link>
      <PageHeader
        icon={<AnalysisTypeIcon type={r?.type ?? 'quality'} size={22} />}
        title={job.data.title}
        subtitle="Results of an unsaved analysis. Save the analysis to keep its latest result."
      />
      {jobActive(job.data) && <JobProgress job={job.data} label="Running" />}
      {job.data.status === 'failed' && <Alert tone="danger" title="The run failed">{job.data.error}</Alert>}
      {job.data.status === 'cancelled' && <Alert tone="info">The run was cancelled.</Alert>}
      <ErrorAlert error={result.error} />
      {r && (
        <>
          <span className="muted small">
            {new Date(r.ranAt).toLocaleString()} · {(r.durationMs / 1000).toFixed(1)} s
          </span>
          {r.type === 'compare' ? (
            <CompareResults result={r} definitions={defs.data} findings={runFindings(jobId!, r.ranAt)} name={job.data.title} />
          ) : (
            <QualityResults result={r} definitions={defs.data} findings={runFindings(jobId!, r.ranAt)} name={job.data.title} />
          )}
        </>
      )}
    </div>
  );
}
