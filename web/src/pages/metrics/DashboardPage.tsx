import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Compass, LayoutDashboard, Pencil, RefreshCw } from 'lucide-react';
import { useDashboard, useDashboardResults, useMetricSources, useRefreshMetrics } from '../../api/hooks';
import { useUser } from '../../lib/auth';
import { Badge, Button, EmptyState, ErrorAlert, PageHeader, Spinner } from '../../components/ui';
import { MetricGrid } from '../../components/metrics/MetricGrid';
import { encodeWidget } from '../../components/metrics/widgets';

export function DashboardPage() {
  const { id } = useParams();
  const user = useUser();
  const dashboard = useDashboard(id);
  const results = useDashboardResults(id, !!dashboard.data);
  const sources = useMetricSources();
  const refresh = useRefreshMetrics();

  if (dashboard.isLoading) return <Spinner label="Loading…" />;
  if (!dashboard.data) {
    return (
      <EmptyState title="Dashboard not found" action={<Link to="/metrics">Back to metrics</Link>}>
        <ErrorAlert error={dashboard.error} />
      </EmptyState>
    );
  }
  const d = dashboard.data;
  return (
    <div className="page page-wide">
      <Link to="/metrics" className="back-link">
        <ArrowLeft size={14} /> Metrics
      </Link>
      <PageHeader
        icon={<LayoutDashboard size={22} />}
        title={
          <>
            {d.name} {!d.shared && <Badge tone="warning">Admins only</Badge>}
          </>
        }
        subtitle={d.description}
        actions={
          <>
            <Button icon={<RefreshCw size={16} />} loading={refresh.isPending} onClick={() => refresh.mutate({ dashboard: d.id })}>
              Refresh
            </Button>
            {user.isAdmin && (
              <Link to={`/metrics/dashboards/${d.id}/edit`} className="btn btn-primary">
                <Pencil size={16} /> Edit
              </Link>
            )}
          </>
        }
      />
      <ErrorAlert error={results.error ?? refresh.error} />
      {d.widgets.length === 0 ? (
        <EmptyState title="This dashboard is empty">{user.isAdmin ? 'Edit it to add charts.' : 'No charts have been added yet.'}</EmptyState>
      ) : (
        <MetricGrid
          widgets={d.widgets}
          results={results.data?.results}
          loading={results.isFetching || refresh.isPending}
          sources={sources.data}
          actions={(w) => (
            <Link to={`/metrics/explore?w=${encodeWidget(w)}`} className="icon-btn icon-btn-sm" title="Open in the explorer" aria-label="Open in the explorer">
              <Compass size={16} />
            </Link>
          )}
        />
      )}
    </div>
  );
}
