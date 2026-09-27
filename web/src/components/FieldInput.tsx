import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ChevronDown, Plus, X } from 'lucide-react';
import type { FormField, OptionItem } from '@dsp/shared';
import { rdnValue } from '@dsp/shared';
import { cx } from './ui';

export interface FieldInputProps {
  field: FormField;
  values: string[];
  onChange: (values: string[]) => void;
  options?: OptionItem[];
  optionsLoading?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  id?: string;
}

const inputType = (field: FormField) => {
  switch (field.format) {
    case 'email':
      return 'email';
    case 'phone':
      return 'tel';
    case 'url':
      return 'url';
    default:
      return 'text';
  }
};

export function optionLabel(options: OptionItem[] | undefined, value: string, field?: FormField): string {
  const found = options?.find((o) => o.value === value);
  if (found) return found.label ?? found.value;
  if (field?.dropdown?.type === 'ldap' && field.dropdown.valueAttribute.toLowerCase() === 'dn') return rdnValue(value);
  return value;
}

/** Renders the right widget for a form field: free text or drop down, single or multi-valued. */
export function FieldInput(props: FieldInputProps) {
  const { field } = props;
  if (field.widget === 'dropdown') {
    return field.multiValued ? <MultiSelect {...props} /> : <SingleSelect {...props} />;
  }
  return field.multiValued ? <MultiText {...props} /> : <SingleText {...props} />;
}

function SingleText({ field, values, onChange, disabled, invalid, id }: FieldInputProps) {
  const value = values[0] ?? '';
  const common = {
    id,
    value,
    disabled,
    placeholder: field.placeholder,
    maxLength: field.maxLength,
    'aria-invalid': invalid || undefined,
    className: 'input',
    onChange: (e: { target: { value: string } }) => onChange(e.target.value === '' ? [] : [e.target.value]),
  };
  return field.widget === 'textarea' ? <textarea rows={4} {...common} /> : <input type={inputType(field)} inputMode={field.format === 'number' ? 'decimal' : undefined} {...common} />;
}

function MultiText({ field, values, onChange, disabled, invalid, id }: FieldInputProps) {
  // Always show at least one editable row.
  const rows = values.length ? values : [''];
  const refs = useRef<(HTMLInputElement | HTMLTextAreaElement | null)[]>([]);
  const set = (i: number, v: string) => {
    const next = [...rows];
    next[i] = v;
    onChange(next.every((x) => x === '') ? [] : next);
  };
  const remove = (i: number) => onChange(rows.filter((_, idx) => idx !== i).filter((x) => x !== ''));
  const add = () => {
    onChange([...rows, '']);
    setTimeout(() => refs.current[rows.length]?.focus(), 0);
  };
  return (
    <div className="multi-text">
      {rows.map((v, i) => (
        <div className="multi-text-row" key={i}>
          {field.widget === 'textarea' ? (
            <textarea
              ref={(el) => {
                refs.current[i] = el;
              }}
              id={i === 0 ? id : undefined}
              className="input"
              rows={3}
              value={v}
              disabled={disabled}
              aria-invalid={invalid || undefined}
              aria-label={`${field.label} value ${i + 1}`}
              placeholder={field.placeholder}
              onChange={(e) => set(i, e.target.value)}
            />
          ) : (
            <input
              ref={(el) => {
                refs.current[i] = el;
              }}
              id={i === 0 ? id : undefined}
              className="input"
              type={inputType(field)}
              value={v}
              disabled={disabled}
              aria-invalid={invalid || undefined}
              aria-label={`${field.label} value ${i + 1}`}
              placeholder={field.placeholder}
              maxLength={field.maxLength}
              onChange={(e) => set(i, e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  if (v.trim()) add();
                }
              }}
            />
          )}
          {!disabled && (rows.length > 1 || v !== '') && (
            <button type="button" className="icon-btn" onClick={() => remove(i)} aria-label={`Remove ${field.label} value ${i + 1}`}>
              <X size={16} />
            </button>
          )}
        </div>
      ))}
      {!disabled && (
        <button type="button" className="link-btn" onClick={add} disabled={rows[rows.length - 1] === ''}>
          <Plus size={14} /> Add value
        </button>
      )}
    </div>
  );
}

