/**
 * Distinguished Name helpers (RFC 4514).
 */

export interface Ava {
  type: string;
  value: string;
}

export type Rdn = Ava[];

export class DnParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DnParseError';
  }
}

const HEX = /^[0-9a-fA-F]{2}$/;

/** Parse a DN string into RDNs (most specific first). Throws DnParseError on malformed input. */
export function parseDn(dn: string): Rdn[] {
  const input = dn.trim();
  if (input === '') return [];

  const rdns: Rdn[] = [];
  let current: Rdn = [];
  let i = 0;

  const skipSpaces = () => {
    while (i < input.length && input[i] === ' ') i++;
  };

  while (i < input.length) {
    skipSpaces();
    // attribute type
    let type = '';
    while (i < input.length && input[i] !== '=') {
      const c = input[i];
      if (c === ',' || c === '+' || c === ';') throw new DnParseError(`Missing "=" in DN component near position ${i}`);
      type += c;
      i++;
    }
    if (i >= input.length) throw new DnParseError('Missing "=" in DN component');
    type = type.trim();
    if (!/^([A-Za-z][A-Za-z0-9-]*|\d+(\.\d+)*)$/.test(type)) {
      throw new DnParseError(`Invalid attribute type "${type}" in DN`);
    }
    i++; // skip '='
    skipSpaces();

    // attribute value
    let value = '';
    if (input[i] === '"') {
      i++;
      while (i < input.length && input[i] !== '"') {
        if (input[i] === '\\' && i + 1 < input.length) {
          value += input[i + 1];
          i += 2;
        } else {
          value += input[i++];
        }
      }
      if (input[i] !== '"') throw new DnParseError('Unterminated quoted value in DN');
      i++;
      skipSpaces();
    } else {
      let trailingSpaces = 0;
      const bytes: number[] = [];
      const flushBytes = () => {
        if (bytes.length) {
          value += new TextDecoder().decode(new Uint8Array(bytes));
          bytes.length = 0;
        }
      };
      while (i < input.length) {
        const c = input[i];
        if (c === ',' || c === '+' || c === ';') break;
        if (c === '\\') {
          const next2 = input.slice(i + 1, i + 3);
          if (HEX.test(next2)) {
            bytes.push(parseInt(next2, 16));
            i += 3;
            trailingSpaces = 0;
            continue;
          }
          flushBytes();
          if (i + 1 >= input.length) throw new DnParseError('Dangling escape at end of DN');
          value += input[i + 1];
          i += 2;
          trailingSpaces = 0;
          continue;
        }
        flushBytes();
        value += c;
        trailingSpaces = c === ' ' ? trailingSpaces + 1 : 0;
        i++;
      }
      flushBytes();
      if (trailingSpaces > 0) value = value.slice(0, value.length - trailingSpaces);
    }

    current.push({ type, value });

    if (i < input.length) {
      const sep = input[i];
      i++;
      if (sep === '+') continue;
      rdns.push(current);
      current = [];
      if (i >= input.length) throw new DnParseError('DN ends with a separator');
    }
  }
  if (current.length) rdns.push(current);
  return rdns;
}

/** Escape an attribute value for use inside a DN (RFC 4514 section 2.4). */
export function escapeDnValue(value: string): string {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const c = value[i];
    if (c === '\0') {
      out += '\\00';
    } else if (',+"\\<>;='.includes(c)) {
      out += '\\' + c;
    } else if ((i === 0 && (c === ' ' || c === '#')) || (i === value.length - 1 && c === ' ')) {
      out += '\\' + c;
    } else {
      out += c;
    }
  }
  return out;
}

export function formatRdn(rdn: Rdn): string {
  return rdn.map((a) => `${a.type}=${escapeDnValue(a.value)}`).join('+');
}

export function formatDn(rdns: Rdn[]): string {
  return rdns.map(formatRdn).join(',');
}

function normalizeAva(a: Ava): Ava {
  return { type: a.type.toLowerCase(), value: a.value.trim().replace(/\s+/g, ' ').toLowerCase() };
}

/** Canonical, case-insensitive representation used for comparisons. */
export function normalizeDn(dn: string): string {
  return formatDn(
    parseDn(dn).map((rdn) =>
      rdn
        .map(normalizeAva)
        .sort((a, b) => (a.type === b.type ? a.value.localeCompare(b.value) : a.type.localeCompare(b.type))),
    ),
  );
}

export function isValidDn(dn: string): boolean {
  try {
    return parseDn(dn).length > 0;
  } catch {
    return false;
  }
}

export function dnEquals(a: string, b: string): boolean {
  try {
    return normalizeDn(a) === normalizeDn(b);
  } catch {
    return false;
  }
}

/**
 * True when `dn` equals `base` or lives beneath it.
 * With `strict`, `dn` must be a proper descendant.
 */
export function isDnUnder(dn: string, base: string, strict = false): boolean {
  try {
    const d = parseDn(dn).map((r) => formatRdn(r.map(normalizeAva)));
    const b = parseDn(base).map((r) => formatRdn(r.map(normalizeAva)));
    if (b.length === 0) return strict ? d.length > 0 : true; // root DSE contains everything
    if (d.length < b.length || (strict && d.length === b.length)) return false;
    const offset = d.length - b.length;
    return b.every((rdn, idx) => rdn === d[offset + idx]);
  } catch {
    return false;
  }
}

/** Number of RDNs between `dn` and `base` (0 = same). -1 if not under. */
export function dnDepthBelow(dn: string, base: string): number {
  if (!isDnUnder(dn, base)) return -1;
  return parseDn(dn).length - parseDn(base).length;
}

export function parentDn(dn: string): string {
  const rdns = parseDn(dn);
  return formatDn(rdns.slice(1));
}

export function rdnOf(dn: string): Rdn {
  const rdns = parseDn(dn);
  if (!rdns.length) throw new DnParseError('Empty DN has no RDN');
  return rdns[0];
}

/** First value of the RDN, handy for display ("cn=Jane Doe,ou=people" -> "Jane Doe"). */
export function rdnValue(dn: string): string {
  try {
    return rdnOf(dn)[0].value;
  } catch {
    return dn;
  }
}

export function buildDn(rdnAttribute: string, rdnValueStr: string, parent: string): string {
  const rdn = `${rdnAttribute}=${escapeDnValue(rdnValueStr)}`;
  return parent.trim() ? `${rdn},${parent.trim()}` : rdn;
}
