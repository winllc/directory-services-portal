import { useId, useState } from 'react';
import type { AttributeChoice, MetricKind, MetricSource, MetricWidget, TimeBucket, ValueTransform, WidgetWidth } from '@dsp/shared';
import { FormRow, Toggle, cx } from '../ui';
import { CHART_LABELS, KIND_HELP, KIND_LABELS, TRANSFORM_LABELS, chartsFor, isDateAttribute, normalizeWidget, suggestTitle } from './widgets';

const SOURCE_GROUPS: { source: AttributeChoice['source']; label: string }[] = [
  { source: 'form', label: 'Form fields' },
  { source: 'list', label: 'Columns' },
  { source: 'operational', label: 'Timestamps' },
  { source: 'dn', label: 'Entry' },
  { source: 'schema', label: 'Other attributes (schema)' },
];

const TEXT_TRANSFORMS: ValueTransform[] = ['none', 'lowercase', 'uppercase', 'emailDomain', 'dnRdn', 'dnParent', 'prefix', 'regex'];
const DATE_TRANSFORMS: ValueTransform[] = ['year', 'quarter', 'month', 'week', 'day'];

/** Attribute picker: the attributes the user may measure, grouped by where they come from. */
function AttributeSelect({
  id,
  value,
  onChange,
  choices,
  allowEmpty,
  filter,
}: {
  id: string;
  value: string | undefined;
  onChange: (v: string | undefined) => void;
  choices: AttributeChoice[];
  allowEmpty?: string;
  filter?: (a: AttributeChoice) => boolean;
}) {
  const shown = filter ? choices.filter(filter) : choices;
  const known = !value || choices.some((c) => c.attribute.toLowerCase() === value.toLowerCase());
  return (
    <select id={id} className="input select" value={value ?? ''} onChange={(e) => onChange(e.target.value || undefined)}>
      {allowEmpty !== undefined ? <option value="">{allowEmpty}</option> : !value && <option value="">Choose an attribute…</option>}
      {!known && <option value={value}>{value}</option>}
      {SOURCE_GROUPS.map((g) => {
        const items = shown.filter((c) => c.source === g.source);
        if (!items.length) return null;
        return (
          <optgroup key={g.source} label={g.label}>
            {items.map((c) => (
              <option key={c.attribute} value={c.attribute}>
                {c.label === c.attribute ? c.attribute : `${c.label} (${c.attribute})`}
              </option>
            ))}
          </optgroup>
        );
      })}
    </select>
  );
}

/**
 * Configures one metric widget: source directory, what to measure and how to draw it. Changes
 * are reported normalized (unused options dropped). The title follows the configuration until
 * the user edits it.
 */
