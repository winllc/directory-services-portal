import { useState } from 'react';
import { Tooltip, TipRow, barPath, pointerIn, rowColor, useWidth, type TipState } from './frame';
import { exact, pct, textWidth, truncate } from './format';

export interface BarDatum {
  key: string;
  label: string;
  value: number;
  share?: number;
  other?: boolean;
  missing?: boolean;
}

const ROW = 30;
const BAR = 18;

/**
 * Horizontal bars, one per category, sorted as given. Each bar carries its value at the tip.
 * {@code percent} plots shares (0-100%) instead of counts, e.g. for field completeness.
 */
export function BarChart({ rows, percent, label }: { rows: BarDatum[]; percent?: boolean; label: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<TipState | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const valueOf = (r: BarDatum) => (percent ? (r.share ?? 0) : r.value);
  const text = (r: BarDatum) => (percent ? pct(r.share) : exact(r.value));
  const max = Math.max(percent ? 1 : 0, ...rows.map(valueOf));
  const labelW = Math.min(Math.max(...rows.map((r) => textWidth(r.label, 12)), 40) + 14, Math.max(90, width * 0.4));
  const valueW = Math.max(...rows.map((r) => textWidth(text(r), 12))) + 10;
  const plotW = Math.max(40, width - labelW - valueW);
  const height = rows.length * ROW + 4;

  const show = (r: BarDatum, x: number, y: number) => {
    setActive(r.key);
    setTip({
      x,
      y,
      content: (
        <>
          <TipRow value={percent ? pct(r.share) : exact(r.value)} label={r.label} />
          {percent ? (
            <div className="viz-tip-sub">{exact(r.value)} entries</div>
          ) : (
            r.share !== undefined && <div className="viz-tip-sub">{pct(r.share)} of entries</div>
          )}
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
        <svg width={width} height={height} role="img" aria-label={label}>
          <line x1={labelW} x2={labelW} y1={0} y2={height} className="viz-baseline" />
          {rows.map((r, i) => {
            const y = i * ROW + (ROW - BAR) / 2 + 2;
            const w = max > 0 ? (valueOf(r) / max) * plotW : 0;
            return (
              <g
                key={r.key + i}
                tabIndex={0}
                className={active && active !== r.key ? 'viz-dim' : undefined}
                aria-label={`${r.label}: ${text(r)}`}
                onPointerMove={(e) => {
                  const p = pointerIn(e, ref.current);
                  show(r, p.x, p.y);
                }}
                onFocus={() => show(r, labelW + w, y)}
                onBlur={hide}
              >
                {/* Hit target: the whole row, not just the painted bar. */}
                <rect x={0} y={i * ROW} width={width} height={ROW} fill="transparent" />
                <text x={labelW - 8} y={y + BAR / 2} className="viz-label" textAnchor="end" dominantBaseline="central">
                  {truncate(r.label, labelW - 12)}
                </text>
                <path d={barPath(labelW, y, Math.max(w, valueOf(r) > 0 ? 2 : 0), BAR, true)} style={{ fill: rowColor(r) }} />
                <text x={labelW + w + 6} y={y + BAR / 2} className="viz-value" dominantBaseline="central">
                  {text(r)}
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
