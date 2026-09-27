import { useMemo } from 'react';
import type { FormField, OptionItem } from '@dsp/shared';
import { FieldInput } from './FieldInput';

/** Multi-value chip picker built on the same widget used for directory forms. */
export function TagSelect({
  id,
  options,
  values,
  onChange,
  allowCustom = true,
  placeholder,
  single,
}: {
  id?: string;
  options: (string | OptionItem)[];
  values: string[];
  onChange: (values: string[]) => void;
  allowCustom?: boolean;
  placeholder?: string;
  single?: boolean;
}) {
  const opts = useMemo(() => options.map((o) => (typeof o === 'string' ? { value: o } : o)), [options]);
  const field: FormField = useMemo(
    () => ({
      id: id ?? 'tag',
      attribute: 'tag',
      label: placeholder ?? 'Values',
      widget: 'dropdown',
      multiValued: !single,
      required: false,
      readOnly: false,
      selfEditable: false,
      allowCustomValues: allowCustom,
      placeholder,
      dropdown: { type: 'static', options: opts },
    }),
    [id, placeholder, allowCustom, opts, single],
  );
  return <FieldInput id={id} field={field} values={values} onChange={onChange} options={opts} />;
}
