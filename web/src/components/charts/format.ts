const whole = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const compactFmt = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const decimal = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

/** 1,284 / 12.9K / 4.2M: exact below 10,000, compact above. */
export function compact(n: number): string {
  if (!Number.isFinite(n)) return '—';
  if (Math.abs(n) < 10_000) return Number.isInteger(n) ? whole.format(n) : decimal.format(n);
  return compactFmt.format(n);
}

/** Full precision with grouping (tables, tooltips). */
export function exact(n: number): string {
  return Number.isInteger(n) ? whole.format(n) : decimal.format(n);
}

/** 0.625 -> "63%"; small shares keep a decimal ("4.5%"). */
export function pct(share: number | undefined): string {
  if (share === undefined || !Number.isFinite(share)) return '—';
  const p = share * 100;
  if (p === 0 || p >= 10 || Number.isInteger(p)) return `${Math.round(p)}%`;
  return `${p.toFixed(1)}%`;
}

/** Round axis ticks from 0 to at least max: 0 / 5 / 10 / 15 ... */
export function niceTicks(max: number, target = 4, integer = true): number[] {
  if (!(max > 0)) return [0, 1];
  const raw = max / target;
  const mag = 10 ** Math.floor(Math.log10(raw));
  let step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? 10 * mag;
  // Counts never need fractional ticks (and 2.5 steps only make sense below 1 or from 25 up).
  if (integer) step = step < 1 ? 1 : step === 2.5 ? 2 : step === 25 ? 20 : step;
  const ticks: number[] = [];
  for (let v = 0; v < max + step; v += step) ticks.push(Number(v.toFixed(10)));
  return ticks;
}

let canvas: CanvasRenderingContext2D | null | undefined;
const widths = new Map<string, number>();

/** Rendered width of chart text, measured with the page font (estimated where canvas is unavailable). */
export function textWidth(s: string, px = 12): number {
  const key = `${px}|${s}`;
  const cached = widths.get(key);
  if (cached !== undefined) return cached;
  if (canvas === undefined) {
    try {
      canvas = typeof document !== 'undefined' ? document.createElement('canvas').getContext('2d') : null;
    } catch {
      canvas = null;
    }
  }
  let w = s.length * px * 0.6;
  if (canvas) {
    canvas.font = `${px}px ${getComputedStyle(document.body).fontFamily}`;
    w = canvas.measureText(s).width;
  }
  if (widths.size > 5000) widths.clear();
  widths.set(key, w);
  return w;
}

/** Shorten text with an ellipsis so it fits maxPx. */
export function truncate(s: string, maxPx: number, px = 12): string {
  if (textWidth(s, px) <= maxPx) return s;
  let lo = 0;
  let hi = s.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (textWidth(s.slice(0, mid) + '…', px) <= maxPx) lo = mid;
    else hi = mid - 1;
  }
  return s.slice(0, Math.max(1, lo)) + '…';
}
