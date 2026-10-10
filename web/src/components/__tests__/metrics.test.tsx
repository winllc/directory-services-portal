import { beforeAll, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { MetricOutcome, MetricWidget } from '@dsp/shared';
import { MetricCard } from '../metrics/MetricCard';
import { MetricView } from '../metrics/MetricView';
import { decodeWidget, encodeWidget, normalizeWidget, suggestTitle } from '../metrics/widgets';
import { toCsv } from '../../lib/csv';
import { niceTicks, pct } from '../charts/format';

beforeAll(() => {
  // jsdom has no layout: report a fixed chart width.
  globalThis.ResizeObserver = class {
    constructor(private cb: ResizeObserverCallback) {}
    observe(el: Element) {
      this.cb([{ contentRect: { width: 600 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      void el;
    }
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  HTMLCanvasElement.prototype.getContext = (() => null) as unknown as typeof HTMLCanvasElement.prototype.getContext;
});

const base: MetricWidget = { id: 'w', title: 'Departments', definitionId: 'def-staff', kind: 'distribution', attribute: 'departmentNumber', chart: 'bar' };

const distribution: MetricOutcome = {
  result: {
    kind: 'distribution',
    total: 8,
    truncated: false,
    computedAt: '2026-01-01T10:00:00Z',
    rows: [
      { key: 'engineering', label: 'Engineering', value: 2, share: 0.25 },
      { key: 'it', label: 'IT', value: 2, share: 0.25 },
      { key: 'missing', label: '(none)', value: 4, share: 0.5, missing: true },
    ],
    warnings: ['Only the first 100000 entries were analysed'],
  },
};

describe('widgets', () => {
  it('drops options the kind does not use and fixes the chart', () => {
    const w = normalizeWidget({ ...base, kind: 'count', transform: 'emailDomain', splitBy: 'title', bucket: 'month', chart: 'donut' });
    expect(w).toEqual({ id: 'w', title: 'Departments', definitionId: 'def-staff', kind: 'count', chart: 'stat' });
    const split = normalizeWidget({ ...base, splitBy: 'acmeOfficeLocation', chart: 'bar', transform: 'none', includeMissing: true });
    expect(split.chart).toBe('stacked');
    expect(split).not.toHaveProperty('transform');
    expect(split).not.toHaveProperty('includeMissing');
  });

  it('round-trips through the explorer URL, including non-ASCII text', () => {
    const w = { ...base, title: 'Büro – Standorte', filter: '(l=Zürich)' };
    const decoded = decodeWidget(encodeWidget(w));
    expect(decoded).toMatchObject({ title: 'Büro – Standorte', filter: '(l=Zürich)', attribute: 'departmentNumber' });
    expect(decodeWidget('not base64!')).toBeNull();
  });

  it('suggests titles from what is measured', () => {
    const source = { id: 'def-staff', name: 'Staff', slug: 'staff', attributes: [{ attribute: 'mail', label: 'E-mail', source: 'form' as const, multiValued: true }] };
    expect(suggestTitle({ ...base, attribute: 'mail', transform: 'emailDomain' }, source)).toBe('E-mail (e-mail domain)');
    expect(suggestTitle({ ...base, kind: 'count', chart: 'stat' }, source)).toBe('Staff entries');
  });
});

describe('formatting', () => {
  it('uses whole-number ticks for counts', () => {
    expect(niceTicks(2)).toEqual([0, 1, 2]);
    expect(niceTicks(9)).toEqual([0, 2, 4, 6, 8, 10]);
    expect(pct(0.045)).toBe('4.5%');
    expect(pct(0.625)).toBe('63%');
  });

  it('writes CSV safely', () => {
    expect(toCsv([['a,b', 'say "hi"', '=SUM(A1)', -3, '-5']])).toBe('"a,b","say ""hi""",\'=SUM(A1),-3,-5\r\n');
  });
});

describe('MetricView', () => {
  it('draws a breakdown as labelled bars', () => {
    render(<MetricView widget={base} outcome={distribution} />);
    const chart = screen.getByRole('img', { name: 'Departments' });
    expect(within(chart).getByText('Engineering')).toBeInTheDocument();
    expect(within(chart).getByText('(none)')).toBeInTheDocument();
    expect(within(chart).getByLabelText('IT: 2')).toBeInTheDocument();
  });

  it('shows counts as a number, and explains locked or failed charts', () => {
    render(<MetricView widget={{ ...base, kind: 'count', chart: 'stat' }} outcome={{ result: { ...distribution.result!, kind: 'count', rows: undefined } }} />);
    expect(screen.getByText('8')).toBeInTheDocument();
    render(<MetricView widget={base} outcome={{ forbidden: true, error: 'no' }} />);
    expect(screen.getByText(/don't have access/)).toBeInTheDocument();
    render(<MetricView widget={base} outcome={{ error: 'Invalid pattern', details: { transformArg: 'Unclosed group' } }} />);
    expect(screen.getByText('Unclosed group')).toBeInTheDocument();
  });

  it('switches between chart and table, and shows warnings', () => {
    render(<MetricCard widget={base} outcome={distribution} sourceName="Staff" />);
    expect(screen.getByText(/Only the first 100000/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show table' }));
    const table = screen.getByRole('table');
    expect(within(table).getByText('Engineering')).toBeInTheDocument();
    expect(within(table).getAllByText('25%')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: 'Show chart' }));
    expect(screen.getByRole('img', { name: 'Departments' })).toBeInTheDocument();
  });

  it('stacks split breakdowns with a legend', () => {
    const outcome: MetricOutcome = {
      result: {
        ...distribution.result!,
        rows: distribution.result!.rows!.slice(0, 2),
        series: [
          { key: 'hq', label: 'HQ' },
          { key: 'missing', label: '(none)', missing: true },
        ],
        matrix: [
          [2, 0],
          [1, 1],
        ],
      },
    };
    render(<MetricView widget={{ ...base, splitBy: 'l', chart: 'stacked' }} outcome={outcome} />);
    expect(screen.getAllByText('HQ').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('IT, (none): 1')).toBeInTheDocument();
  });
});
