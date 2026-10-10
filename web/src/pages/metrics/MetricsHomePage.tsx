import { Link } from 'react-router-dom';
import { BarChart3, Compass, LayoutDashboard, Plus, Share2 } from 'lucide-react';
import { useDashboards, useMetricSummary } from '../../api/hooks';
import { useUser } from '../../lib/auth';
import { DefinitionIcon } from '../../components/Layout';
import { Badge, Card, EmptyState, ErrorAlert, PageHeader, Spinner } from '../../components/ui';
import { BarChart, compact } from '../../components/charts';

export function MetricsHomePage() {
  const user = useUser();
  const summary = useMetricSummary();
  const dashboards = useDashboards();
  const rows = (summary.data ?? []).filter((r) => r.count !== undefined && r.count !== null);
  const total = rows.reduce((a, r) => a + (r.count ?? 0), 0);

  return (
    <div className="page page-wide">
      <PageHeader
        icon={<BarChart3 size={22} />}
        title="Metrics"
        subtitle="Statistics and charts computed live from the directories you can see."
        actions={
          <>
            <Link to="/metrics/explore" className="btn btn-secondary">
              <Compass size={16} /> Explore
            </Link>
            {user.isAdmin && (
              <Link to="/metrics/dashboards/new/edit" className="btn btn-primary">
                <Plus size={16} /> New dashboard
              </Link>
            )}
          </>
        }
      />

      <section className="stack">
        <h2 className="section-title">Dashboards</h2>
        <ErrorAlert error={dashboards.error} />
        {dashboards.isLoading ? (
          <Spinner />
        ) : dashboards.data?.length ? (
          <div className="tile-grid">
            {dashboards.data.map((d) => (
              <Link key={d.id} to={`/metrics/dashboards/${d.id}`} className="tile">
                <div className="tile-icon">
                  <LayoutDashboard size={20} />
                </div>
                <div className="tile-body">
                  <div className="tile-title">
                    {d.name} {!d.shared && <Badge tone="warning">Admins only</Badge>}
                  </div>
                  {d.description && <div className="tile-desc">{d.description}</div>}
                  <div className="tile-meta">
                    {d.widgets} chart{d.widgets === 1 ? '' : 's'}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <EmptyState
            icon={<Share2 size={22} />}
            title="No dashboards yet"
            action={
              user.isAdmin ? (
                <Link to="/metrics/dashboards/new/edit" className="btn btn-primary">
                  <Plus size={16} /> New dashboard
                </Link>
              ) : undefined
            }
          >
            {user.isAdmin ? 'Combine charts from any directory into a dashboard and share it.' : 'An administrator can share dashboards with you.'}
          </EmptyState>
        )}
      </section>

      <section className="stack">
        <h2 className="section-title">Directory overviews</h2>
        <p className="muted small">A ready-made dashboard for each directory, built from its form: size, growth, breakdowns and completeness.</p>
        <ErrorAlert error={summary.error} />
        {summary.isLoading ? (
          <Spinner />
        ) : (
          <div className="split split-even">
            <div className="tile-grid tile-grid-narrow">
              {summary.data?.map((d) => (
                <Link key={d.id} to={`/metrics/directories/${d.slug}`} className="tile">
                  <div className="tile-icon">
                    <DefinitionIcon icon={d.icon} size={20} />
                  </div>
                  <div className="tile-body">
                    <div className="tile-title">{d.name}</div>
                    <div className="tile-stat">
                      {d.error ? <span className="text-danger small">{d.error}</span> : `${compact(d.count ?? 0)} entries${d.truncated ? '+' : ''}`}
                    </div>
                  </div>
                </Link>
              ))}
            </div>
            {rows.length > 1 && (
              <Card title="Entries per directory" subtitle={`${compact(total)} entries in ${rows.length} directories (some entries can appear in more than one)`}>
                <BarChart
                  label="Entries per directory"
                  rows={[...rows].sort((a, b) => (b.count ?? 0) - (a.count ?? 0)).map((r) => ({ key: r.id, label: r.name, value: r.count ?? 0 }))}
                />
              </Card>
            )}
          </div>
        )}
        {summary.data?.length === 0 && <EmptyState title="No directories">You don't have access to any directory yet.</EmptyState>}
      </section>
    </div>
  );
}
