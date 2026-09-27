/**
 * An in-memory directory server used for demos, development and tests.
 * It mimics the behaviour of a real LDAP server closely enough to exercise the
 * portal: scoped searches with RFC 4515 filters, schema checking (MUST/MAY,
 * SINGLE-VALUE, unknown attributes), naming rules, leaf-only deletes and binds.
 */
import type { AttributeTypeDef, DirectoryEntry, EntryAttributes, ObjectClassDef, SchemaSnapshot } from '@dsp/shared';
import { formatDn, isDnUnder, normalizeDn, parentDn, parseDn, dnEquals } from '@dsp/shared';
import {
  DirectoryError,
  type Directory,
  type ModifyChange,
  type SearchOptions,
  type SearchResult,
} from './directory';
import { evaluateFilter, FilterParseError, parseFilter } from './filter';
import { parseSchemaText } from './schemaParser';

interface StoredEntry {
  dn: string;
  attrs: Map<string, string[]>;
}

export interface MemorySeedEntry {
  dn: string;
  attributes: Record<string, string | string[]>;
}

export interface MemoryDirectoryOptions {
  schemaText: string;
  entries: MemorySeedEntry[];
  enforceSchema?: boolean;
}

const OPERATIONAL = new Set(['memberof', 'entrydn', 'createtimestamp', 'modifytimestamp']);

export class MemoryDirectory implements Directory {
  readonly mode = 'memory' as const;
  private readonly entries = new Map<string, StoredEntry>();
  private readonly attrTypes = new Map<string, AttributeTypeDef>();
  private readonly objectClasses = new Map<string, ObjectClassDef>();
  private readonly schema: SchemaSnapshot;
  private readonly enforceSchema: boolean;

  constructor(options: MemoryDirectoryOptions) {
    const parsed = parseSchemaText(options.schemaText, 'server');
    if (parsed.errors.length) throw new Error(`Invalid seed schema: ${parsed.errors.join('; ')}`);
    this.schema = {
      attributeTypes: parsed.attributeTypes,
      objectClasses: parsed.objectClasses,
      subschemaDn: 'cn=Subschema',
    };
    for (const at of parsed.attributeTypes) for (const n of at.names) this.attrTypes.set(n.toLowerCase(), at);
    for (const oc of parsed.objectClasses) for (const n of oc.names) this.objectClasses.set(n.toLowerCase(), oc);
    this.enforceSchema = options.enforceSchema ?? true;

    // Seed parents before children.
    const seed = [...options.entries].sort((a, b) => parseDn(a.dn).length - parseDn(b.dn).length);
    for (const e of seed) {
      const attrs: EntryAttributes = {};
      for (const [k, v] of Object.entries(e.attributes)) attrs[k] = Array.isArray(v) ? v : [v];
      this.addSync(e.dn, attrs, true);
    }
  }

  /** Canonical (lower-cased primary) name for an attribute type or alias. */
  private canonical(name: string): string {
    const at = this.attrTypes.get(name.toLowerCase());
    return (at ? at.names[0] : name).toLowerCase();
  }

  private key(dn: string): string {
    try {
      return normalizeDn(dn);
    } catch {
      throw new DirectoryError('INVALID_DN', `Invalid DN: ${dn}`);
    }
  }

  private lookupFn(entry: StoredEntry) {
    return (name: string): string[] => {
      const c = this.canonical(name);
      if (c === 'memberof') return this.memberOf(entry.dn);
      if (c === 'entrydn') return [entry.dn];
      return entry.attrs.get(c) ?? [];
    };
  }

  private memberOf(dn: string): string[] {
    const groups: string[] = [];
    for (const e of this.entries.values()) {
      const members = [...(e.attrs.get('member') ?? []), ...(e.attrs.get('uniquemember') ?? [])];
      if (members.some((m) => dnEquals(m, dn))) groups.push(e.dn);
    }
    return groups;
  }

  private toEntry(entry: StoredEntry, attributes?: string[]): DirectoryEntry {
    const wanted = attributes?.filter((a) => a !== '*' && a !== '+').map((a) => this.canonical(a));
    const all = !wanted?.length || attributes?.includes('*');
    const out: EntryAttributes = {};
    for (const [k, v] of entry.attrs) {
      if (all || wanted!.includes(k)) out[k] = [...v];
    }
    for (const op of OPERATIONAL) {
      if (wanted?.includes(op)) {
        const v = this.lookupFn(entry)(op);
        if (v.length) out[op] = v;
      }
    }
    return { dn: entry.dn, attributes: out };
  }