function SingleSelect({ field, values, onChange, options, optionsLoading, disabled, invalid, id }: FieldInputProps) {
  const value = values[0] ?? '';
  const listId = useId();
  if (field.allowCustomValues) {
    return (
      <>
        <input
          id={id}
          className="input"
          list={listId}
          value={value}
          disabled={disabled}
          placeholder={field.placeholder ?? 'Choose or type a value'}
          aria-invalid={invalid || undefined}
          onChange={(e) => onChange(e.target.value === '' ? [] : [e.target.value])}
        />
        <datalist id={listId}>
          {options?.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label && o.label !== o.value ? o.label : undefined}
            </option>
          ))}
        </datalist>
      </>
    );
  }
  const known = options?.some((o) => o.value === value);
  return (
    <div className="select-wrap">
      <select
        id={id}
        className="input select"
        value={value}
        disabled={disabled || optionsLoading}
        aria-invalid={invalid || undefined}
        onChange={(e) => onChange(e.target.value === '' ? [] : [e.target.value])}
      >
        <option value="">{optionsLoading ? 'Loading…' : field.required ? 'Select…' : '— None —'}</option>
        {value && !known && !optionsLoading && <option value={value}>{optionLabel(options, value, field)} (current)</option>}
        {options?.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label ?? o.value}
          </option>
        ))}
      </select>
      <ChevronDown size={16} className="select-caret" aria-hidden />
    </div>
  );
}

function MultiSelect({ field, values, onChange, options, optionsLoading, disabled, invalid, id }: FieldInputProps) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  const available = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (options ?? [])
      .filter((o) => !values.includes(o.value))
      .filter((o) => !q || (o.label ?? o.value).toLowerCase().includes(q) || o.value.toLowerCase().includes(q))
      .slice(0, 100);
  }, [options, values, query]);

  const canAddCustom =
    field.allowCustomValues && query.trim() !== '' && !values.includes(query.trim()) && !available.some((o) => o.value === query.trim());

  const add = (v: string) => {
    if (!v || values.includes(v)) return;
    onChange([...values, v]);
    setQuery('');
    setActive(0);
    inputRef.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, available.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (open && available[active]) add(available[active].value);
      else if (canAddCustom) add(query.trim());
    } else if (e.key === 'Backspace' && query === '' && values.length) {
      onChange(values.slice(0, -1));
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className={cx('multiselect', disabled && 'disabled', invalid && 'invalid')} onClick={() => inputRef.current?.focus()}>
      <div className="chips">
        {values.map((v) => (
          <span className="chip" key={v} title={v}>
            {optionLabel(options, v, field)}
            {!disabled && (
              <button
                type="button"
                aria-label={`Remove ${optionLabel(options, v, field)}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onChange(values.filter((x) => x !== v));
                }}
              >
                <X size={12} />
              </button>
            )}
          </span>
        ))}
        {!disabled && (
          <input
            ref={inputRef}
            id={id}
            className="chip-input"
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-invalid={invalid || undefined}
            value={query}
            placeholder={values.length ? '' : optionsLoading ? 'Loading…' : (field.placeholder ?? 'Search or select…')}
            onChange={(e) => {
              setQuery(e.target.value);
              setOpen(true);
              setActive(0);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            onKeyDown={onKeyDown}
          />
        )}
      </div>
      {open && !disabled && (available.length > 0 || canAddCustom) && (
        <ul className="combo-list" id={listId} role="listbox">
          {available.map((o, i) => (
            <li
              key={o.value}
              role="option"
              aria-selected={i === active}
              className={cx(i === active && 'active')}
              onMouseDown={(e) => {
                e.preventDefault();
                add(o.value);
              }}
              onMouseEnter={() => setActive(i)}
            >
              <span>{o.label ?? o.value}</span>
              {o.label && o.label !== o.value && <small>{o.value}</small>}
            </li>
          ))}
          {canAddCustom && (
            <li
              role="option"
              aria-selected={false}
              className="combo-custom"
              onMouseDown={(e) => {
                e.preventDefault();
                add(query.trim());
              }}
            >
              <Plus size={14} /> Add “{query.trim()}”
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
