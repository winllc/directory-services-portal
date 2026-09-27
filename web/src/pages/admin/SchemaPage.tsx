import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Database, Download, FilePlus2, RefreshCw, Search, Upload, Wand2 } from 'lucide-react';
import type { AttributeTypeDef, ObjectClassDef, ObjectClassKind } from '@dsp/shared';
import { SchemaIndex } from '@dsp/shared';
import { useAdminSchema, useSchemaMutations } from '../../api/hooks';
import { TagSelect } from '../../components/TagSelect';
import { Alert, Badge, Button, ConfirmDialog, ErrorAlert, FormRow, Modal, PageHeader, Spinner, Tabs, Toggle, useToast } from '../../components/ui';

export const SYNTAXES: { oid: string; label: string }[] = [
  { oid: '1.3.6.1.4.1.1466.115.121.1.15', label: 'Directory String (UTF-8 text)' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.26', label: 'IA5 String (ASCII)' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.12', label: 'Distinguished Name' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.27', label: 'Integer' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.7', label: 'Boolean' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.24', label: 'Generalized Time' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.50', label: 'Telephone Number' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.38', label: 'OID' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.40', label: 'Octet String (binary)' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.28', label: 'JPEG' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.44', label: 'Printable String' },
  { oid: '1.3.6.1.4.1.1466.115.121.1.41', label: 'Postal Address' },
];

export function syntaxLabel(syntax?: string): string {
  if (!syntax) return '—';
  const base = syntax.replace(/\{\d+\}$/, '');
  const found = SYNTAXES.find((s) => s.oid === base);
  const len = /\{(\d+)\}$/.exec(syntax)?.[1];
  return found ? `${found.label}${len ? ` (max ${len})` : ''}` : syntax;
}

const EQUALITY = ['caseIgnoreMatch', 'caseExactMatch', 'caseIgnoreIA5Match', 'distinguishedNameMatch', 'integerMatch', 'booleanMatch', 'telephoneNumberMatch', 'octetStringMatch', 'objectIdentifierMatch', 'generalizedTimeMatch'];

function SourceBadge({ source }: { source: string }) {
  return source === 'custom' ? <Badge tone="accent">Custom</Badge> : <Badge>Server</Badge>;
}

const blankAttr = (): Omit<AttributeTypeDef, 'source'> => ({
  oid: '',
  names: [],
  desc: '',
  syntax: SYNTAXES[0].oid,
  equality: 'caseIgnoreMatch',
  singleValue: false,
  noUserModification: false,
});

const blankClass = (): Omit<ObjectClassDef, 'source'> => ({ oid: '', names: [], desc: '', sup: ['top'], kind: 'AUXILIARY', must: [], may: [] });

function AttributeEditor({ initial, originalOid, onClose, attributeNames }: { initial: Omit<AttributeTypeDef, 'source'>; originalOid?: string; onClose: () => void; attributeNames: string[] }) {
  const [d, setD] = useState(initial);
  const { saveAttribute } = useSchemaMutations();
  const toast = useToast();
  const set = <K extends keyof typeof d>(k: K, v: (typeof d)[K]) => setD((s) => ({ ...s, [k]: v }));
  const save = () =>
    saveAttribute.mutate(
      { definition: { ...d, desc: d.desc || undefined, sup: d.sup || undefined, displayName: d.displayName || undefined }, originalOid },
      {
        onSuccess: () => {
          toast.success(`Attribute type ${d.names[0]} saved`);
          onClose();
        },
      },
    );
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={originalOid ? `Customize attribute ${initial.names[0] ?? ''}` : 'New attribute type'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={save} loading={saveAttribute.isPending} disabled={!d.oid || !d.names.length}>
            Save
          </Button>
        </>
      }
    >
      <ErrorAlert error={saveAttribute.error} />
      <Alert tone="info">
        Custom definitions are stored in the portal and layered over the directory’s schema. To use a brand-new attribute on the server, also load it into the directory (use <em>Export custom</em>).
      </Alert>
      <div className="form-grid">
        <FormRow label="OID" htmlFor="at-oid" required hint="Numeric OID from your private enterprise arc, e.g. 1.3.6.1.4.1.99999.1.10">
          <input id="at-oid" className="input mono" value={d.oid} onChange={(e) => set('oid', e.target.value.trim())} />
        </FormRow>
        <FormRow label="Names" htmlFor="at-names" required hint="First name is the primary name; others are aliases.">
          <TagSelect id="at-names" options={[]} values={d.names} onChange={(v) => set('names', v)} placeholder="Type a name and press Enter" />
        </FormRow>
        <FormRow label="Description" htmlFor="at-desc">
          <input id="at-desc" className="input" value={d.desc ?? ''} onChange={(e) => set('desc', e.target.value)} />
        </FormRow>
        <FormRow label="Display name (portal only)" htmlFor="at-display">
          <input id="at-display" className="input" value={d.displayName ?? ''} onChange={(e) => set('displayName', e.target.value)} />
        </FormRow>
        <FormRow label="Syntax" htmlFor="at-syntax">
          <input id="at-syntax" className="input mono" list="syntax-list" value={d.syntax ?? ''} onChange={(e) => set('syntax', e.target.value)} />
          <datalist id="syntax-list">
            {SYNTAXES.map((s) => (
              <option key={s.oid} value={s.oid}>
                {s.label}
              </option>
            ))}
          </datalist>
          <div className="form-hint">{syntaxLabel(d.syntax)}</div>
        </FormRow>
        <FormRow label="Equality matching rule" htmlFor="at-eq">
          <input id="at-eq" className="input mono" list="eq-list" value={d.equality ?? ''} onChange={(e) => set('equality', e.target.value)} />
          <datalist id="eq-list">
            {EQUALITY.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </FormRow>
        <FormRow label="Superior attribute (SUP)" htmlFor="at-sup">
          <TagSelect id="at-sup" single options={attributeNames} values={d.sup ? [d.sup] : []} onChange={(v) => set('sup', v[0])} placeholder="None" />
        </FormRow>
        <div className="span-2 toggles">
          <Toggle checked={d.singleValue} onChange={(v) => set('singleValue', v)} label="Single value" description="SINGLE-VALUE: at most one value per entry." />
          <Toggle checked={d.noUserModification} onChange={(v) => set('noUserModification', v)} label="No user modification" description="Operational attribute maintained by the server." />
        </div>
      </div>
    </Modal>
  );
}