export function WidgetEditor({
  value,
  onChange,
  sources,
  errors,
  showLayout = true,
}: {
  value: MetricWidget;
  onChange: (w: MetricWidget) => void;
  sources: MetricSource[];
  errors?: Record<string, string>;
  showLayout?: boolean;
}) {
  const uid = useId();
  const source = sources.find((s) => s.id === value.definitionId);
  const choices = source?.attributes ?? [];
  const [titleTouched, setTitleTouched] = useState(() => !!value.title && value.title !== suggestTitle(value, source));
  const [advanced, setAdvanced] = useState(!!value.filter);

  const update = (patch: Partial<MetricWidget>) => {
    const next = normalizeWidget({ ...value, ...patch });
    const nextSource = sources.find((s) => s.id === next.definitionId);
    onChange(titleTouched ? next : { ...next, title: suggestTitle(next, nextSource) });
  };

  const setKind = (kind: MetricKind) => {
    const patch: Partial<MetricWidget> = { kind };
    if (kind === 'timeline' && (!value.attribute || !choices.some((c) => c.attribute === value.attribute && isDateAttribute(c)))) {
      patch.attribute = 'createTimestamp';
    }
    if (kind === 'distribution' && (!value.attribute || value.attribute === 'createTimestamp' || value.attribute === 'modifyTimestamp')) {
      patch.attribute = choices.find((c) => c.widget === 'dropdown')?.attribute ?? choices[0]?.attribute;
    }
    if (kind === 'numeric' && !value.attribute) patch.attribute = choices.find((c) => c.multiValued)?.attribute ?? choices[0]?.attribute;
    patch.chart = chartsFor({ kind, splitBy: kind === 'distribution' ? value.splitBy : undefined })[0];
    if (kind === 'count') patch.width = value.width ?? 'third';
    update(patch);
  };

  const err = (k: string) => errors?.[k];
  const charts = chartsFor(value);
  const transformOptions = value.attribute && choices.some((c) => c.attribute === value.attribute && isDateAttribute(c))
    ? [...DATE_TRANSFORMS, ...TEXT_TRANSFORMS]
    : [...TEXT_TRANSFORMS, ...DATE_TRANSFORMS];

  return (
    <div className="widget-editor">
      <div className="form-grid">
        <FormRow label="Directory" htmlFor={`${uid}-src`} required error={err('definitionId')}>
          <select
            id={`${uid}-src`}
            className="input select"
            value={value.definitionId}
            onChange={(e) => update({ definitionId: e.target.value })}
          >
            {!source && <option value="">Choose a directory…</option>}
            {sources.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </FormRow>
        <FormRow label="Chart" htmlFor={`${uid}-chart`}>
          <div className="segmented" role="radiogroup" aria-label="Chart" id={`${uid}-chart`}>
            {charts.map((c) => (
              <button
                key={c}
                type="button"
                role="radio"
                aria-checked={value.chart === c}
                className={cx(value.chart === c && 'active')}
                onClick={() => update({ chart: c })}
              >
                {CHART_LABELS[c]}
              </button>
            ))}
          </div>
        </FormRow>
        <div className="span-2">
          <FormRow label="Measure" hint={KIND_HELP[value.kind]}>
            <div className="segmented" role="radiogroup" aria-label="Measure">
              {(Object.keys(KIND_LABELS) as MetricKind[]).map((k) => (
                <button key={k} type="button" role="radio" aria-checked={value.kind === k} className={cx(value.kind === k && 'active')} onClick={() => setKind(k)}>
                  {KIND_LABELS[k]}
                </button>
              ))}
            </div>
          </FormRow>
        </div>

        {value.kind === 'distribution' && (
          <>
            <FormRow label="Attribute" htmlFor={`${uid}-attr`} required error={err('attribute')}>
              <AttributeSelect id={`${uid}-attr`} value={value.attribute} onChange={(attribute) => update({ attribute })} choices={choices} />
            </FormRow>
            <FormRow label="Group values by" htmlFor={`${uid}-tr`}>
              <select
                id={`${uid}-tr`}
                className="input select"
                value={value.transform ?? 'none'}
                onChange={(e) => update({ transform: e.target.value as ValueTransform })}
              >
                {transformOptions.map((t) => (
                  <option key={t} value={t}>
                    {TRANSFORM_LABELS[t]}
                  </option>
                ))}
              </select>
            </FormRow>
            {(value.transform === 'prefix' || value.transform === 'regex') && (
              <div className="span-2">
                <FormRow
                  label={value.transform === 'prefix' ? 'Number of characters' : 'Pattern'}
                  htmlFor={`${uid}-targ`}
                  error={err('transformArg')}
                  hint={value.transform === 'regex' ? 'Values are grouped by the first capture group, e.g. ^([A-Za-z]+) for the first word.' : undefined}
                >
                  <input
                    id={`${uid}-targ`}
                    className={cx('input', value.transform === 'regex' && 'mono')}
                    type={value.transform === 'prefix' ? 'number' : 'text'}
                    min={1}
                    max={64}
                    value={value.transformArg ?? ''}
                    onChange={(e) => update({ transformArg: e.target.value })}
                  />
                </FormRow>
              </div>
            )}
            <FormRow label="Split by (stacked)" htmlFor={`${uid}-split`} error={err('splitBy')} hint="Optional second attribute, drawn as segments">
              <AttributeSelect
                id={`${uid}-split`}
                value={value.splitBy}
                onChange={(splitBy) => update({ splitBy, chart: splitBy ? 'stacked' : value.chart === 'stacked' ? 'bar' : value.chart })}
                choices={choices}
                allowEmpty="No split"
              />
            </FormRow>
            {value.splitBy ? (
              <FormRow label="Group split values by" htmlFor={`${uid}-str`}>
                <select
                  id={`${uid}-str`}
                  className="input select"
                  value={value.splitTransform ?? 'none'}
                  onChange={(e) => update({ splitTransform: e.target.value as ValueTransform })}
                >
                  {[...TEXT_TRANSFORMS.filter((t) => t !== 'prefix' && t !== 'regex'), ...DATE_TRANSFORMS].map((t) => (
                    <option key={t} value={t}>
                      {TRANSFORM_LABELS[t]}
                    </option>
                  ))}
                </select>
              </FormRow>
            ) : (
              <div />
            )}
            <FormRow label="Show the top" htmlFor={`${uid}-limit`} hint="The rest are combined as “Other”">
              <input
                id={`${uid}-limit`}
                className="input"
                type="number"
                min={1}
                max={100}
                value={value.limit ?? 10}
                onChange={(e) => update({ limit: e.target.value ? Math.min(100, Math.max(1, Number(e.target.value))) : undefined })}
              />
            </FormRow>
            <FormRow label="Order" htmlFor={`${uid}-sort`}>
              <select id={`${uid}-sort`} className="input select" value={value.sort ?? 'count'} onChange={(e) => update({ sort: e.target.value as 'count' | 'label' })}>
                <option value="count">Most entries first</option>
                <option value="label">Alphabetical</option>
              </select>
            </FormRow>
            <div className="span-2">
              <Toggle
                checked={value.includeMissing !== false}
                onChange={(v) => update({ includeMissing: v })}
                label="Count entries without a value as “(none)”"
              />
            </div>
          </>
        )}

        {value.kind === 'completeness' && (
          <div className="span-2">
            <FormRow label="Attributes" hint="None selected: every field of the directory's form" error={err('attributes')}>
              <div className="check-grid">
                {choices
                  .filter((c) => c.source !== 'dn')
                  .map((c) => {
                    const on = value.attributes?.includes(c.attribute) ?? false;
                    return (
                      <label key={c.attribute} className="check">
                        <input
                          type="checkbox"
                          checked={on}
                          onChange={() =>
                            update({ attributes: on ? value.attributes?.filter((a) => a !== c.attribute) : [...(value.attributes ?? []), c.attribute] })
                          }
                        />
                        {c.label}
                      </label>
                    );
                  })}
              </div>
            </FormRow>
            <Toggle checked={value.sort === 'count'} onChange={(v) => update({ sort: v ? 'count' : undefined })} label="Least complete first" />
          </div>
        )}

        {value.kind === 'timeline' && (
          <>
            <FormRow label="Date attribute" htmlFor={`${uid}-dattr`} required error={err('attribute')} hint="GeneralizedTime or ISO dates">
              <AttributeSelect id={`${uid}-dattr`} value={value.attribute} onChange={(attribute) => update({ attribute })} choices={choices} />
            </FormRow>
            <FormRow label="Period" htmlFor={`${uid}-bucket`}>
              <select id={`${uid}-bucket`} className="input select" value={value.bucket ?? 'month'} onChange={(e) => update({ bucket: e.target.value as TimeBucket })}>
                {(['day', 'week', 'month', 'quarter', 'year'] as TimeBucket[]).map((b) => (
                  <option key={b} value={b}>
                    {b[0].toUpperCase() + b.slice(1)}
                  </option>
                ))}
              </select>
            </FormRow>
            <FormRow label="Show the last" htmlFor={`${uid}-tl`} hint="Periods; empty shows all">
              <input
                id={`${uid}-tl`}
                className="input"
                type="number"
                min={1}
                max={100}
                value={value.limit ?? ''}
                onChange={(e) => update({ limit: e.target.value ? Math.min(100, Math.max(1, Number(e.target.value))) : undefined })}
              />
            </FormRow>
            <div className="form-row-inline">
              <Toggle checked={!!value.cumulative} onChange={(v) => update({ cumulative: v })} label="Running total" />
            </div>
          </>
        )}

        {value.kind === 'numeric' && (
          <>
            <FormRow label="Attribute" htmlFor={`${uid}-nattr`} required error={err('attribute')}>
              <AttributeSelect id={`${uid}-nattr`} value={value.attribute} onChange={(attribute) => update({ attribute })} choices={choices} />
            </FormRow>
            <FormRow label="Measure" htmlFor={`${uid}-measure`}>
              <select
                id={`${uid}-measure`}
                className="input select"
                value={value.measure ?? 'values'}
                onChange={(e) => update({ measure: e.target.value as 'values' | 'valueCount' })}
              >
                <option value="values">The values (numbers)</option>
                <option value="valueCount">How many values each entry has</option>
              </select>
            </FormRow>
            <FormRow label="Histogram bins" htmlFor={`${uid}-bins`} hint="Small whole-number ranges get one bin per number">
              <input
                id={`${uid}-bins`}
                className="input"
                type="number"
                min={2}
                max={50}
                value={value.bins ?? ''}
                placeholder="10"
                onChange={(e) => update({ bins: e.target.value ? Math.min(50, Math.max(2, Number(e.target.value))) : undefined })}
              />
            </FormRow>
          </>
        )}

        <div className="span-2">
          <button type="button" className="link-btn" onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
            {advanced ? 'Hide filter' : 'Only count some entries (LDAP filter)…'}
          </button>
          {advanced && (
            <FormRow label="Extra filter" htmlFor={`${uid}-filter`} error={err('filter')} hint="Combined with the directory's own filter, e.g. (employeeType=Contractor)">
              <input id={`${uid}-filter`} className="input mono" value={value.filter ?? ''} onChange={(e) => update({ filter: e.target.value })} />
            </FormRow>
          )}
        </div>

        <div className={showLayout ? undefined : 'span-2'}>
          <FormRow label="Title" htmlFor={`${uid}-title`} required error={err('title')}>
            <input
              id={`${uid}-title`}
              className="input"
              value={value.title}
              maxLength={128}
              onChange={(e) => {
                setTitleTouched(true);
                onChange({ ...value, title: e.target.value });
              }}
            />
          </FormRow>
        </div>
        {showLayout && (
          <FormRow label="Width on the dashboard" htmlFor={`${uid}-width`}>
            <select id={`${uid}-width`} className="input select" value={value.width ?? 'half'} onChange={(e) => update({ width: e.target.value as WidgetWidth })}>
              <option value="third">One third</option>
              <option value="half">Half</option>
              <option value="two-thirds">Two thirds</option>
              <option value="full">Full width</option>
            </select>
          </FormRow>
        )}
        <div className="span-2">
          <FormRow label="Description" htmlFor={`${uid}-desc`}>
            <input
              id={`${uid}-desc`}
              className="input"
              value={value.description ?? ''}
              maxLength={500}
              onChange={(e) => onChange({ ...value, description: e.target.value || undefined })}
            />
          </FormRow>
        </div>
      </div>
    </div>
  );
}
