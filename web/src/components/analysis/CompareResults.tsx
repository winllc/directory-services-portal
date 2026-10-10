import { Fragment, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Search } from 'lucide-react';
import type { AnalysisResult, CompareRow, DirectoryDefinition } from '@dsp/shared';
import { Alert, Badge, Button, Card, cx } from '../ui';
import { BarChart, exact, pct } from '../charts';
import { downloadCsv } from '../../lib/csv';
import { dnParam } from '../../api/client';
import { Kpi, OkBadge, PAGE_SIZE, Pager, Values } from './common';

const STATUS_LABEL: Record<CompareRow['status'], string> = {
  missing: 'Missing from a source',
  different: 'Values differ',
  duplicate: 'Duplicate key',
};

const STATUS_TONE: Record<CompareRow['status'], 'warning' | 'danger' | 'info'> = { missing: 'warning', different: 'danger', duplicate: 'info' };

export function CompareResults({ result, definitions }: { result: AnalysisResult; definitions?: DirectoryDefinition[] }) {
  const c = result.compare!;
  const [status, setStatus] = useState<'' | CompareRow['status']>('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const labels = c.sources.map((s) => s.label);
  const slugOf = (i: number) => definitions?.find((d) => d.id === c.sources[i].definitionId)?.slug;
  const s = c.summary;

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return c.rows.filter((r) => (!status || r.status === status || (status === 'duplicate' && r.duplicate)) && (!needle || r.key.toLowerCase().includes(needle)));
  }, [c.rows, status, q]);
  const shown = rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const exportCsv = () => {
    const head = ['Key', 'Status', ...labels.map((l) => `In ${l}`), ...labels.map((l) => `DN in ${l}`), 'Attribute', ...labels.map((l) => `Value in ${l}`)];
    const out: (string | number)[][] = [head];
    for (const r of rows) {
      const base = [r.key, STATUS_LABEL[r.status] + (r.duplicate && r.status !== 'duplicate' ? ' (duplicate key)' : ''), ...r.present.map((p) => (p ? 'yes' : 'no')), ...r.dns.map((d) => d?.join(' | ') ?? '')];
      if (!r.differences?.length) out.push([...base, '', ...labels.map(() => '')]);
      for (const d of r.differences ?? []) out.push([...base, d.label, ...d.values.map((v) => (v ? v.join(' | ') : ''))]);
    }
    downloadCsv('comparison.csv', out);
  };

  return (
    <div className="stack">
      {result.warnings?.map((w) => (
        <Alert key={w} tone="warning">
          {w}
        </Alert>
      ))}
      {c.sources.some((x) => x.truncated) && <Alert tone="warning">Some sources were larger than the scan limit; results cover the entries read.</Alert>}
      <div className="stat-row">
        <Kpi label="Keys" value={exact(s.keys)} sub={`${exact(s.inAll)} in every source`} />
        <Kpi label="Identical" value={exact(s.identical)} sub={pct(s.keys ? s.identical / s.keys : 0) + ' of keys'} tone="good" />
        <Kpi label="Values differ" value={exact(s.different)} tone={s.different ? 'critical' : undefined} />
        <Kpi label="Missing from a source" value={exact(s.partial)} tone={s.partial ? 'warning' : undefined} />
        <Kpi label="Duplicate keys" value={exact(s.duplicates)} tone={s.duplicates ? 'warning' : undefined} />
      </div>

      <div className="split split-even">
        <Card title="Sources">
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>Source</th>
                  <th className="num">Entries</th>
                  <th className="num">No key</th>
                  <th className="num">Duplicates</th>
                  <th className="num">Only here</th>
                </tr>
              </thead>
              <tbody>
                {c.sources.map((x, i) => (
                  <tr key={i}>
                    <td>
                      {x.label}
                      {x.label !== x.definitionName && <div className="muted small">{x.definitionName}</div>}
                    </td>
                    <td className="num">{exact(x.entries)}</td>
                    <td className="num">{exact(x.missingKey ?? 0)}</td>
                    <td className="num">{exact(x.duplicateKeys ?? 0)}</td>
                    <td className="num">{exact(s.onlyIn[i] ?? 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Differences per attribute" subtitle="Keys present everywhere whose values don't match">
          {c.attributes.length ? (
            <BarChart label="Differences per attribute" rows={c.attributes.map((a) => ({ key: a.label, label: a.label, value: a.differences, share: s.inAll ? a.differences / s.inAll : 0 }))} />
          ) : (
            <p className="muted">No attributes compared: only presence is checked.</p>
          )}
        </Card>
      </div>

      <Card
        title={`Findings (${exact(c.rowCount)})`}
        subtitle={c.rowsTruncated ? `Showing the first ${exact(c.rows.length)}; the counts above cover everything.` : undefined}
        actions={
          <Button size="sm" icon={<Download size={14} />} onClick={exportCsv} disabled={!rows.length}>
            CSV
          </Button>
        }
      >
        <div className="toolbar">
          <div className="segmented" role="radiogroup" aria-label="Status">
            {(['', 'different', 'missing', 'duplicate'] as const).map((st) => (
              <button
                key={st}
                type="button"
                role="radio"
                aria-checked={status === st}
                className={cx(status === st && 'active')}
                onClick={() => {
                  setStatus(st);
                  setPage(1);
                }}
              >
                {st ? STATUS_LABEL[st] : 'All'}
              </button>
            ))}
          </div>
          <div className="search-box search-sm">
            <Search size={15} aria-hidden />
            <input
              className="input input-sm"
              placeholder="Find a key"
              aria-label="Find a key"
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(1);
              }}
            />
          </div>
        </div>
        {c.rowCount === 0 ? (
          <div className="empty-inline">
            <OkBadge>Every key is in every source with identical values.</OkBadge>
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table table-compact compare-table">
              <thead>
                <tr>
                  <th>Key</th>
                  <th>Finding</th>
                  {labels.map((l) => (
                    <th key={l}>{l}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => (
                  <Fragment key={r.key}>
                    <tr className="compare-key-row">
                      <td className="mono">{r.key}</td>
                      <td>
                        <Badge tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</Badge>
                        {r.duplicate && r.status !== 'duplicate' && <Badge tone="info">Duplicate key</Badge>}
                      </td>
                      {r.dns.map((dns, i) => (
                        <td key={i}>
                          {dns ? (
                            dns.map((dn) => {
                              const slug = slugOf(i);
                              return slug ? (
                                <Link key={dn} to={`/d/${slug}/entry?${dnParam(dn)}`} className="dn-link mono small">
                                  {dn}
                                </Link>
                              ) : (
                                <div key={dn} className="mono small">
                                  {dn}
                                </div>
                              );
                            })
                          ) : (
                            <span className="muted">not present</span>
                          )}
                        </td>
                      ))}
                    </tr>
                    {r.differences?.map((d) => (
                      <tr key={d.label} className="compare-diff-row">
                        <td />
                        <td className="muted small">{d.label}</td>
                        {d.values.map((v, i) => (
                          <td key={i}>
                            <Values values={v} />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pager page={page} total={rows.length} onPage={setPage} />
      </Card>
    </div>
  );
}
