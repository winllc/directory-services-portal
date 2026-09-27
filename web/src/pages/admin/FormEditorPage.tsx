import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  AlignLeft,
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Copy,
  Eye,
  ListChecks,
  Plus,
  Trash2,
  Type,
} from 'lucide-react';
import type { DropdownSource, FieldWidget, FormDefinition, FormField, OptionItem, TextFormat } from '@dsp/shared';
import { lintForm, SchemaIndex } from '@dsp/shared';
import { useAdminSchema, useFormMutations, useForms, type FormInput } from '../../api/hooks';
import { EntryForm } from '../../components/EntryForm';
import { TagSelect } from '../../components/TagSelect';
import { Alert, Badge, Button, Card, ErrorAlert, FormRow, PageHeader, Spinner, Toggle, cx, useToast } from '../../components/ui';

let seq = 0;
const newId = () => `f-${Date.now().toString(36)}-${(seq++).toString(36)}`;

export function humanize(attr: string): string {
  const words = attr
    .replace(/^(acme|x|ms|ds|ibm|nds|ns)(?=[A-Z])/, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]/g, ' ')
    .trim();
  return words.charAt(0).toUpperCase() + words.slice(1).toLowerCase();
}

function guessFormat(attr: string, index?: SchemaIndex): TextFormat | undefined {
  const at = index?.attribute(attr);
  const syntax = at?.syntax ?? '';
  if (/mail/i.test(attr)) return 'email';
  if (syntax.startsWith('1.3.6.1.4.1.1466.115.121.1.50') || /phone|mobile|fax/i.test(attr)) return 'phone';
  if (syntax.startsWith('1.3.6.1.4.1.1466.115.121.1.12')) return 'dn';
  if (syntax.startsWith('1.3.6.1.4.1.1466.115.121.1.27')) return 'number';
  if (/url|uri/i.test(attr)) return 'url';
  return undefined;
}

export function fieldFromSchema(attr: string, index: SchemaIndex | undefined, required: boolean): FormField {
  const at = index?.attribute(attr);
  return {
    id: newId(),
    attribute: at?.names[0] ?? attr,
    label: at?.displayName ?? humanize(at?.names[0] ?? attr),
    helpText: at?.desc,
    widget: 'text',
    multiValued: index ? !index.isSingleValue(attr) && !['cn', 'sn', 'uid', 'givenname'].includes(attr.toLowerCase()) : false,
    required,
    readOnly: !!at?.noUserModification,
    selfEditable: false,
    format: guessFormat(attr, index),
  };
}

const WIDGETS: { id: FieldWidget; label: string; icon: typeof Type }[] = [
  { id: 'text', label: 'Free text', icon: Type },
  { id: 'textarea', label: 'Text area', icon: AlignLeft },
  { id: 'dropdown', label: 'Drop down', icon: ListChecks },
];

function OptionsEditor({ options, onChange }: { options: OptionItem[]; onChange: (o: OptionItem[]) => void }) {
  const [bulk, setBulk] = useState(false);
  const [text, setText] = useState('');
  if (bulk) {
    return (
      <div className="options-editor">
        <textarea
          className="input mono"
          rows={6}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={'One option per line.\nUse value|Label to show a different label.'}
          aria-label="Options (one per line)"
        />
        <div className="row-gap">
          <Button
            size="sm"
            variant="primary"
            onClick={() => {
              const parsed = text
                .split('\n')
                .map((l) => l.trim())
                .filter(Boolean)
                .map((l) => {
                  const [value, label] = l.split('|').map((s) => s.trim());
                  return label ? { value, label } : { value };
                });
              onChange(parsed.filter((o, i) => parsed.findIndex((x) => x.value === o.value) === i));
              setBulk(false);
            }}
          >
            Apply
          </Button>
          <Button size="sm" onClick={() => setBulk(false)}>
            Cancel
          </Button>
        </div>
      </div>
    );
  }
  return (
    <div className="options-editor">
      {options.map((o, i) => (
        <div className="option-row" key={i}>
          <input
            className="input input-sm"
            placeholder="Value (stored)"
            value={o.value}
            aria-label={`Option ${i + 1} value`}
            onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))}
          />
          <input
            className="input input-sm"
            placeholder="Label (optional)"
            value={o.label ?? ''}
            aria-label={`Option ${i + 1} label`}
            onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, label: e.target.value || undefined } : x)))}
          />
          <button type="button" className="icon-btn" aria-label={`Remove option ${i + 1}`} onClick={() => onChange(options.filter((_, j) => j !== i))}>
            <Trash2 size={14} />
          </button>
        </div>
      ))}
      <div className="row-gap">
        <button type="button" className="link-btn" onClick={() => onChange([...options, { value: '' }])}>
          <Plus size={14} /> Add option
        </button>
        <button
          type="button"
          className="link-btn"
          onClick={() => {
            setText(options.map((o) => (o.label ? `${o.value}|${o.label}` : o.value)).join('\n'));
            setBulk(true);
          }}
        >
          Edit as text
        </button>
      </div>
    </div>
  );
}

