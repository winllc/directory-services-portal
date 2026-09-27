import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, Plus, Search, SearchX } from 'lucide-react';
import type { DirectoryEntry, FormDefinition, FormField } from '@dsp/shared';
import { getAttr, rdnValue } from '@dsp/shared';
import { useDefinition, useEntries } from '../api/hooks';
import { DefinitionIcon } from '../components/Layout';
import { FieldValue } from '../components/EntryDetails';
import { Alert, Button, EmptyState, ErrorAlert, PageHeader, Spinner, useDebounced } from '../components/ui';
import { AccessBadge } from './HomePage';

const PAGE_SIZE = 25;

function columnLabel(form: FormDefinition | undefined, attr: string): string {
  return form?.fields.find((f) => f.attribute.toLowerCase() === attr.toLowerCase())?.label ?? attr;
}

function Cell({ entry, attr, field, definitionId }: { entry: DirectoryEntry; attr: string; field?: FormField; definitionId: string }) {
  const values = getAttr(entry.attributes, attr);
  if (field) return <FieldValue field={{ ...field, widget: field.widget === 'textarea' ? 'text' : field.widget }} values={values} definitionId={definitionId} />;
  if (!values.length) return <span className="muted">—</span>;
  return <>{values.join(', ')}</>;
}

export function DirectoryPage() {
  const { slug = '' } = useParams();
  // Remount per directory so the search box state resets.
  return <DirectoryView key={slug} slug={slug} />;
}

function DirectoryView({ slug }: { slug: string }) {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 1) || 1;
  const sort = params.get('sort') ?? undefined;
  const order = (params.get('order') as 'asc' | 'desc' | null) ?? 'asc';
  const [search, setSearch] = useState(q);
  const debounced = useDebounced(search, 300);

  const def = useDefinition(slug);
  const definition = def.data?.definition;
  const form = def.data?.form;

  useEffect(() => {
    // Push the debounced search box value into the URL (only when the user typed).
    setParams(
      (prev) => {
        if ((prev.get('q') ?? '') === debounced) return prev;
        const next = new URLSearchParams(prev);
        if (debounced) next.set('q', debounced);
        else next.delete('q');
        next.delete('page');
        return next;
      },
      { replace: true },
    );
  }, [debounced, setParams]);

  const entries = useEntries(definition?.id ?? slug, { q, page, pageSize: PAGE_SIZE, sort, order });
  const columns = useMemo(() => definition?.listAttributes ?? [], [definition]);

  const setParam = (updates: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(updates)) {
      if (v === undefined) next.delete(k);
      else next.set(k, v);
    }
    setParams(next);
  };

  const toggleSort = (attr: string) => {
    const current = sort ?? definition?.titleAttribute;
    if (current?.toLowerCase() === attr.toLowerCase()) setParam({ sort: attr, order: order === 'asc' ? 'desc' : 'asc', page: undefined });
    else setParam({ sort: attr, order: 'asc', page: undefined });
  };

  if (def.isLoading) return <Spinner label="Loading directory…" />;
  if (def.error || !definition || !form) return <div className="page"><ErrorAlert error={def.error ?? new Error('Directory not found')} /></div>;

  const total = entries.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const entryLink = (dn: string) => `/d/${definition.slug}/entry?dn=${encodeURIComponent(dn)}`;
  const activeSort = (sort ?? definition.titleAttribute).toLowerCase();

  return (
    <div className="page">
      <PageHeader
        icon={<DefinitionIcon icon={definition.icon} size={22} />}
        title={definition.name}
        subtitle={
          <>
            {definition.description && <span>{definition.description} </span>}
            <AccessBadge access={definition.access} mode={definition.mode} />
          </>
        }
        actions={
          definition.access === 'write' && (
            <Button variant="primary" icon={<Plus size={16} />} onClick={() => navigate(`/d/${definition.slug}/new`)}>
              New {form.name.toLowerCase()}
            </Button>
          )
        }
      />

      <div className="toolbar">
        <div className="search-box">
          <Search size={16} aria-hidden />
          <input
            className="input"
            type="search"
            placeholder={`Search ${definition.searchAttributes.map((a) => columnLabel(form, a).toLowerCase()).join(', ') || 'entries'}…`}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search"
          />
        </div>
        <div className="toolbar-meta muted">
          {entries.isFetching ? <span className="spinner spinner-sm" /> : `${total} ${total === 1 ? 'entry' : 'entries'}`}
        </div>
      </div>

      {entries.data?.truncated && <Alert tone="warning">Only the first 1000 matches are shown. Refine your search to narrow the results.</Alert>}
      <ErrorAlert error={entries.error} />

      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              {columns.map((c) => {
                const isActive = activeSort === c.toLowerCase();
                return (
                  <th key={c} aria-sort={isActive ? (order === 'asc' ? 'ascending' : 'descending') : undefined}>
                    <button type="button" className="th-sort" onClick={() => toggleSort(c)}>
                      {columnLabel(form, c)}
                      {isActive && (order === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} />)}
                    </button>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {entries.data?.entries.map((e) => (
              <tr key={e.dn} onClick={() => navigate(entryLink(e.dn))} className="row-link">
                {columns.map((c, i) => (
                  <td key={c}>
                    {i === 0 ? (
                      <Link to={entryLink(e.dn)} onClick={(ev) => ev.stopPropagation()} className="row-title">
                        {getAttr(e.attributes, c)[0] ?? rdnValue(e.dn)}
                      </Link>
                    ) : (
                      <Cell entry={e} attr={c} field={form.fields.find((f) => f.attribute.toLowerCase() === c.toLowerCase())} definitionId={definition.id} />
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {entries.isLoading && <Spinner />}
        {entries.data && entries.data.entries.length === 0 && (
          <EmptyState icon={<SearchX size={28} />} title={q ? 'No matches' : 'No entries yet'}>
            {q ? `Nothing matches “${q}”.` : definition.access === 'write' ? 'Create the first entry with the button above.' : 'This directory is empty.'}
          </EmptyState>
        )}
      </div>

      {pages > 1 && (
        <div className="pagination">
          <Button size="sm" icon={<ChevronLeft size={14} />} disabled={page <= 1} onClick={() => setParam({ page: String(page - 1) })}>
            Previous
          </Button>
          <span className="muted">
            Page {page} of {pages}
          </span>
          <Button size="sm" disabled={page >= pages} onClick={() => setParam({ page: String(page + 1) })}>
            Next <ChevronRight size={14} />
          </Button>
        </div>
      )}
    </div>
  );
}
