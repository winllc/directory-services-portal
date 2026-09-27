import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, BookOpen, FolderTree, Pencil, Play } from 'lucide-react';
import type { DirectoryDefinition, DirectoryEntry, SelfMatch } from '@dsp/shared';
import { escapeFilterValue, firstAttr, isDnUnder, isValidDn, rdnValue, SchemaIndex, slugify } from '@dsp/shared';
import { previewSearch, useAdminDefinitions, useAdminSchema, useDefinitionMutations, useForms, useServerInfo, type DefinitionInput } from '../../api/hooks';
import { DnBrowser } from '../../components/DnBrowser';
import { DEFINITION_ICONS } from '../../components/Layout';
import { TagSelect } from '../../components/TagSelect';
import { Alert, Button, Card, ErrorAlert, FormRow, PageHeader, Spinner, Toggle, cx, useToast } from '../../components/ui';

function blank(formId: string, baseDn: string): DefinitionInput {
  return {
    name: '',
    slug: '',
    description: '',
    formId,
    baseDn,
    scope: 'one',
    filter: '',
    mode: 'readwrite',
    everyoneCanRead: false,
    listAttributes: [],
    searchAttributes: [],
    titleAttribute: 'cn',
    createContainers: [baseDn],
    selfMatch: { type: 'none' },
    icon: 'folder',
  };
}

export function DefinitionEditorPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const defs = useAdminDefinitions();
  const forms = useForms();
  const schema = useAdminSchema();
  if (defs.isLoading || forms.isLoading || schema.isLoading) return <Spinner />;
  const existing = defs.data?.find((d) => d.id === id);
  if (!isNew && !existing) return <div className="page"><ErrorAlert error={new Error('Definition not found')} /></div>;
  return <DefinitionEditor key={id ?? 'new'} existing={existing} index={schema.data ? new SchemaIndex(schema.data.merged) : undefined} />;
}

