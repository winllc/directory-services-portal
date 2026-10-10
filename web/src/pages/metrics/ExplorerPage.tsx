import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Compass, LayoutDashboard, Link2 } from 'lucide-react';
import type { MetricWidget } from '@dsp/shared';
import { useDashboard, useDashboardMutations, useDashboards, useEvaluate, useMetricSources } from '../../api/hooks';
import { useUser } from '../../lib/auth';
import { Button, Card, EmptyState, ErrorAlert, FormRow, Modal, PageHeader, Spinner, useDebounced, useToast } from '../../components/ui';
import { MetricCard } from '../../components/metrics/MetricCard';
import { WidgetEditor } from '../../components/metrics/WidgetEditor';
import { decodeWidget, encodeWidget, newWidget, normalizeWidget } from '../../components/metrics/widgets';

/** Add the explored chart to an existing dashboard, or start a new one with it. */
function AddToDashboard({ widget, onClose }: { widget: MetricWidget; onClose: () => void }) {
  const dashboards = useDashboards();
  const [target, setTarget] = useState('');
  const [name, setName] = useState('');
  const existing = useDashboard(target && target !== 'new' ? target : undefined);
  const { create, update } = useDashboardMutations();
  const toast = useToast();
  const save = async () => {
    const w = { ...normalizeWidget(widget), id: undefined };
    try {
      if (target === 'new') {
        const d = await create.mutateAsync({ name: name.trim(), shared: true, widgets: [w] });
        toast.success(`Created “${d.name}”`);
      } else if (existing.data) {
        const d = existing.data;
        await update.mutateAsync({ id: d.id, name: d.name, description: d.description, shared: d.shared, widgets: [...d.widgets, w] });
        toast.success(`Added to “${d.name}”`);
      }
      onClose();
    } catch (e) {
      toast.error(e);
    }
  };
  return (
    <Modal
      open
      onClose={onClose}
      title="Add to dashboard"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            onClick={save}
            loading={create.isPending || update.isPending}
            disabled={!target || (target === 'new' ? !name.trim() : !existing.data)}
          >
            Add
          </Button>
        </>
      }
    >
      <FormRow label="Dashboard" htmlFor="atd-target">
        <select id="atd-target" className="input select" value={target} onChange={(e) => setTarget(e.target.value)}>
          <option value="">Choose…</option>
          {dashboards.data?.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
          <option value="new">New dashboard…</option>
        </select>
      </FormRow>
      {target === 'new' && (
        <FormRow label="Name" htmlFor="atd-name" hint="Shared with everyone who can read its directories">
          <input id="atd-name" className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </FormRow>
      )}
    </Modal>
  );
}

/**
 * Build a chart from any directory the user can read and see it update as it is configured. The
 * configuration lives in the URL, so an exploration can be bookmarked or sent to someone else.
 */
export function ExplorerPage() {
  const user = useUser();
  const sources = useMetricSources();
  const [params, setParams] = useSearchParams();
  const [widget, setWidget] = useState<MetricWidget | null>(() => decodeWidget(params.get('w')));
  const [adding, setAdding] = useState(false);
  const toast = useToast();

  useEffect(() => {
    if (!widget && sources.data?.length) setWidget(newWidget(sources.data[0]));
  }, [widget, sources.data]);

  const debounced = useDebounced(widget, 400);
  const evaluated = useMemo(() => (debounced && debounced.definitionId ? [{ ...normalizeWidget(debounced), id: 'explore' }] : []), [debounced]);
  const result = useEvaluate(evaluated, evaluated.length > 0 && !!evaluated[0].title);

  useEffect(() => {
    if (!debounced) return;
    const next = encodeWidget(debounced);
    if (params.get('w') !== next) setParams({ w: next }, { replace: true });
  }, [debounced, params, setParams]);

  if (sources.isLoading) return <Spinner label="Loading…" />;
  if (!sources.data?.length) return <EmptyState title="Nothing to explore">You don't have access to any directory.</EmptyState>;
  if (!widget) return <Spinner />;
  const outcome = result.data?.explore;
  const source = sources.data.find((s) => s.id === widget.definitionId);

  return (
    <div className="page page-wide">
      <Link to="/metrics" className="back-link">
        <ArrowLeft size={14} /> Metrics
      </Link>
      <PageHeader
        icon={<Compass size={22} />}
        title="Explore"
        subtitle="Pick a directory and an attribute; the chart updates as you go."
        actions={
          <>
            <Button
              icon={<Link2 size={16} />}
              onClick={() => {
                navigator.clipboard?.writeText(window.location.href).then(
                  () => toast.success('Link copied'),
                  () => toast.info(window.location.href),
                );
              }}
            >
              Copy link
            </Button>
            {user.isAdmin && (
              <Button variant="primary" icon={<LayoutDashboard size={16} />} onClick={() => setAdding(true)} disabled={!outcome?.result}>
                Add to dashboard
              </Button>
            )}
          </>
        }
      />
      <ErrorAlert error={result.error} />
      <div className="explorer">
        <Card title="Chart settings">
          <WidgetEditor value={widget} onChange={setWidget} sources={sources.data} errors={outcome?.details} showLayout={false} />
        </Card>
        <div className="explorer-preview">
          <MetricCard
            widget={{ ...normalizeWidget(widget), width: 'full' }}
            outcome={outcome}
            loading={result.isFetching}
            sourceName={source?.name}
          />
        </div>
      </div>
      {adding && <AddToDashboard widget={widget} onClose={() => setAdding(false)} />}
    </div>
  );
}
