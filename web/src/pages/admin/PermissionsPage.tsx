import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { KeyRound, Plus, Search, Trash2, User, UsersRound } from 'lucide-react';
import type { DirectoryDefinition, PermissionGrant, SubjectSearchResult, SubjectType } from '@dsp/shared';
import { rdnValue } from '@dsp/shared';
import { useAdminDefinitions, useGrantMutations, useGrants, useSubjects } from '../../api/hooks';
import { DefinitionIcon } from '../../components/Layout';
import { Alert, Badge, Button, Card, EmptyState, ErrorAlert, PageHeader, Spinner, Tabs, cx, useDebounced, useToast } from '../../components/ui';

function SubjectIcon({ type }: { type: SubjectType }) {
  return type === 'user' ? <User size={16} aria-label="User" /> : <UsersRound size={16} aria-label="Group" />;
}

function AddGrant({ definition }: { definition: DirectoryDefinition }) {
  const [type, setType] = useState<SubjectType>('user');
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<SubjectSearchResult | null>(null);
  const [access, setAccess] = useState<'read' | 'write'>('read');
  const debounced = useDebounced(query, 250);
  const subjects = useSubjects(debounced, type);
  const { create } = useGrantMutations();
  const toast = useToast();
  const readonly = definition.mode === 'readonly';

  const submit = () => {
    if (!picked) return;
    create.mutate(
      { definitionId: definition.id, subjectType: picked.type, subject: picked.id, subjectLabel: picked.label, access: readonly ? 'read' : access },
      {
        onSuccess: () => {
          toast.success(`Granted ${readonly ? 'read' : access} access to ${picked.label}`);
          setPicked(null);
          setQuery('');
        },
      },
    );
  };

  return (
    <div className="add-grant">
      <div className="segmented" role="radiogroup" aria-label="Subject type">
        {(['user', 'group'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="radio"
            aria-checked={type === t}
            className={cx(type === t && 'active')}
            onClick={() => {
              setType(t);
              setPicked(null);
            }}
          >
            <SubjectIcon type={t} /> {t === 'user' ? 'User' : 'Group'}
          </button>
        ))}
      </div>
      {picked ? (
        <div className="picked-subject">
          <SubjectIcon type={picked.type} />
          <div>
            <strong>{picked.label}</strong>
            <div className="muted small">{picked.dn}</div>
          </div>
          <button type="button" className="link-btn" onClick={() => setPicked(null)}>
            Change
          </button>
        </div>
      ) : (
        <div className="subject-search">
          <div className="search-box">
            <Search size={16} aria-hidden />
            <input className="input" placeholder={`Search ${type}s in the directory…`} value={query} onChange={(e) => setQuery(e.target.value)} aria-label={`Search ${type}s`} />
          </div>
          <ul className="subject-results">
            {subjects.data?.map((s) => (
              <li key={s.type + s.id}>
                <button type="button" onClick={() => setPicked(s)}>
                  <SubjectIcon type={s.type} />
                  <span>
                    {s.label}
                    <span className="muted small"> {s.dn}</span>
                  </span>
                </button>
              </li>
            ))}
            {subjects.data?.length === 0 && <li className="muted empty-inline">No {type}s found.</li>}
          </ul>
        </div>
      )}
      <div className="add-grant-actions">
        <select className="input select" value={readonly ? 'read' : access} disabled={readonly} onChange={(e) => setAccess(e.target.value as 'read' | 'write')} aria-label="Access level">
          <option value="read">Can view</option>
          <option value="write">Can view and edit</option>
        </select>
        <Button variant="primary" icon={<Plus size={16} />} disabled={!picked} loading={create.isPending} onClick={submit}>
          Grant access
        </Button>
      </div>
      <ErrorAlert error={create.error} />
    </div>
  );
}

