/**
 * RFC 4515 search filter parser and evaluator. Used to validate admin-supplied filters
 * and by the in-memory directory to evaluate searches.
 */

export type FilterNode =
  | { type: 'and'; filters: FilterNode[] }
  | { type: 'or'; filters: FilterNode[] }
  | { type: 'not'; filter: FilterNode }
  | { type: 'equality' | 'approx' | 'gte' | 'lte'; attribute: string; value: string }
  | { type: 'present'; attribute: string }
  | { type: 'substring'; attribute: string; initial?: string; any: string[]; final?: string };

export class FilterParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FilterParseError';
  }
}

function unescapeValue(raw: string): string {
  const bytes: number[] = [];
  const enc = new TextEncoder();
  for (let i = 0; i < raw.length; i++) {
    if (raw[i] === '\\') {
      const hex = raw.slice(i + 1, i + 3);
      if (!/^[0-9a-fA-F]{2}$/.test(hex)) throw new FilterParseError(`Invalid escape sequence "\\${hex}"`);
      bytes.push(parseInt(hex, 16));
      i += 2;
    } else {
      bytes.push(...enc.encode(raw[i]));
    }
  }
  return new TextDecoder().decode(new Uint8Array(bytes));
}

export function parseFilter(input: string): FilterNode {
  let s = input.trim();
  if (!s) throw new FilterParseError('Filter is empty');
  if (!s.startsWith('(')) s = `(${s})`;
  let pos = 0;

  const parseOne = (): FilterNode => {
    if (s[pos] !== '(') throw new FilterParseError(`Expected "(" at position ${pos}`);
    pos++;
    const c = s[pos];
    let node: FilterNode;
    if (c === '&' || c === '|') {
      pos++;
      const filters: FilterNode[] = [];
      while (s[pos] === '(') filters.push(parseOne());
      node = c === '&' ? { type: 'and', filters } : { type: 'or', filters };
    } else if (c === '!') {
      pos++;
      node = { type: 'not', filter: parseOne() };
    } else {
      let end = pos;
      while (end < s.length && s[end] !== ')') {
        if (s[end] === '(') throw new FilterParseError(`Unexpected "(" at position ${end}`);
        end++;
      }
      if (end >= s.length) throw new FilterParseError('Unbalanced parentheses in filter');
      node = parseItem(s.slice(pos, end));
      pos = end;
    }
    if (s[pos] !== ')') throw new FilterParseError(`Expected ")" at position ${pos}`);
    pos++;
    return node;
  };

  const node = parseOne();
  if (pos !== s.length) throw new FilterParseError(`Unexpected trailing characters at position ${pos}`);
  return node;
}

function parseItem(item: string): FilterNode {
  const m = /^([A-Za-z0-9][A-Za-z0-9.;-]*)(~=|>=|<=|=)(.*)$/s.exec(item);
  if (!m) throw new FilterParseError(`Invalid filter component "(${item})"`);
  const [, attribute, op, rawValue] = m;
  if (op === '=') {
    if (rawValue === '*') return { type: 'present', attribute };
    if (rawValue.includes('*')) {
      const parts = rawValue.split('*');
      const initial = parts[0] ? unescapeValue(parts[0]) : undefined;
      const final = parts[parts.length - 1] ? unescapeValue(parts[parts.length - 1]) : undefined;
      const any = parts.slice(1, -1).filter(Boolean).map(unescapeValue);
      return { type: 'substring', attribute, initial, any, final };
    }
    return { type: 'equality', attribute, value: unescapeValue(rawValue) };
  }
  const type = op === '~=' ? 'approx' : op === '>=' ? 'gte' : 'lte';
  return { type, attribute, value: unescapeValue(rawValue) };
}

export function isValidFilter(input: string): boolean {
  try {
    parseFilter(input);
    return true;
  } catch {
    return false;
  }
}

/** Returns all values for an attribute; `resolve` maps aliases (e.g. "surname" -> "sn"). */
export type AttributeLookup = (attribute: string) => string[];

function norm(v: string): string {
  return v.trim().replace(/\s+/g, ' ').toLowerCase();
}

function compare(a: string, b: string): number {
  const na = Number(a);
  const nb = Number(b);
  if (a.trim() !== '' && b.trim() !== '' && !Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return norm(a).localeCompare(norm(b));
}

export function evaluateFilter(node: FilterNode, lookup: AttributeLookup): boolean {
  switch (node.type) {
    case 'and':
      return node.filters.every((f) => evaluateFilter(f, lookup));
    case 'or':
      return node.filters.some((f) => evaluateFilter(f, lookup));
    case 'not':
      return !evaluateFilter(node.filter, lookup);
    case 'present':
      return lookup(node.attribute).length > 0;
    case 'equality':
    case 'approx': {
      const target = norm(node.value);
      return lookup(node.attribute).some((v) => norm(v) === target);
    }
    case 'gte':
      return lookup(node.attribute).some((v) => compare(v, node.value) >= 0);
    case 'lte':
      return lookup(node.attribute).some((v) => compare(v, node.value) <= 0);
    case 'substring':
      return lookup(node.attribute).some((raw) => {
        let v = norm(raw);
        if (node.initial) {
          const i = norm(node.initial);
          if (!v.startsWith(i)) return false;
          v = v.slice(i.length);
        }
        for (const part of node.any) {
          const p = norm(part);
          const idx = v.indexOf(p);
          if (idx < 0) return false;
          v = v.slice(idx + p.length);
        }
        if (node.final) return v.endsWith(norm(node.final));
        return true;
      });
  }
}