function FieldEditor({
  field,
  index,
  attributeOptions,
  isRdn,
  expanded,
  onToggle,
  onChange,
  onMove,
  onDuplicate,
  onRemove,
  position,
  count,
  sections,
}: {
  field: FormField;
  index?: SchemaIndex;
  attributeOptions: string[];
  isRdn: boolean;
  expanded: boolean;
  onToggle: () => void;
  onChange: (f: FormField) => void;
  onMove: (dir: -1 | 1) => void;
  onDuplicate: () => void;
  onRemove: () => void;
  position: number;
  count: number;
  sections: string[];
}) {
  const set = <K extends keyof FormField>(k: K, v: FormField[K]) => onChange({ ...field, [k]: v });
  const at = index?.attribute(field.attribute);
  const singleInSchema = !!index && index.isSingleValue(field.attribute);
  const id = (s: string) => `${field.id}-${s}`;
  const dd = field.dropdown;

  const setWidget = (w: FieldWidget) => {
    const next: FormField = { ...field, widget: w };
    if (w === 'dropdown' && !next.dropdown) next.dropdown = { type: 'static', options: [] };
    onChange(next);
  };
  const setSource = (type: DropdownSource['type']) => {
    if (type === dd?.type) return;
    set(
      'dropdown',
      type === 'static'
        ? { type: 'static', options: [] }
        : { type: 'ldap', baseDn: '', scope: 'sub', filter: '(objectClass=*)', valueAttribute: 'dn', labelAttribute: 'cn' },
    );
  };

  const WidgetIcon = WIDGETS.find((w) => w.id === field.widget)?.icon ?? Type;

  return (
    <div className={cx('field-card', expanded && 'expanded')}>
      <div className="field-card-head">
        <button type="button" className="field-card-toggle" onClick={onToggle} aria-expanded={expanded}>
          {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          <WidgetIcon size={16} className="muted" />
          <span className="field-card-label">{field.label || <em className="muted">Untitled</em>}</span>
          <code className="muted small">{field.attribute}</code>
        </button>
        <div className="field-card-badges">
          {isRdn && <Badge tone="accent">RDN</Badge>}
          {field.required && <Badge tone="warning">required</Badge>}
          {field.multiValued && <Badge tone="info">multi</Badge>}
          {field.readOnly && <Badge>read only</Badge>}
          {field.selfEditable && <Badge tone="success">self-service</Badge>}
        </div>
        <div className="field-card-actions">
          <button type="button" className="icon-btn icon-btn-sm" aria-label="Move up" disabled={position === 0} onClick={() => onMove(-1)}>
            <ArrowUp size={14} />
          </button>
          <button type="button" className="icon-btn icon-btn-sm" aria-label="Move down" disabled={position === count - 1} onClick={() => onMove(1)}>
            <ArrowDown size={14} />
          </button>
          <button type="button" className="icon-btn icon-btn-sm" aria-label="Duplicate field" onClick={onDuplicate}>
            <Copy size={14} />
          </button>
          <button type="button" className="icon-btn icon-btn-sm" aria-label="Remove field" onClick={onRemove}>
            <Trash2 size={14} />
          </button>
        </div>
      </div>
      {expanded && (
        <div className="field-card-body">
          <div className="form-grid">
            <FormRow label="LDAP attribute" htmlFor={id('attr')} hint={at ? at.desc ?? `OID ${at.oid}` : field.attribute ? 'Not in the schema' : undefined}>
              <TagSelect
                id={id('attr')}
                single
                options={attributeOptions}
                values={field.attribute ? [field.attribute] : []}
                onChange={(v) => {
                  const attr = v[0] ?? '';
                  const next: FormField = { ...field, attribute: attr };
                  if (!field.label || field.label === humanize(field.attribute)) next.label = index?.attribute(attr)?.displayName ?? humanize(attr);
                  if (index?.isSingleValue(attr)) next.multiValued = false;
                  onChange(next);
                }}
              />
            </FormRow>
            <FormRow label="Label" htmlFor={id('label')}>
              <input id={id('label')} className="input" value={field.label} onChange={(e) => set('label', e.target.value)} />
            </FormRow>

            <div className="span-2">
              <div className="form-label">Input type</div>
              <div className="segmented" role="radiogroup" aria-label="Input type">
                {WIDGETS.map((w) => (
                  <button key={w.id} type="button" role="radio" aria-checked={field.widget === w.id} className={cx(field.widget === w.id && 'active')} onClick={() => setWidget(w.id)}>
                    <w.icon size={14} /> {w.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="span-2 toggles">
              <Toggle
                checked={field.multiValued}
                onChange={(v) => set('multiValued', v)}
                disabled={singleInSchema && !field.multiValued}
                label="Multiple values"
                description={singleInSchema ? 'The schema marks this attribute SINGLE-VALUE.' : 'Allow more than one value.'}
              />
              <Toggle checked={field.required} onChange={(v) => set('required', v)} label="Required" />
              <Toggle checked={field.readOnly} onChange={(v) => set('readOnly', v)} label="Read only" description={isRdn ? 'Still settable when creating (names the entry).' : 'Shown but never written.'} />
              <Toggle checked={field.selfEditable} onChange={(v) => set('selfEditable', v)} disabled={field.readOnly} label="Self-service" description="Users may edit this on their own entry." />
            </div>

            {field.widget === 'dropdown' ? (
              <div className="span-2 dropdown-config">
                <div className="form-label">Options</div>
                <div className="segmented" role="radiogroup" aria-label="Option source">
                  <button type="button" role="radio" aria-checked={dd?.type === 'static'} className={cx(dd?.type === 'static' && 'active')} onClick={() => setSource('static')}>
                    Fixed list
                  </button>
                  <button type="button" role="radio" aria-checked={dd?.type === 'ldap'} className={cx(dd?.type === 'ldap' && 'active')} onClick={() => setSource('ldap')}>
                    Directory search
                  </button>
                </div>
                {dd?.type === 'static' && <OptionsEditor options={dd.options} onChange={(options) => set('dropdown', { type: 'static', options })} />}
                {dd?.type === 'ldap' && (
                  <div className="form-grid">
                    <FormRow label="Search base" htmlFor={id('base')}>
                      <input id={id('base')} className="input mono" value={dd.baseDn} onChange={(e) => set('dropdown', { ...dd, baseDn: e.target.value })} placeholder="ou=people,dc=example,dc=com" />
                    </FormRow>
                    <FormRow label="Scope" htmlFor={id('scope')}>
                      <select id={id('scope')} className="input select" value={dd.scope} onChange={(e) => set('dropdown', { ...dd, scope: e.target.value as 'one' | 'sub' })}>
                        <option value="one">One level</option>
                        <option value="sub">Subtree</option>
                      </select>
                    </FormRow>
                    <FormRow label="Filter" htmlFor={id('filter')}>
                      <input id={id('filter')} className="input mono" value={dd.filter} onChange={(e) => set('dropdown', { ...dd, filter: e.target.value })} />
                    </FormRow>
                    <FormRow label="Stored value" htmlFor={id('va')} hint='Attribute stored in the entry; "dn" stores the DN (for member/manager style links).'>
                      <input id={id('va')} className="input mono" list="attr-names" value={dd.valueAttribute} onChange={(e) => set('dropdown', { ...dd, valueAttribute: e.target.value })} />
                    </FormRow>
                    <FormRow label="Displayed label" htmlFor={id('la')}>
                      <input id={id('la')} className="input mono" list="attr-names" value={dd.labelAttribute} onChange={(e) => set('dropdown', { ...dd, labelAttribute: e.target.value })} />
                    </FormRow>
                  </div>
                )}
                <Toggle checked={!!field.allowCustomValues} onChange={(v) => set('allowCustomValues', v)} label="Allow values not in the list" />
              </div>
            ) : (
              <>
                <FormRow label="Format" htmlFor={id('format')}>
                  <select id={id('format')} className="input select" value={field.format ?? 'plain'} onChange={(e) => set('format', e.target.value === 'plain' ? undefined : (e.target.value as TextFormat))}>
                    <option value="plain">Any text</option>
                    <option value="email">E-mail address</option>
                    <option value="phone">Phone number</option>
                    <option value="url">URL</option>
                    <option value="number">Number</option>
                    <option value="dn">Distinguished name</option>
                  </select>
                </FormRow>
                <FormRow label="Pattern (regular expression)" htmlFor={id('pattern')} hint="The whole value must match.">
                  <input id={id('pattern')} className="input mono" value={field.pattern ?? ''} onChange={(e) => set('pattern', e.target.value || undefined)} />
                </FormRow>
                <FormRow label="Max length" htmlFor={id('max')}>
                  <input id={id('max')} className="input" type="number" min={1} value={field.maxLength ?? ''} onChange={(e) => set('maxLength', e.target.value ? Number(e.target.value) : undefined)} />
                </FormRow>
                <FormRow label="Placeholder" htmlFor={id('ph')}>
                  <input id={id('ph')} className="input" value={field.placeholder ?? ''} onChange={(e) => set('placeholder', e.target.value || undefined)} />
                </FormRow>
              </>
            )}
            <FormRow label="Section" htmlFor={id('section')} hint="Fields with the same section are grouped.">
              <input id={id('section')} className="input" list="section-names" value={field.section ?? ''} onChange={(e) => set('section', e.target.value || undefined)} />
              <datalist id="section-names">
                {sections.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </FormRow>
            <FormRow label="Help text" htmlFor={id('help')}>
              <input id={id('help')} className="input" value={field.helpText ?? ''} onChange={(e) => set('helpText', e.target.value || undefined)} />
            </FormRow>
          </div>
        </div>
      )}
    </div>
  );
}

function blankForm(): FormInput {
  return { name: '', description: '', objectClasses: ['top'], rdnAttribute: 'cn', fields: [] };
}

export function FormEditorPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const forms = useForms();
  const schema = useAdminSchema();
  const existing = forms.data?.find((f) => f.id === id);
  if ((!isNew && forms.isLoading) || schema.isLoading) return <Spinner />;
  if (!isNew && !existing) return <div className="page"><ErrorAlert error={forms.error ?? new Error('Form not found')} /></div>;
  return <FormEditor key={id ?? 'new'} existing={existing} index={schema.data ? new SchemaIndex(schema.data.merged) : undefined} />;
}

function FormEditor({ existing, index }: { existing?: FormDefinition; index?: SchemaIndex }) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const toast = useToast();
  const { create, update } = useFormMutations();
  const [form, setForm] = useState<FormInput>(() => {
    if (existing) {
      const { id: _i, createdAt: _c, updatedAt: _u, ...rest } = existing;
      return structuredClone(rest);
    }
    const cls = params.get('class');
    const f = blankForm();
    if (cls && index) {
      const classes = index.expandClasses([cls]).map((c) => c.names[0]);
      f.objectClasses = [...new Set(['top', ...classes.reverse()])];
      f.name = humanize(cls);
      const { must } = index.classAttributes([cls]);
      f.fields = must.map((m) => fieldFromSchema(m, index, true));
      f.rdnAttribute = must.find((m) => ['cn', 'uid', 'ou'].includes(m.toLowerCase())) ?? must[0] ?? 'cn';
    }
    return f;
  });
  const [expanded, setExpanded] = useState<string | null>(null);
  const [showPreview, setShowPreview] = useState(false);

  const set = <K extends keyof FormInput>(k: K, v: FormInput[K]) => setForm((s) => ({ ...s, [k]: v }));
  const lint = useMemo(() => lintForm(form, index), [form, index]);
  const classNames = useMemo(() => index?.schema.objectClasses.map((c) => c.names[0]).sort((a, b) => a.localeCompare(b)) ?? [], [index]);
  const allAttributes = useMemo(() => index?.schema.attributeTypes.map((a) => a.names[0]).sort((a, b) => a.localeCompare(b)) ?? [], [index]);
  const classAttrs = useMemo(() => (index ? index.classAttributes(form.objectClasses) : { must: [], may: [] }), [index, form.objectClasses]);
  const used = new Set(form.fields.map((f) => (index?.primaryAttributeName(f.attribute) ?? f.attribute).toLowerCase()));
  const suggestions = [
    ...classAttrs.must.filter((a) => !used.has(a.toLowerCase())).map((a) => ({ a, must: true })),
    ...classAttrs.may.filter((a) => !used.has(a.toLowerCase()) && a.toLowerCase() !== 'userpassword').map((a) => ({ a, must: false })),
  ];
  const attributeOptions = classAttrs.must.length + classAttrs.may.length ? [...classAttrs.must, ...classAttrs.may] : allAttributes;
  const sections = [...new Set(form.fields.map((f) => f.section).filter((s): s is string => !!s))];

  const updateField = (i: number, f: FormField) => {
    setForm((s) => {
      const fields = [...s.fields];
      const prevAttr = fields[i].attribute;
      fields[i] = f;
      const rdn = s.rdnAttribute.toLowerCase() === prevAttr.toLowerCase() ? f.attribute : s.rdnAttribute;
      return { ...s, fields, rdnAttribute: rdn };
    });
  };
  const addField = (f: FormField) => {
    setForm((s) => ({ ...s, fields: [...s.fields, f] }));
    setExpanded(f.id);
  };
  const move = (i: number, dir: -1 | 1) =>
    setForm((s) => {
      const fields = [...s.fields];
      [fields[i], fields[i + dir]] = [fields[i + dir], fields[i]];
      return { ...s, fields };
    });

  const save = () => {
    const payload: FormInput = { ...form, description: form.description || undefined };
    const onSuccess = (r: { form: FormDefinition; warnings: string[] }) => {
      toast.success(`Form “${r.form.name}” saved`);
      if (!existing) navigate(`/admin/forms/${r.form.id}`, { replace: true });
    };
    if (existing) update.mutate({ id: existing.id, form: payload }, { onSuccess });
    else create.mutate(payload, { onSuccess });
  };
  const saving = create.isPending || update.isPending;
  const previewForm: FormDefinition = { ...form, id: 'preview', createdAt: '', updatedAt: '' };

  return (
    <div className="page page-wide">
      <Link to="/admin/forms" className="back-link">
        <ArrowLeft size={16} /> Forms
      </Link>
      <PageHeader
        title={existing ? `Edit form: ${existing.name}` : 'New form'}
        actions={
          <>
            <Button
              icon={<Eye size={16} />}
              onClick={() => setShowPreview((v) => !v)}
            >
              {showPreview ? 'Hide preview' : 'Preview'}
            </Button>
            <Button variant="primary" onClick={save} loading={saving} disabled={lint.errors.length > 0}>
              Save form
            </Button>
          </>
        }
      />
      <ErrorAlert error={create.error ?? update.error} />
      {lint.errors.length > 0 && (
        <Alert tone="danger" title="Fix before saving">
          <ul className="alert-list">{lint.errors.map((e) => <li key={e}>{e}</li>)}</ul>
        </Alert>
      )}
      {lint.warnings.length > 0 && (
        <Alert tone="warning" title="Schema warnings">
          <ul className="alert-list">{lint.warnings.map((e) => <li key={e}>{e}</li>)}</ul>
        </Alert>
      )}
      <datalist id="attr-names">
        <option value="dn" />
        {allAttributes.map((a) => (
          <option key={a} value={a} />
        ))}
      </datalist>

      <div className={cx('editor-layout', showPreview && 'with-preview')}>
        <div className="editor-main">
          <Card title="Object type">
            <div className="form-grid">
              <FormRow label="Form name" htmlFor="form-name" required>
                <input id="form-name" className="input" value={form.name} onChange={(e) => set('name', e.target.value)} />
              </FormRow>
              <FormRow label="Naming attribute (RDN)" htmlFor="form-rdn" hint="Names new entries, e.g. uid=jdoe,ou=people,…">
                <select id="form-rdn" className="input select" value={form.rdnAttribute} onChange={(e) => set('rdnAttribute', e.target.value)}>
                  {!form.fields.some((f) => f.attribute.toLowerCase() === form.rdnAttribute.toLowerCase()) && <option value={form.rdnAttribute}>{form.rdnAttribute} (not a field)</option>}
                  {form.fields.map((f) => (
                    <option key={f.id} value={f.attribute}>
                      {f.attribute}
                    </option>
                  ))}
                </select>
              </FormRow>
              <div className="span-2">
                <FormRow label="Object classes" htmlFor="form-classes" hint="Written to new entries. Include the structural class and any auxiliary classes (custom schemas welcome).">
                  <TagSelect id="form-classes" options={classNames} values={form.objectClasses} onChange={(v) => set('objectClasses', v)} />
                </FormRow>
              </div>
              <div className="span-2">
                <FormRow label="Description" htmlFor="form-desc">
                  <input id="form-desc" className="input" value={form.description ?? ''} onChange={(e) => set('description', e.target.value)} />
                </FormRow>
              </div>
            </div>
          </Card>

          <Card
            title={`Fields (${form.fields.length})`}
            actions={
              <Button size="sm" icon={<Plus size={14} />} onClick={() => addField({ ...fieldFromSchema('', index, false), label: '' })}>
                Add field
              </Button>
            }
          >
            {form.fields.length === 0 && <p className="muted">No fields yet. Add attributes from the schema suggestions or add a field manually.</p>}
            <div className="field-list">
              {form.fields.map((f, i) => (
                <FieldEditor
                  key={f.id}
                  field={f}
                  index={index}
                  attributeOptions={attributeOptions}
                  isRdn={f.attribute.toLowerCase() === form.rdnAttribute.toLowerCase()}
                  expanded={expanded === f.id}
                  onToggle={() => setExpanded((x) => (x === f.id ? null : f.id))}
                  onChange={(nf) => updateField(i, nf)}
                  onMove={(dir) => move(i, dir)}
                  onDuplicate={() => addField({ ...structuredClone(f), id: newId(), attribute: '', label: `${f.label} (copy)` })}
                  onRemove={() => setForm((s) => ({ ...s, fields: s.fields.filter((_, j) => j !== i) }))}
                  position={i}
                  count={form.fields.length}
                  sections={sections}
                />
              ))}
            </div>
          </Card>

          {suggestions.length > 0 && (
            <Card title="Add attributes from the schema" subtitle={`Allowed by ${form.objectClasses.filter((c) => c !== 'top').join(', ')}. Required ones are highlighted.`}>
              <div className="chips">
                {suggestions.map(({ a, must }) => (
                  <button key={a} type="button" className={cx('chip chip-add', must && 'chip-strong')} onClick={() => addField(fieldFromSchema(a, index, must))} title={index?.attribute(a)?.desc}>
                    <Plus size={12} /> {a}
                  </button>
                ))}
              </div>
            </Card>
          )}
        </div>

        {showPreview && (
          <aside className="editor-preview">
            <Card title="Preview" subtitle="How the form renders when creating an entry. Directory-backed drop downs load when used in a directory.">
              {form.fields.length ? (
                <EntryForm key={form.fields.map((f) => `${f.id}:${f.attribute}:${f.widget}:${f.multiValued}`).join('|')} definitionId="" form={previewForm} mode="create" submitLabel="Validate" onSubmit={() => toast.success('All values are valid')} />
              ) : (
                <p className="muted">Add fields to see a preview.</p>
              )}
            </Card>
          </aside>
        )}
      </div>
    </div>
  );
}
