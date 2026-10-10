import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowDown, ArrowLeft, ArrowUp, Copy, LayoutDashboard, Pencil, Plus, Trash2 } from 'lucide-react';
import type { MetricWidget } from '@dsp/shared';
import { useDashboard, useDashboardMutations, useEvaluate, useMetricSources } from '../../api/hooks';
import { Button, Card, ConfirmDialog, EmptyState, ErrorAlert, FormRow, Modal, PageHeader, Spinner, Toggle, useDebounced, useToast } from '../../components/ui';
import { MetricCard } from '../../components/metrics/MetricCard';
import { MetricGrid } from '../../components/metrics/MetricGrid';
import { WidgetEditor } from '../../components/metrics/WidgetEditor';
import { newWidget, normalizeWidget } from '../../components/metrics/widgets';

let draftIds = 0;
const draftId = () => `draft-${Date.now().toString(36)}-${++draftIds}`;

/** Edit one chart in a dialog with a live preview. */
function WidgetDialog({ initial, adding, onSave, onClose }: { initial: MetricWidget; adding: boolean; onSave: (w: MetricWidget) => void; onClose: () => void }) {
  const sources = useMetricSources();
  const [w, setW] = useState(initial);
  const debounced = useDebounced(w, 400);
  const preview = useMemo(() => (debounced.definitionId ? [{ ...normalizeWidget(debounced), id: 'preview' }] : []), [debounced]);
  const result = useEvaluate(preview, !!debounced.title);
  const outcome = result.data?.preview;
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={adding ? 'Add a chart' : `Edit “${initial.title}”`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!w.title.trim() || !w.definitionId} onClick={() => onSave(normalizeWidget(w))}>
            {adding ? 'Add chart' : 'Apply'}
          </Button>
        </>
      }
    >
      <div className="widget-dialog">
        <WidgetEditor value={w} onChange={setW} sources={sources.data ?? []} errors={outcome?.details} />
        <MetricCard widget={{ ...normalizeWidget(w), width: 'full' }} outcome={outcome} loading={result.isFetching} />
      </div>
    </Modal>
  );
}

