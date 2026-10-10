import { useState } from 'react';
import { MISSING_COLOR, OTHER_COLOR, seriesColor } from './frame';
import { compact, exact, pct } from './format';
import type { BarDatum } from './BarChart';

/** More slices than this fold into "Other": a donut is for part-to-whole at a glance. */
export const MAX_SLICES = 6;

function fold(rows: BarDatum[]): BarDatum[] {
  const plain = rows.filter((r) => !r.other && !r.missing);
  const special = rows.filter((r) => r.other || r.missing);
  if (plain.length <= MAX_SLICES) return rows;
  const kept = plain.slice(0, MAX_SLICES - 1);
  const rest = plain.slice(MAX_SLICES - 1);
  const existing = special.find((r) => r.other);
  const value = rest.reduce((a, r) => a + r.value, existing?.value ?? 0);
  const share = rest.reduce((a, r) => a + (r.share ?? 0), existing?.share ?? 0);
  return [
    ...kept,
    { key: 'other', label: 'Other', value, share, other: true },
    ...special.filter((r) => !r.other),
  ];
}

function arc(cx: number, cy: number, r0: number, r1: number, a0: number, a1: number): string {
  const p = (r: number, a: number) => `${cx + r * Math.sin(a)},${cy - r * Math.cos(a)}`;
  const large = a1 - a0 > Math.PI ? 1 : 0;
  // A full circle can't be drawn as one arc; nudge the end so it stays visible.
  const end = a1 - a0 >= 2 * Math.PI ? a1 - 0.0001 : a1;
  return `M${p(r1, a0)}A${r1},${r1} 0 ${large} 1 ${p(r1, end)}L${p(r0, end)}A${r0},${r0} 0 ${large} 0 ${p(r0, a0)}Z`;
}

/**
 * Share of entries per category (single-valued attributes, a handful of categories). The legend
 * doubles as direct labels with each slice's count and share; the centre shows the total.
 */
export function DonutChart({ rows, total, label }: { rows: BarDatum[]; total: number; label: string }) {
  const [active, setActive] = useState<number | null>(null);
  const slices = fold(rows);
  const sum = slices.reduce((a, r) => a + r.value, 0) || 1;
  let n = 0;
  const colors = slices.map((r) => (r.missing ? MISSING_COLOR : r.other ? OTHER_COLOR : seriesColor(n++)));
  const size = 168;
  const c = size / 2;
  let angle = 0;
  const focused = active !== null ? slices[active] : null;
  return (
    <div className="viz viz-donut">
      <svg width={size} height={size} role="img" aria-label={label} viewBox={`0 0 ${size} ${size}`}>
        {slices.map((r, i) => {
          const a0 = angle;
          angle += (r.value / sum) * 2 * Math.PI;
          if (r.value <= 0) return null;
          return (
            <path
              key={r.key + i}
              d={arc(c, c, c - 26, c - 2, a0, angle)}
              className={cx(active !== null && active !== i && 'viz-dim')}
              style={{ fill: colors[i], stroke: 'var(--surface)', strokeWidth: 2 }}
              onPointerEnter={() => setActive(i)}
              onPointerLeave={() => setActive(null)}
            />
          );
        })}
        <text x={c} y={c - 6} textAnchor="middle" className="viz-donut-value">
          {focused ? pct(focused.value / sum) : compact(total)}
        </text>
        <text x={c} y={c + 14} textAnchor="middle" className="viz-tick">
          {focused ? truncateLabel(focused.label) : 'entries'}
        </text>
      </svg>
      <ul className="viz-legend viz-legend-list">
        {slices.map((r, i) => (
          <li
            key={r.key + i}
            tabIndex={0}
            className={cx(active === i && 'active')}
            onPointerEnter={() => setActive(i)}
            onPointerLeave={() => setActive(null)}
            onFocus={() => setActive(i)}
            onBlur={() => setActive(null)}
          >
            <span className="viz-key" style={{ background: colors[i] }} aria-hidden />
            <span className="viz-legend-label">{r.label}</span>
            <span className="viz-legend-value">{exact(r.value)}</span>
            <span className="viz-legend-share">{pct(r.value / sum)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function truncateLabel(s: string) {
  return s.length > 16 ? s.slice(0, 15) + '…' : s;
}

function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(' ') || undefined;
}