function DefinitionEditor({ existing, index }: { existing?: DirectoryDefinition; index?: SchemaIndex }) {
  const forms = useForms();
  const info = useServerInfo();
  const navigate = useNavigate();
  const toast = useToast();
  const { create, update } = useDefinitionMutations();
  const [slugTouched, setSlugTouched] = useState(!!existing);
  const [browsing, setBrowsing] = useState<null | 'base' | 'container'>(null);
  const [preview, setPreview] = useState<{ entries: DirectoryEntry[]; truncated: boolean } | null>(null);
  const [previewError, setPreviewError] = useState<unknown>(null);
  const [previewing, setPreviewing] = useState(false);
  const [d, setD] = useState<DefinitionInput>(() => {
    if (existing) {
      const { id: _i, createdAt: _c, updatedAt: _u, ...rest } = existing;
      return { ...structuredClone(rest), filter: rest.filter ?? '', description: rest.description ?? '' };
    }
    const f = forms.data?.[0];
    const def = blank(f?.id ?? '', info.data?.baseDn ?? '');
    if (f) {
      def.titleAttribute = f.rdnAttribute;
      def.listAttributes = f.fields.slice(0, 4).map((x) => x.attribute);
      def.searchAttributes = f.fields.filter((x) => x.widget !== 'textarea').slice(0, 3).map((x) => x.attribute);
    }
    return def;
  });

  const form = forms.data?.find((f) => f.id === d.formId);
  const formAttrs = form?.fields.map((f) => f.attribute) ?? [];
  const allAttrs = useMemo(() => index?.schema.attributeTypes.map((a) => a.names[0]).sort((a, b) => a.localeCompare(b)) ?? [], [index]);
  const set = <K extends keyof DefinitionInput>(k: K, v: DefinitionInput[K]) => setD((s) => ({ ...s, [k]: v }));

  const defaultFilter = useMemo(() => {
    if (!form) return '(objectClass=*)';
    const classes = form.objectClasses.filter((c) => c.toLowerCase() !== 'top' && index?.objectClass(c)?.kind !== 'AUXILIARY');
    if (!classes.length) return '(objectClass=*)';
    const parts = classes.map((c) => `(objectClass=${escapeFilterValue(c)})`);
    return parts.length === 1 ? parts[0] : `(&${parts.join('')})`;
  }, [form, index]);

  const problems: string[] = [];
  if (!d.name.trim()) problems.push('Name is required');
  if (!d.slug) problems.push('Slug is required');
  if (!form) problems.push('Choose a form');
  if (!isValidDn(d.baseDn)) problems.push('Base DN is not a valid DN');
  if (!d.listAttributes.length) problems.push('Choose at least one list column');
  for (const c of d.createContainers) {
    if (!isValidDn(c) || !isDnUnder(c, d.baseDn)) problems.push(`Container ${c} must be within the base DN`);
  }
  if (d.selfMatch.type === 'attribute' && (!d.selfMatch.entryAttribute || !d.selfMatch.userAttribute)) problems.push('Self match needs both attributes');

  const runPreview = async () => {
    setPreviewing(true);
    setPreviewError(null);
    try {
      setPreview(
        await previewSearch({
          baseDn: d.baseDn,
          scope: d.scope,
          filter: d.filter?.trim() || defaultFilter,
          attributes: [d.titleAttribute, ...d.listAttributes],
        }),
      );
    } catch (e) {
      setPreviewError(e);
      setPreview(null);
    } finally {
      setPreviewing(false);
    }
  };

  const save = () => {
    const payload: DefinitionInput = { ...d, filter: d.filter?.trim() || undefined, description: d.description || undefined };
    const onSuccess = (r: DirectoryDefinition) => {
      toast.success(`“${r.name}” saved`);
      if (!existing) navigate(`/admin/definitions/${r.id}`, { replace: true });
    };
    if (existing) update.mutate({ id: existing.id, def: payload }, { onSuccess });
    else create.mutate(payload, { onSuccess });
  };

  const setSelfMatch = (m: SelfMatch) => set('selfMatch', m);

  return (
    <div className="page">
      <Link to="/admin/definitions" className="back-link">
        <ArrowLeft size={16} /> Directory definitions
      </Link>
      <PageHeader
        title={existing ? `Edit: ${existing.name}` : 'New directory definition'}
        actions={
          <>
            {existing && (
              <Link className="btn btn-secondary" to={`/d/${existing.slug}`}>
                Open directory
              </Link>
            )}
            <Button variant="primary" onClick={save} loading={create.isPending || update.isPending} disabled={problems.length > 0}>
              Save
            </Button>
          </>
        }
      />
      <ErrorAlert error={create.error ?? update.error} />
      {problems.length > 0 && (d.name || existing) && (
        <Alert tone="warning">
          <ul className="alert-list">{problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </Alert>
      )}

      <Card title="General">
        <div className="form-grid">
          <FormRow label="Name" htmlFor="def-name" required>
            <input
              id="def-name"
              className="input"
              value={d.name}
              onChange={(e) => {
                const name = e.target.value;
                setD((s) => ({ ...s, name, slug: slugTouched ? s.slug : slugify(name) }));
              }}
            />
          </FormRow>
          <FormRow label="URL slug" htmlFor="def-slug" hint={`/d/${d.slug || '…'}`} required>
            <input
              id="def-slug"
              className="input mono"
              value={d.slug}
              onChange={(e) => {
                setSlugTouched(true);
                set('slug', slugify(e.target.value));
              }}
            />
          </FormRow>
          <div className="span-2">
            <FormRow label="Description" htmlFor="def-desc">
              <input id="def-desc" className="input" value={d.description ?? ''} onChange={(e) => set('description', e.target.value)} />
            </FormRow>
          </div>
          <FormRow label="Form (object type)" htmlFor="def-form" hint={form ? `Object classes: ${form.objectClasses.join(', ')}` : undefined}>
            <select
              id="def-form"
              className="input select"
              value={d.formId}
              onChange={(e) => {
                const f = forms.data?.find((x) => x.id === e.target.value);
                setD((s) => ({
                  ...s,
                  formId: e.target.value,
                  titleAttribute: f?.rdnAttribute ?? s.titleAttribute,
                  listAttributes: s.listAttributes.length ? s.listAttributes : (f?.fields.slice(0, 4).map((x) => x.attribute) ?? []),
                }));
              }}
            >
              {!form && <option value="">Choose a form…</option>}
              {forms.data?.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name}
                </option>
              ))}
            </select>
          </FormRow>
          <div>
            <div className="form-label">Icon</div>
            <div className="icon-picker" role="radiogroup" aria-label="Icon">
              {Object.entries(DEFINITION_ICONS).map(([key, Icon]) => (
                <button key={key} type="button" role="radio" aria-checked={d.icon === key} aria-label={key} className={cx('icon-choice', d.icon === key && 'active')} onClick={() => set('icon', key)}>
                  <Icon size={18} />
                </button>
              ))}
            </div>
          </div>
        </div>
      </Card>

      <Card title="Namespace" subtitle="All reads and writes through this definition are confined to entries below the base DN that match the filter.">
        <div className="form-grid">
          <div className="span-2">
            <FormRow label="Base DN" htmlFor="def-base" required>
              <div className="input-group">
                <input
                  id="def-base"
                  className="input mono"
                  value={d.baseDn}
                  placeholder="ou=people,dc=example,dc=com"
                  onChange={(e) => {
                    const v = e.target.value;
                    setD((s) => ({ ...s, baseDn: v, createContainers: s.createContainers.length <= 1 && (s.createContainers[0] ?? '') === s.baseDn ? [v] : s.createContainers }));
                  }}
                />
                <Button icon={<FolderTree size={16} />} onClick={() => setBrowsing('base')}>
                  Browse
                </Button>
              </div>
            </FormRow>
          </div>
          <FormRow label="Scope" htmlFor="def-scope">
            <select id="def-scope" className="input select" value={d.scope} onChange={(e) => set('scope', e.target.value as 'one' | 'sub')}>
              <option value="one">One level (direct children)</option>
              <option value="sub">Subtree (all descendants)</option>
            </select>
          </FormRow>
          <FormRow label="Filter" htmlFor="def-filter" hint={`Leave empty to use ${defaultFilter}`}>
            <input id="def-filter" className="input mono" value={d.filter ?? ''} placeholder={defaultFilter} onChange={(e) => set('filter', e.target.value)} />
          </FormRow>
          <div className="span-2">
            <FormRow label="Containers for new entries" htmlFor="def-containers" hint="Parent DNs users can create entries in (must be within the base DN).">
              <div className="input-group">
                <TagSelect id="def-containers" options={[d.baseDn].filter(Boolean)} values={d.createContainers} onChange={(v) => set('createContainers', v)} />
                <Button icon={<FolderTree size={16} />} onClick={() => setBrowsing('container')}>
                  Browse
                </Button>
              </div>
            </FormRow>
          </div>
        </div>
        <div className="preview-row">
          <Button icon={<Play size={14} />} size="sm" onClick={runPreview} loading={previewing} disabled={!isValidDn(d.baseDn)}>
            Test namespace
          </Button>
          {preview && (
            <span className="muted small">
              {preview.entries.length}
              {preview.truncated ? '+' : ''} entries match (showing up to 25)
            </span>
          )}
        </div>
        <ErrorAlert error={previewError} />
        {preview && preview.entries.length > 0 && (
          <ul className="preview-list">
            {preview.entries.map((e) => (
              <li key={e.dn}>
                <strong>{firstAttr(e.attributes, d.titleAttribute) ?? rdnValue(e.dn)}</strong> <code className="muted small">{e.dn}</code>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Access mode">
        <div className="mode-choice">
          <button
            type="button"
            className={cx('mode-card', d.mode === 'readwrite' && 'active')}
            onClick={() => set('mode', 'readwrite')}
            aria-pressed={d.mode === 'readwrite'}
          >
            <Pencil size={20} />
            <strong>Read / write directory</strong>
            <span className="muted small">Users with write permission can create, edit and delete entries.</span>
          </button>
          <button
            type="button"
            className={cx('mode-card', d.mode === 'readonly' && 'active')}
            onClick={() => setD((s) => ({ ...s, mode: 'readonly', everyoneCanRead: true }))}
            aria-pressed={d.mode === 'readonly'}
          >
            <BookOpen size={20} />
            <strong>Read-only white pages</strong>
            <span className="muted small">Nobody can write through this definition, not even administrators.</span>
          </button>
        </div>
        <Toggle
          checked={d.everyoneCanRead}
          onChange={(v) => set('everyoneCanRead', v)}
          label="Every signed-in user can read"
          description="Otherwise only administrators and users/groups granted access on the Permissions page."
        />
      </Card>

      <Card title="Display">
        <div className="form-grid">
          <FormRow label="Title attribute" htmlFor="def-title">
            <select id="def-title" className="input select" value={d.titleAttribute} onChange={(e) => set('titleAttribute', e.target.value)}>
              {!formAttrs.includes(d.titleAttribute) && <option value={d.titleAttribute}>{d.titleAttribute}</option>}
              {formAttrs.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </FormRow>
          <div />
          <div className="span-2">
            <FormRow label="List columns" htmlFor="def-cols" hint="In display order; the first column links to the entry.">
              <TagSelect id="def-cols" options={formAttrs.length ? formAttrs : allAttrs} values={d.listAttributes} onChange={(v) => set('listAttributes', v)} />
            </FormRow>
          </div>
          <div className="span-2">
            <FormRow label="Quick search attributes" htmlFor="def-search" hint="Substring-matched by the search box.">
              <TagSelect id="def-search" options={formAttrs.length ? formAttrs : allAttrs} values={d.searchAttributes} onChange={(v) => set('searchAttributes', v)} />
            </FormRow>
          </div>
        </div>
      </Card>

      <Card title="Find the signed-in user" subtitle="Used by the “My profile” page to show users their own entries in this directory.">
        <div className="segmented" role="radiogroup" aria-label="Self match">
          {(
            [
              ['none', 'Disabled'],
              ['dn', 'Entry DN is the user’s DN'],
              ['attribute', 'Attribute match'],
            ] as const
          ).map(([t, label]) => (
            <button
              key={t}
              type="button"
              role="radio"
              aria-checked={d.selfMatch.type === t}
              className={cx(d.selfMatch.type === t && 'active')}
              onClick={() => setSelfMatch(t === 'attribute' ? { type: 'attribute', entryAttribute: 'uid', userAttribute: 'uid' } : { type: t })}
            >
              {label}
            </button>
          ))}
        </div>
        {d.selfMatch.type === 'attribute' && (
          <div className="form-grid self-match">
            <FormRow label="Entry attribute" htmlFor="sm-entry" hint="Attribute on entries in this directory, e.g. member, owner, acmeAssignedTo, uid.">
              <TagSelect
                id="sm-entry"
                single
                options={allAttrs}
                values={[d.selfMatch.entryAttribute]}
                onChange={(v) => d.selfMatch.type === 'attribute' && setSelfMatch({ ...d.selfMatch, entryAttribute: v[0] ?? '' })}
              />
            </FormRow>
            <FormRow label="equals the user’s" htmlFor="sm-user" hint='"dn" means the user’s distinguished name.'>
              <TagSelect
                id="sm-user"
                single
                options={['dn', ...allAttrs]}
                values={[d.selfMatch.userAttribute]}
                onChange={(v) => d.selfMatch.type === 'attribute' && setSelfMatch({ ...d.selfMatch, userAttribute: v[0] ?? '' })}
              />
            </FormRow>
          </div>
        )}
      </Card>

      <DnBrowser
        key={browsing ?? 'closed'}
        open={!!browsing}
        initial={d.baseDn}
        onClose={() => setBrowsing(null)}
        onSelect={(dn) => {
          if (browsing === 'base') setD((s) => ({ ...s, baseDn: dn, createContainers: s.createContainers.length ? s.createContainers : [dn] }));
          else if (!d.createContainers.includes(dn)) set('createContainers', [...d.createContainers, dn]);
          setBrowsing(null);
        }}
      />
    </div>
  );
}
