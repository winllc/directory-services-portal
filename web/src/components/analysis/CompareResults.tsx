import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Search } from 'lucide-react';
import type { AnalysisResult, CompareRow, DirectoryDefinition } from '@dsp/shared';
import { Alert, Badge, Button, Card, ErrorAlert, cx } from '../ui';
import { BarChart, exact, pct } from '../charts';
import { dnParam } from '../../api/client';
import type { FindingsSource } from '../../api/hooks';
import { Kpi, OkBadge, Pager, Values, truncationNote, useFindings } from './common';

const STATUS_LABEL: Record<CompareRow['status'], string> = {
  missing: 'Missing from a source',
  different: 'Values differ',
  duplicate: 'Duplicate key',
};

const STATUS_TONE: Record<CompareRow['status'], 'warning' | 'danger' | 'info'> = { missing: 'warning', different: 'danger', duplicate: 'info' };

export function CompareResults({
  result,
  definitions,
  findings,
  name = 'comparison',
}: {
  result: AnalysisResult;
  definitions?: DirectoryDefinition[];
  findings: FindingsSource;
  name?: string;
}) {
  const c = result.compare!;
  const [status, setStatus] = useState<'' | CompareRow['status']>('');
  const [q, setQ] = useState('');
  const labels = c.sources.map((s) => s.label);
  const slugOf = (i: number) => definitions?.find((d) => d.id === c.sources[i].definitionId)?.slug;
  const s = c.summary;

  const list = useFindings<CompareRow>(findings, { q, status: status || undefined }, `${name}.csv`);

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
        subtitle={truncationNote(c.rowsTruncated, list.listed)}
        actions={
          <Button size="sm" icon={<Download size={14} />} onClick={list.exportCsv} loading={list.exporting} disabled={!list.total} title="Every finding matching the filters">
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
                onClick={() => setStatus(st)}
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
              onChange={(e) => setQ(e.target.value)}
            />
          </div>
        </div>
        <ErrorAlert error={list.error} />
        {c.rowCount === 0 ? (
          <div className="empty-inline">
            <OkBadge>Every key is in every source with identical values.</OkBadge>
          </div>
        ) : list.loaded && list.total === 0 ? (
          <p className="muted empty-inline">No findings match these filters.</p>
        ) : (
          <div className={cx('table-wrap', list.loading && 'is-refreshing')} aria-busy={list.loading}>
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
                {list.items.map((r) => (
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
        <Pager page={list.page} total={list.total} onPage={list.setPage} />
      </Card>
    </div>
  );
}
