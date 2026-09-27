/**
 * Parser/serializer for LDAP schema descriptions (RFC 4512 section 4.1).
 */
import type { AttributeTypeDef, ObjectClassDef, ObjectClassKind, SchemaSource } from '@dsp/shared';

export class SchemaParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SchemaParseError';
  }
}

type Token = { kind: 'lparen' | 'rparen' | 'dollar' | 'word' | 'string'; value: string };

function tokenize(input: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < input.length) {
    const c = input[i];
    if (/\s/.test(c)) {
      i++;
    } else if (c === '(') {
      tokens.push({ kind: 'lparen', value: c });
      i++;
    } else if (c === ')') {
      tokens.push({ kind: 'rparen', value: c });
      i++;
    } else if (c === '$') {
      tokens.push({ kind: 'dollar', value: c });
      i++;
    } else if (c === "'") {
      let j = i + 1;
      let value = '';
      while (j < input.length && input[j] !== "'") {
        // RFC 4512 escapes: \27 = ', \5C = \
        if (input[j] === '\\' && /^[0-9a-fA-F]{2}$/.test(input.slice(j + 1, j + 3))) {
          value += String.fromCharCode(parseInt(input.slice(j + 1, j + 3), 16));
          j += 3;
        } else {
          value += input[j++];
        }
      }
      if (j >= input.length) throw new SchemaParseError('Unterminated quoted string');
      tokens.push({ kind: 'string', value });
      i = j + 1;
    } else {
      let j = i;
      while (j < input.length && !/[\s()$']/.test(input[j])) j++;
      tokens.push({ kind: 'word', value: input.slice(i, j) });
      i = j;
    }
  }
  return tokens;
}

class Reader {
  private pos = 0;
  constructor(private readonly tokens: Token[]) {}

  peek(): Token | undefined {
    return this.tokens[this.pos];
  }

  next(): Token {
    const t = this.tokens[this.pos++];
    if (!t) throw new SchemaParseError('Unexpected end of schema description');
    return t;
  }

  expect(kind: Token['kind']): Token {
    const t = this.next();
    if (t.kind !== kind) throw new SchemaParseError(`Expected ${kind} but found "${t.value}"`);
    return t;
  }

  /** oid | "(" oid *( "$" oid ) ")" -- also tolerates quoted / space separated lists. */
  oids(): string[] {
    const t = this.peek();
    if (!t) throw new SchemaParseError('Expected OID list');
    if (t.kind !== 'lparen') {
      this.next();
      return [t.value];
    }
    this.next();
    const out: string[] = [];
    for (;;) {
      const n = this.next();
      if (n.kind === 'rparen') break;
      if (n.kind === 'dollar') continue;
      out.push(n.value);
    }
    return out;
  }

  /** qdescrs / qdstrings: 'a' | ( 'a' 'b' ) */
  qstrings(): string[] {
    const t = this.peek();
    if (!t) throw new SchemaParseError('Expected quoted string list');
    if (t.kind !== 'lparen') {
      this.next();
      return [t.value];
    }
    this.next();
    const out: string[] = [];
    for (;;) {
      const n = this.next();
      if (n.kind === 'rparen') break;
      if (n.kind !== 'dollar') out.push(n.value);
    }
    return out;
  }

  /** Skip an extension value (qdstrings). */
  skipExtension(): void {
    const t = this.peek();
    if (t && (t.kind === 'string' || t.kind === 'lparen')) this.qstrings();
  }
}

function start(input: string): { reader: Reader; oid: string } {
  const reader = new Reader(tokenize(input));
  reader.expect('lparen');
  const oid = reader.next();
  if (oid.kind !== 'word' && oid.kind !== 'string') throw new SchemaParseError('Expected OID');
  return { reader, oid: oid.value };
}

export function parseAttributeType(input: string, source: SchemaSource = 'server'): AttributeTypeDef {
  const { reader, oid } = start(input);
  const def: AttributeTypeDef = { oid, names: [], singleValue: false, noUserModification: false, source };
  for (;;) {
    const t = reader.next();
    if (t.kind === 'rparen') break;
    const kw = t.value.toUpperCase();
    switch (kw) {
      case 'NAME':
        def.names = reader.qstrings();
        break;
      case 'DESC':
        def.desc = reader.next().value;
        break;
      case 'OBSOLETE':
        def.obsolete = true;
        break;
      case 'SUP':
        def.sup = reader.oids()[0];
        break;
      case 'EQUALITY':
        def.equality = reader.next().value;
        break;
      case 'ORDERING':
        def.ordering = reader.next().value;
        break;
      case 'SUBSTR':
        def.substr = reader.next().value;
        break;
      case 'SYNTAX':
        def.syntax = reader.next().value;
        break;
      case 'SINGLE-VALUE':
        def.singleValue = true;
        break;
      case 'COLLECTIVE':
        def.collective = true;
        break;
      case 'NO-USER-MODIFICATION':
        def.noUserModification = true;
        break;
      case 'USAGE':
        def.usage = reader.next().value as AttributeTypeDef['usage'];
        break;
      default:
        if (kw.startsWith('X-')) reader.skipExtension();
        // Unknown keywords without values are ignored for robustness.
        break;
    }
  }
  if (!def.names.length) def.names = [oid];
  return def;
}

export function parseObjectClass(input: string, source: SchemaSource = 'server'): ObjectClassDef {
  const { reader, oid } = start(input);
  const def: ObjectClassDef = { oid, names: [], sup: [], kind: 'STRUCTURAL', must: [], may: [], source };
  for (;;) {
    const t = reader.next();
    if (t.kind === 'rparen') break;
    const kw = t.value.toUpperCase();
    switch (kw) {
      case 'NAME':
        def.names = reader.qstrings();
        break;
      case 'DESC':
        def.desc = reader.next().value;
        break;
      case 'OBSOLETE':
        def.obsolete = true;
        break;
      case 'SUP':
        def.sup = reader.oids();
        break;
      case 'ABSTRACT':
      case 'STRUCTURAL':
      case 'AUXILIARY':
        def.kind = kw as ObjectClassKind;
        break;
      case 'MUST':
        def.must = reader.oids();
        break;
      case 'MAY':
        def.may = reader.oids();
        break;
      default:
        if (kw.startsWith('X-')) reader.skipExtension();
        break;
    }
  }
  if (!def.names.length) def.names = [oid];
  return def;
}

function qdescrs(names: string[]): string {
  const q = (s: string) => `'${s.replace(/\\/g, '\\5C').replace(/'/g, '\\27')}'`;
  return names.length === 1 ? q(names[0]) : `( ${names.map(q).join(' ')} )`;
}

function oidList(oids: string[]): string {
  return oids.length === 1 ? oids[0] : `( ${oids.join(' $ ')} )`;
}

export function formatAttributeType(def: AttributeTypeDef): string {
  const parts = ['(', def.oid, 'NAME', qdescrs(def.names)];
  if (def.desc) parts.push('DESC', qdescrs([def.desc]));
  if (def.obsolete) parts.push('OBSOLETE');
  if (def.sup) parts.push('SUP', def.sup);
  if (def.equality) parts.push('EQUALITY', def.equality);
  if (def.ordering) parts.push('ORDERING', def.ordering);
  if (def.substr) parts.push('SUBSTR', def.substr);
  if (def.syntax) parts.push('SYNTAX', def.syntax);
  if (def.singleValue) parts.push('SINGLE-VALUE');
  if (def.collective) parts.push('COLLECTIVE');
  if (def.noUserModification) parts.push('NO-USER-MODIFICATION');
  if (def.usage && def.usage !== 'userApplications') parts.push('USAGE', def.usage);
  parts.push(')');
  return parts.join(' ');
}

export function formatObjectClass(def: ObjectClassDef): string {
  const parts = ['(', def.oid, 'NAME', qdescrs(def.names)];
  if (def.desc) parts.push('DESC', qdescrs([def.desc]));
  if (def.obsolete) parts.push('OBSOLETE');
  if (def.sup.length) parts.push('SUP', oidList(def.sup));
  parts.push(def.kind);
  if (def.must.length) parts.push('MUST', oidList(def.must));
  if (def.may.length) parts.push('MAY', oidList(def.may));
  parts.push(')');
  return parts.join(' ');
}

export interface ParsedSchemaText {
  attributeTypes: AttributeTypeDef[];
  objectClasses: ObjectClassDef[];
  errors: string[];
}

/**
 * Parse free-form schema text such as an OpenLDAP .schema file, an LDIF schema
 * (olcAttributeTypes / olcObjectClasses / attributeTypes: / objectClasses:) or bare
 * "( ... )" descriptions labelled with attributetype / objectclass keywords.
 */
export function parseSchemaText(text: string, source: SchemaSource = 'custom'): ParsedSchemaText {
  const result: ParsedSchemaText = { attributeTypes: [], objectClasses: [], errors: [] };
  // Unfold LDIF continuation lines and strip comments.
  const unfolded = text
    .replace(/\r\n/g, '\n')
    .replace(/\n[ \t]/g, ' ')
    .split('\n')
    .filter((l) => !l.trim().startsWith('#'))
    .join('\n');

  const re =
    /(olcAttributeTypes|attributeTypes|attributetype|olcObjectClasses|objectClasses|objectclass)\s*:?\s*(\{\d+\})?\s*\(/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(unfolded))) {
    const openIdx = m.index + m[0].length - 1;
    // Find matching paren, respecting quotes.
    let depth = 0;
    let inQuote = false;
    let end = -1;
    for (let i = openIdx; i < unfolded.length; i++) {
      const c = unfolded[i];
      if (c === "'") inQuote = !inQuote;
      if (inQuote) continue;
      if (c === '(') depth++;
      if (c === ')') {
        depth--;
        if (depth === 0) {
          end = i;
          break;
        }
      }
    }
    if (end < 0) {
      result.errors.push(`Unbalanced parentheses in definition starting at offset ${m.index}`);
      break;
    }
    const body = unfolded.slice(openIdx, end + 1);
    const isAttr = /attribute/i.test(m[1]);
    try {
      if (isAttr) result.attributeTypes.push(parseAttributeType(body, source));
      else result.objectClasses.push(parseObjectClass(body, source));
    } catch (e) {
      result.errors.push(`${isAttr ? 'Attribute type' : 'Object class'}: ${(e as Error).message}`);
    }
    re.lastIndex = end + 1;
  }
  return result;
}