function ClassEditor({ initial, originalOid, onClose, attributeNames, classNames }: { initial: Omit<ObjectClassDef, 'source'>; originalOid?: string; onClose: () => void; attributeNames: string[]; classNames: string[] }) {
  const [d, setD] = useState(initial);
  const { saveClass } = useSchemaMutations();
  const toast = useToast();
  const set = <K extends keyof typeof d>(k: K, v: (typeof d)[K]) => setD((s) => ({ ...s, [k]: v }));
  const save = () =>
    saveClass.mutate(
      { definition: { ...d, desc: d.desc || undefined, displayName: d.displayName || undefined }, originalOid },
      {
        onSuccess: () => {
          toast.success(`Object class ${d.names[0]} saved`);
          onClose();
        },
      },
    );
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={originalOid ? `Customize object class ${initial.names[0] ?? ''}` : 'New object class'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={save} loading={saveClass.isPending} disabled={!d.oid || !d.names.length}>
            Save
          </Button>
        </>
      }
    >
      <ErrorAlert error={saveClass.error} />
      <div className="form-grid">
        <FormRow label="OID" htmlFor="oc-oid" required>
          <input id="oc-oid" className="input mono" value={d.oid} onChange={(e) => set('oid', e.target.value.trim())} />
        </FormRow>
        <FormRow label="Names" htmlFor="oc-names" required>
          <TagSelect id="oc-names" options={[]} values={d.names} onChange={(v) => set('names', v)} placeholder="Type a name and press Enter" />
        </FormRow>
        <FormRow label="Description" htmlFor="oc-desc">
          <input id="oc-desc" className="input" value={d.desc ?? ''} onChange={(e) => set('desc', e.target.value)} />
        </FormRow>
        <FormRow label="Kind" htmlFor="oc-kind">
          <select id="oc-kind" className="input select" value={d.kind} onChange={(e) => set('kind', e.target.value as ObjectClassKind)}>
            <option value="STRUCTURAL">Structural</option>
            <option value="AUXILIARY">Auxiliary</option>
            <option value="ABSTRACT">Abstract</option>
          </select>
        </FormRow>
        <div className="span-2">
          <FormRow label="Superior classes (SUP)" htmlFor="oc-sup">
            <TagSelect id="oc-sup" options={classNames} values={d.sup} onChange={(v) => set('sup', v)} />
          </FormRow>
        </div>
        <div className="span-2">
          <FormRow label="Required attributes (MUST)" htmlFor="oc-must">
            <TagSelect id="oc-must" options={attributeNames} values={d.must} onChange={(v) => set('must', v)} />
          </FormRow>
        </div>
        <div className="span-2">
          <FormRow label="Optional attributes (MAY)" htmlFor="oc-may">
            <TagSelect id="oc-may" options={attributeNames} values={d.may} onChange={(v) => set('may', v)} />
          </FormRow>
        </div>
      </div>
    </Modal>
  );
}

