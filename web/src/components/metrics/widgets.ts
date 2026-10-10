import type { AttributeChoice, ChartType, MetricKind, MetricSource, MetricWidget, ValueTransform } from '@dsp/shared';

export const KIND_LABELS: Record<MetricKind, string> = {
  count: 'Count',
  distribution: 'Breakdown',
  completeness: 'Completeness',
  timeline: 'Over time',
  numeric: 'Numbers',
};

export const KIND_HELP: Record<MetricKind, string> = {
  count: 'How many entries there are.',
  distribution: 'Entries per value of an attribute, optionally split by a second attribute.',
  completeness: 'Share of entries with a value in each attribute.',
  timeline: 'Entries per day, week, month, quarter or year of a date attribute.',
  numeric: 'Statistics and a histogram of numeric values, or of how many values each entry has.',
};

export const CHART_LABELS: Record<ChartType, string> = {
  stat: 'Number',
  bar: 'Bars',
  column: 'Columns',
  stacked: 'Stacked bars',
  donut: 'Donut',
  line: 'Line',
  area: 'Area',
  table: 'Table',
};

export const TRANSFORM_LABELS: Record<ValueTransform, string> = {
  none: 'As is',
  lowercase: 'Lower case',
  uppercase: 'Upper case',
  emailDomain: 'E-mail domain',
  dnRdn: 'Name from DN (first RDN value)',
  dnParent: 'Parent of DN (container)',
  prefix: 'First characters',
  regex: 'Regular expression (first group)',
  year: 'Year',
  quarter: 'Quarter',
  month: 'Month',
  week: 'Week',
  day: 'Day',
};

export function chartsFor(w: Pick<MetricWidget, 'kind' | 'splitBy'>): ChartType[] {
  switch (w.kind) {
    case 'count':
      return ['stat'];
    case 'distribution':
      return w.splitBy ? ['stacked', 'table'] : ['bar', 'column', 'donut', 'table'];
    case 'completeness':
      return ['bar', 'table'];
    case 'timeline':
      return ['column', 'line', 'area', 'table'];
    case 'numeric':
      return ['column', 'stat', 'table'];
  }
}

/** Drop options the widget's kind doesn't use, and make the chart fit the kind. */
export function normalizeWidget(w: MetricWidget): MetricWidget {
  const out: MetricWidget = {
    id: w.id,
    title: w.title,
    description: w.description || undefined,
    definitionId: w.definitionId,
    filter: w.filter?.trim() || undefined,
    kind: w.kind,
    chart: w.chart,
    width: w.width,
  };
  if (w.kind === 'distribution') {
    Object.assign(out, {
      attribute: w.attribute,
      transform: w.transform && w.transform !== 'none' ? w.transform : undefined,
      transformArg: w.transform === 'prefix' || w.transform === 'regex' ? w.transformArg : undefined,
      splitBy: w.splitBy || undefined,
      splitTransform: w.splitBy && w.splitTransform && w.splitTransform !== 'none' ? w.splitTransform : undefined,
      limit: w.limit,
      includeMissing: w.includeMissing === false ? false : undefined,
      sort: w.sort === 'label' ? 'label' : undefined,
    });
  } else if (w.kind === 'completeness') {
    Object.assign(out, { attributes: w.attributes?.length ? w.attributes : undefined, sort: w.sort === 'count' ? 'count' : undefined });
  } else if (w.kind === 'timeline') {
    Object.assign(out, { attribute: w.attribute, bucket: w.bucket ?? 'month', cumulative: w.cumulative || undefined, limit: w.limit });
  } else if (w.kind === 'numeric') {
    Object.assign(out, { attribute: w.attribute, measure: w.measure === 'valueCount' ? 'valueCount' : undefined, bins: w.bins });
  }
  const charts = chartsFor(out);
  if (!charts.includes(out.chart)) out.chart = charts[0];
  for (const k of Object.keys(out) as (keyof MetricWidget)[]) if (out[k] === undefined) delete out[k];
  return out;
}

export function attributeLabel(source: MetricSource | undefined, attribute: string | undefined): string {
  if (!attribute) return '';
  const a = source?.attributes.find((x) => x.attribute.toLowerCase() === attribute.toLowerCase());
  return a?.label ?? attribute;
}

/** A title from what the widget measures, used until the user types their own. */
export function suggestTitle(w: MetricWidget, source: MetricSource | undefined): string {
  const attr = attributeLabel(source, w.attribute);
  switch (w.kind) {
    case 'count':
      return source ? `${source.name} entries` : 'Entries';
    case 'distribution': {
      const t = w.transform && w.transform !== 'none' ? ` (${TRANSFORM_LABELS[w.transform].toLowerCase()})` : '';
      const split = w.splitBy ? ` by ${attributeLabel(source, w.splitBy).toLowerCase()}` : '';
      return attr ? `${attr}${t}${split}` : 'Breakdown';
    }
    case 'completeness':
      return 'Field completeness';
    case 'timeline':
      return `${w.cumulative ? 'Total entries' : 'Entries'} by ${w.bucket ?? 'month'}${attr ? ` (${attr.toLowerCase()})` : ''}`;
    case 'numeric':
      return w.measure === 'valueCount' ? `${attr || 'Values'} per entry` : attr || 'Numbers';
  }
}

export function newWidget(source?: MetricSource): MetricWidget {
  const firstDropdown = source?.attributes.find((a) => a.widget === 'dropdown' && !a.multiValued) ?? source?.attributes.find((a) => a.source === 'form');
  const w: MetricWidget = {
    title: '',
    definitionId: source?.id ?? '',
    kind: 'distribution',
    attribute: firstDropdown?.attribute,
    chart: 'bar',
    width: 'half',
  };
  return { ...w, title: suggestTitle(w, source) };
}

export function isDateAttribute(a: AttributeChoice) {
  return a.source === 'operational' || /date|time|expir|end$|start$/i.test(a.attribute);
}

/** Widgets travel in the explorer URL so explorations can be bookmarked and shared. */
export function encodeWidget(w: MetricWidget): string {
  const bytes = new TextEncoder().encode(JSON.stringify(normalizeWidget({ ...w, id: undefined })));
  let bin = '';
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeWidget(s: string | null): MetricWidget | null {
  if (!s) return null;
  try {
    const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
    const json = new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
    const w = JSON.parse(json) as MetricWidget;
    return w && typeof w === 'object' && typeof w.definitionId === 'string' && typeof w.kind === 'string' ? w : null;
  } catch {
    return null;
  }
}
