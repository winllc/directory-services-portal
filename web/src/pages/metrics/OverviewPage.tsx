import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Compass, RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { fetchOverview, useMetricSources, useMetricSummary, useOverview } from '../../api/hooks';
import { JobProgress } from '../../components/jobs/JobProgress';
import { DefinitionIcon } from '../../components/Layout';
import { Button, EmptyState, ErrorAlert, PageHeader, Spinner } from '../../components/ui';
import { MetricGrid } from '../../components/metrics/MetricGrid';
import { encodeWidget } from '../../components/metrics/widgets';

/** The out-of-the-box dashboard of one directory. */
export function OverviewPage() {
  const { slug } = useParams();
  const navigate = useNavigate();
  const summary = useMetricSummary();
  const def = summary.data?.find((d) => d.slug === slug);
  const overview = useOverview(def?.id);
  const sources = useMetricSources();
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<unknown>(null);
  const refresh = async () => {
    if (!def) return;
    setRefreshing(true);
    setRefreshError(null);
    try {
      await overview.refresh(() => fetchOverview(def.id, true));
    } catch (e) {
      setRefreshError(e);
    } finally {
      setRefreshing(false);
    }
  };

  if (summary.isLoading) return <Spinner label="Loading…" />;
  if (!def) {
    return (
      <EmptyState title="Directory not found" action={<Link to="/metrics">Back to metrics</Link>}>
        It doesn't exist, or you don't have access to it.
      </EmptyState>
    );
  }
  return (
    <div className="page page-wide">
      <Link to="/metrics" className="back-link">
        <ArrowLeft size={14} /> Metrics
      </Link>
      <PageHeader
        icon={<DefinitionIcon icon={def.icon} size={22} />}
        title={`${def.name} overview`}
        subtitle={overview.data?.definition.description ?? 'Generated from the directory and its form.'}
        actions={
          <>
            <select
              className="input select select-sm"
              aria-label="Directory"
              value={def.slug}
              onChange={(e) => navigate(`/metrics/directories/${e.target.value}`)}
            >
              {summary.data?.map((d) => (
                <option key={d.id} value={d.slug}>
                  {d.name}
                </option>
              ))}
            </select>
            <Button icon={<RefreshCw size={16} />} loading={refreshing || !!overview.job} onClick={refresh}>
              Refresh
            </Button>
            <Link to={`/d/${def.slug}`} className="btn btn-secondary">
              Open directory
            </Link>
          </>
        }
      />
      <ErrorAlert error={overview.error ?? refreshError} />
      {overview.job && <JobProgress job={overview.job} label="Computing the overview" />}
      {overview.isLoading && <Spinner label="Computing…" />}
      {overview.data && (
        <MetricGrid
          widgets={overview.data.widgets}
          results={overview.data.results}
          loading={overview.isFetching || refreshing}
          sources={sources.data}
          actions={(w) => (
            <Link
              to={`/metrics/explore?w=${encodeWidget(w)}`}
              className="icon-btn icon-btn-sm"
              title="Customize in the explorer"
              aria-label="Customize in the explorer"
            >
              <Compass size={16} />
            </Link>
          )}
        />
      )}
    </div>
  );
}
