import { Fragment, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, History, Search, X } from 'lucide-react';
import type { AuditEvent, AuditOutcome } from '@dsp/shared';
import { useAdminDefinitions, useAudit } from '../../api/hooks';
import { Alert, Badge, Button, EmptyState, ErrorAlert, PageHeader, Spinner, useDebounced } from '../../components/ui';

const PAGE_SIZE = 50;

const CATEGORIES: { value: string; label: string }[] = [
  { value: '', label: 'All activity' },
  { value: 'entry', label: 'Directory entries' },
  { value: 'auth', label: 'Sign-ins' },
  { value: 'grant', label: 'Permissions' },
  { value: 'definition', label: 'Directory definitions' },
  { value: 'form', label: 'Forms' },
  { value: 'schema', label: 'Schema' },
];

const ACTION_LABELS: Record<string, string> = {
  'auth.login': 'Signed in',
  'auth.logout': 'Signed out',
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
};

const FAILED_LABELS: Record<string, string> = { 'auth.login': 'Sign-in failed' };

function actionLabel(e: AuditEvent): string {
  if (e.outcome !== 'success' && FAILED_LABELS[e.action]) return FAILED_LABELS[e.action];
  return ACTION_LABELS[e.action] ?? e.action;
}

function OutcomeBadge({ outcome }: { outcome: AuditOutcome }) {
  if (outcome === 'success') return <Badge tone="success">Success</Badge>;
  if (outcome === 'denied') return <Badge tone="warning">Denied</Badge>;
  return <Badge tone="danger">Failed</Badge>;
}

const formatTime = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

function Values({ values }: { values: string[] }) {
  if (!values.length) return <span className="muted">—</span>;
  return (
    <ul className="plain-list">
      {values.map((v, i) => (
        <li key={i} className="audit-value">
          {v}
        </li>
      ))}
    </ul>
  );
}