  async search(options: SearchOptions): Promise<SearchResult> {
    let filter;
    try {
      filter = parseFilter(options.filter || '(objectClass=*)');
    } catch (e) {
      throw new DirectoryError('INVALID_FILTER', (e as FilterParseError).message);
    }
    const baseKey = this.key(options.base);
    if (options.base.trim() !== '' && !this.entries.has(baseKey)) {
      throw new DirectoryError('NO_SUCH_OBJECT', 'The entry does not exist');
    }
    const baseDepth = parseDn(options.base).length;
    const limit = options.limit ?? 1000;
    const results: DirectoryEntry[] = [];
    let truncated = false;
    for (const entry of this.entries.values()) {
      const depth = parseDn(entry.dn).length;
      if (!isDnUnder(entry.dn, options.base)) continue;
      if (options.scope === 'base' && depth !== baseDepth) continue;
      if (options.scope === 'one' && depth !== baseDepth + 1) continue;
      if (!evaluateFilter(filter, this.lookupFn(entry))) continue;
      if (results.length >= limit) {
        truncated = true;
        break;
      }
      results.push(this.toEntry(entry, options.attributes));
    }
    return { entries: results, truncated };
  }

  async get(dn: string, attributes?: string[]): Promise<DirectoryEntry | null> {
    const e = this.entries.get(this.key(dn));
    return e ? this.toEntry(e, attributes) : null;
  }

  private collectClasses(names: string[]): ObjectClassDef[] {
    const out = new Map<string, ObjectClassDef>();
    const visit = (n: string) => {
      const oc = this.objectClasses.get(n.toLowerCase());
      if (!oc) throw new DirectoryError('SCHEMA_VIOLATION', `Unknown object class "${n}"`);
      if (out.has(oc.oid)) return;
      out.set(oc.oid, oc);
      oc.sup.forEach(visit);
    };
    names.forEach(visit);
    return [...out.values()];
  }

  private checkSchema(dn: string, attrs: Map<string, string[]>): void {
    if (!this.enforceSchema) return;
    const ocNames = attrs.get('objectclass') ?? [];
    if (!ocNames.length) throw new DirectoryError('SCHEMA_VIOLATION', 'objectClass attribute is required');
    const classes = this.collectClasses(ocNames);
    if (!classes.some((c) => c.kind === 'STRUCTURAL')) {
      throw new DirectoryError('SCHEMA_VIOLATION', 'No structural object class provided');
    }
    const extensible = classes.some((c) => c.names.some((n) => n.toLowerCase() === 'extensibleobject'));
    const must = new Set(classes.flatMap((c) => c.must.map((a) => this.canonical(a))));
    const may = new Set(classes.flatMap((c) => c.may.map((a) => this.canonical(a))));
    must.add('objectclass');
    for (const m of must) {
      if (!(attrs.get(m)?.length)) {
        const name = this.attrTypes.get(m)?.names[0] ?? m;
        throw new DirectoryError('SCHEMA_VIOLATION', `Object class violation: attribute "${name}" is required`);
      }
    }
    for (const [k, v] of attrs) {
      const at = this.attrTypes.get(k);
      if (!at) throw new DirectoryError('SCHEMA_VIOLATION', `Undefined attribute type "${k}"`);
      if (!extensible && !must.has(k) && !may.has(k)) {
        throw new DirectoryError('SCHEMA_VIOLATION', `Attribute "${at.names[0]}" is not allowed by the entry's object classes`);
      }
      if (at.singleValue && v.length > 1) {
        throw new DirectoryError('SCHEMA_VIOLATION', `Attribute "${at.names[0]}" is single-valued`);
      }
    }
    // The naming attribute values must be present in the entry.
    for (const ava of parseDn(dn)[0] ?? []) {
      const vals = attrs.get(this.canonical(ava.type)) ?? [];
      if (!vals.some((v) => v.toLowerCase() === ava.value.toLowerCase())) {
        throw new DirectoryError('SCHEMA_VIOLATION', `Naming attribute "${ava.type}" is missing its RDN value`);
      }
    }
  }

  private toMap(attributes: EntryAttributes): Map<string, string[]> {
    const map = new Map<string, string[]>();
    for (const [k, v] of Object.entries(attributes)) {
      const c = this.canonical(k);
      if (OPERATIONAL.has(c)) continue;
      const vals = [...(map.get(c) ?? [])];
      for (const x of v) if (x !== '' && !vals.some((y) => y.toLowerCase() === x.toLowerCase())) vals.push(x);
      if (vals.length) map.set(c, vals);
    }
    return map;
  }

  private addSync(dn: string, attributes: EntryAttributes, seeding = false): void {
    const key = this.key(dn);
    if (!key) throw new DirectoryError('INVALID_DN', 'Cannot add the root DSE');
    if (this.entries.has(key)) throw new DirectoryError('ALREADY_EXISTS', 'An entry with that name already exists');
    const parent = parentDn(dn);
    if (parent && !this.entries.has(this.key(parent)) && !seeding) {
      throw new DirectoryError('NO_SUCH_OBJECT', `Parent entry ${parent} does not exist`);
    }
    const attrs = this.toMap(attributes);
    // Like most servers, add missing RDN values automatically.
    for (const ava of parseDn(dn)[0]) {
      const c = this.canonical(ava.type);
      const vals = attrs.get(c) ?? [];
      if (!vals.some((v) => v.toLowerCase() === ava.value.toLowerCase())) attrs.set(c, [ava.value, ...vals]);
    }
    this.checkSchema(dn, attrs);
    this.entries.set(key, { dn: formatDn(parseDn(dn)), attrs });
  }

