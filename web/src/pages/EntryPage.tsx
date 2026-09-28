import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { firstAttr, getAttr, rdnValue } from '@dsp/shared';
import { useDefinition, useEntry, useEntryMutations } from '../api/hooks';
import { EntryDetails } from '../components/EntryDetails';
import { EntryForm } from '../components/EntryForm';
import { DefinitionIcon } from '../components/Layout';
import { DirectoryNotFound } from '../components/DirectoryNotFound';
import { Badge, Button, Card, ConfirmDialog, ErrorAlert, FormRow, PageHeader, Spinner, useToast } from '../components/ui';

export function EntryPage() {
  const { slug = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const dn = params.get('dn');
  const editing = params.get('edit') === '1';
  const navigate = useNavigate();
  const toast = useToast();
  const def = useDefinition(slug);
  const definition = def.data?.definition;
  const form = def.data?.form;
  const entry = useEntry(definition?.id, dn);
  const { update, remove } = useEntryMutations(definition?.id ?? slug);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [showRaw, setShowRaw] = useState(false);

  if (def.isLoading || entry.isLoading) return <Spinner label="Loading…" />;
  if (!definition || !form) return <DirectoryNotFound slug={slug} error={def.error} />;
  if (entry.error || !entry.data) {
    return (
      <div className="page">
        <Link to={`/d/${definition.slug}`} className="back-link">
          <ArrowLeft size={16} /> {definition.name}
        </Link>
        <ErrorAlert error={entry.error ?? new Error('Entry not found')} />
      </div>
    );
  }

  const e = entry.data;
  const title = firstAttr(e.attributes, definition.titleAttribute) ?? rdnValue(e.dn);
  const canWrite = definition.access === 'write';
  const setEditing = (v: boolean) => {
    const next = new URLSearchParams(params);
    if (v) next.set('edit', '1');
    else next.delete('edit');
    setParams(next, { replace: true });
  };

  return (
    <div className="page">
      <Link to={`/d/${definition.slug}`} className="back-link">
        <ArrowLeft size={16} /> {definition.name}
      </Link>
      <PageHeader
        icon={<DefinitionIcon icon={definition.icon} size={22} />}
        title={title}
        subtitle={
          <span className="dn-line">
            <code title={e.dn}>{e.dn}</code>
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              aria-label="Copy DN"
              title="Copy DN"
              onClick={() => navigator.clipboard?.writeText(e.dn).then(() => toast.success('DN copied'))}
            >
              <Copy size={14} />
            </button>
          </span>
        }
        actions={
          canWrite &&
          !editing && (
            <>
              <Button icon={<Pencil size={16} />} variant="primary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              <Button icon={<Trash2 size={16} />} variant="danger" onClick={() => setConfirmDelete(true)}>
                Delete
              </Button>
            </>
          )
        }
      />

      {editing && canWrite ? (
        <Card title={`Edit ${form.name.toLowerCase()}`}>
          <EntryForm
            key={e.dn}
            definitionId={definition.id}
            form={form}
            mode="update"
            entry={e.attributes}
            submitting={update.isPending}
            error={update.error}
            onCancel={() => {
              update.reset();
              setEditing(false);
            }}
            onSubmit={(values) =>
              update.mutate(
                { dn: e.dn, values },
                {
                  onSuccess: (updated) => {
                    toast.success('Changes saved');
                    const next = new URLSearchParams();
                    next.set('dn', updated.dn);
                    setParams(next, { replace: true });
                  },
                },
              )
            }
          />
        </Card>
      ) : (
        <Card
          title="Details"
          actions={
            <div className="class-badges">
              {getAttr(e.attributes, 'objectClass')
                .filter((c) => c.toLowerCase() !== 'top')
                .map((c) => (
                  <Badge key={c}>{c}</Badge>
                ))}
            </div>
          }
        >
          <EntryDetails form={form} attributes={e.attributes} definitionId={definition.id} />
          <div className="raw-toggle">
            <button type="button" className="link-btn" onClick={() => setShowRaw((v) => !v)}>
              {showRaw ? 'Hide' : 'Show'} raw attributes
            </button>
            {showRaw && (
              <pre className="raw-ldif">
                {`dn: ${e.dn}\n` +
                  Object.entries(e.attributes)
                    .flatMap(([k, vs]) => vs.map((v) => `${k}: ${v}`))
                    .join('\n')}
              </pre>
            )}
          </div>
        </Card>
      )}

      <ConfirmDialog
        open={confirmDelete}
        title="Delete entry?"
        message={
          <>
            This permanently removes <strong>{title}</strong> (<code>{e.dn}</code>) from the directory.
          </>
        }
        loading={remove.isPending}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={() =>
          remove.mutate(e.dn, {
            onSuccess: () => {
              toast.success(`${title} deleted`);
              navigate(`/d/${definition.slug}`);
            },
            onError: (err) => {
              setConfirmDelete(false);
              toast.error(err);
            },
          })
        }
      />
    </div>
  );
}

export function CreateEntryPage() {
  const { slug = '' } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const def = useDefinition(slug);
  const definition = def.data?.definition;
  const form = def.data?.form;
  const { create } = useEntryMutations(definition?.id ?? slug);
  const containers = definition?.createContainers.length ? definition.createContainers : definition ? [definition.baseDn] : [];
  const [parent, setParent] = useState<string>('');

  if (def.isLoading) return <Spinner />;
  if (!definition || !form) return <DirectoryNotFound slug={slug} error={def.error} />;
  if (definition.access !== 'write') {
    return (
      <div className="page">
        <ErrorAlert error={new Error('You do not have permission to create entries in this directory.')} />
      </div>
    );
  }
  const selectedParent = parent || containers[0];

  return (
    <div className="page">
      <Link to={`/d/${definition.slug}`} className="back-link">
        <ArrowLeft size={16} /> {definition.name}
      </Link>
      <PageHeader icon={<DefinitionIcon icon={definition.icon} size={22} />} title={`New ${form.name.toLowerCase()}`} subtitle={`Object classes: ${form.objectClasses.join(', ')}`} />
      <Card>
        <EntryForm
          definitionId={definition.id}
          form={form}
          mode="create"
          submitting={create.isPending}
          error={create.error}
          onCancel={() => navigate(`/d/${definition.slug}`)}
          header={
            containers.length > 1 ? (
              <FormRow label="Create in" htmlFor="container" hint="The container (parent DN) for the new entry.">
                <select id="container" className="input select" value={selectedParent} onChange={(ev) => setParent(ev.target.value)}>
                  {containers.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
              </FormRow>
            ) : (
              <p className="muted form-intro">
                New entries are created as <code>{form.rdnAttribute}=…,{selectedParent}</code>
              </p>
            )
          }
          onSubmit={(values) =>
            create.mutate(
              { parentDn: selectedParent, values },
              {
                onSuccess: (created) => {
                  toast.success('Entry created');
                  navigate(`/d/${definition.slug}/entry?dn=${encodeURIComponent(created.dn)}`, { replace: true });
                },
              },
            )
          }
        />
      </Card>
    </div>
  );
}