function EventDetails({ event, definitionName }: { event: AuditEvent; definitionName?: string }) {
  const facts: [string, string | undefined][] = [
    ['Who', event.actor ? `${event.actor.username} (${event.actor.dn})` : 'Not signed in'],
    ['Signed in with', event.actor?.authMethod === 'x509' ? 'Certificate' : event.actor?.authMethod === 'password' ? 'Password' : event.actor?.authMethod],
    ['From', event.sourceAddress],
    ['Target', event.target?.id],
    ['Directory', definitionName ?? event.definitionId],
    ['Reason', event.message],
    ...Object.entries(event.details ?? {}).map(([k, v]) => [k, v] as [string, string]),
    ['Event ID', event.id],
  ];
  return (
    <div className="audit-details">
      <dl className="audit-facts">
        {facts
          .filter(([, v]) => v)
          .map(([k, v]) => (
            <Fragment key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </Fragment>
          ))}
      </dl>
      {!!event.changes?.length && (
        <table className="table table-compact audit-changes">
          <thead>
            <tr>
              <th>Attribute</th>
              <th>Before</th>
              <th>After</th>
            </tr>
          </thead>
          <tbody>
            {event.changes.map((c) => (
              <tr key={c.attribute}>
                <td>
                  <code>{c.attribute}</code>
                </td>
                <td>
                  <Values values={c.before} />
                </td>
                <td>
                  <Values values={c.after} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [open, setOpen] = useState<string | null>(null);
  const q = useDebounced(search, 300);
  const action = params.get('action') ?? '';
  const outcome = params.get('outcome') ?? '';
  const target = params.get('target') ?? '';
  const actor = params.get('actor') ?? '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const page = Math.max(Number(params.get('page')) || 1, 1);
  const audit = useAudit({ q, action, outcome, target, actor, from, to, page, pageSize: PAGE_SIZE });
  const defs = useAdminDefinitions();

  const setParam = (next: Record<string, string>) => {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v);
      else p.delete(k);
    }
    if (!('page' in next)) p.delete('page');
    setParams(p, { replace: true });
  };

  const data = audit.data;
  const pages = data ? Math.max(Math.ceil(data.total / data.pageSize), 1) : 1;
  const definitionName = (id?: string) => defs.data?.find((d) => d.id === id)?.name;

  return (
    <div className="page">
      <PageHeader
        icon={<History size={22} />}
        title="Audit log"
        subtitle="Every change made through the portal, and every sign-in, with who made it and what changed."
      />

      <div className="toolbar">
        <div className="search-box">
          <Search size={16} aria-hidden />
          <input
            className="input"
            type="search"
            placeholder="Search people, entries, actions, addresses…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search the audit log"
          />
        </div>
        <div className="toolbar-right">
          <select className="input select select-sm" value={action} onChange={(e) => setParam({ action: e.target.value })} aria-label="Activity">
            {CATEGORIES.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <select className="input select select-sm" value={outcome} onChange={(e) => setParam({ outcome: e.target.value })} aria-label="Outcome">
            <option value="">Any outcome</option>
            <option value="success">Succeeded</option>
            <option value="denied">Denied</option>
            <option value="failed">Failed</option>
          </select>
          <input className="input input-sm audit-date" type="date" value={from} max={to || undefined} onChange={(e) => setParam({ from: e.target.value })} aria-label="From date" />
          <span className="muted small">to</span>
          <input className="input input-sm audit-date" type="date" value={to} min={from || undefined} onChange={(e) => setParam({ to: e.target.value })} aria-label="To date" />
        </div>
      </div>

      {(target || actor) && (
        <div className="audit-scope">
          {target && (
            <Badge tone="info">
              Target: <code>{target}</code>
              <button type="button" className="icon-btn icon-btn-sm" aria-label="Clear target filter" onClick={() => setParam({ target: '' })}>
                <X size={12} />
              </button>
            </Badge>
          )}
          {actor && (
            <Badge tone="info">
              By: <code>{actor}</code>
              <button type="button" className="icon-btn icon-btn-sm" aria-label="Clear person filter" onClick={() => setParam({ actor: '' })}>
                <X size={12} />
              </button>
            </Badge>
          )}
        </div>
      )}

      <ErrorAlert error={audit.error} />

      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>When</th>
              <th>Who</th>
              <th>What</th>
              <th>Target</th>
              <th>Outcome</th>
              <th aria-label="Details" />
            </tr>
          </thead>
          <tbody>
            {data?.events.map((e) => {
              const expanded = open === e.id;
              return (
                <Fragment key={e.id}>
                  <tr className="row-link" onClick={() => setOpen(expanded ? null : e.id)} aria-expanded={expanded}>
                    <td className="nowrap small">{formatTime(e.at)}</td>
                    <td>
                      {e.actor ? (
                        <button
                          type="button"
                          className="link-btn"
                          title={`Show everything ${e.actor.username} did`}
                          onClick={(ev) => {
                            ev.stopPropagation();
                            setParam({ actor: e.actor!.dn });
                          }}
                        >
                          {e.actor.username}
                        </button>
                      ) : (
                        <span className="muted">Not signed in</span>
                      )}
                    </td>
                    <td>
                      {actionLabel(e)}
                      {e.changes?.length ? <span className="muted small"> · {e.changes.length} change{e.changes.length === 1 ? '' : 's'}</span> : null}
                    </td>
                    <td>
                      {e.target?.name ?? e.target?.id ?? <span className="muted">—</span>}
                      {e.definitionId && <div className="muted small">{definitionName(e.definitionId) ?? e.definitionId}</div>}
                    </td>
                    <td>
                      <OutcomeBadge outcome={e.outcome} />
                    </td>
                    <td className="cell-actions">{expanded ? <ChevronUp size={16} aria-hidden /> : <ChevronDown size={16} aria-hidden />}</td>
                  </tr>
                  {expanded && (
                    <tr className="audit-expanded">
                      <td colSpan={6}>
                        <EventDetails event={e} definitionName={definitionName(e.definitionId)} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
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
            {data.total} event{data.total === 1 ? '' : 's'} · page {page} of {pages}
          </span>
          <Button size="sm" disabled={page >= pages} onClick={() => setParam({ page: String(page + 1) })}>
            Next <ChevronRight size={14} />
          </Button>
        </div>
      )}

      {data && data.retained >= data.capacity && data.oldest && (
        <Alert tone="info">
          This page searches the most recent {data.capacity.toLocaleString()} events, back to {formatTime(data.oldest)}. Older events are kept in the
          audit files on the server.
        </Alert>
      )}
    </div>
  );
}
