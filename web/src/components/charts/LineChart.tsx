import { useState } from 'react';
import { Tooltip, TipRow, pointerIn, useWidth, type TipState } from './frame';
import { compact, exact, niceTicks, textWidth, truncate } from './format';
import type { BarDatum } from './BarChart';

const HEIGHT = 200;
const AXIS_BAND = 26;
const TOP = 14;

/**
 * One series over an ordered axis, as a 2px line (or a line over a 10% wash for "area"). A
 * crosshair snaps to the nearest period; the last point carries an end marker and its value.
 */
export function LineChart({ rows, label, area }: { rows: BarDatum[]; label: string; area?: boolean }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<TipState | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(0, ...rows.map((r) => r.value));
  const ticks = niceTicks(max, 4, rows.every((r) => Number.isInteger(r.value)));
  const top = ticks[ticks.length - 1] || 1;
  const axisW = Math.max(...ticks.map((t) => textWidth(compact(t), 11))) + 10;
  const endLabelW = rows.length ? textWidth(compact(rows[rows.length - 1].value), 12) + 12 : 0;
  const plotW = Math.max(40, width - axisW - endLabelW - 8);
  const step = rows.length > 1 ? plotW / (rows.length - 1) : 0;
  const x = (i: number) => axisW + 8 + (rows.length > 1 ? i * step : plotW / 2);
  const y = (v: number) => TOP + HEIGHT - (v / top) * HEIGHT;
  const maxLabelW = Math.max(...rows.map((r) => textWidth(r.label, 11))) + 12;
  const slot = rows.length > 1 ? step : plotW;
  const every = Math.max(1, Math.ceil(maxLabelW / Math.max(slot, 1)));
  const line = rows.map((r, i) => `${i ? 'L' : 'M'}${x(i)},${y(r.value)}`).join('');
  const fill = rows.length ? `${line}L${x(rows.length - 1)},${y(0)}L${x(0)},${y(0)}Z` : '';
  const last = rows.length - 1;

  const showAt = (i: number) => {
    setHover(i);
    const r = rows[i];
    setTip({ x: x(i), y: y(r.value), content: <TipRow value={exact(r.value)} label={r.label} swatch="var(--series-1)" line /> });
  };
  const onMove = (e: React.PointerEvent) => {
    const p = pointerIn(e, ref.current);
    const i = rows.length > 1 ? Math.round((p.x - axisW - 8) / step) : 0;
    showAt(Math.max(0, Math.min(last, i)));
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    e.preventDefault();
    const cur = hover ?? (e.key === 'ArrowRight' ? -1 : rows.length);
    showAt(Math.max(0, Math.min(last, cur + (e.key === 'ArrowRight' ? 1 : -1))));
  };
  const hide = () => {
    setHover(null);
    setTip(null);
  };

  return (
    <div className="viz" ref={ref}>
      {width > 0 && (
        <svg
          width={width}
          height={TOP + HEIGHT + AXIS_BAND}
          role="img"
          aria-label={`${label}. Use the arrow keys to read values.`}
          tabIndex={0}
          onPointerMove={onMove}
          onPointerLeave={hide}
          onKeyDown={onKey}
          onFocus={() => rows.length && showAt(hover ?? last)}
          onBlur={hide}
        >
          {ticks.map((t) => (
            <g key={t}>
              <line x1={axisW} x2={width} y1={y(t)} y2={y(t)} className={t === 0 ? 'viz-baseline' : 'viz-grid'} />
              <text x={axisW - 6} y={y(t)} className="viz-tick" textAnchor="end" dominantBaseline="central">
                {compact(t)}
              </text>
            </g>
          ))}
          {rows.map(
            (r, i) =>
              i % every === 0 && (
                <text key={r.key} x={x(i)} y={TOP + HEIGHT + 16} className="viz-tick" textAnchor="middle">
                  {truncate(r.label, slot * every - 4, 11)}
                </text>
              ),
          )}
          {area && <path d={fill} style={{ fill: 'var(--series-1)', fillOpacity: 0.1 }} />}
          <path d={line} className="viz-line" style={{ stroke: 'var(--series-1)' }} />
          {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={TOP} y2={TOP + HEIGHT} className="viz-crosshair" />}
          {rows.length > 0 && (
            <>
              <circle cx={x(last)} cy={y(rows[last].value)} r={4} className="viz-dot" style={{ fill: 'var(--series-1)' }} />
              <text x={x(last) + 8} y={y(rows[last].value)} className="viz-value" dominantBaseline="central">
                {compact(rows[last].value)}
              </text>
            </>
          )}
          {hover !== null && hover !== last && (
            <circle cx={x(hover)} cy={y(rows[hover].value)} r={4} className="viz-dot" style={{ fill: 'var(--series-1)' }} />
          )}
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  );
}
