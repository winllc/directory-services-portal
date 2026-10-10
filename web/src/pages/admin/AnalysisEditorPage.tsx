import { useEffect, useId, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Play, Plus, Trash2 } from 'lucide-react';
import type {
  AnalysisDefinition,
  AnalysisInput,
  AnalysisResult,
  AnalysisSource,
  AttributeMapping,
  CompareOptions,
  QualityRule,
  RuleFormat,
  RuleType,
} from '@dsp/shared';
import { useAdminDefinitions, useAnalysis, useAnalysisMutations, useAnalysisResult, useMetricSources } from '../../api/hooks';
import { JobProgress } from '../../components/jobs/JobProgress';
import { useAnalysisRun } from '../../components/analysis/useAnalysisRun';
import { Alert, Button, Card, ConfirmDialog, EmptyState, ErrorAlert, FormRow, PageHeader, Spinner, Toggle, useToast } from '../../components/ui';
import { CompareResults } from '../../components/analysis/CompareResults';
import { QualityResults } from '../../components/analysis/QualityResults';
import { AnalysisTypeIcon } from './AnalysisListPage';

const MAX_SOURCES = 6;

const RULE_LABELS: Record<RuleType, string> = {
  required: 'Is present',
  singleValue: 'Has a single value',
  unique: 'Is unique',
  pattern: 'Matches a pattern',
  format: 'Has a valid format',
  allowedValues: 'Is one of a list',
  length: 'Has a length between',
  dnExists: 'Refers to existing entries',
};

const FORMATS: RuleFormat[] = ['email', 'phone', 'url', 'number', 'integer', 'date', 'dn'];

const defaultCompare = (): CompareOptions => ({ attributes: [], ignoreCase: true, normalizeWhitespace: true, ignoreOrder: true, keyIgnoreCase: true });

function blank(type: 'compare' | 'quality', firstDef?: string, secondDef?: string): AnalysisInput {
  return type === 'compare'
    ? {
        name: '',
        type,
        sources: [
          { definitionId: firstDef ?? '', keyAttribute: 'uid' },
          { definitionId: secondDef ?? firstDef ?? '', keyAttribute: 'uid' },
        ],
        compare: { ...defaultCompare(), attributes: [{ label: 'E-mail', attributes: ['mail'] }] },
      }
    : { name: '', type, sources: [{ definitionId: firstDef ?? '' }], quality: { formRules: true, rules: [] } };
}

function toInput(a: AnalysisDefinition): AnalysisInput {
  return { name: a.name, description: a.description, type: a.type, sources: a.sources, compare: a.compare, quality: a.quality };
}

/** Free-text attribute input with suggestions from every directory used. */
function AttrInput({ id, value, onChange, list, placeholder, invalid }: { id?: string; value: string; onChange: (v: string) => void; list: string; placeholder?: string; invalid?: boolean }) {
  return (
    <input
      id={id}
      className="input mono input-sm"
      list={list}
      value={value}
      placeholder={placeholder ?? 'attribute'}
      aria-invalid={invalid || undefined}
      onChange={(e) => onChange(e.target.value.trim())}
    />
  );
}