export function DashboardEditorPage() {
  const { id } = useParams();
  const isNew = id === 'new';
  const navigate = useNavigate();
  const toast = useToast();
  const existing = useDashboard(isNew ? undefined : id);
  const sources = useMetricSources();
  const { create, update, remove } = useDashboardMutations();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [shared, setShared] = useState(true);
  const [widgets, setWidgets] = useState<MetricWidget[]>([]);
  const [editing, setEditing] = useState<{ index: number; widget: MetricWidget } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [loaded, setLoaded] = useState(isNew);

  useEffect(() => {
    if (existing.data && !loaded) {
      setName(existing.data.name);
      setDescription(existing.data.description ?? '');
      setShared(existing.data.shared);
      setWidgets(existing.data.widgets);
      setLoaded(true);
    }
  }, [existing.data, loaded]);

  const keyed = useMemo(() => widgets.map((w) => ({ ...w, id: w.id ?? draftId() })), [widgets]);
  const debounced = useDebounced(keyed, 300);
  const preview = useEvaluate(debounced, debounced.length > 0);

  if (!loaded) return existing.isLoading ? <Spinner label="Loading…" /> : <EmptyState title="Dashboard not found" />;

  const move = (i: number, d: number) =>
    setWidgets((ws) => {
      const next = [...ws];
      const [w] = next.splice(i, 1);
      next.splice(Math.max(0, Math.min(next.length, i + d)), 0, w);
      return next;
    });

  const save = async () => {
    const input = { name: name.trim(), description: description.trim() || undefined, shared, widgets: keyed.map((w) => ({ ...w, id: w.id?.startsWith('draft-') ? undefined : w.id })) };
    try {
      const d = isNew ? await create.mutateAsync(input) : await update.mutateAsync({ id: id!, ...input });
      toast.success('Dashboard saved');
      navigate(`/metrics/dashboards/${d.id}`);
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <div className="page page-wide">
      <Link to={isNew ? '/metrics' : `/metrics/dashboards/${id}`} className="back-link">
        <ArrowLeft size={14} /> {isNew ? 'Metrics' : 'Dashboard'}
      </Link>
      <PageHeader
        icon={<LayoutDashboard size={22} />}
        title={isNew ? 'New dashboard' : `Edit ${existing.data?.name ?? 'dashboard'}`}
        actions={
          <>
            {!isNew && (
              <Button variant="danger" icon={<Trash2 size={16} />} onClick={() => setConfirmDelete(true)}>
                Delete
              </Button>
            )}
            <Button variant="primary" onClick={save} loading={create.isPending || update.isPending} disabled={!name.trim()}>
              Save
            </Button>
          </>
        }
      />
      <ErrorAlert error={create.error ?? update.error} />
      <Card title="General">
        <div className="form-grid">
          <FormRow label="Name" htmlFor="dash-name" required>
            <input id="dash-name" className="input" value={name} maxLength={128} onChange={(e) => setName(e.target.value)} />
          </FormRow>
          <FormRow label="Description" htmlFor="dash-desc">
            <input id="dash-desc" className="input" value={description} maxLength={1000} onChange={(e) => setDescription(e.target.value)} />
          </FormRow>
          <div className="span-2">
            <Toggle
              checked={shared}
              onChange={setShared}
              label="Share with all signed-in users"
              description="People only see charts from directories they can read; the rest show as locked."
            />
          </div>
        </div>
      </Card>

      <div className="toolbar">
        <h2 className="section-title">Charts ({widgets.length})</h2>
        <Button variant="primary" icon={<Plus size={16} />} onClick={() => setEditing({ index: -1, widget: newWidget(sources.data?.[0]) })} disabled={!sources.data?.length}>
          Add chart
        </Button>
      </div>
      {widgets.length === 0 ? (
        <EmptyState title="No charts yet">Add a chart, or build one in the explorer and use “Add to dashboard”.</EmptyState>
      ) : (
        <MetricGrid
          widgets={keyed}
          results={preview.data}
          loading={preview.isFetching}
          sources={sources.data}
          actions={(w, i) => (
            <>
              <button type="button" className="icon-btn icon-btn-sm" title="Move earlier" aria-label="Move earlier" disabled={i === 0} onClick={() => move(i, -1)}>
                <ArrowUp size={16} />
              </button>
              <button type="button" className="icon-btn icon-btn-sm" title="Move later" aria-label="Move later" disabled={i === widgets.length - 1} onClick={() => move(i, 1)}>
                <ArrowDown size={16} />
              </button>
              <button type="button" className="icon-btn icon-btn-sm" title="Edit" aria-label="Edit" onClick={() => setEditing({ index: i, widget: widgets[i] })}>
                <Pencil size={16} />
              </button>
              <button
                type="button"
                className="icon-btn icon-btn-sm"
                title="Duplicate"
                aria-label="Duplicate"
                onClick={() => setWidgets((ws) => [...ws.slice(0, i + 1), { ...w, id: draftId(), title: `${w.title} (copy)` }, ...ws.slice(i + 1)])}
              >
                <Copy size={16} />
              </button>
              <button type="button" className="icon-btn icon-btn-sm" title="Remove" aria-label="Remove" onClick={() => setWidgets((ws) => ws.filter((_, j) => j !== i))}>
                <Trash2 size={16} />
              </button>
            </>
          )}
        />
      )}
      {editing && (
        <WidgetDialog
          initial={editing.widget}
          adding={editing.index < 0}
          onClose={() => setEditing(null)}
          onSave={(w) => {
            setWidgets((ws) => (editing.index < 0 ? [...ws, { ...w, id: draftId() }] : ws.map((x, j) => (j === editing.index ? { ...w, id: x.id } : x))));
            setEditing(null);
          }}
        />
      )}
      <ConfirmDialog
        open={confirmDelete}
        title="Delete dashboard"
        message={`Delete “${existing.data?.name}”? Its charts are removed for everyone.`}
        loading={remove.isPending}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          try {
            await remove.mutateAsync(id!);
            toast.success('Dashboard deleted');
            navigate('/metrics');
          } catch (e) {
            toast.error(e);
          }
        }}
      />
    </div>
  );
}
