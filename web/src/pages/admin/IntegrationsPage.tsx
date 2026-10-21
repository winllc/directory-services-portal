import { useState } from 'react';
import { Check, Copy, KeyRound, Pencil, Plus, RefreshCw, Send, Trash2, Webhook as WebhookIcon, Workflow } from 'lucide-react';
import type { ApiToken, Webhook, WebhookInput } from '@dsp/shared';
import {
  useDefinitions,
  useApiTokens,
  useApiTokenSettings,
  useCreateApiToken,
  useRevokeApiToken,
  useWebhookDeliveries,
  useWebhookMutations,
  useWebhooks,
  useWebhookSettings,
} from '../../api/hooks';
import { Alert, Badge, Button, ConfirmDialog, EmptyState, ErrorAlert, FormRow, Modal, PageHeader, Spinner, Tabs, Toggle, useToast } from '../../components/ui';

function Time({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="muted">—</span>;
  const d = new Date(iso);
  return (
    <time dateTime={iso} title={`${iso} (UTC)`}>
      {d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}
    </time>
  );
}

const EVENT_LABELS: Record<string, string> = {
  'entry.changed': 'An entry is created, changed or deleted',
  'group.membership_changed': 'Members are added to or removed from a group',
  'form.submitted': 'An entry form is saved (including My profile)',
};

/** Shows a secret once, with a copy button. */
function SecretModal({ title, secret, onClose, what }: { title: string; secret: string | null; onClose: () => void; what: string }) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);
  return (
    <Modal open={!!secret} onClose={onClose} title={title} footer={<Button onClick={onClose}>Done</Button>}>
      <Alert tone="warning">Copy this {what} now. It is not shown again.</Alert>
      <div className="audit-secret">
        <code data-testid="integration-secret">{secret}</code>
        <Button
          size="sm"
          icon={copied ? <Check size={14} /> : <Copy size={14} />}
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(secret ?? '');
              setCopied(true);
            } catch {
              toast.error(`Copy failed: select the ${what} and copy it by hand.`);
            }
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
    </Modal>
  );
}

