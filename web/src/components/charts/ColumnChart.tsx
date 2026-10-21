import { useState } from 'react';
import { Tooltip, TipRow, barPath, pointerIn, rowColor, useWidth, type TipState } from './frame';
import { compact, exact, niceTicks, pct, textWidth, truncate } from './format';
import type { BarDatum } from './BarChart';

const HEIGHT = 200;
const AXIS_BAND = 26;
const TOP = 10;

/**
 * Vertical columns over an ordered axis (time periods, histogram bins). Gridlines are hairlines;
 * x labels thin out to avoid collisions; the tallest column is labelled, the rest are on hover
 * and in the table view.
 */
export function ColumnChart({ rows, label, valueLabel = 'entries', xLabel }: { rows: BarDatum[]; label: string; valueLabel?: string; xLabel?: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<TipState | null>(null);
  const [active, setActive] = useState<number | null>(null);
  const max = Math.max(0, ...rows.map((r) => r.value));
  const ticks = niceTicks(max, 4, rows.every((r) => Number.isInteger(r.value)));
  const top = ticks[ticks.length - 1] || 1;
  const axisW = Math.max(...ticks.map((t) => textWidth(compact(t), 11))) + 10;
  const plotW = Math.max(40, width - axisW - 4);
  const band = rows.length ? plotW / rows.length : plotW;
  const colW = Math.min(24, Math.max(2, band - 2));
  const y = (v: number) => TOP + HEIGHT - (v / top) * HEIGHT;
  const maxLabelW = Math.max(...rows.map((r) => textWidth(r.label, 11))) + 10;
  const every = Math.max(1, Math.ceil(maxLabelW / band));
  const peak = rows.reduce((best, r, i) => (r.value > (rows[best]?.value ?? -1) ? i : best), 0);

  const show = (i: number, x: number, yy: number) => {
    const r = rows[i];
    setActive(i);
    setTip({
      x,
      y: yy,
      content: (
        <>
          <TipRow value={exact(r.value)} label={r.label} />
          {r.share !== undefined && <div className="viz-tip-sub">{pct(r.share)} of {valueLabel}</div>}
        </>
      ),
    });
  };
  const hide = () => {
    setActive(null);
    setTip(null);
  };

  return (
    <div className="viz" ref={ref} onPointerLeave={hide}>
      {width > 0 && (
        <svg width={width} height={TOP + HEIGHT + AXIS_BAND + (xLabel ? 18 : 0)} role="img" aria-label={label}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={axisW} x2={width} y1={y(t)} y2={y(t)} className={t === 0 ? 'viz-baseline' : 'viz-grid'} />
              <text x={axisW - 6} y={y(t)} className="viz-tick" textAnchor="end" dominantBaseline="central">
                {compact(t)}
              </text>
            </g>
          ))}
          {rows.map((r, i) => {
            const cx = axisW + band * i + band / 2;
            const h = (r.value / top) * HEIGHT;
            return (
              <g
                key={r.key + i}
                tabIndex={0}
                role="img"
                aria-label={`${r.label}: ${exact(r.value)}`}
                className={active !== null && active !== i ? 'viz-dim' : undefined}
                onPointerMove={(e) => {
                  const p = pointerIn(e, ref.current);
                  show(i, p.x, p.y);
                }}
                onFocus={() => show(i, cx, y(r.value))}
                onBlur={hide}
              >
                <rect x={axisW + band * i} y={TOP} width={band} height={HEIGHT} fill="transparent" />
                <path d={barPath(cx - colW / 2, y(r.value), colW, Math.max(h, r.value > 0 ? 2 : 0), false)} style={{ fill: rowColor(r) }} />
                {i % every === 0 && (
                  <text x={cx} y={TOP + HEIGHT + 16} className="viz-tick" textAnchor="middle">
                    {truncate(r.label, band * every - 4, 11)}
                  </text>
                )}
              </g>
            );
          })}
          {xLabel && (
            <text x={axisW + plotW / 2} y={TOP + HEIGHT + AXIS_BAND + 12} className="viz-tick" textAnchor="middle">
              {xLabel}
            </text>
          )}
          {rows.length > 0 && max > 0 && (
            <text x={axisW + band * peak + band / 2} y={y(rows[peak].value) - 6} className="viz-value" textAnchor="middle">
              {compact(rows[peak].value)}
            </text>
          )}
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  );
}