function ImportModal({ onClose }: { onClose: () => void }) {
  const [text, setText] = useState('');
  const { importText } = useSchemaMutations();
  const toast = useToast();
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title="Import schema"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            icon={<Upload size={16} />}
            loading={importText.isPending}
            disabled={!text.trim()}
            onClick={() =>
              importText.mutate(text, {
                onSuccess: (r) => {
                  toast.success(`Imported ${r.attributeTypes} attribute types and ${r.objectClasses} object classes`);
                  if (!r.errors.length) onClose();
                },
              })
            }
          >
            Import
          </Button>
        </>
      }
    >
      <p className="muted">
        Paste an OpenLDAP <code>.schema</code> file, a <code>cn=config</code> LDIF (<code>olcAttributeTypes</code>/<code>olcObjectClasses</code>) or raw RFC 4512 descriptions. Definitions are added as custom schema; matching OIDs or names are replaced.
      </p>
      <ErrorAlert error={importText.error} />
      {importText.data?.errors.length ? (
        <Alert tone="warning" title="Some definitions could not be parsed">
          <ul className="alert-list">
            {importText.data.errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        </Alert>
      ) : null}
      <textarea
        className="input mono"
        rows={14}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={"attributetype ( 1.3.6.1.4.1.99999.1.20 NAME 'acmeShirtSize'\n  DESC 'T-shirt size' EQUALITY caseIgnoreMatch\n  SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )\n\nobjectclass ( 1.3.6.1.4.1.99999.10.20 NAME 'acmeSwag'\n  SUP top AUXILIARY MAY acmeShirtSize )"}
        aria-label="Schema text"
      />
    </Modal>
  );
}

type Tab = 'classes' | 'attributes';