function DirectoryChecks({ legend, value, onChange, hint }: { legend: string; value: string[]; onChange: (v: string[]) => void; hint: string }) {
  const defs = useDefinitions();
  return (
    <fieldset className="form-section">
      <legend>{legend}</legend>
      <p className="form-hint">{hint}</p>
      <div className="check-grid">
        {defs.data?.map((d) => (
          <label key={d.id} className="check">
            <input
              type="checkbox"
              checked={value.includes(d.id)}
              onChange={(e) => onChange(e.target.checked ? [...value, d.id] : value.filter((x) => x !== d.id))}
            />
            {d.name}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

// ---------------------------------------------------------------------------
// API tokens
// ---------------------------------------------------------------------------

function tokenTone(t: ApiToken) {
  return t.status === 'active' ? 'success' : t.status === 'expired' ? 'warning' : 'neutral';
}

function TokensTab() {
  const tokens = useApiTokens();
  const settings = useApiTokenSettings();
  const create = useCreateApiToken();
  const revoke = useRevokeApiToken();
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['entries:read']);
  const [definitionIds, setDefinitionIds] = useState<string[]>([]);
  const [days, setDays] = useState(90);
  const [rate, setRate] = useState('');
  const [secret, setSecret] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<ApiToken | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate(
      { name: name.trim(), scopes, definitionIds, expiresInDays: days, rateLimitPerMinute: rate ? Number(rate) : undefined },
      {
        onSuccess: (r) => {
          setSecret(r.secret);
          setName('');
        },
      },
    );
  };

  return (
    <>
      <p className="muted">
        Other systems call the integration API (<code>/api/v1</code>, described by its{' '}
        <a href="/api/v1/openapi" target="_blank" rel="noreferrer">
          OpenAPI document
        </a>
        ) with <code>Authorization: Bearer &lt;token&gt;</code>. A token acts as you, with your directory permissions at the time of each call,
        limited to its scopes and directories; every change it makes is audited.
      </p>
      <form className="card card-body stack" onSubmit={submit} aria-label="New API token">
        <div className="form-grid">
          <FormRow label="Name" htmlFor="api-token-name" required>
            <input id="api-token-name" className="input" placeholder="e.g. HR system sync" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          </FormRow>
          <FormRow label="Expires after (days)" htmlFor="api-token-days" hint={`At most ${settings.data?.maxTokenDays ?? 365} days.`}>
            <input
              id="api-token-days"
              className="input"
              type="number"
              min={1}
              max={settings.data?.maxTokenDays ?? 365}
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
            />
          </FormRow>
          <FormRow label="Requests per minute" htmlFor="api-token-rate" hint={`Empty: the default, ${settings.data?.defaultRateLimitPerMinute ?? 120}.`}>
            <input id="api-token-rate" className="input" type="number" min={1} max={6000} value={rate} onChange={(e) => setRate(e.target.value)} />
          </FormRow>
        </div>
        <fieldset className="form-section">
          <legend>Scopes</legend>
          <div className="check-grid">
            {settings.data?.scopes.map((s) => (
              <label key={s.id} className="check" title={s.description}>
                <input
                  type="checkbox"
                  checked={scopes.includes(s.id)}
                  onChange={(e) => setScopes(e.target.checked ? [...scopes, s.id] : scopes.filter((x) => x !== s.id))}
                />
                <span>
                  <code>{s.id}</code> <span className="muted small">{s.description}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <DirectoryChecks legend="Directories" value={definitionIds} onChange={setDefinitionIds} hint="None selected: every directory you can reach." />
        <div>
          <Button type="submit" variant="primary" icon={<Plus size={14} />} disabled={!name.trim() || scopes.length === 0} loading={create.isPending}>
            Create token
          </Button>
        </div>
      </form>
      <ErrorAlert error={create.error ?? tokens.error ?? revoke.error} />

      <div className="table-wrap">
        <table className="table" aria-label="Integration API tokens">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Token</th>
              <th scope="col">Scopes</th>
              <th scope="col">Created</th>
              <th scope="col">Expires</th>
              <th scope="col">Last used</th>
              <th scope="col">Status</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {tokens.data?.map((t) => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td>
                  <code>{t.prefix}…</code>
                </td>
                <td className="small">{t.scopes.join(', ')}</td>
                <td className="small">
                  <Time iso={t.createdAt} /> <span className="muted">by {t.createdBy}</span>
                </td>
                <td className="small">
                  <Time iso={t.expiresAt} />
                </td>
                <td className="small">{t.lastUsedAt ? <Time iso={t.lastUsedAt} /> : <span className="muted">Never</span>}</td>
                <td>
                  <Badge tone={tokenTone(t)} title={t.revokedAt ? `Revoked ${t.revokedAt} by ${t.revokedBy}` : undefined}>
                    {t.status === 'active' ? 'Active' : t.status === 'expired' ? 'Expired' : 'Revoked'}
                  </Badge>
                </td>
                <td className="cell-actions">
                  {t.status !== 'revoked' && (
                    <Button size="sm" variant="ghost" icon={<Trash2 size={14} />} onClick={() => setRevoking(t)} aria-label={`Revoke ${t.name}`}>
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
            Create one for each system that reads or changes the directory through the API.
          </EmptyState>
        )}
      </div>

      <SecretModal title="Token created" what="token" secret={secret} onClose={() => setSecret(null)} />
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
// Webhooks
// ---------------------------------------------------------------------------

const EMPTY: WebhookInput = { name: '', url: 'https://', events: ['entry.changed'], definitionIds: [], enabled: true };

function WebhookDialog({ editing, onClose, onCreated }: { editing: Webhook | 'new' | null; onClose: () => void; onCreated: (secret: string) => void }) {
  const settings = useWebhookSettings();
  const m = useWebhookMutations();
  const [form, setForm] = useState<WebhookInput>(EMPTY);
  const [lastOpened, setLastOpened] = useState<Webhook | 'new' | null>(null);
  if (editing !== lastOpened) {
    setLastOpened(editing);
    setForm(editing && editing !== 'new' ? { name: editing.name, url: editing.url, events: editing.events, definitionIds: editing.definitionIds, enabled: editing.enabled } : EMPTY);
    m.create.reset();
    m.update.reset();
  }
  const mutation = editing === 'new' ? m.create : m.update;
  const error = mutation.error as { details?: Record<string, string> } | null;
  const save = (e: React.FormEvent) => {
    e.preventDefault();
    if (editing === 'new') {
      m.create.mutate(form, {
        onSuccess: (r) => {
          onClose();
          onCreated(r.secret);
        },
      });
    } else if (editing) {
      m.update.mutate({ id: editing.id, body: form }, { onSuccess: onClose });
    }
  };
  return (
    <Modal
      open={!!editing}
      onClose={onClose}
      wide
      title={editing === 'new' ? 'New webhook' : 'Edit webhook'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="webhook-form" loading={mutation.isPending} disabled={!form.name.trim() || form.events.length === 0}>
            Save
          </Button>
        </>
      }
    >
      <form id="webhook-form" className="stack" onSubmit={save}>
        {/* Field messages show at their fields. */}
        {!error?.details && <ErrorAlert error={mutation.error} />}
        <div className="form-grid">
          <FormRow label="Name" htmlFor="webhook-name" required error={error?.details?.name}>
            <input id="webhook-name" className="input" value={form.name} maxLength={100} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </FormRow>
          <FormRow
            label="URL"
            htmlFor="webhook-url"
            required
            error={error?.details?.url}
            hint="https:// only. Private, loopback and link-local addresses are refused unless the host is in WEBHOOK_ALLOWED_HOSTS."
          >
            <input id="webhook-url" className="input" type="url" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} />
          </FormRow>
        </div>
        <fieldset className="form-section">
          <legend>Events</legend>
          <div className="stack">
            {(settings.data?.eventTypes ?? Object.keys(EVENT_LABELS)).map((t) => (
              <label key={t} className="check">
                <input
                  type="checkbox"
                  checked={form.events.includes(t)}
                  onChange={(e) => setForm({ ...form, events: e.target.checked ? [...form.events, t] : form.events.filter((x) => x !== t) })}
                />
                <span>
                  <code>{t}</code> <span className="muted small">{EVENT_LABELS[t]}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <DirectoryChecks
          legend="Directories"
          value={form.definitionIds}
          onChange={(definitionIds) => setForm({ ...form, definitionIds })}
          hint="None selected: changes in every directory."
        />
        <Toggle checked={form.enabled} onChange={(enabled) => setForm({ ...form, enabled })} label="Enabled" />
      </form>
    </Modal>
  );
}

function Deliveries({ webhook }: { webhook: Webhook }) {
  const deliveries = useWebhookDeliveries(webhook.id);
  if (deliveries.isLoading) return <Spinner label="Loading deliveries…" />;
  if (!deliveries.data?.length) return <p className="muted small">No deliveries yet. Send a test event to try the webhook.</p>;
  return (
    <div className="table-wrap">
      <table className="table" aria-label={`Deliveries of ${webhook.name}`}>
        <thead>
          <tr>
            <th scope="col">Queued</th>
            <th scope="col">Event</th>
            <th scope="col">Status</th>
            <th scope="col">Attempts</th>
            <th scope="col">Last answer</th>
            <th scope="col">Payload</th>
          </tr>
        </thead>
        <tbody>
          {deliveries.data.map((d) => (
            <tr key={d.id}>
              <td className="small">
                <Time iso={d.createdAt} />
              </td>
              <td className="small">
                <code>{d.eventType}</code>
              </td>
              <td>
                <Badge tone={d.status === 'delivered' ? 'success' : d.status === 'failed' ? 'danger' : 'info'}>{d.status}</Badge>
                {d.nextAttemptAt && (
                  <div className="muted small">
                    next <Time iso={d.nextAttemptAt} />
                  </div>
                )}
              </td>
              <td>{d.attempts}</td>
              <td className="small">{d.lastStatus ? `HTTP ${d.lastStatus}` : (d.lastError ?? '—')}</td>
              <td className="small">
                <details>
                  <summary>Show</summary>
                  <pre className="code-block">{d.payload}</pre>
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function WebhooksTab() {
  const hooks = useWebhooks();
  const settings = useWebhookSettings();
  const m = useWebhookMutations();
  const toast = useToast();
  const [editing, setEditing] = useState<Webhook | 'new' | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<Webhook | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <>
      <p className="muted">
        Webhooks send an HTTPS POST to another system when entries or group members change. Each request is signed:{' '}
        <code>X-DSP-Signature: t=&lt;time&gt;,v1=&lt;HMAC-SHA256(secret, time + "." + body)&gt;</code>. Failed deliveries are retried up to{' '}
        {settings.data?.maxAttempts ?? 8} times.
      </p>
      {settings.data && !settings.data.enabled && <Alert tone="warning">Webhooks are turned off on this server (WEBHOOKS_ENABLED=false).</Alert>}
      <div>
        <Button variant="primary" icon={<Plus size={14} />} onClick={() => setEditing('new')} disabled={settings.data && !settings.data.enabled}>
          New webhook
        </Button>
      </div>
      <ErrorAlert error={hooks.error ?? m.remove.error ?? m.rotate.error ?? m.test.error} />
      <div className="stack">
        {hooks.data?.map((w) => (
          <section key={w.id} className="card card-body stack" aria-labelledby={`webhook-${w.id}`}>
            <div className="toolbar">
              <div>
                <h3 id={`webhook-${w.id}`} className="card-title">
                  {w.name} {w.enabled ? <Badge tone="success">Enabled</Badge> : <Badge>Disabled</Badge>}
                </h3>
                <div className="muted small">
                  <code>{w.url}</code> · {w.events.join(', ')}
                </div>
              </div>
              <div className="toolbar-meta cell-actions">
                <Button
                  size="sm"
                  icon={<Send size={14} />}
                  disabled={!w.enabled}
                  onClick={() => m.test.mutate(w.id, { onSuccess: () => (setOpen(w.id), toast.success('Test event queued.')) })}
                >
                  Send test event
                </Button>
                <Button size="sm" variant="ghost" icon={<Pencil size={14} />} onClick={() => setEditing(w)} aria-label={`Edit ${w.name}`}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<RefreshCw size={14} />}
                  onClick={() => m.rotate.mutate(w.id, { onSuccess: (r) => setSecret(r.secret) })}
                  aria-label={`New secret for ${w.name}`}
                >
                  New secret
                </Button>
                <Button size="sm" variant="ghost" icon={<Trash2 size={14} />} onClick={() => setDeleting(w)} aria-label={`Delete ${w.name}`}>
                  Delete
                </Button>
              </div>
            </div>
            <details open={open === w.id} onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open ? w.id : open === w.id ? null : open)}>
              <summary>Deliveries</summary>
              {open === w.id && <Deliveries webhook={w} />}
            </details>
          </section>
        ))}
      </div>
      {hooks.isLoading && <Spinner />}
      {hooks.data?.length === 0 && (
        <EmptyState icon={<WebhookIcon size={28} />} title="No webhooks">
          Add one to tell another system about directory changes as they happen.
        </EmptyState>
      )}

      <WebhookDialog editing={editing} onClose={() => setEditing(null)} onCreated={setSecret} />
      <SecretModal title="Signing secret" what="secret" secret={secret} onClose={() => setSecret(null)} />
      <ConfirmDialog
        open={!!deleting}
        title="Delete webhook"
        message={`Delete "${deleting?.name}"? Deliveries still waiting are dropped.`}
        loading={m.remove.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={() => deleting && m.remove.mutate(deleting.id, { onSuccess: () => setDeleting(null) })}
      />
    </>
  );
}

type Tab = 'tokens' | 'webhooks';

export function IntegrationsPage() {
  const [tab, setTab] = useState<Tab>('tokens');
  return (
    <div className="page">
      <PageHeader
        icon={<Workflow size={22} />}
        title="Integrations"
        subtitle="API tokens for other systems, and webhooks that tell them about changes."
      />
      <Tabs<Tab>
        tabs={[
          { id: 'tokens', label: 'API tokens' },
          { id: 'webhooks', label: 'Webhooks' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'tokens' ? <TokensTab /> : <WebhooksTab />}
    </div>
  );
}
