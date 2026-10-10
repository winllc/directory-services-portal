import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Search } from 'lucide-react';
import type { AnalysisResult, DirectoryDefinition, Severity } from '@dsp/shared';
import { Alert, Button, Card } from '../ui';
import { exact, pct } from '../charts';
import { downloadCsv } from '../../lib/csv';
import { dnParam } from '../../api/client';
import { Kpi, Meter, OkBadge, PAGE_SIZE, Pager, SeverityBadge, Values } from './common';

export function QualityResults({ result, definitions }: { result: AnalysisResult; definitions?: DirectoryDefinition[] }) {
  const qr = result.quality!;
  const [rule, setRule] = useState('');
  const [severity, setSeverity] = useState<'' | Severity>('');
  const [source, setSource] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [showPassing, setShowPassing] = useState(false);
  const s = qr.summary;
  const failing = qr.rules.filter((r) => r.violations > 0);
  const passing = qr.rules.length - failing.length;
  const listedRules = showPassing ? qr.rules : failing;
  const ruleById = useMemo(() => new Map(qr.rules.map((r) => [r.id, r])), [qr.rules]);
  const slugOf = (i: number) => definitions?.find((d) => d.id === qr.sources[i]?.definitionId)?.slug;

  const issues = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return qr.issues.filter(
      (i) =>
        (!rule || i.ruleId === rule) &&
        (!severity || i.severity === severity) &&
        (source === '' || String(i.source) === source) &&
        (!needle || i.dn.toLowerCase().includes(needle) || i.title.toLowerCase().includes(needle) || i.message.toLowerCase().includes(needle)),
    );
  }, [qr.issues, rule, severity, source, q]);
  const shown = issues.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const tone = s.cleanShare >= 0.95 ? 'good' : s.cleanShare >= 0.8 ? 'warning' : 'critical';

  const exportCsv = () =>
    downloadCsv('data-quality.csv', [
      ['Source', 'Entry', 'DN', 'Rule', 'Severity', 'Attribute', 'Values', 'Finding'],
      ...issues.map((i) => [qr.sources[i.source]?.label ?? '', i.title, i.dn, ruleById.get(i.ruleId)?.label ?? i.ruleId, i.severity, i.attribute, i.values.join(' | '), i.message]),
    ]);

  return (
    <div className="stack">
      {result.warnings?.map((w) => (
        <Alert key={w} tone="warning">
          {w}
        </Alert>
      ))}
      <div className="stat-row">
        <Kpi label="Entries checked" value={exact(s.entries)} sub={qr.sources.map((x) => `${x.label}: ${exact(x.entries)}`).join(' · ')} />
        <Kpi
          label="Entries without findings"
          value={pct(s.cleanShare)}
          sub={<Meter value={s.cleanShare} label="Entries without findings" tone={tone} />}
          tone={tone}
        />
        <Kpi label="Entries with findings" value={exact(s.entriesWithIssues)} />
        <Kpi label="Errors" value={exact(s.errors)} tone={s.errors ? 'critical' : undefined} />
        <Kpi label="Warnings" value={exact(s.warnings)} tone={s.warnings ? 'warning' : undefined} />
      </div>

      <Card
        title="Rules"
        subtitle={`${failing.length} of ${qr.rules.length} rules found problems`}
        actions={
          passing > 0 && (
            <Button size="sm" variant="ghost" onClick={() => setShowPassing((v) => !v)} aria-expanded={showPassing}>
              {showPassing ? 'Hide passing rules' : `Show ${passing} passing rule${passing === 1 ? '' : 's'}`}
            </Button>
          )
        }
      >
        <div className="table-wrap">
          <table className="table table-compact">
            <thead>
              <tr>
                <th>Rule</th>
                <th>Attribute</th>
                <th>Severity</th>
                <th className="num">Findings</th>
                <th className="meter-col">Share of entries</th>
              </tr>
            </thead>
            <tbody>
              {listedRules.length === 0 && (
                <tr>
                  <td colSpan={5}>
                    <OkBadge>Every rule passes.</OkBadge>
                  </td>
                </tr>
              )}
              {listedRules.map((r) => {
                const entries = r.source !== undefined ? (qr.sources[r.source]?.entries ?? 0) : s.entries;
                const share = entries ? Math.min(1, r.violations / entries) : 0;
                return (
                  <tr
                    key={r.id}
                    className={r.violations ? 'row-link' : undefined}
                    onClick={() => {
                      if (!r.violations) return;
                      setRule(r.id);
                      setPage(1);
                    }}
                  >
                    <td>
                      {r.label}
                      {r.source !== undefined && qr.sources.length > 1 && <div className="muted small">{qr.sources[r.source]?.label}</div>}
                    </td>
                    <td className="mono small">{r.attribute}</td>
                    <td>{r.violations ? <SeverityBadge severity={r.severity} /> : <OkBadge />}</td>
                    <td className="num">{exact(r.violations)}</td>
                    <td className="meter-col">
                      {r.violations > 0 && <Meter value={share} label={`${r.label}: ${pct(share)}`} tone={r.severity === 'warning' ? 'warning' : 'critical'} />}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <Card
        title={`Findings (${exact(s.issues)})`}
        subtitle={qr.issuesTruncated ? `Showing the first ${exact(qr.issues.length)}; the counts above cover everything.` : undefined}
        actions={
          <Button size="sm" icon={<Download size={14} />} onClick={exportCsv} disabled={!issues.length}>
            CSV
          </Button>
        }
      >
        <div className="toolbar">
          <div className="toolbar-right">
            <select className="input select select-sm" aria-label="Rule" value={rule} onChange={(e) => (setRule(e.target.value), setPage(1))}>
              <option value="">All rules</option>
              {qr.rules
                .filter((r) => r.violations > 0)
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.label}
                  </option>
                ))}
            </select>
            <select className="input select select-sm" aria-label="Severity" value={severity} onChange={(e) => (setSeverity(e.target.value as '' | Severity), setPage(1))}>
              <option value="">Errors and warnings</option>
              <option value="error">Errors</option>
              <option value="warning">Warnings</option>
            </select>
            {qr.sources.length > 1 && (
              <select className="input select select-sm" aria-label="Source" value={source} onChange={(e) => (setSource(e.target.value), setPage(1))}>
                <option value="">All sources</option>
                {qr.sources.map((x, i) => (
                  <option key={i} value={String(i)}>
                    {x.label}
                  </option>
                ))}
              </select>
            )}
          </div>
          <div className="search-box search-sm">
            <Search size={15} aria-hidden />
            <input className="input input-sm" placeholder="Find an entry" aria-label="Find an entry" value={q} onChange={(e) => (setQ(e.target.value), setPage(1))} />
          </div>
        </div>
        {s.issues === 0 ? (
          <div className="empty-inline">
            <OkBadge>No findings: every entry passes every rule.</OkBadge>
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>Entry</th>
                  <th>Severity</th>
                  <th>Finding</th>
                  <th>Values</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((i, n) => {
                  const slug = slugOf(i.source);
                  return (
                    <tr key={`${i.ruleId}-${i.dn}-${n}`}>
                      <td>
                        {slug ? <Link to={`/d/${slug}/entry?${dnParam(i.dn)}`}>{i.title}</Link> : i.title}
                        <div className="muted small mono ellipsis" title={i.dn}>
                          {i.dn}
                        </div>
                        {qr.sources.length > 1 && <div className="muted small">{qr.sources[i.source]?.label}</div>}
                      </td>
                      <td>
                        <SeverityBadge severity={i.severity} />
                      </td>
                      <td>
                        {i.message}
                        <div className="muted small">{ruleById.get(i.ruleId)?.label}</div>
                      </td>
                      <td>
                        <Values values={i.values} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Pager page={page} total={issues.length} onPage={setPage} />
      </Card>
    </div>
  );
}
