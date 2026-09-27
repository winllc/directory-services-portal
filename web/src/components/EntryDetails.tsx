import type { EntryAttributes, FormDefinition, FormField } from '@dsp/shared';
import { getAttr } from '@dsp/shared';
import { useFieldOptions } from '../api/hooks';
import { optionLabel } from './FieldInput';
import { groupBySection } from './EntryForm';

export function FieldValue({ field, values, definitionId }: { field: FormField; values: string[]; definitionId: string }) {
  const isLdap = field.widget === 'dropdown' && field.dropdown?.type === 'ldap';
  const q = useFieldOptions(definitionId, field.id, isLdap && values.length > 0);
  const options = field.dropdown?.type === 'static' ? field.dropdown.options : q.data;
  if (!values.length) return <span className="muted">—</span>;

  const render = (v: string) => {
    const label = optionLabel(options, v, field);
    if (field.format === 'email') return <a href={`mailto:${v}`}>{v}</a>;
    if (field.format === 'phone') return <a href={`tel:${v.replace(/[^\d+]/g, '')}`}>{v}</a>;
    if (field.format === 'url' && /^https?:\/\//i.test(v)) {
      return (
        <a href={v} target="_blank" rel="noreferrer noopener">
          {v}
        </a>
      );
    }
    if (field.widget === 'dropdown' && field.multiValued) return <span className="chip chip-static" title={v}>{label}</span>;
    return <span title={label !== v ? v : undefined}>{label}</span>;
  };

  if (field.widget === 'textarea') {
    return (
      <div className="value-text">
        {values.map((v, i) => (
          <p key={i}>{v}</p>
        ))}
      </div>
    );
  }
  return (
    <div className={field.widget === 'dropdown' && field.multiValued ? 'chips' : 'value-list'}>
      {values.map((v) => (
        <span key={v}>{render(v)}</span>
      ))}
    </div>
  );
}

export function EntryDetails({ form, attributes, definitionId, hideEmpty }: { form: FormDefinition; attributes: EntryAttributes; definitionId: string; hideEmpty?: boolean }) {
  const fields = hideEmpty ? form.fields.filter((f) => getAttr(attributes, f.attribute).length) : form.fields;
  return (
    <div className="entry-details">
      {groupBySection(fields).map((g) => (
        <section key={g.section || '_'} className="detail-section">
          {g.section && <h3>{g.section}</h3>}
          <dl className="detail-grid">
            {g.fields.map((f) => (
              <div key={f.id} className={f.widget === 'textarea' ? 'span-2' : undefined}>
                <dt>{f.label}</dt>
                <dd>
                  <FieldValue field={f} values={getAttr(attributes, f.attribute)} definitionId={definitionId} />
                </dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
    </div>
  );
}
