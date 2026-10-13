import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Plus, Search, X } from 'lucide-react';
import type { FormField, OptionItem } from '@dsp/shared';
import { rdnValue } from '@dsp/shared';
import { useOptionSearch } from '../api/hooks';
import { cx, useDebounced } from './ui';

/**
 * Type-ahead picker for drop downs backed by a directory search: the directory is queried as the
 * user types (after {@code minChars} characters), with the label and an optional second line per
 * match. Works for single and multi-valued fields; a single value is replaced when picking another.
 */
export function DirectorySearchSelect({
  field,
  values,
  onChange,
  known,
  definitionId,
  minChars,
  disabled,
  invalid,
  id,
}: {
  field: FormField;
  values: string[];
  onChange: (values: string[]) => void;
  /** Labels of values already known (looked up for stored values). */
  known?: OptionItem[];
  definitionId: string;
  minChars: number;
  disabled?: boolean;
  invalid?: boolean;
  id?: string;
}) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const q = useDebounced(query.trim(), 250);
  const enough = q.length >= minChars;
  const search = useOptionSearch(definitionId, field.id, q, open && enough && !disabled);
  // Labels of everything seen, so a picked option keeps its label.
  const [seen, setSeen] = useState<Map<string, OptionItem>>(() => new Map());
  useEffect(() => {
    const add = [...(known ?? []), ...(search.data?.options ?? [])];
    if (!add.length) return;
    setSeen((prev) => {
      const next = new Map(prev);
      add.forEach((o) => next.set(o.value, o));
      return next;
    });
  }, [known, search.data]);

  const results = useMemo(() => (enough ? (search.data?.options ?? []).filter((o) => !values.includes(o.value)) : []), [enough, search.data, values]);
  const typed = query.trim();
  const canAddCustom = !!field.allowCustomValues && typed !== '' && !values.includes(typed) && !results.some((o) => o.value === typed);
  const isDn = field.dropdown?.type === 'ldap' && field.dropdown.valueAttribute.toLowerCase() === 'dn';
  const labelOf = (v: string) => seen.get(v)?.label ?? (isDn ? rdnValue(v) : v);

  const pick = (v: string) => {
    if (!v) return;
    onChange(field.multiValued ? (values.includes(v) ? values : [...values, v]) : [v]);
    setQuery('');
    setActive(0);
    if (!field.multiValued) setOpen(false);
    inputRef.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (results[active]) pick(results[active].value);
      else if (canAddCustom) pick(typed);
    } else if (e.key === 'Backspace' && query === '' && values.length) {
      onChange(values.slice(0, -1));
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const hint = !enough
    ? minChars > 0 && `Type ${minChars - query.trim().length} more character${minChars - query.trim().length === 1 ? '' : 's'} to search`
    : search.isFetching && !search.data
      ? 'Searching…'
      : search.isError
        ? 'The search failed'
        : results.length === 0 && !canAddCustom
          ? 'No matches'
          : null;

  return (
    <div className={cx('multiselect', 'dir-search', disabled && 'disabled', invalid && 'invalid')} onClick={() => inputRef.current?.focus()}>
      <div className="chips">
        <Search size={14} className="dir-search-icon" aria-hidden />
        {values.map((v) => (
          <span className="chip" key={v} title={seen.get(v)?.description ? `${seen.get(v)?.description}\n${v}` : v}>
            {labelOf(v)}
            {!disabled && (
              <button
                type="button"
                aria-label={`Remove ${labelOf(v)}`}
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
            placeholder={values.length && !field.multiValued ? 'Search to replace…' : (field.placeholder ?? 'Type to search the directory…')}
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
      {open && !disabled && (results.length > 0 || canAddCustom || hint) && (
        <ul className={cx('combo-list', search.isFetching && 'is-loading')} id={listId} role="listbox">
          {results.map((o, i) => (
            <li
              key={o.value}
              role="option"
              aria-selected={i === active}
              className={cx(i === active && 'active')}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(o.value);
              }}
              onMouseEnter={() => setActive(i)}
            >
              <span>{o.label ?? o.value}</span>
              {(o.description || (o.label && o.label !== o.value && !isDn)) && <small>{o.description ?? o.value}</small>}
            </li>
          ))}
          {canAddCustom && (
            <li
              role="option"
              aria-selected={false}
              className="combo-custom"
              onMouseDown={(e) => {
                e.preventDefault();
                pick(typed);
              }}
            >
              <Plus size={14} /> Use “{typed}”
            </li>
          )}
          {hint && <li className="combo-hint" role="presentation">{hint}</li>}
          {enough && search.data?.truncated && (
            <li className="combo-hint" role="presentation">
              More matches than shown: keep typing to narrow the search.
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