  async add(dn: string, attributes: EntryAttributes): Promise<void> {
    this.addSync(dn, attributes);
  }

  async modify(dn: string, changes: ModifyChange[]): Promise<void> {
    const key = this.key(dn);
    const entry = this.entries.get(key);
    if (!entry) throw new DirectoryError('NO_SUCH_OBJECT', 'The entry does not exist');
    const attrs = this.cloneAttrs(entry.attrs);
    for (const ch of changes) {
      const c = this.canonical(ch.attribute);
      if (OPERATIONAL.has(c)) throw new DirectoryError('SCHEMA_VIOLATION', `Attribute "${ch.attribute}" is read-only`);
      const current = attrs.get(c) ?? [];
      const values = ch.values.filter((v) => v !== '');
      if (ch.operation === 'replace') {
        if (values.length) attrs.set(c, [...new Set(values)]);
        else attrs.delete(c);
      } else if (ch.operation === 'add') {
        for (const v of values) {
          if (current.some((x) => x.toLowerCase() === v.toLowerCase())) {
            throw new DirectoryError('SCHEMA_VIOLATION', `Value "${v}" already exists for ${ch.attribute}`);
          }
          current.push(v);
        }
        attrs.set(c, current);
      } else {
        if (!values.length) attrs.delete(c);
        else {
          const next = current.filter((x) => !values.some((v) => v.toLowerCase() === x.toLowerCase()));
          if (next.length) attrs.set(c, next);
          else attrs.delete(c);
        }
      }
    }
    this.checkSchema(entry.dn, attrs);
    entry.attrs = attrs;
  }

  private cloneAttrs(attrs: Map<string, string[]>): Map<string, string[]> {
    return new Map([...attrs].map(([k, v]): [string, string[]] => [k, [...v]]));
  }

  private hasChildren(dn: string): boolean {
    for (const e of this.entries.values()) if (isDnUnder(e.dn, dn, true)) return true;
    return false;
  }

  async rename(dn: string, newRdn: string): Promise<string> {
    const key = this.key(dn);
    const entry = this.entries.get(key);
    if (!entry) throw new DirectoryError('NO_SUCH_OBJECT', 'The entry does not exist');
    if (this.hasChildren(dn)) throw new DirectoryError('NOT_ALLOWED_ON_NON_LEAF', 'The entry has children and cannot be renamed');
    const parent = parentDn(entry.dn);
    const newDn = parent ? `${newRdn},${parent}` : newRdn;
    const newKey = this.key(newDn);
    if (newKey !== key && this.entries.has(newKey)) {
      throw new DirectoryError('ALREADY_EXISTS', 'An entry with that name already exists');
    }
    const attrs = this.cloneAttrs(entry.attrs);
    // deleteOldRdn = true
    for (const ava of parseDn(entry.dn)[0]) {
      const c = this.canonical(ava.type);
      const next = (attrs.get(c) ?? []).filter((v) => v.toLowerCase() !== ava.value.toLowerCase());
      if (next.length) attrs.set(c, next);
      else attrs.delete(c);
    }
    for (const ava of parseDn(newDn)[0]) {
      const c = this.canonical(ava.type);
      const vals = attrs.get(c) ?? [];
      if (!vals.some((v) => v.toLowerCase() === ava.value.toLowerCase())) attrs.set(c, [ava.value, ...vals]);
    }
    this.checkSchema(newDn, attrs);
    this.entries.delete(key);
    const formatted = formatDn(parseDn(newDn));
    this.entries.set(newKey, { dn: formatted, attrs });
    return formatted;
  }

  async delete(dn: string): Promise<void> {
    const key = this.key(dn);
    if (!this.entries.has(key)) throw new DirectoryError('NO_SUCH_OBJECT', 'The entry does not exist');
    if (this.hasChildren(dn)) throw new DirectoryError('NOT_ALLOWED_ON_NON_LEAF', 'The entry has children and cannot be removed');
    this.entries.delete(key);
  }

  async authenticate(dn: string, password: string): Promise<boolean> {
    if (!dn || !password) return false;
    let key: string;
    try {
      key = normalizeDn(dn);
    } catch {
      return false;
    }
    const entry = this.entries.get(key);
    if (!entry) return false;
    return (entry.attrs.get('userpassword') ?? []).includes(password);
  }

  async readSchema(): Promise<SchemaSnapshot> {
    return structuredClone({ ...this.schema, fetchedAt: new Date().toISOString() });
  }

  async ping(): Promise<void> {}
}