function RuleEditor({ rule, onChange, onRemove, list, multiSource, error }: { rule: QualityRule; onChange: (r: QualityRule) => void; onRemove: () => void; list: string; multiSource: boolean; error?: string }) {
  const uid = useId();
  const set = (patch: Partial<QualityRule>) => onChange({ ...rule, ...patch });
  return (
    <div className="rule-row">
      <div className="rule-main">
        <AttrInput id={`${uid}-a`} value={rule.attribute} onChange={(attribute) => set({ attribute })} list={list} invalid={!rule.attribute} />
        <select className="input select input-sm" aria-label="Check" value={rule.type} onChange={(e) => set({ type: e.target.value as RuleType })}>
          {(Object.keys(RULE_LABELS) as RuleType[]).map((t) => (
            <option key={t} value={t}>
              {RULE_LABELS[t]}
            </option>
          ))}
        </select>
        {rule.type === 'pattern' && (
          <input className="input mono input-sm" aria-label="Pattern" placeholder="regular expression, e.g. B-\d{4}" value={rule.pattern ?? ''} onChange={(e) => set({ pattern: e.target.value })} />
        )}
        {rule.type === 'format' && (
          <select className="input select input-sm" aria-label="Format" value={rule.format ?? ''} onChange={(e) => set({ format: (e.target.value || undefined) as RuleFormat })}>
            <option value="">Choose…</option>
            {FORMATS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        )}
        {rule.type === 'allowedValues' && (
          <input
            className="input input-sm"
            aria-label="Allowed values"
            placeholder="comma-separated values"
            value={(rule.values ?? []).join(', ')}
            onChange={(e) => set({ values: e.target.value.split(',').map((v) => v.trim()).filter(Boolean) })}
          />
        )}
        {rule.type === 'length' && (
          <span className="input-group">
            <input className="input input-sm" type="number" min={0} aria-label="Minimum length" placeholder="min" value={rule.min ?? ''} onChange={(e) => set({ min: e.target.value === '' ? undefined : Number(e.target.value) })} />
            <input className="input input-sm" type="number" min={0} aria-label="Maximum length" placeholder="max" value={rule.max ?? ''} onChange={(e) => set({ max: e.target.value === '' ? undefined : Number(e.target.value) })} />
          </span>
        )}
        {rule.type === 'unique' && multiSource && (
          <select className="input select input-sm" aria-label="Unique within" value={rule.scope ?? 'source'} onChange={(e) => set({ scope: e.target.value as 'source' | 'all' })}>
            <option value="source">within each source</option>
            <option value="all">across all sources</option>
          </select>
        )}
        <select className="input select input-sm" aria-label="Severity" value={rule.severity ?? 'error'} onChange={(e) => set({ severity: e.target.value as 'error' | 'warning' })}>
          <option value="error">Error</option>
          <option value="warning">Warning</option>
        </select>
        <button type="button" className="icon-btn icon-btn-sm" aria-label="Remove rule" title="Remove rule" onClick={onRemove}>
          <Trash2 size={16} />
        </button>
      </div>
      <input className="input input-sm rule-desc" aria-label="Description" placeholder="Description (optional)" value={rule.description ?? ''} onChange={(e) => set({ description: e.target.value || undefined })} />
      {error && <div className="form-error">{error}</div>}
      {(rule.type === 'unique' || rule.type === 'allowedValues' || rule.type === 'pattern') && (
        <label className="check small">
          <input type="checkbox" checked={rule.ignoreCase !== false} onChange={(e) => set({ ignoreCase: e.target.checked })} /> Ignore case
        </label>
      )}
    </div>
  );
}

export function AnalysisEditorPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const isNew = id === 'new';
  const navigate = useNavigate();
  const toast = useToast();
  const existing = useAnalysis(isNew ? undefined : id);
  const defs = useAdminDefinitions();
  const sources = useMetricSources();
  const { create, update, remove, run } = useAnalysisMutations();
  const [draft, setDraft] = useState<AnalysisInput | null>(null);
  const [saved, setSaved] = useState<string>('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const latest = useAnalysisResult(isNew ? undefined : id);
  const runState = useAnalysisRun(isNew ? undefined : id);
  const listId = useId();

  useEffect(() => {
    if (draft) return;
    if (isNew && defs.data) {
      const type = params.get('type') === 'compare' ? 'compare' : 'quality';
      const d = blank(type, defs.data[0]?.id, defs.data[1]?.id);
      setDraft(d);
    } else if (existing.data) {
      const d = toInput(existing.data);
      setDraft(d);
      setSaved(JSON.stringify(d));
    }
  }, [draft, isNew, defs.data, existing.data, params]);

  const attributes = useMemo(() => {
    const used = new Set(draft?.sources.map((s) => s.definitionId));
    const names = new Set<string>();
    sources.data?.filter((s) => used.has(s.id)).forEach((s) => s.attributes.forEach((a) => names.add(a.attribute)));
    return [...names].sort((a, b) => a.localeCompare(b));
  }, [draft?.sources, sources.data]);

  if (!draft) return existing.isError ? <EmptyState title="Analysis not found" /> : <Spinner label="Loading…" />;

  const dirty = JSON.stringify(draft) !== saved;
  const set = (patch: Partial<AnalysisInput>) => setDraft((d) => (d ? { ...d, ...patch } : d));
  const setSource = (i: number, patch: Partial<AnalysisSource>) => set({ sources: draft.sources.map((s, j) => (j === i ? { ...s, ...patch } : s)) });
  const compare = draft.compare ?? defaultCompare();
  const setCompare = (patch: Partial<CompareOptions>) => set({ compare: { ...compare, ...patch } });
  const quality = draft.quality ?? { formRules: true, rules: [] };
  const errors = (create.error ?? update.error ?? run.error) as { details?: Record<string, string> } | null;
  const err = (k: string) => errors?.details?.[k];
  const isCompare = draft.type === 'compare';

  const cleaned = (): AnalysisInput => ({
    ...draft,
    name: draft.name.trim() || (isCompare ? 'Comparison' : 'Quality check'),
    sources: draft.sources.map((s) => ({
      definitionId: s.definitionId,
      filter: s.filter?.trim() || undefined,
      label: s.label?.trim() || undefined,
      keyAttribute: isCompare ? s.keyAttribute : undefined,
    })),
    compare: isCompare ? { ...compare, attributes: compare.attributes.filter((m) => m.attributes.some(Boolean)) } : undefined,
    quality: isCompare ? undefined : quality,
  });

  const save = async () => {
    try {
      const input = cleaned();
      const a = isNew ? await create.mutateAsync(input) : await update.mutateAsync({ id: id!, ...input });
      toast.success('Analysis saved');
      setSaved(JSON.stringify(toInput(a)));
      setDraft(toInput(a));
      if (isNew) navigate(`/admin/analysis/${a.id}`, { replace: true });
    } catch (e) {
      toast.error(e);
    }
  };

  const runNow = async () => {
    try {
      // Saved and unchanged: run the saved analysis so its result and "last run" are kept.
      const r = !isNew && !dirty ? await run.mutateAsync({ id: id! }) : await run.mutateAsync({ draft: cleaned() });
      runState.start(r.job.id);
    } catch {
      // shown by ErrorAlert
    }
  };

  // A draft run's result once it is the latest thing run here; otherwise the saved result.
  const draftShown = runState.isDraft && runState.draftResult;
  const result: AnalysisResult | undefined = draftShown ? runState.draftResult : (latest.data?.result ?? undefined);

  const minSources = isCompare ? 2 : 1;

  return (
    <div className="page page-wide">
      <Link to="/admin/analysis" className="back-link">
        <ArrowLeft size={14} /> Data analysis
      </Link>
      <PageHeader
        icon={<AnalysisTypeIcon type={draft.type} size={22} />}
        title={isNew ? (isCompare ? 'New comparison' : 'New quality check') : existing.data?.name}
        subtitle={isCompare ? 'Match entries across directories by a key and report what is missing or different.' : 'Check entries for missing, malformed and duplicate values.'}
        actions={
          <>
            {!isNew && (
              <Button variant="danger" icon={<Trash2 size={16} />} onClick={() => setConfirmDelete(true)}>
                Delete
              </Button>
            )}
            <Button onClick={save} loading={create.isPending || update.isPending} disabled={!dirty && !isNew}>
              Save
            </Button>
            <Button variant="primary" icon={<Play size={16} />} onClick={runNow} loading={run.isPending || runState.running}>
              Run{dirty && !isNew ? ' (unsaved)' : ''}
            </Button>
          </>
        }
      />
      <ErrorAlert error={create.error ?? update.error ?? run.error} />
      {runState.job && runState.running && <JobProgress job={runState.job} label={`Running “${runState.job.title}”`} />}
      {runState.job?.status === 'failed' && <Alert tone="danger" title="The run failed">{runState.job.error}</Alert>}
      {runState.job?.status === 'cancelled' && <Alert tone="info">The run was cancelled.</Alert>}
      <datalist id={listId}>
        {attributes.map((a) => (
          <option key={a} value={a} />
        ))}
      </datalist>

      <Card title="General">
        <div className="form-grid">
          <FormRow label="Name" htmlFor="an-name" required>
            <input id="an-name" className="input" value={draft.name} maxLength={128} onChange={(e) => set({ name: e.target.value })} />
          </FormRow>
          <FormRow label="Description" htmlFor="an-desc">
            <input id="an-desc" className="input" value={draft.description ?? ''} maxLength={1000} onChange={(e) => set({ description: e.target.value || undefined })} />
          </FormRow>
        </div>
      </Card>

      <Card
        title="Sources"
        subtitle={isCompare ? 'Entries are matched by their key attribute (e.g. uid, mail or employeeNumber).' : 'Directories to check. Add a filter to check part of one.'}
        actions={
          <Button
            size="sm"
            icon={<Plus size={14} />}
            disabled={draft.sources.length >= MAX_SOURCES}
            onClick={() => set({ sources: [...draft.sources, { definitionId: defs.data?.[0]?.id ?? '', keyAttribute: isCompare ? draft.sources[0]?.keyAttribute : undefined }] })}
          >
            Add source
          </Button>
        }
      >
        {err('sources') && <Alert tone="danger">{err('sources')}</Alert>}
        <div className="source-list">
          {draft.sources.map((s, i) => (
            <div key={i} className="source-row">
              <span className="source-index" aria-hidden>
                {i + 1}
              </span>
              <FormRow label="Directory" htmlFor={`src-${i}`} error={err(`sources[${i}].definitionId`)}>
                <select id={`src-${i}`} className="input select" value={s.definitionId} onChange={(e) => setSource(i, { definitionId: e.target.value })}>
                  {defs.data?.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}
                    </option>
                  ))}
                </select>
              </FormRow>
              {isCompare && (
                <FormRow label="Key attribute" htmlFor={`key-${i}`} error={err(`sources[${i}].keyAttribute`)}>
                  <AttrInput id={`key-${i}`} value={s.keyAttribute ?? ''} onChange={(keyAttribute) => setSource(i, { keyAttribute })} list={listId} />
                </FormRow>
              )}
              <FormRow label="Filter" htmlFor={`flt-${i}`} error={err(`sources[${i}].filter`)}>
                <input id={`flt-${i}`} className="input mono input-sm" placeholder="optional, e.g. (employeeType=Contractor)" value={s.filter ?? ''} onChange={(e) => setSource(i, { filter: e.target.value })} />
              </FormRow>
              <FormRow label="Label" htmlFor={`lbl-${i}`}>
                <input id={`lbl-${i}`} className="input input-sm" placeholder={defs.data?.find((d) => d.id === s.definitionId)?.name} value={s.label ?? ''} onChange={(e) => setSource(i, { label: e.target.value })} />
              </FormRow>
              <button
                type="button"
                className="icon-btn icon-btn-sm source-remove"
                aria-label={`Remove source ${i + 1}`}
                title="Remove source"
                disabled={draft.sources.length <= minSources}
                onClick={() => set({ sources: draft.sources.filter((_, j) => j !== i) })}
              >
                <Trash2 size={16} />
              </button>
            </div>
          ))}
        </div>
      </Card>

      {isCompare ? (
        <Card
          title="Compared attributes"
          subtitle="For entries found in every source, these values must match. Use one attribute for all sources, or a different one per source."
          actions={
            <Button size="sm" icon={<Plus size={14} />} onClick={() => setCompare({ attributes: [...compare.attributes, { attributes: [''] }] })}>
              Add attribute
            </Button>
          }
        >
          {compare.attributes.length === 0 && <p className="muted">No attributes: only whether each key exists in every source is checked.</p>}
          <div className="mapping-list">
            {compare.attributes.map((m, i) => {
              const perSource = m.attributes.length > 1;
              const setMapping = (patch: Partial<AttributeMapping>) => setCompare({ attributes: compare.attributes.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
              return (
                <div key={i} className="mapping-row">
                  <input className="input input-sm" aria-label="Label" placeholder="Label (optional)" value={m.label ?? ''} onChange={(e) => setMapping({ label: e.target.value || undefined })} />
                  <div className="mapping-attrs">
                    {(perSource ? draft.sources : [draft.sources[0]]).map((s, j) => (
                      <AttrInput
                        key={j}
                        value={m.attributes[j] ?? ''}
                        onChange={(v) => setMapping({ attributes: perSource ? draft.sources.map((_, k) => (k === j ? v : (m.attributes[k] ?? ''))) : [v] })}
                        list={listId}
                        placeholder={perSource ? `in ${s.label || defs.data?.find((d) => d.id === s.definitionId)?.name || `source ${j + 1}`}` : 'attribute (all sources)'}
                      />
                    ))}
                  </div>
                  <label className="check small">
                    <input
                      type="checkbox"
                      checked={perSource}
                      onChange={(e) => setMapping({ attributes: e.target.checked ? draft.sources.map(() => m.attributes[0] ?? '') : [m.attributes[0] ?? ''] })}
                    />
                    Different per source
                  </label>
                  <button type="button" className="icon-btn icon-btn-sm" aria-label="Remove attribute" title="Remove" onClick={() => setCompare({ attributes: compare.attributes.filter((_, j) => j !== i) })}>
                    <Trash2 size={16} />
                  </button>
                  {err(`compare.attributes[${i}]`) && <div className="form-error">{err(`compare.attributes[${i}]`)}</div>}
                </div>
              );
            })}
          </div>
          <div className="toggles">
            <Toggle checked={compare.ignoreCase} onChange={(ignoreCase) => setCompare({ ignoreCase })} label="Ignore case" />
            <Toggle checked={compare.normalizeWhitespace} onChange={(normalizeWhitespace) => setCompare({ normalizeWhitespace })} label="Ignore extra spaces" />
            <Toggle checked={compare.ignoreOrder} onChange={(ignoreOrder) => setCompare({ ignoreOrder })} label="Ignore the order of multiple values" />
            <Toggle checked={compare.keyIgnoreCase} onChange={(keyIgnoreCase) => setCompare({ keyIgnoreCase })} label="Match keys regardless of case" />
          </div>
          <p className="muted small">DN values always compare by their normalized form.</p>
        </Card>
      ) : (
        <Card
          title="Rules"
          actions={
            <Button size="sm" icon={<Plus size={14} />} onClick={() => set({ quality: { ...quality, rules: [...quality.rules, { attribute: '', type: 'required' }] } })}>
              Add rule
            </Button>
          }
        >
          <Toggle
            checked={quality.formRules}
            onChange={(formRules) => set({ quality: { ...quality, formRules } })}
            label="Check every form field's rules"
            description="Required fields, single values, formats, patterns, maximum lengths and drop-down options, as configured on each directory's form."
          />
          {err('quality.rules') && <Alert tone="danger">{err('quality.rules')}</Alert>}
          <div className="rule-list">
            {quality.rules.map((r, i) => (
              <RuleEditor
                key={i}
                rule={r}
                list={listId}
                multiSource={draft.sources.length > 1}
                error={Object.entries(errors?.details ?? {}).find(([k]) => k.startsWith(`quality.rules[${i}]`))?.[1]}
                onChange={(next) => set({ quality: { ...quality, rules: quality.rules.map((x, j) => (j === i ? next : x)) } })}
                onRemove={() => set({ quality: { ...quality, rules: quality.rules.filter((_, j) => j !== i) } })}
              />
            ))}
          </div>
        </Card>
      )}

      {result && (
        <section className="stack" aria-label="Results">
          <div className="toolbar">
            <h2 className="section-title">{draftShown ? 'Results (unsaved settings)' : 'Latest results'}</h2>
            <span className="muted small">
              {new Date(result.ranAt).toLocaleString()} · {(result.durationMs / 1000).toFixed(1)} s
              {!draftShown && latest.data ? ` · run by ${latest.data.ranBy}` : ''}
            </span>
          </div>
          {!draftShown && (latest.data?.stale || dirty) && (
            <Alert tone="info">The settings have changed since this run. Run it again to see current results.</Alert>
          )}
          {result.type === 'compare' ? <CompareResults result={result} definitions={defs.data} /> : <QualityResults result={result} definitions={defs.data} />}
        </section>
      )}
      {!result && !runState.job && !isNew && latest.data === null && <p className="muted">This analysis hasn't been run yet.</p>}
      <ConfirmDialog
        open={confirmDelete}
        title="Delete analysis"
        message={`Delete “${existing.data?.name}”?`}
        loading={remove.isPending}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          try {
            await remove.mutateAsync(id!);
            toast.success('Analysis deleted');
            navigate('/admin/analysis');
          } catch (e) {
            toast.error(e);
          }
        }}
      />
    </div>
  );
}
