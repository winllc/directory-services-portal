import { Fragment, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Download,
  History,
  KeyRound,
  Plus,
  Search,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-react';
import type { AuditEvent, AuditOutcome, AuditSeverity, AuditToken, AuditVerification } from '@dsp/shared';
import {
  downloadAuditExport,
  useAudit,
  useAuditActions,
  useAuditStatus,
  useAuditTokens,
  useCreateAuditToken,
  useRevokeAuditToken,
  useVerifyAudit,
  type AuditFilters,
} from '../../api/hooks';
import {
  Alert,
  Badge,
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  ErrorAlert,
  FormRow,
  Modal,
  PageHeader,
  Spinner,
  Tabs,
  useDebounced,
  useToast,
} from '../../components/ui';

const PAGE_SIZE = 50;

const CATEGORY_LABELS: Record<string, string> = {
  authentication: 'Sign-ins',
  authorization: 'Access denied',
  account: 'Permissions',
  configuration: 'Configuration',
  data: 'Directory data, forms and schema',
  license: 'License',
  system: 'System',
  audit: 'Audit log',
};

export const ACTION_LABELS: Record<string, string> = {
  'auth.login': 'Signed in',
  'auth.logout': 'Signed out',
  'auth.lockout': 'Sign-in locked out',
  'auth.password-changed': 'Changed own password',
  'auth.password-reset': 'Reset a password',
  'access.denied': 'Access denied',
  'entry.create': 'Created entry',
  'entry.update': 'Updated entry',
  'entry.self_update': 'Updated own entry',
  'entry.delete': 'Deleted entry',
  'grant.save': 'Granted access',
  'grant.revoke': 'Revoked access',
  'definition.create': 'Created directory',
  'definition.update': 'Updated directory',
  'definition.delete': 'Deleted directory',
  'form.create': 'Created form',
  'form.update': 'Updated form',
  'form.delete': 'Deleted form',
  'schema.import': 'Imported schema',
  'schema.attribute_type.save': 'Saved attribute type',
  'schema.attribute_type.delete': 'Deleted attribute type',
  'schema.object_class.save': 'Saved object class',
  'schema.object_class.delete': 'Deleted object class',
  'dashboard.create': 'Created dashboard',
  'dashboard.update': 'Updated dashboard',
  'dashboard.delete': 'Deleted dashboard',
  'analysis.create': 'Created analysis',
  'analysis.update': 'Updated analysis',
  'analysis.delete': 'Deleted analysis',
  'analysis.export': 'Downloaded analysis findings',
  'data.exported': 'Downloaded data',
  'license.installed': 'Installed a license',
  'license.rejected': 'License refused',
  'license.install': 'Uploaded a license',
  'license.mode-changed': 'License mode changed',
  'system.started': 'Portal started',
  'system.stopped': 'Portal stopped',
  'job.completed': 'Job completed',
  'job.failed': 'Job failed',
  'audit.exported': 'Exported the audit log',
  'audit.verified': 'Verified the audit log',
  'audit.token-created': 'Created an audit API token',
  'audit.token-revoked': 'Revoked an audit API token',
  'audit.pruned': 'Pruned old audit events',
  'audit.delivery-failed': 'Audit delivery failing',
  'audit.delivery-recovered': 'Audit delivery recovered',
};

const FAILED_LABELS: Record<string, string> = { 'auth.login': 'Sign-in failed' };

export function actionLabel(e: Pick<AuditEvent, 'action' | 'outcome'>): string {
  if (e.outcome !== 'success' && FAILED_LABELS[e.action]) return FAILED_LABELS[e.action];
  return ACTION_LABELS[e.action] ?? e.action;
}

function OutcomeBadge({ outcome }: { outcome: AuditOutcome }) {
  if (outcome === 'success') return <Badge tone="success">Success</Badge>;
  if (outcome === 'denied') return <Badge tone="warning">Denied</Badge>;
  return <Badge tone="danger">Failed</Badge>;
}

const SEVERITY_TONES: Record<AuditSeverity, 'neutral' | 'info' | 'warning' | 'danger'> = {
  info: 'neutral',
  notice: 'info',
  warning: 'warning',
  alert: 'danger',
};

function SeverityBadge({ severity }: { severity: AuditSeverity }) {
  return <Badge tone={SEVERITY_TONES[severity] ?? 'neutral'}>{severity}</Badge>;
}

const formatTime = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

function Time({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="muted">—</span>;
  return <time dateTime={iso} title={`${iso} (UTC)`}>{formatTime(iso)}</time>;
}

function actorText(e: AuditEvent): string {
  if (e.actor.type === 'system') return 'System';
  if (e.actor.type === 'anonymous') return e.actor.id ? `${e.actor.id} (not signed in)` : 'Not signed in';
  return e.actor.name && e.actor.name !== e.actor.id ? `${e.actor.id} (${e.actor.name})` : (e.actor.id ?? e.actor.type);
}

/** A date or datetime-local value as the API takes it: dates as is, local times as UTC instants. */
function toApiTime(v: string): string {
  if (!v || v.length === 10) return v;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toISOString();
}

// ---------------------------------------------------------------------------
// Event detail
// ---------------------------------------------------------------------------

function EventDetail({ event, onClose, onFilter }: { event: AuditEvent; onClose: () => void; onFilter: (f: Partial<Record<string, string>>) => void }) {
  const changes = useMemo(() => {
    const fields = new Map<string, { old?: unknown; new?: unknown }>();
    const other: [string, unknown][] = [];
    for (const [k, v] of Object.entries(event.details ?? {})) {
      const m = /^(.*)\.(old|new)$/.exec(k);
      if (m) {
        const f = fields.get(m[1]) ?? {};
        f[m[2] as 'old' | 'new'] = v;
        fields.set(m[1], f);
      } else {
        other.push([k, v]);
      }
    }
    return { fields: [...fields.entries()], other };
  }, [event]);
  const facts: [string, React.ReactNode][] = [
    ['Time', <Time key="t" iso={event.time} />],
    ['Action', <><code>{event.action}</code> · {actionLabel(event)}</>],
    ['Category', CATEGORY_LABELS[event.category] ?? event.category],
    ['Outcome', <OutcomeBadge key="o" outcome={event.outcome} />],
    ['Severity', <SeverityBadge key="s" severity={event.severity} />],
    ['Actor', `${actorText(event)} · ${event.actor.type}${event.actor.roles.length ? ` · ${event.actor.roles.join(', ')}` : ''}`],
    ['Source', event.source ? `${event.source.ip ?? '—'}${event.source.userAgent ? ` · ${event.source.userAgent}` : ''}` : '—'],
    ['Target', event.target ? `${event.target.type ?? ''} ${event.target.name ?? ''}${event.target.id && event.target.id !== event.target.name ? ` (${event.target.id})` : ''}` : '—'],
    ['Reason', event.reason ?? '—'],
    ['Seq', String(event.seq)],
    ['Event ID', <code key="id">{event.id}</code>],
    ['Request', event.requestId ? <code key="r">{event.requestId}</code> : '—'],
    ['Session', event.session ? <code key="se">{event.session}</code> : '—'],
    ['Product', `${event.product} ${event.version} on ${event.host}${event.installationId ? ` · ${event.installationId}` : ''}`],
    ['Previous hash', <code key="p" className="audit-hash">{event.prevHash}</code>],
    ['Hash', <code key="h" className="audit-hash">{event.hash}</code>],
  ];
  const show = (v: unknown) => (v === null || v === undefined ? <span className="muted">—</span> : String(v));
  return (
    <Modal open onClose={onClose} wide title={`${actionLabel(event)} · seq ${event.seq}`}>
      <dl className="audit-facts">
        {facts.map(([k, v]) => (
          <Fragment key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </Fragment>
        ))}
      </dl>
      <div className="audit-detail-links">
        {event.actor.id && (
          <Button size="sm" onClick={() => onFilter({ actor: String(event.details.actorDn ?? event.actor.id) })}>
            Everything by this actor
          </Button>
        )}
        {event.target?.id && (
          <Button size="sm" onClick={() => onFilter({ target: event.target!.id! })}>
            Everything on this target
          </Button>
        )}
        {event.requestId && (
          <Button size="sm" onClick={() => onFilter({ q: event.requestId! })}>
            Same request
          </Button>
        )}
      </div>
      {changes.fields.length > 0 && (
        <table className="table table-compact audit-changes">
          <thead>
            <tr>
              <th>Field</th>
              <th>Before</th>
              <th>After</th>
            </tr>
          </thead>
          <tbody>
            {changes.fields.map(([field, v]) => (
              <tr key={field}>
                <td>
                  <code>{field}</code>
                </td>
                <td className="audit-value">{show(v.old)}</td>
                <td className="audit-value">{show(v.new)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {changes.other.length > 0 && (
        <dl className="audit-facts">
          {changes.other.map(([k, v]) => (
            <Fragment key={k}>
              <dt>{k}</dt>
              <dd className="audit-value">{show(v)}</dd>
            </Fragment>
          ))}
        </dl>
      )}
      <h3 className="audit-json-title">JSON</h3>
      <pre className="audit-json" aria-label="Event JSON">
        {JSON.stringify(event, null, 2)}
      </pre>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Events tab
// ---------------------------------------------------------------------------

const TEXT_FILTERS = ['q', 'actor', 'target', 'ip'] as const;

function EventsTab() {
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const [text, setText] = useState(() => Object.fromEntries(TEXT_FILTERS.map((k) => [k, params.get(k) ?? ''])) as Record<(typeof TEXT_FILTERS)[number], string>);
  const debounced = useDebounced(text, 300);
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);
  const actions = useAuditActions();

  const category = params.get('category') ?? '';
  const action = params.get('action') ?? '';
  const outcome = params.get('outcome') ?? '';
  const severity = params.get('severity') ?? '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const page = Math.max(Number(params.get('page')) || 1, 1);

  const setParam = (next: Record<string, string>) => {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v);
      else p.delete(k);
    }
    if (!('page' in next)) p.delete('page');
    setParams(p, { replace: true });
  };

  // Text filters follow the URL (links from other pages) and update it once typing pauses.
  useEffect(() => {
    const changed = TEXT_FILTERS.filter((k) => (params.get(k) ?? '') !== debounced[k]);
    if (changed.length) setParam(Object.fromEntries(changed.map((k) => [k, debounced[k]])));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced]);

  const filters: AuditFilters = {
    q: params.get('q') ?? '',
    category,
    action,
    outcome,
    severity,
    actor: params.get('actor') ?? '',
    target: params.get('target') ?? '',
    ip: params.get('ip') ?? '',
    from: toApiTime(from),
    to: toApiTime(to),
  };
  const audit = useAudit({ ...filters, page, pageSize: PAGE_SIZE });
  const data = audit.data;
  const pages = data ? Math.max(Math.ceil(data.total / data.pageSize), 1) : 1;
  const actionChoices = (actions.data?.actions ?? []).filter((a) => !category || a.category === category);
  const filtered = Object.entries(filters).some(([, v]) => v);

  const exportAs = async (format: 'csv' | 'ndjson') => {
    setExporting(format);
    try {
      const count = await downloadAuditExport(format, filters);
      toast.success(`Exported ${count.toLocaleString()} event${count === 1 ? '' : 's'} as ${format.toUpperCase()}.`);
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(null);
    }
  };

  return (
    <>
      <div className="audit-filters" role="search">
        <div className="search-box">
          <Search size={16} aria-hidden />
          <input
            className="input"
            type="search"
            placeholder="Search the event JSON…"
            value={text.q}
            onChange={(e) => setText({ ...text, q: e.target.value })}
            aria-label="Search the audit log"
          />
        </div>
        <select className="input select select-sm" value={category} onChange={(e) => setParam({ category: e.target.value, action: '' })} aria-label="Category">
          <option value="">All categories</option>
          {(actions.data?.categories ?? Object.keys(CATEGORY_LABELS)).map((c) => (
            <option key={c} value={c}>
              {CATEGORY_LABELS[c] ?? c}
            </option>
          ))}
        </select>
        <select className="input select select-sm" value={action} onChange={(e) => setParam({ action: e.target.value })} aria-label="Action">
          <option value="">All actions</option>
          {action && !actionChoices.some((a) => a.action === action) && <option value={action}>{action}</option>}
          {actionChoices.map((a) => (
            <option key={a.action} value={a.action}>
              {a.action}
            </option>
          ))}
        </select>
        <select className="input select select-sm" value={outcome} onChange={(e) => setParam({ outcome: e.target.value })} aria-label="Outcome">
          <option value="">Any outcome</option>
          <option value="success">Succeeded</option>
          <option value="denied">Denied</option>
          <option value="failure">Failed</option>
        </select>
        <select className="input select select-sm" value={severity} onChange={(e) => setParam({ severity: e.target.value })} aria-label="Severity">
          <option value="">Any severity</option>
          {(['info', 'notice', 'warning', 'alert'] as const).map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <input className="input input-sm" placeholder="Actor contains…" value={text.actor} onChange={(e) => setText({ ...text, actor: e.target.value })} aria-label="Actor" />
        <input className="input input-sm" placeholder="Target contains…" value={text.target} onChange={(e) => setText({ ...text, target: e.target.value })} aria-label="Target" />
        <input className="input input-sm audit-ip" placeholder="Source IP" value={text.ip} onChange={(e) => setText({ ...text, ip: e.target.value })} aria-label="Source IP" />
        <label className="audit-range">
          <span className="muted small">From</span>
          <input className="input input-sm audit-date" type="datetime-local" value={from} max={to || undefined} onChange={(e) => setParam({ from: e.target.value })} aria-label="From" />
        </label>
        <label className="audit-range">
          <span className="muted small">to</span>
          <input className="input input-sm audit-date" type="datetime-local" value={to} min={from || undefined} onChange={(e) => setParam({ to: e.target.value })} aria-label="To" />
        </label>
        {filtered && (
          <Button
            size="sm"
            variant="ghost"
            icon={<X size={14} />}
            onClick={() => {
              setText({ q: '', actor: '', target: '', ip: '' });
              setParams(new URLSearchParams(), { replace: true });
            }}
          >
            Clear
          </Button>
        )}
        <div className="toolbar-right">
          <Button size="sm" icon={<Download size={14} />} loading={exporting === 'csv'} onClick={() => exportAs('csv')}>
            Export CSV
          </Button>
          <Button size="sm" icon={<Download size={14} />} loading={exporting === 'ndjson'} onClick={() => exportAs('ndjson')}>
            Export NDJSON
          </Button>
        </div>
      </div>

      <ErrorAlert error={audit.error} />

      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Severity</th>
              <th>Action</th>
              <th>Outcome</th>
              <th>Actor</th>
              <th>Target</th>
              <th>Source IP</th>
            </tr>
          </thead>
          <tbody>
            {data?.events.map((e) => (
              <tr
                key={e.id}
                className="row-link"
                tabIndex={0}
                onClick={() => setOpen(e)}
                onKeyDown={(ev) => {
                  if (ev.key === 'Enter') setOpen(e);
                }}
              >
                <td className="nowrap small">
                  <Time iso={e.time} />
                </td>
                <td>
                  <SeverityBadge severity={e.severity} />
                </td>
                <td>
                  {actionLabel(e)}
                  <div className="muted small">
                    <code>{e.action}</code>
                  </div>
                </td>
                <td>
                  <OutcomeBadge outcome={e.outcome} />
                </td>
                <td>{e.actor.type === 'user' ? actorText(e) : <span className="muted">{actorText(e)}</span>}</td>
                <td>{e.target ? (e.target.name ?? e.target.id) : <span className="muted">—</span>}</td>
                <td className="nowrap small">{e.source?.ip ?? <span className="muted">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {audit.isLoading && <Spinner />}
        {data && data.events.length === 0 && (
          <EmptyState icon={<History size={28} />} title="No matching activity">
            Nothing in the audit log matches these filters.
          </EmptyState>
        )}
      </div>

      {data && (
        <div className="pagination">
          <Button size="sm" icon={<ChevronLeft size={14} />} disabled={page <= 1} onClick={() => setParam({ page: String(page - 1) })}>
            Previous
          </Button>
          <span className="muted">
            {data.total.toLocaleString()} event{data.total === 1 ? '' : 's'} · page {page} of {pages}
          </span>
          <Button size="sm" disabled={page >= pages} onClick={() => setParam({ page: String(page + 1) })}>
            Next <ChevronRight size={14} />
          </Button>
        </div>
      )}

      {data?.truncated && (
        <Alert tone="info">
          This search stopped after reading the newest {data.scanned.toLocaleString()} events. Narrow it with a start time to search further back.
        </Alert>
      )}

      {open && (
        <EventDetail
          event={open}
          onClose={() => setOpen(null)}
          onFilter={(f) => {
            setOpen(null);
            setText((t) => ({ ...t, ...f }));
            setParam(f as Record<string, string>);
          }}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Delivery tab
// ---------------------------------------------------------------------------

const OUTPUT_LABELS: Record<string, string> = {
  stdout: 'Standard output',
  file: 'File',
  splunk: 'Splunk HEC',
  syslog: 'Syslog',
};

function DeliveryTab() {
  const status = useAuditStatus();
  const s = status.data;
  return (
    <>
      <ErrorAlert error={status.error} />
      {status.isLoading && <Spinner />}
      {s && (
        <>
          <div className="table-wrap">
            <table className="table" aria-label="Delivery status">
              <thead>
                <tr>
                  <th>Output</th>
                  <th>Status</th>
                  <th>Destination</th>
                  <th>Last delivered</th>
                  <th>Backlog</th>
                  <th>Last error</th>
                </tr>
              </thead>
              <tbody>
                {s.outputs.map((o) => (
                  <tr key={o.output}>
                    <td>{OUTPUT_LABELS[o.output] ?? o.output}</td>
                    <td>
                      {!o.enabled ? (
                        <Badge>Off</Badge>
                      ) : o.failingSince ? (
                        <Badge tone="danger">Failing</Badge>
                      ) : (
                        <Badge tone="success">On</Badge>
                      )}
                    </td>
                    <td className="small">{o.destination ?? <span className="muted">—</span>}</td>
                    <td className="small">
                      {o.lastDeliveredSeq !== null ? `seq ${o.lastDeliveredSeq}` : '—'}
                      {o.lastDeliveredAt && (
                        <div className="muted">
                          <Time iso={o.lastDeliveredAt} />
                        </div>
                      )}
                      {o.enabled && !o.background && <div className="muted">written as each event is stored</div>}
                    </td>
                    <td>{o.backlog ?? '—'}</td>
                    <td className="small">
                      {o.lastError ? (
                        <>
                          {o.lastError}
                          <div className="muted">
                            <Time iso={o.lastErrorAt} />
                          </div>
                        </>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <dl className="audit-facts audit-settings">
            <dt>Retention</dt>
            <dd>
              {s.retentionDays > 0 ? `${s.retentionDays} days (events every output has delivered are pruned daily)` : 'Kept forever'}{' '}
              <span className="muted small">· set by ENS_AUDIT_RETENTION_DAYS</span>
            </dd>
            <dt>Events</dt>
            <dd>
              {s.oldestSeq !== null ? `seq ${s.oldestSeq} to ${s.headSeq}, since ` : 'None yet'}
              {s.oldestTime && <Time iso={s.oldestTime} />}
            </dd>
            <dt>Store</dt>
            <dd>
              <code>{s.store}</code> <span className="muted small">· {s.writer}</span>
            </dd>
          </dl>
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// API tokens tab
// ---------------------------------------------------------------------------

function TokensTab() {
  const tokens = useAuditTokens();
  const create = useCreateAuditToken();
  const revoke = useRevokeAuditToken();
  const toast = useToast();
  const [name, setName] = useState('');
  const [created, setCreated] = useState<{ token: AuditToken; secret: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [revoking, setRevoking] = useState<AuditToken | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate(name.trim(), {
      onSuccess: (r) => {
        setCreated(r);
        setCopied(false);
        setName('');
      },
    });
  };

  return (
    <>
      <p className="muted">
        SIEMs such as Splunk can pull the audit trail from <code>GET /api/audit/events?after=&lt;seq&gt;&amp;limit=500</code> with{' '}
        <code>Authorization: Bearer &lt;token&gt;</code>. Tokens can only read the audit log, and keep working while the portal is unlicensed or
        read-only.
      </p>
      <form className="audit-token-form" onSubmit={submit}>
        <FormRow label="New token" htmlFor="audit-token-name">
          <input id="audit-token-name" className="input" placeholder="e.g. Splunk production" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </FormRow>
        <Button type="submit" variant="primary" icon={<Plus size={14} />} disabled={!name.trim()} loading={create.isPending}>
          Create token
        </Button>
      </form>
      <ErrorAlert error={create.error ?? tokens.error ?? revoke.error} />

      <div className="table-wrap">
        <table className="table" aria-label="API tokens">
          <thead>
            <tr>
              <th>Name</th>
              <th>Token</th>
              <th>Created</th>
              <th>Last used</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {tokens.data?.map((t) => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td>
                  <code>{t.prefix}…</code>
                </td>
                <td className="small">
                  <Time iso={t.createdAt} /> <span className="muted">by {t.createdBy}</span>
                </td>
                <td className="small">{t.lastUsedAt ? <Time iso={t.lastUsedAt} /> : <span className="muted">Never</span>}</td>
                <td>
                  {t.active ? (
                    <Badge tone="success">Active</Badge>
                  ) : (
                    <Badge title={t.revokedAt ? `Revoked ${t.revokedAt} by ${t.revokedBy}` : undefined}>Revoked</Badge>
                  )}
                </td>
                <td className="cell-actions">
                  {t.active && (
                    <Button size="sm" variant="ghost" icon={<Trash2 size={14} />} onClick={() => setRevoking(t)}>
                      Revoke
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {tokens.isLoading && <Spinner />}
        {tokens.data?.length === 0 && (
          <EmptyState icon={<KeyRound size={28} />} title="No API tokens">
            Create one for each SIEM or forwarder that pulls the audit log.
          </EmptyState>
        )}
      </div>

      <Modal
        open={!!created}
        onClose={() => setCreated(null)}
        title="Token created"
        footer={<Button onClick={() => setCreated(null)}>Done</Button>}
      >
        <Alert tone="warning">Copy this token now. It is not shown again; only a hash of it is kept.</Alert>
        <div className="audit-secret">
          <code data-testid="audit-token-secret">{created?.secret}</code>
          <Button
            size="sm"
            icon={copied ? <Check size={14} /> : <Copy size={14} />}
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(created?.secret ?? '');
                setCopied(true);
              } catch {
                toast.error('Copy failed: select the token and copy it by hand.');
              }
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </Modal>

      <ConfirmDialog
        open={!!revoking}
        title="Revoke token"
        message={`Revoke "${revoking?.name}"? Anything still using it gets 401 from now on.`}
        confirmLabel="Revoke"
        loading={revoke.isPending}
        onCancel={() => setRevoking(null)}
        onConfirm={() => revoking && revoke.mutate(revoking.id, { onSuccess: () => setRevoking(null) })}
      />
    </>
  );
}

// ---------------------------------------------------------------------------

function VerifyResult({ result }: { result: AuditVerification }) {
  return (
    <Alert tone={result.intact ? 'success' : 'danger'} title={result.intact ? 'Integrity verified' : `Broken at seq ${result.brokenSeq}`}>
      {result.message}
      {result.unreadableLines > 0 && ` ${result.unreadableLines} unreadable line(s) were skipped.`}
    </Alert>
  );
}

type Tab = 'events' | 'delivery' | 'tokens';

export function AuditPage() {
  const [tab, setTab] = useState<Tab>('events');
  const verify = useVerifyAudit();
  return (
    <div className="page">
      <PageHeader
        icon={<History size={22} />}
        title="Audit log"
        subtitle="Every sign-in, change and denial, tamper-evident (hash-chained) and deliverable to a SIEM."
        actions={
          <Button icon={<ShieldCheck size={16} />} loading={verify.isPending} onClick={() => verify.mutate()}>
            Verify integrity
          </Button>
        }
      />
      <ErrorAlert error={verify.error} />
      {verify.data && <VerifyResult result={verify.data} />}
      <Tabs<Tab>
        tabs={[
          { id: 'events', label: 'Events' },
          { id: 'delivery', label: 'Delivery' },
          { id: 'tokens', label: 'API tokens' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'events' && <EventsTab />}
      {tab === 'delivery' && <DeliveryTab />}
      {tab === 'tokens' && <TokensTab />}
    </div>
  );
}
