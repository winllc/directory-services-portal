import { useState } from 'react';
import { AlertTriangle, Search } from 'lucide-react';
import type { DirectorySearchMatch, DropdownSource } from '@dsp/shared';
import { useOptionPreview } from '../api/hooks';
import { FormRow, cx, useDebounced } from './ui';
import { TagSelect } from './TagSelect';

type Ldap = Extract<DropdownSource, { type: 'ldap' }>;

const MATCH_LABELS: Record<DirectorySearchMatch, string> = {
  contains: 'Contains the text',
  startsWith: 'Starts with the text',
  exact: 'Equals the text',
};

const num = (v: string, min: number, max: number): number | undefined => {
  if (v.trim() === '') return undefined;
  const n = Math.round(Number(v));
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : undefined;
};

/**
 * Settings of a drop down backed by a directory search: where to search, what to store and show,
 * and how searching is tuned, with a live test against the directory.
 */
export function DirectorySearchConfig({ value, onChange, idPrefix, attributeOptions }: { value: Ldap; onChange: (v: Ldap) => void; idPrefix: string; attributeOptions: string[] }) {
  const id = (s: string) => `${idPrefix}-${s}`;
  const set = <K extends keyof Ldap>(k: K, v: Ldap[K]) => {
    const next = { ...value, [k]: v };
    if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) delete next[k];
    onChange(next);
  };
  const search = value.mode === 'search';
  const [testQuery, setTestQuery] = useState('');
  const debouncedSource = useDebounced(value, 500);
  const debouncedQuery = useDebounced(testQuery, 300);
  const preview = useOptionPreview(debouncedSource, debouncedQuery, true);
  const p = preview.data;
  const minChars = value.minChars ?? (search ? 2 : 0);
  const tooShort = search && debouncedQuery.trim().length < minChars;

  return (
    <div className="dir-config">
      <div className="form-grid">
        <FormRow label="Search base" htmlFor={id('base')}>
          <input id={id('base')} className="input mono" value={value.baseDn} onChange={(e) => set('baseDn', e.target.value)} placeholder="ou=people,dc=example,dc=com" />
        </FormRow>
        <FormRow label="Scope" htmlFor={id('scope')}>
          <select id={id('scope')} className="input select" value={value.scope} onChange={(e) => set('scope', e.target.value as 'one' | 'sub')}>
            <option value="one">One level</option>
            <option value="sub">Subtree</option>
          </select>
        </FormRow>
        <div className="span-2">
          <FormRow label="Filter" htmlFor={id('filter')} hint="Which entries are options, e.g. (&(objectClass=inetOrgPerson)(!(employeeType=Former)))">
            <input id={id('filter')} className="input mono" value={value.filter} onChange={(e) => set('filter', e.target.value)} />
          </FormRow>
        </div>
        <FormRow label="Stored value" htmlFor={id('va')} hint='Attribute stored in the entry; "dn" stores the DN (for member/manager style links).'>
          <input id={id('va')} className="input mono" list="attr-names" value={value.valueAttribute} onChange={(e) => set('valueAttribute', e.target.value)} />
        </FormRow>
        <FormRow label="Displayed label" htmlFor={id('la')}>
          <input id={id('la')} className="input mono" list="attr-names" value={value.labelAttribute} onChange={(e) => set('labelAttribute', e.target.value)} />
        </FormRow>
      </div>

      <div className="dir-config-section">
        <div className="form-label">How users find options</div>
        <div className="segmented" role="radiogroup" aria-label="How users find options">
          <button type="button" role="radio" aria-checked={!search} className={cx(!search && 'active')} onClick={() => set('mode', undefined)}>
            Load all options
          </button>
          <button type="button" role="radio" aria-checked={search} className={cx(search && 'active')} onClick={() => set('mode', 'search')}>
            Search as you type
          </button>
        </div>
        <p className="form-hint">
          {search
            ? 'The directory is searched as the user types: best for large sets such as people or devices.'
            : 'All options are loaded when the form opens: best for short lists. Lists longer than the limit can still be searched by typing.'}
        </p>
      </div>

      <div className="form-grid">
        <div className="span-2">
          <FormRow label="Match typed text against" hint={`Each typed word must match one of these. Default: ${value.labelAttribute || 'the label'}${value.valueAttribute && value.valueAttribute.toLowerCase() !== 'dn' && value.valueAttribute !== value.labelAttribute ? ` and ${value.valueAttribute}` : ''}.`}>
            <TagSelect
              id={id('search-attrs')}
              options={attributeOptions.filter((a) => a.toLowerCase() !== 'dn')}
              values={value.searchAttributes ?? []}
              onChange={(v) => set('searchAttributes', v)}
              placeholder="e.g. cn, uid, mail"
            />
          </FormRow>
        </div>
        <FormRow label="Matching" htmlFor={id('match')}>
          <select id={id('match')} className="input select" value={value.match ?? 'contains'} onChange={(e) => set('match', e.target.value === 'contains' ? undefined : (e.target.value as DirectorySearchMatch))}>
            {(Object.keys(MATCH_LABELS) as DirectorySearchMatch[]).map((m) => (
              <option key={m} value={m}>
                {MATCH_LABELS[m]}
              </option>
            ))}
          </select>
        </FormRow>
        {search ? (
          <FormRow label="Characters before searching" htmlFor={id('min')} hint="Fewer searches on large directories">
            <input id={id('min')} className="input" type="number" min={0} max={10} placeholder="2" value={value.minChars ?? ''} onChange={(e) => set('minChars', num(e.target.value, 0, 10))} />
          </FormRow>
        ) : (
          <FormRow label="Reuse loaded options for" htmlFor={id('cache')} hint="Seconds; 0 always asks the directory">
            <input id={id('cache')} className="input" type="number" min={0} max={3600} placeholder="30" value={value.cacheSeconds ?? ''} onChange={(e) => set('cacheSeconds', num(e.target.value, 0, 3600))} />
          </FormRow>
        )}
        <FormRow label="Most options shown" htmlFor={id('limit')} hint={search ? 'Per search (default 25)' : 'Default 500'}>
          <input id={id('limit')} className="input" type="number" min={1} max={2000} placeholder={search ? '25' : '500'} value={value.limit ?? ''} onChange={(e) => set('limit', num(e.target.value, 1, 2000))} />
        </FormRow>
        <FormRow label="Sort by" htmlFor={id('sort')} hint="Default: the label">
          <input id={id('sort')} className="input mono" list="attr-names" placeholder={value.labelAttribute} value={value.sortAttribute ?? ''} onChange={(e) => set('sortAttribute', e.target.value.trim() || undefined)} />
        </FormRow>
        <FormRow label="Second line" htmlFor={id('desc')} hint="Shown under each option, e.g. mail or title">
          <input id={id('desc')} className="input mono" list="attr-names" value={value.descriptionAttribute ?? ''} onChange={(e) => set('descriptionAttribute', e.target.value.trim() || undefined)} />
        </FormRow>
        <FormRow label="Label template" htmlFor={id('tpl')} hint='Overrides the label, e.g. "{cn} ({uid})". Empty brackets are dropped.'>
          <input id={id('tpl')} className="input mono" value={value.labelTemplate ?? ''} onChange={(e) => set('labelTemplate', e.target.value || undefined)} />
        </FormRow>
      </div>

      <div className="dir-test">
        <div className="dir-test-head">
          <Search size={15} aria-hidden />
          <label htmlFor={id('test')} className="form-label">
            Test search
          </label>
          <input
            id={id('test')}
            className="input input-sm"
            value={testQuery}
            placeholder={search ? 'Type as a user would…' : 'Optional: type to narrow'}
            onChange={(e) => setTestQuery(e.target.value)}
          />
        </div>
        {!value.baseDn ? (
          <p className="muted small">Enter a search base to try it.</p>
        ) : p?.error ? (
          <p className="text-danger small">
            <AlertTriangle size={14} aria-hidden /> {p.error}
          </p>
        ) : tooShort ? (
          <p className="muted small">Users see results after typing {minChars} character{minChars === 1 ? '' : 's'}.</p>
        ) : p ? (
          <div className={cx('dir-test-results', preview.isFetching && 'is-loading')}>
            <div className="muted small">
              {p.options.length} option{p.options.length === 1 ? '' : 's'}
              {p.truncated ? ' (more match: the limit applies)' : ''} · {p.durationMs} ms
            </div>
            {p.options.length > 0 && (
              <ul className="dir-test-list">
                {p.options.slice(0, 15).map((o) => (
                  <li key={o.value}>
                    <span>{o.label ?? o.value}</span>
                    {o.description && <small className="muted">{o.description}</small>}
                    <code className="muted small">{o.value}</code>
                  </li>
                ))}
                {p.options.length > 15 && <li className="muted small">… {p.options.length - 15} more</li>}
              </ul>
            )}
            {p.filter && (
              <div className="small">
                <span className="muted">LDAP filter: </span>
                <code className="dir-test-filter">{p.filter}</code>
              </div>
            )}
          </div>
        ) : (
          <p className="muted small">{preview.isFetching ? 'Searching…' : ''}</p>
        )}
      </div>
    </div>
  );
}
