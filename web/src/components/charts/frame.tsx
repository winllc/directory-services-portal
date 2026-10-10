import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';

/** Width of an element, tracked with ResizeObserver (charts redraw to fit their card). */
export function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const ro = new ResizeObserver((entries) => {
      const w = Math.floor(entries[0].contentRect.width);
      setWidth((prev) => (prev === w ? prev : w));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}

export interface TipState {
  x: number;
  y: number;
  content: ReactNode;
}

/**
 * Hover/focus readout. Positioned inside the chart container and kept within its bounds.
 * Content is React nodes, so labels from the directory are always rendered as text.
 */
export function Tooltip({ tip, width }: { tip: TipState | null; width: number }) {
  if (!tip) return null;
  const flip = tip.x > width - 180;
  return (
    <div
      className="viz-tooltip"
      role="status"
      style={{ left: flip ? undefined : tip.x + 12, right: flip ? width - tip.x + 12 : undefined, top: Math.max(0, tip.y - 8) }}
    >
      {tip.content}
    </div>
  );
}

/** Tooltip body: value first (strong), label after, optional series key. */
export function TipRow({ value, label, swatch, line }: { value: string; label: ReactNode; swatch?: string; line?: boolean }) {
  return (
    <div className="viz-tip-row">
      {swatch && <span className={line ? 'viz-key-line' : 'viz-key'} style={{ background: swatch }} aria-hidden />}
      <strong>{value}</strong>
      <span className="viz-tip-label">{label}</span>
    </div>
  );
}

export const SERIES_COUNT = 8;
export const seriesColor = (i: number) => `var(--series-${(i % SERIES_COUNT) + 1})`;
/** "Other" and "(none)" buckets recede: they are not categories of their own. */
export const OTHER_COLOR = 'var(--viz-other)';
export const MISSING_COLOR = 'var(--viz-missing)';

export function rowColor(row: { other?: boolean; missing?: boolean }, base = 'var(--series-1)') {
  if (row.missing) return MISSING_COLOR;
  if (row.other) return OTHER_COLOR;
  return base;
}

/** Path of a bar with a 4px rounded data-end and a square baseline. */
export function barPath(x: number, y: number, w: number, h: number, horizontal: boolean, r = 4): string {
  if (w <= 0 || h <= 0) return '';
  if (horizontal) {
    const rr = Math.min(r, w, h / 2);
    return `M${x},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + h - rr}Q${x + w},${y + h} ${x + w - rr},${y + h}H${x}Z`;
  }
  const rr = Math.min(r, h, w / 2);
  return `M${x},${y + h}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + h}Z`;
}

/** Pointer position relative to the chart container. */
export function pointerIn(e: { clientX: number; clientY: number }, el: HTMLElement | null): { x: number; y: number } {
  const box = el?.getBoundingClientRect();
  return { x: e.clientX - (box?.left ?? 0), y: e.clientY - (box?.top ?? 0) };
}