export function SchemaPage() {
  const schema = useAdminSchema();
  const { refresh, deleteAttribute, deleteClass } = useSchemaMutations();
  const toast = useToast();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>('classes');
  const [filter, setFilter] = useState('');
  const [source, setSource] = useState<'all' | 'server' | 'custom'>('all');
  const [selected, setSelected] = useState<string | null>(null);
  const [editAttr, setEditAttr] = useState<{ def: Omit<AttributeTypeDef, 'source'>; originalOid?: string } | null>(null);
  const [editClass, setEditClass] = useState<{ def: Omit<ObjectClassDef, 'source'>; originalOid?: string } | null>(null);
  const [importing, setImporting] = useState(false);
  const [deleting, setDeleting] = useState<{ kind: Tab; oid: string; name: string } | null>(null);

  const index = useMemo(() => (schema.data ? new SchemaIndex(schema.data.merged) : null), [schema.data]);
  const attributeNames = useMemo(() => schema.data?.merged.attributeTypes.map((a) => a.names[0]).sort((a, b) => a.localeCompare(b)) ?? [], [schema.data]);
  const classNames = useMemo(() => schema.data?.merged.objectClasses.map((a) => a.names[0]).sort((a, b) => a.localeCompare(b)) ?? [], [schema.data]);

  const match = (x: { names: string[]; oid: string; desc?: string; source: string }) => {
    if (source !== 'all' && x.source !== source) return false;
    const f = filter.trim().toLowerCase();
    return !f || x.oid.includes(f) || x.names.some((n) => n.toLowerCase().includes(f)) || (x.desc ?? '').toLowerCase().includes(f);
  };

  if (schema.isLoading) return <Spinner label="Loading schema…" />;
  if (!schema.data || !index) return <div className="page"><ErrorAlert error={schema.error} /></div>;

  const classes = schema.data.merged.objectClasses.filter(match).sort((a, b) => a.names[0].localeCompare(b.names[0]));
  const attrs = schema.data.merged.attributeTypes.filter(match).sort((a, b) => a.names[0].localeCompare(b.names[0]));
  const selClass = tab === 'classes' && selected ? index.objectClass(selected) : undefined;
  const selAttr = tab === 'attributes' && selected ? index.attribute(selected) : undefined;
  const server = schema.data.server;

  return (
    <div className="page">
      <PageHeader
        icon={<Database size={22} />}
        title="Schema"
        subtitle={
          server ? (
            <>
              Pulled from <code>{server.subschemaDn}</code> {server.fetchedAt && <>on {new Date(server.fetchedAt).toLocaleString()}</>} · {server.objectClasses} object classes, {server.attributeTypes} attribute types ·{' '}
              {schema.data.custom.objectClasses.length + schema.data.custom.attributeTypes.length} custom
            </>
          ) : (
            'The directory schema has not been pulled yet.'
          )
        }
        actions={
          <>
            <Button
              icon={<RefreshCw size={16} />}
              loading={refresh.isPending}
              onClick={() =>
                refresh.mutate(undefined, {
                  onSuccess: (r) => toast.success(`Pulled ${r.objectClasses} object classes and ${r.attributeTypes} attribute types`),
                  onError: (e) => toast.error(e),
                })
              }
            >
              Pull from directory
            </Button>
            <Button icon={<Upload size={16} />} onClick={() => setImporting(true)}>
              Import
            </Button>
            <a className="btn btn-secondary" href="/api/admin/schema/export" download="custom.schema">
              <Download size={16} /> Export custom
            </a>
          </>
        }
      />

      <div className="toolbar">
        <Tabs<Tab>
          value={tab}
          onChange={(t) => {
            setTab(t);
            setSelected(null);
          }}
          tabs={[
            { id: 'classes', label: `Object classes (${schema.data.merged.objectClasses.length})` },
            { id: 'attributes', label: `Attribute types (${schema.data.merged.attributeTypes.length})` },
          ]}
        />
        <div className="toolbar-right">
          <div className="search-box search-sm">
            <Search size={14} aria-hidden />
            <input className="input" placeholder="Filter by name, OID, description" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter schema" />
          </div>
          <select className="input select select-sm" value={source} onChange={(e) => setSource(e.target.value as typeof source)} aria-label="Source">
            <option value="all">All sources</option>
            <option value="server">Server</option>
            <option value="custom">Custom</option>
          </select>
          <Button
            variant="primary"
            icon={<FilePlus2 size={16} />}
            onClick={() => (tab === 'classes' ? setEditClass({ def: blankClass() }) : setEditAttr({ def: blankAttr() }))}
          >
            New {tab === 'classes' ? 'object class' : 'attribute'}
          </Button>
        </div>
      </div>

      <div className="split">
        <div className="table-wrap split-list">
          <table className="table table-compact">
            <thead>
              {tab === 'classes' ? (
                <tr>
                  <th>Name</th>
                  <th>Kind</th>
                  <th>Superior</th>
                  <th>Source</th>
                </tr>
              ) : (
                <tr>
                  <th>Name</th>
                  <th>Syntax</th>
                  <th>Values</th>
                  <th>Source</th>
                </tr>
              )}
            </thead>
            <tbody>
              {tab === 'classes'
                ? classes.map((c) => (
                    <tr key={c.oid + c.names[0]} className={`row-link ${selected === c.names[0] ? 'selected' : ''}`} onClick={() => setSelected(c.names[0])}>
                      <td>
                        <strong>{c.names[0]}</strong>
                        {c.desc && <div className="muted small ellipsis">{c.desc}</div>}
                      </td>
                      <td>
                        <Badge tone={c.kind === 'STRUCTURAL' ? 'info' : c.kind === 'AUXILIARY' ? 'success' : 'neutral'}>{c.kind.toLowerCase()}</Badge>
                      </td>
                      <td className="muted">{c.sup.join(', ') || '—'}</td>
                      <td>
                        <SourceBadge source={c.source} />
                      </td>
                    </tr>
                  ))
                : attrs.map((a) => (
                    <tr key={a.oid + a.names[0]} className={`row-link ${selected === a.names[0] ? 'selected' : ''}`} onClick={() => setSelected(a.names[0])}>
                      <td>
                        <strong>{a.names[0]}</strong>
                        {a.names.length > 1 && <span className="muted small"> ({a.names.slice(1).join(', ')})</span>}
                        {a.desc && <div className="muted small ellipsis">{a.desc}</div>}
                      </td>
                      <td className="muted small">{syntaxLabel(a.syntax ?? (a.sup ? `inherits ${a.sup}` : undefined))}</td>
                      <td>{index.isSingleValue(a.names[0]) ? <Badge>single</Badge> : <Badge tone="info">multi</Badge>}</td>
                      <td>
                        <SourceBadge source={a.source} />
                      </td>
                    </tr>
                  ))}
            </tbody>
          </table>
          {(tab === 'classes' ? classes : attrs).length === 0 && <div className="empty-inline muted">No matches.</div>}
        </div>

        <aside className="split-detail card">
          {selClass ? (
            (() => {
              const { must, may } = index.classAttributes([selClass.names[0]]);
              const chain = index.expandClasses([selClass.names[0]]).map((c) => c.names[0]);
              return (
                <div className="schema-detail">
                  <h3>{selClass.names[0]}</h3>
                  <div className="detail-meta">
                    <SourceBadge source={selClass.source} /> <Badge>{selClass.kind}</Badge> <code>{selClass.oid}</code>
                  </div>
                  {selClass.desc && <p>{selClass.desc}</p>}
                  <h4>Inheritance</h4>
                  <p className="muted">{chain.join(' → ')}</p>
                  <h4>Required attributes ({must.length})</h4>
                  <div className="chips">{must.map((m) => <span key={m} className="chip chip-static chip-strong">{m}</span>)}</div>
                  <h4>Optional attributes ({may.length})</h4>
                  <div className="chips">{may.map((m) => <span key={m} className="chip chip-static">{m}</span>)}</div>
                  <div className="detail-actions">
                    <Button size="sm" variant="primary" icon={<Wand2 size={14} />} onClick={() => navigate(`/admin/forms/new?class=${encodeURIComponent(selClass.names[0])}`)}>
                      Create form
                    </Button>
                    <Button size="sm" onClick={() => setEditClass({ def: { ...selClass }, originalOid: selClass.source === 'custom' ? selClass.oid : undefined })}>
                      {selClass.source === 'custom' ? 'Edit' : 'Customize'}
                    </Button>
                    {selClass.source === 'custom' && (
                      <Button size="sm" variant="danger" onClick={() => setDeleting({ kind: 'classes', oid: selClass.oid, name: selClass.names[0] })}>
                        Delete
                      </Button>
                    )}
                  </div>
                </div>
              );
            })()
          ) : selAttr ? (
            <div className="schema-detail">
              <h3>{selAttr.names[0]}</h3>
              <div className="detail-meta">
                <SourceBadge source={selAttr.source} /> <code>{selAttr.oid}</code>
              </div>
              {selAttr.desc && <p>{selAttr.desc}</p>}
              <dl className="kv">
                <dt>Aliases</dt>
                <dd>{selAttr.names.slice(1).join(', ') || '—'}</dd>
                <dt>Syntax</dt>
                <dd>{syntaxLabel(selAttr.syntax)}</dd>
                <dt>Superior</dt>
                <dd>{selAttr.sup ?? '—'}</dd>
                <dt>Equality</dt>
                <dd>{selAttr.equality ?? '—'}</dd>
                <dt>Values</dt>
                <dd>{index.isSingleValue(selAttr.names[0]) ? 'Single' : 'Multiple'}</dd>
                <dt>User modifiable</dt>
                <dd>{selAttr.noUserModification ? 'No' : 'Yes'}</dd>
                <dt>Used by</dt>
                <dd>
                  {schema.data.merged.objectClasses
                    .filter((c) => [...c.must, ...c.may].some((x) => selAttr.names.some((n) => n.toLowerCase() === x.toLowerCase())))
                    .map((c) => c.names[0])
                    .join(', ') || '—'}
                </dd>
              </dl>
              <div className="detail-actions">
                <Button size="sm" onClick={() => setEditAttr({ def: { ...selAttr }, originalOid: selAttr.source === 'custom' ? selAttr.oid : undefined })}>
                  {selAttr.source === 'custom' ? 'Edit' : 'Customize'}
                </Button>
                {selAttr.source === 'custom' && (
                  <Button size="sm" variant="danger" onClick={() => setDeleting({ kind: 'attributes', oid: selAttr.oid, name: selAttr.names[0] })}>
                    Delete
                  </Button>
                )}
              </div>
            </div>
          ) : (
            <div className="muted empty-inline">Select an {tab === 'classes' ? 'object class' : 'attribute type'} to see its details.</div>
          )}
        </aside>
      </div>

      {editAttr && <AttributeEditor initial={editAttr.def} originalOid={editAttr.originalOid} attributeNames={attributeNames} onClose={() => setEditAttr(null)} />}
      {editClass && <ClassEditor initial={editClass.def} originalOid={editClass.originalOid} attributeNames={attributeNames} classNames={classNames} onClose={() => setEditClass(null)} />}
      {importing && <ImportModal onClose={() => setImporting(false)} />}
      <ConfirmDialog
        open={!!deleting}
        title="Delete custom definition?"
        message={
          <>
            Remove the custom definition of <strong>{deleting?.name}</strong>? If the directory defines it too, the server version will be used again.
          </>
        }
        loading={deleteAttribute.isPending || deleteClass.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          const m = deleting!.kind === 'classes' ? deleteClass : deleteAttribute;
          m.mutate(deleting!.oid, {
            onSuccess: () => {
              toast.success('Custom definition removed');
              setDeleting(null);
              setSelected(null);
            },
            onError: (e) => toast.error(e),
          });
        }}
      />
    </div>
  );
}
