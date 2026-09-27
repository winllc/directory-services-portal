import { useMemo, useState, type FormEvent, type ReactNode } from 'react';
import type { EntryAttributes, FormDefinition, FormField } from '@dsp/shared';
import { getAttr, isFieldWritable, normalizeValues, validateFormValues, type ValidationMode } from '@dsp/shared';
import { ApiError } from '../api/client';
import { useFieldOptions, type Values } from '../api/hooks';
import { FieldInput } from './FieldInput';
import { Alert, Button, FormRow } from './ui';

export function groupBySection(fields: FormField[]): { section: string; fields: FormField[] }[] {
  const groups: { section: string; fields: FormField[] }[] = [];
  for (const f of fields) {
    const section = f.section?.trim() || '';
    let g = groups.find((x) => x.section === section);
    if (!g) groups.push((g = { section, fields: [] }));
    g.fields.push(f);
  }
  return groups;
}

function initialValues(form: FormDefinition, entry?: EntryAttributes): Values {
  const out: Values = {};
  for (const f of form.fields) {
    const key = f.attribute.toLowerCase();
    out[key] = entry ? [...getAttr(entry, key)] : [...(f.defaultValues ?? [])];
  }
  return out;
}

const sameValues = (a: string[], b: string[]) => {
  const x = normalizeValues(a);
  const y = normalizeValues(b);
  return x.length === y.length && x.every((v, i) => v === y[i]);
};

function ConnectedField({
  definitionId,
  field,
  values,
  onChange,
  disabled,
  error,
}: {
  definitionId: string;
  field: FormField;
  values: string[];
  onChange: (v: string[]) => void;
  disabled: boolean;
  error?: string;
}) {
  const isLdap = field.widget === 'dropdown' && field.dropdown?.type === 'ldap';
  const q = useFieldOptions(definitionId, field.id, isLdap && !!definitionId);
  const options = field.dropdown?.type === 'static' ? field.dropdown.options : q.data;
  const id = `field-${field.id}`;
  return (
    <FormRow
      label={field.label}
      htmlFor={id}
      required={field.required && !disabled}
      hint={
        <>
          {field.helpText}
          {q.isError && <span className="text-danger"> Could not load options.</span>}
        </>
      }
      error={error}
    >
      <FieldInput
        id={id}
        field={field}
        values={values}
        onChange={onChange}
        options={options}
        optionsLoading={isLdap && q.isLoading}
        disabled={disabled}
        invalid={!!error}
      />
    </FormRow>
  );
}

export interface EntryFormProps {
  definitionId: string;
  form: FormDefinition;
  mode: ValidationMode;
  entry?: EntryAttributes;
  submitLabel?: string;
  submitting?: boolean;
  error?: unknown;
  onSubmit: (values: Values) => void;
  onCancel?: () => void;
  /** Extra controls rendered above the fields (e.g. container picker). */
  header?: ReactNode;
  /** Hide fields the user cannot change instead of showing them disabled. */
  hideReadOnly?: boolean;
}

/**
 * Renders a form definition for create / update / self-service editing, validates with the
 * same rules as the server and submits only changed attributes on update.
 */
export function EntryForm({ definitionId, form, mode, entry, submitLabel, submitting, error, onSubmit, onCancel, header, hideReadOnly }: EntryFormProps) {
  const initial = useMemo(() => initialValues(form, entry), [form, entry]);
  const [values, setValues] = useState<Values>(initial);
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState(false);

  const serverErrors = error instanceof ApiError ? (error.details ?? {}) : {};
  const errors = { ...serverErrors, ...clientErrors };

  const visible = form.fields.filter((f) => {
    const writable = isFieldWritable(form, f, mode);
    if (mode === 'create') return writable;
    return writable || !hideReadOnly;
  });

  const dirty = form.fields.some((f) => !sameValues(values[f.attribute.toLowerCase()] ?? [], initial[f.attribute.toLowerCase()] ?? []));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    // Only send what changed on update so untouched legacy values are never rewritten.
    const payload: Values = {};
    for (const f of form.fields) {
      if (!isFieldWritable(form, f, mode)) continue;
      const key = f.attribute.toLowerCase();
      const v = normalizeValues(values[key]);
      if (mode === 'create' || !sameValues(v, initial[key] ?? [])) payload[key] = v;
    }
    const { errors: errs } = validateFormValues(form, payload, { mode });
    setClientErrors(errs);
    if (Object.keys(errs).length) return;
    onSubmit(payload);
  };

  const groups = groupBySection(visible);
  const topError = error && !(error instanceof ApiError && error.details && Object.keys(error.details).length && error.status === 400);

  return (
    <form className="entry-form" onSubmit={submit} noValidate>
      {header}
      {topError ? (
        <Alert tone="danger">{(error as Error).message}</Alert>
      ) : touched && Object.keys(errors).length > 0 ? (
        <Alert tone="danger">Please correct the highlighted fields.</Alert>
      ) : null}
      {groups.map((g) => (
        <fieldset key={g.section || '_'} className="form-section">
          {g.section && <legend>{g.section}</legend>}
          <div className="form-grid">
            {g.fields.map((f) => {
              const key = f.attribute.toLowerCase();
              const writable = isFieldWritable(form, f, mode);
              return (
                <div key={f.id} className={f.widget === 'textarea' || f.multiValued ? 'span-2' : undefined}>
                  <ConnectedField
                    definitionId={definitionId}
                    field={f}
                    values={values[key] ?? []}
                    disabled={!writable || !!submitting}
                    error={errors[key]}
                    onChange={(v) => {
                      setValues((s) => ({ ...s, [key]: v }));
                      if (clientErrors[key]) setClientErrors(({ [key]: _, ...rest }) => rest);
                    }}
                  />
                </div>
              );
            })}
          </div>
        </fieldset>
      ))}
      <div className="form-actions">
        {onCancel && (
          <Button onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
        )}
        <Button type="submit" variant="primary" loading={submitting} disabled={mode !== 'create' && !dirty}>
          {submitLabel ?? (mode === 'create' ? 'Create' : 'Save changes')}
        </Button>
      </div>
    </form>
  );
}