function GrantsTable({ grants, definition }: { grants: PermissionGrant[]; definition: DirectoryDefinition }) {
  const { create, remove } = useGrantMutations();
  const toast = useToast();
  if (!grants.length) return <p className="muted">No explicit grants. {definition.everyoneCanRead ? 'Every signed-in user can read.' : 'Only administrators can access this directory.'}</p>;
  return (
    <div className="table-wrap">
      <table className="table table-compact">
        <thead>
          <tr>
            <th>Subject</th>
            <th>Access</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {grants.map((g) => (
            <tr key={g.id}>
              <td>
                <div className="cell-with-icon">
                  <SubjectIcon type={g.subjectType} />
                  <div>
                    <strong>{g.subjectLabel ?? (g.subjectType === 'group' ? rdnValue(g.subject) : g.subject)}</strong>
                    <div className="muted small">{g.subject}</div>
                  </div>
                </div>
              </td>
              <td>
                <select
                  className="input select select-sm"
                  value={g.access}
                  disabled={definition.mode === 'readonly'}
                  aria-label={`Access for ${g.subjectLabel ?? g.subject}`}
                  onChange={(e) =>
                    create.mutate(
                      { definitionId: g.definitionId, subjectType: g.subjectType, subject: g.subject, subjectLabel: g.subjectLabel, access: e.target.value as 'read' | 'write' },
                      { onSuccess: () => toast.success('Access updated'), onError: (err) => toast.error(err) },
                    )
                  }
                >
                  <option value="read">Can view</option>
                  <option value="write">Can view and edit</option>
                </select>
              </td>
              <td className="cell-actions">
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Trash2 size={14} />}
                  onClick={() => remove.mutate(g.id, { onSuccess: () => toast.success('Access revoked'), onError: (err) => toast.error(err) })}
                >
                  Revoke
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Matrix({ definitions, grants }: { definitions: DirectoryDefinition[]; grants: PermissionGrant[] }) {
  const subjects = useMemo(() => {
    const map = new Map<string, { type: SubjectType; subject: string; label: string }>();
    for (const g of grants) {
      const key = `${g.subjectType}:${g.subject.toLowerCase()}`;
      if (!map.has(key)) map.set(key, { type: g.subjectType, subject: g.subject, label: g.subjectLabel ?? g.subject });
    }
    return [...map.entries()].sort((a, b) => a[1].label.localeCompare(b[1].label));
  }, [grants]);
  return (
    <div className="table-wrap">
      <table className="table table-compact matrix">
        <thead>
          <tr>
            <th>Subject</th>
            {definitions.map((d) => (
              <th key={d.id}>{d.name}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              <em>All signed-in users</em>
            </td>
            {definitions.map((d) => (
              <td key={d.id}>{d.everyoneCanRead ? <Badge>view</Badge> : <span className="muted">—</span>}</td>
            ))}
          </tr>
          {subjects.map(([key, s]) => (
            <tr key={key}>
              <td>
                <div className="cell-with-icon">
                  <SubjectIcon type={s.type} /> {s.label}
                </div>
              </td>
              {definitions.map((d) => {
                const g = grants.find((x) => x.definitionId === d.id && `${x.subjectType}:${x.subject.toLowerCase()}` === key);
                return <td key={d.id}>{g ? g.access === 'write' ? <Badge tone="success">edit</Badge> : <Badge tone="info">view</Badge> : <span className="muted">—</span>}</td>;
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">Administrators always have full access to read/write directories. White pages are never writable.</p>
    </div>
  );
}

export function PermissionsPage() {
  const defs = useAdminDefinitions();
  const grants = useGrants();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<'definition' | 'matrix'>('definition');
  const selectedId = params.get('definition') ?? defs.data?.[0]?.id;
  const selected = defs.data?.find((d) => d.id === selectedId);

  if (defs.isLoading || grants.isLoading) return <Spinner />;

  return (
    <div className="page page-wide">
      <PageHeader icon={<KeyRound size={22} />} title="Permissions" subtitle="Grant users or directory groups read or write access to specific directory definitions." />
      <ErrorAlert error={defs.error ?? grants.error} />
      {!defs.data?.length ? (
        <EmptyState title="No directory definitions" icon={<KeyRound size={28} />}>Create a directory definition first.</EmptyState>
      ) : (
        <>
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { id: 'definition', label: 'By directory' },
              { id: 'matrix', label: 'Overview matrix' },
            ]}
          />
          {tab === 'matrix' ? (
            <Card>
              <Matrix definitions={defs.data} grants={grants.data ?? []} />
            </Card>
          ) : (
            <div className="split split-narrow">
              <nav className="card definition-picker" aria-label="Directory definitions">
                {defs.data.map((d) => {
                  const count = grants.data?.filter((g) => g.definitionId === d.id).length ?? 0;
                  return (
                    <button key={d.id} type="button" className={cx('picker-item', d.id === selected?.id && 'active')} onClick={() => setParams({ definition: d.id })}>
                      <DefinitionIcon icon={d.icon} />
                      <span className="picker-name">{d.name}</span>
                      <span className="muted small">{count}</span>
                    </button>
                  );
                })}
              </nav>
              {selected && (
                <div className="stack">
                  <Card
                    title={selected.name}
                    subtitle={<code>{selected.baseDn}</code>}
                    actions={
                      <>
                        {selected.mode === 'readonly' ? <Badge tone="info">White pages</Badge> : <Badge tone="success">Read / write</Badge>}
                        {selected.everyoneCanRead && <Badge>Everyone can view</Badge>}
                      </>
                    }
                  >
                    {selected.mode === 'readonly' && <Alert tone="info">This is a read-only definition: grants can only give view access.</Alert>}
                    <GrantsTable definition={selected} grants={grants.data?.filter((g) => g.definitionId === selected.id) ?? []} />
                  </Card>
                  <Card title="Add access">
                    <AddGrant key={selected.id} definition={selected} />
                  </Card>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
