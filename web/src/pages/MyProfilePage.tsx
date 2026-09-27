import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, Pencil, UserCircle2, UserX } from 'lucide-react';
import type { DirectoryEntry, SelfEntryResult } from '@dsp/shared';
import { firstAttr, rdnValue } from '@dsp/shared';
import { useMyEntries, useSelfUpdate } from '../api/hooks';
import { useUser } from '../lib/auth';
import { EntryDetails } from '../components/EntryDetails';
import { EntryForm } from '../components/EntryForm';
import { DefinitionIcon } from '../components/Layout';
import { Alert, Badge, Button, Card, EmptyState, ErrorAlert, PageHeader, Spinner, useToast } from '../components/ui';
import { AccessBadge } from './HomePage';

function SelfEntry({ result, entry }: { result: SelfEntryResult; entry: DirectoryEntry }) {
  const [editing, setEditing] = useState(false);
  const update = useSelfUpdate();
  const toast = useToast();
  const { definition, form, editableFields } = result;
  const title = firstAttr(entry.attributes, definition.titleAttribute) ?? rdnValue(entry.dn);

  return (
    <div className="self-entry">
      <div className="self-entry-head">
        <div>
          <div className="self-entry-title">{title}</div>
          <code className="muted small">{entry.dn}</code>
        </div>
        <div className="self-entry-actions">
          {definition.access !== 'none' && (
            <Link className="btn btn-ghost btn-sm" to={`/d/${definition.slug}/entry?dn=${encodeURIComponent(entry.dn)}`}>
              <ExternalLink size={14} /> Open
            </Link>
          )}
          {editableFields.length > 0 && !editing && (
            <Button size="sm" variant="primary" icon={<Pencil size={14} />} onClick={() => setEditing(true)}>
              Edit my details
            </Button>
          )}
        </div>
      </div>
      {editing ? (
        <EntryForm
          key={entry.dn}
          definitionId={definition.id}
          form={form}
          mode="self"
          entry={entry.attributes}
          hideReadOnly
          submitting={update.isPending}
          error={update.error}
          onCancel={() => {
            update.reset();
            setEditing(false);
          }}
          onSubmit={(values) =>
            update.mutate(
              { definitionId: definition.id, dn: entry.dn, values },
              {
                onSuccess: () => {
                  toast.success('Your details were updated');
                  setEditing(false);
                },
              },
            )
          }
        />
      ) : (
        <EntryDetails form={form} attributes={entry.attributes} definitionId={definition.id} hideEmpty />
      )}
    </div>
  );
}

export function MyProfilePage() {
  const user = useUser();
  const me = useMyEntries();
  const found = me.data?.filter((r) => r.entries.length) ?? [];
  const missing = me.data?.filter((r) => !r.entries.length) ?? [];

  return (
    <div className="page">
      <PageHeader icon={<UserCircle2 size={22} />} title="My profile" subtitle="How you appear across the directories configured in this portal." />

      <Card className="identity-card">
        <div className="identity">
          <div className="avatar avatar-lg" aria-hidden>
            {user.displayName
              .split(/\s+/)
              .map((p) => p[0])
              .slice(0, 2)
              .join('')
              .toUpperCase()}
          </div>
          <div className="identity-main">
            <h2>
              {user.displayName} {user.isAdmin && <Badge tone="accent">Administrator</Badge>}
            </h2>
            <div className="identity-grid">
              <span className="muted">Username</span>
              <span>{user.username}</span>
              {user.mail && (
                <>
                  <span className="muted">E-mail</span>
                  <span>{user.mail}</span>
                </>
              )}
              <span className="muted">Distinguished name</span>
              <code>{user.dn}</code>
              <span className="muted">Groups</span>
              <span className="chips">
                {user.groups.length ? user.groups.map((g) => <span key={g} className="chip chip-static" title={g}>{rdnValue(g)}</span>) : <span className="muted">None</span>}
              </span>
            </div>
          </div>
        </div>
      </Card>

      {me.isLoading && <Spinner label="Looking you up…" />}
      <ErrorAlert error={me.error} />

      {me.data && me.data.length === 0 && (
        <EmptyState icon={<UserX size={28} />} title="No directories can locate you">
          None of the directory definitions are configured with a “find me” rule.
        </EmptyState>
      )}

      {found.map((r) => (
        <Card
          key={r.definition.id}
          title={
            <span className="card-title-icon">
              <DefinitionIcon icon={r.definition.icon} /> {r.definition.name}
            </span>
          }
          subtitle={r.definition.description}
          actions={
            <>
              <AccessBadge access={r.definition.access} mode={r.definition.mode} />
              {r.editableFields.length > 0 && <Badge tone="success">Self-service</Badge>}
            </>
          }
        >
          {r.error && <Alert tone="warning">{r.error}</Alert>}
          {r.entries.map((e) => (
            <SelfEntry key={e.dn} result={r} entry={e} />
          ))}
        </Card>
      ))}

      {missing.length > 0 && (
        <Card title="Not listed in" subtitle="Directories that could not find an entry for you.">
          <ul className="plain-list">
            {missing.map((r) => (
              <li key={r.definition.id}>
                <DefinitionIcon icon={r.definition.icon} size={16} /> {r.definition.name}
                {r.error && <span className="text-danger small"> — {r.error}</span>}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
