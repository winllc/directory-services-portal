import { useState } from 'react';
import { Tooltip, TipRow, MISSING_COLOR, OTHER_COLOR, barPath, pointerIn, seriesColor, useWidth, type TipState } from './frame';
import { exact, pct, textWidth, truncate } from './format';
import type { BarDatum } from './BarChart';

export interface SeriesDatum {
  key: string;
  label: string;
  other?: boolean;
  missing?: boolean;
}

const ROW = 32;
const BAR = 20;
const GAP = 2;

export function seriesColors(series: SeriesDatum[]): string[] {
  let n = 0;
  return series.map((s) => (s.missing ? MISSING_COLOR : s.other ? OTHER_COLOR : seriesColor(n++)));
}

export function Legend({ series, colors }: { series: SeriesDatum[]; colors: string[] }) {
  return (
    <ul className="viz-legend">
      {series.map((s, i) => (
        <li key={s.key + i}>
          <span className="viz-key" style={{ background: colors[i] }} aria-hidden />
          {s.label}
        </li>
      ))}
    </ul>
  );
}

/**
 * Part-to-whole per row: one horizontal bar per category, split into series segments separated
 * by a 2px surface gap. A legend names every series; the row total sits at the bar's end.
 */
export function StackedBarChart({ rows, series, matrix, label }: { rows: BarDatum[]; series: SeriesDatum[]; matrix: number[][]; label: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<TipState | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const colors = seriesColors(series);
  // A bar's length is the sum of its segments (multi-valued splits can exceed the row count).
  const totals = matrix.map((line) => line.reduce((a, b) => a + b, 0));
  const max = Math.max(1, ...totals);
  const labelW = Math.min(Math.max(...rows.map((r) => textWidth(r.label, 12)), 40) + 14, Math.max(90, width * 0.4));
  const valueW = Math.max(...rows.map((r) => textWidth(exact(r.value), 12))) + 10;
  const plotW = Math.max(40, width - labelW - valueW);
  const height = rows.length * ROW + 4;

  const hide = () => {
    setActive(null);
    setTip(null);
  };

  return (
    <div className="viz" ref={ref} onPointerLeave={hide}>
      <Legend series={series} colors={colors} />
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={label}>
          <line x1={labelW} x2={labelW} y1={0} y2={height} className="viz-baseline" />
          {rows.map((r, i) => {
            const y = i * ROW + (ROW - BAR) / 2 + 2;
            let x = labelW;
            const segments = matrix[i] ?? [];
            const lastNonZero = segments.reduce((acc, v, j) => (v > 0 ? j : acc), -1);
            return (
              <g key={r.key + i}>
                <text x={labelW - 8} y={y + BAR / 2} className="viz-label" textAnchor="end" dominantBaseline="central">
                  {truncate(r.label, labelW - 12)}
                </text>
                {segments.map((v, j) => {
                  if (v <= 0) return null;
                  const full = (v / max) * plotW;
                  const w = Math.max(1, j === lastNonZero ? full : full - GAP);
                  const segX = x;
                  x += full;
                  const id = `${i}:${j}`;
                  const content = (
                    <>
                      <TipRow value={exact(v)} label={series[j].label} swatch={colors[j]} />
                      <div className="viz-tip-sub">
                        {r.label} · {pct(totals[i] ? v / totals[i] : 0)} of the bar
                      </div>
                    </>
                  );
                  return (
                    <path
                      key={j}
                      tabIndex={0}
                      role="img"
                      aria-label={`${r.label}, ${series[j].label}: ${exact(v)}`}
                      className={active && active !== id ? 'viz-dim' : undefined}
                      d={j === lastNonZero ? barPath(segX, y, w, BAR, true) : `M${segX},${y}h${w}v${BAR}h${-w}Z`}
                      style={{ fill: colors[j] }}
                      onPointerMove={(e) => {
                        const p = pointerIn(e, ref.current);
                        setActive(id);
                        setTip({ ...p, content });
                      }}
                      onFocus={() => {
                        setActive(id);
                        setTip({ x: segX + w, y, content });
                      }}
                      onBlur={hide}
                    />
                  );
                })}
                <text x={labelW + (totals[i] / max) * plotW + 6} y={y + BAR / 2} className="viz-value" dominantBaseline="central">
                  {exact(r.value)}
                </text>
              </g>
            );
          })}
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  );
}
