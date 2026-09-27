import type {
  AccessLevel,
  DirectoryDefinition,
  DirectoryEntry,
  EntryAttributes,
  EntryListResponse,
  FormDefinition,
  FormField,
  OptionItem,
  SelfEntryResult,
  SessionUser,
} from '@dsp/shared';
import {
  buildDn,
  dnDepthBelow,
  dnEquals,
  escapeFilterValue,
  firstAttr,
  getAttr,
  isDnUnder,
  isValidDn,
  parseDn,
  rdnOf,
  validateFormValues,
  type ValidationMode,
} from '@dsp/shared';
import type { Directory, ModifyChange } from '../ldap/directory';
import type { ConfigStore } from '../store/configStore';
import { badRequest, forbidden, HttpError, notFound } from '../errors';
import { effectiveAccess, hasAccess } from './permissions';
import type { SchemaService } from './schemaService';

/** Attributes that are never returned to clients. */
const HIDDEN_ATTRIBUTES = new Set(['userpassword', 'unicodepwd', 'sambantpassword', 'sambalmpassword', 'krbprincipalkey']);
const LIST_LIMIT = 1000;
const OPTIONS_LIMIT = 500;
const OPTIONS_TTL_MS = 30_000;

export interface ListQuery {
  q?: string;
  page?: number;
  pageSize?: number;
  sort?: string;
  order?: 'asc' | 'desc';
}

function sanitize(entry: DirectoryEntry): DirectoryEntry {
  const attributes: EntryAttributes = {};
  for (const [k, v] of Object.entries(entry.attributes)) {
    if (!HIDDEN_ATTRIBUTES.has(k.toLowerCase())) attributes[k.toLowerCase()] = v;
  }
  return { dn: entry.dn, attributes };
}

const sameSet = (a: string[], b: string[]) =>
  a.length === b.length && a.every((x) => b.some((y) => y === x));

export class EntryService {
  private readonly optionCache = new Map<string, { at: number; options: OptionItem[] }>();

  constructor(
    private readonly directory: Directory,
    private readonly store: ConfigStore,
    private readonly schema: SchemaService,
  ) {}

  // ---------------------------------------------------------------------------
  // Lookups & access
  // ---------------------------------------------------------------------------

  definition(idOrSlug: string): DirectoryDefinition {
    const def = this.store.snapshot.definitions.find((d) => d.id === idOrSlug || d.slug === idOrSlug);
    if (!def) throw notFound('Directory definition not found');
    return def;
  }

  form(def: DirectoryDefinition): FormDefinition {
    const form = this.store.snapshot.forms.find((f) => f.id === def.formId);
    if (!form) throw new HttpError(500, `Definition "${def.name}" references a missing form`);
    return form;
  }

  access(user: SessionUser, def: DirectoryDefinition): AccessLevel {
    return effectiveAccess(user, def, this.store.snapshot.grants);
  }

  require(user: SessionUser, def: DirectoryDefinition, level: AccessLevel): void {
    const actual = this.access(user, def);
    if (!hasAccess(actual, level)) {
      if (level === 'write' && def.mode === 'readonly') throw forbidden('This directory is read only');
      throw forbidden(level === 'write' ? 'You do not have write access to this directory' : 'You do not have access to this directory');
    }
  }

  /** The LDAP filter selecting entries that belong to a definition. */
  async baseFilter(def: DirectoryDefinition, form: FormDefinition): Promise<string> {
    if (def.filter?.trim()) {
      const f = def.filter.trim();
      return f.startsWith('(') ? f : `(${f})`;
    }
    const index = await this.schema.mergedIndex();
    // Auxiliary classes are optional on existing entries; select by the structural/abstract ones.
    const classes = form.objectClasses.filter((c) => {
      if (c.toLowerCase() === 'top') return false;
      return index.objectClass(c)?.kind !== 'AUXILIARY';
    });
    if (!classes.length) return '(objectClass=*)';
    const parts = classes.map((c) => `(objectClass=${escapeFilterValue(c)})`);
    return parts.length === 1 ? parts[0] : `(&${parts.join('')})`;
  }

  private assertInNamespace(def: DirectoryDefinition, dn: string): void {
    if (!isValidDn(dn)) throw badRequest('Invalid DN');
    const depth = dnDepthBelow(dn, def.baseDn);
    if (depth < 1 || (def.scope === 'one' && depth !== 1)) {
      throw notFound('Entry is outside of this directory');
    }
  }

  private returnAttributes(def: DirectoryDefinition, form: FormDefinition, extra: string[] = []): string[] {
    const set = new Map<string, string>();
    for (const a of ['objectClass', def.titleAttribute, ...def.listAttributes, ...form.fields.map((f) => f.attribute), ...extra]) {
      if (a) set.set(a.toLowerCase(), a);
    }
    return [...set.values()];
  }

  /** Read an entry that belongs to the definition (namespace + filter), without access checks. */
  private async loadEntry(def: DirectoryDefinition, form: FormDefinition, dn: string): Promise<DirectoryEntry> {
    this.assertInNamespace(def, dn);
    const filter = await this.baseFilter(def, form);
    try {
      const { entries } = await this.directory.search({
        base: dn,
        scope: 'base',
        filter,
        attributes: this.returnAttributes(def, form),
      });
      if (!entries[0]) throw notFound('Entry not found in this directory');
      return sanitize(entries[0]);
    } catch (err) {
      if ((err as { code?: string }).code === 'NO_SUCH_OBJECT') throw notFound('Entry not found');
      throw err;
    }
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  async list(user: SessionUser, def: DirectoryDefinition, query: ListQuery): Promise<EntryListResponse> {
    this.require(user, def, 'read');
    const form = this.form(def);
    const base = await this.baseFilter(def, form);
    let filter = base;
    const q = query.q?.trim();
    if (q) {
      const attrs = def.searchAttributes.length ? def.searchAttributes : [def.titleAttribute];
      const terms = q.split(/\s+/).slice(0, 5).map(escapeFilterValue);
      // Each term must match at least one searchable attribute.
      const termFilters = terms.map((t) => `(|${attrs.map((a) => `(${a}=*${t}*)`).join('')})`);
      filter = `(&${base}${termFilters.join('')})`;
    }
    const { entries, truncated } = await this.directory.search({
      base: def.baseDn,
      scope: def.scope,
      filter,
      attributes: [...new Set(['objectClass', def.titleAttribute, ...def.listAttributes])],
      limit: LIST_LIMIT,
    });

    const sortAttr = (query.sort || def.titleAttribute).toLowerCase();
    const dir = query.order === 'desc' ? -1 : 1;
    const key = (e: DirectoryEntry) => (sortAttr === 'dn' ? e.dn : (getAttr(e.attributes, sortAttr)[0] ?? '')).toLowerCase();
    const sorted = entries
      .filter((e) => !dnEquals(e.dn, def.baseDn))
      .map(sanitize)
      .sort((a, b) => key(a).localeCompare(key(b)) * dir);

    const pageSize = Math.min(Math.max(query.pageSize ?? 25, 1), 200);
    const page = Math.max(query.page ?? 1, 1);
    return {
      entries: sorted.slice((page - 1) * pageSize, page * pageSize),
      total: sorted.length,
      page,
      pageSize,
      truncated,
    };
  }

  async get(user: SessionUser, def: DirectoryDefinition, dn: string): Promise<DirectoryEntry> {
    this.require(user, def, 'read');
    return this.loadEntry(def, this.form(def), dn);
  }

  // ---------------------------------------------------------------------------
  // Drop down options
  // ---------------------------------------------------------------------------

  async fieldOptions(field: FormField): Promise<OptionItem[]> {
    const src = field.dropdown;
    if (!src) return [];
    if (src.type === 'static') return src.options;
    const cacheKey = JSON.stringify(src);
    const cached = this.optionCache.get(cacheKey);
    if (cached && Date.now() - cached.at < OPTIONS_TTL_MS) return cached.options;

    const valueIsDn = src.valueAttribute.toLowerCase() === 'dn';
    const { entries } = await this.directory.search({
      base: src.baseDn,
      scope: src.scope,
      filter: src.filter || '(objectClass=*)',
      attributes: [...new Set([src.labelAttribute, valueIsDn ? '1.1' : src.valueAttribute].filter(Boolean))],
      limit: OPTIONS_LIMIT,
    });
    const options: OptionItem[] = [];
    for (const e of entries) {
      const label = firstAttr(e.attributes, src.labelAttribute);
      const values = valueIsDn ? [e.dn] : getAttr(e.attributes, src.valueAttribute);
      for (const value of values) {
        if (!options.some((o) => o.value === value)) options.push({ value, label: label ?? value });
      }
    }
    options.sort((a, b) => (a.label ?? a.value).localeCompare(b.label ?? b.value));
    this.optionCache.set(cacheKey, { at: Date.now(), options });
    return options;
  }

  async options(user: SessionUser, def: DirectoryDefinition, fieldId: string): Promise<OptionItem[]> {
    const canRead = hasAccess(this.access(user, def), 'read') || def.selfMatch.type !== 'none';
    if (!canRead) throw forbidden();
    const field = this.form(def).fields.find((f) => f.id === fieldId);
    if (!field) throw notFound('Field not found');
    return this.fieldOptions(field);
  }

  private async resolveOptions(form: FormDefinition, fieldKeys: string[]): Promise<Record<string, OptionItem[]>> {
    const out: Record<string, OptionItem[]> = {};
    for (const f of form.fields) {
      if (f.widget !== 'dropdown' || f.dropdown?.type !== 'ldap' || f.allowCustomValues) continue;
      if (!fieldKeys.includes(f.attribute.toLowerCase())) continue;
      out[f.id] = await this.fieldOptions(f);
    }
    return out;
  }

  private async validate(form: FormDefinition, values: Record<string, unknown>, mode: ValidationMode) {
    const keys = Object.keys(values ?? {}).map((k) => k.toLowerCase());
    const resolvedOptions = await this.resolveOptions(form, mode === 'create' ? form.fields.map((f) => f.attribute.toLowerCase()) : keys);
    const result = validateFormValues(form, values ?? {}, { mode, resolvedOptions });
    if (Object.keys(result.errors).length) throw badRequest('Some fields are invalid', result.errors);
    return result.values;
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  async create(
    user: SessionUser,
    def: DirectoryDefinition,
    input: { parentDn?: string; values: Record<string, unknown> },
  ): Promise<DirectoryEntry> {
    this.require(user, def, 'write');
    const form = this.form(def);
    const parent = input.parentDn?.trim() || def.createContainers[0] || def.baseDn;
    const allowed = def.createContainers.length ? def.createContainers : [def.baseDn];
    if (!allowed.some((c) => dnEquals(c, parent))) throw badRequest('New entries cannot be created in that container');
    if (!isDnUnder(parent, def.baseDn)) throw badRequest('Container is outside of this directory');
    if (def.scope === 'one' && !dnEquals(parent, def.baseDn)) {
      throw badRequest('This directory only contains direct children of its base DN');
    }

    const values = await this.validate(form, input.values, 'create');
    const rdnKey = form.rdnAttribute.toLowerCase();
    const rdnVal = values[rdnKey]?.[0];
    if (!rdnVal) throw badRequest('Missing naming attribute', { [rdnKey]: 'Required' });

    const dn = buildDn(form.rdnAttribute, rdnVal, parent);
    const attributes: EntryAttributes = { objectClass: [...form.objectClasses] };
    for (const field of form.fields) {
      const v = values[field.attribute.toLowerCase()];
      if (v?.length) attributes[field.attribute] = v;
    }
    await this.directory.add(dn, attributes);
    return this.loadEntry(def, form, dn);
  }

  async update(
    user: SessionUser,
    def: DirectoryDefinition,
    dn: string,
    input: Record<string, unknown>,
    mode: 'update' | 'self' = 'update',
  ): Promise<DirectoryEntry> {
    if (def.mode === 'readonly') throw forbidden('This directory is read only');
    if (mode === 'update') this.require(user, def, 'write');
    const form = this.form(def);
    const current = await this.loadEntry(def, form, dn);
    if (mode === 'self') await this.assertSelf(user, def, form, current.dn);

    const values = await this.validate(form, input, mode);
    const index = await this.schema.mergedIndex();

    // Rename first if the naming attribute's value changed.
    let targetDn = current.dn;
    const rdnKey = form.rdnAttribute.toLowerCase();
    const currentRdn = rdnOf(current.dn);
    const rdnAva = currentRdn.find((a) => index.primaryAttributeName(a.type).toLowerCase() === index.primaryAttributeName(form.rdnAttribute).toLowerCase());
    const newRdnValues = values[rdnKey];
    if (rdnAva && newRdnValues && !newRdnValues.some((v) => v.toLowerCase() === rdnAva.value.toLowerCase())) {
      if (currentRdn.length > 1) throw badRequest('Entries with multi-valued RDNs cannot be renamed here');
      targetDn = await this.directory.rename(current.dn, buildDn(rdnAva.type, newRdnValues[0], '').replace(/,$/, ''));
    }

    const changes: ModifyChange[] = [];
    // Ensure the entry carries all of the form's object classes (e.g. auxiliary extensions).
    const currentClasses = getAttr(current.attributes, 'objectClass').map((c) => c.toLowerCase());
    const missingClasses = form.objectClasses.filter((c) => !currentClasses.includes(c.toLowerCase()));
    const writesSomething = Object.entries(values).some(([k, v]) => !sameSet(v, getAttr(current.attributes, k)));
    if (missingClasses.length && writesSomething) {
      changes.push({ operation: 'add', attribute: 'objectClass', values: missingClasses });
    }
    for (const [key, next] of Object.entries(values)) {
      const field = form.fields.find((f) => f.attribute.toLowerCase() === key)!;
      const prev = getAttr(current.attributes, key);
      if (sameSet(prev, next)) continue;
      if (key === rdnKey && targetDn !== current.dn) {
        // Rename already updated the RDN value; replace to capture any extra values.
        changes.push({ operation: 'replace', attribute: field.attribute, values: next });
        continue;
      }
      if (next.length === 0) {
        if (prev.length) changes.push({ operation: 'delete', attribute: field.attribute, values: [] });
      } else {
        changes.push({ operation: 'replace', attribute: field.attribute, values: next });
      }
    }
    if (changes.length) await this.directory.modify(targetDn, changes);
    return this.loadEntry(def, form, targetDn);
  }

  async remove(user: SessionUser, def: DirectoryDefinition, dn: string): Promise<void> {
    this.require(user, def, 'write');
    const form = this.form(def);
    const current = await this.loadEntry(def, form, dn);
    await this.directory.delete(current.dn);
  }

  // ---------------------------------------------------------------------------
  // Self service
  // ---------------------------------------------------------------------------

  private async userAttributeValues(user: SessionUser, attribute: string): Promise<string[]> {
    if (attribute.toLowerCase() === 'dn') return [user.dn];
    const entry = await this.directory.get(user.dn, [attribute]);
    return getAttr(entry?.attributes, attribute);
  }

  /** Find the entries in a definition that represent the given user. */
  async findSelf(user: SessionUser, def: DirectoryDefinition, form: FormDefinition): Promise<DirectoryEntry[]> {
    const match = def.selfMatch;
    if (match.type === 'none') return [];
    if (match.type === 'dn') {
      if (dnDepthBelow(user.dn, def.baseDn) < 1) return [];
      try {
        return [await this.loadEntry(def, form, user.dn)];
      } catch (err) {
        if (err instanceof HttpError && err.status === 404) return [];
        throw err;
      }
    }
    const values = await this.userAttributeValues(user, match.userAttribute);
    if (!values.length) return [];
    const base = await this.baseFilter(def, form);
    const ors = values.slice(0, 20).map((v) => `(${match.entryAttribute}=${escapeFilterValue(v)})`).join('');
    const { entries } = await this.directory.search({
      base: def.baseDn,
      scope: def.scope,
      filter: `(&${base}(|${ors}))`,
      attributes: this.returnAttributes(def, form),
      limit: 50,
    });
    return entries.filter((e) => !dnEquals(e.dn, def.baseDn)).map(sanitize);
  }

  private async assertSelf(user: SessionUser, def: DirectoryDefinition, form: FormDefinition, dn: string): Promise<void> {
    const mine = await this.findSelf(user, def, form);
    if (!mine.some((e) => dnEquals(e.dn, dn))) throw forbidden('You can only edit your own entry');
  }

  async selfOverview(user: SessionUser, summaries: (def: DirectoryDefinition) => SelfEntryResult['definition']): Promise<SelfEntryResult[]> {
    const results: SelfEntryResult[] = [];
    for (const def of this.store.snapshot.definitions) {
      if (def.selfMatch.type === 'none') continue;
      const form = this.store.snapshot.forms.find((f) => f.id === def.formId);
      if (!form) continue;
      const editableFields =
        def.mode === 'readwrite' ? form.fields.filter((f) => f.selfEditable && !f.readOnly).map((f) => f.attribute) : [];
      try {
        const entries = await this.findSelf(user, def, form);
        results.push({ definition: summaries(def), form, entries, editableFields });
      } catch (err) {
        results.push({ definition: summaries(def), form, entries: [], editableFields, error: (err as Error).message });
      }
    }
    return results;
  }
}

/** Validate a definition's structural fields (used by the admin API). */
export function assertDefinitionShape(def: DirectoryDefinition): void {
  if (!isValidDn(def.baseDn)) throw badRequest('Base DN is not a valid DN', { baseDn: 'Invalid DN' });
  for (const c of def.createContainers) {
    if (!isValidDn(c)) throw badRequest(`Container "${c}" is not a valid DN`, { createContainers: 'Invalid DN' });
    if (!isDnUnder(c, def.baseDn)) {
      throw badRequest(`Container "${c}" is outside the base DN`, { createContainers: 'Must be within the base DN' });
    }
    if (def.scope === 'one' && !dnEquals(c, def.baseDn)) {
      throw badRequest('With one-level scope, entries can only be created directly under the base DN', { createContainers: 'Must equal the base DN' });
    }
  }
  parseDn(def.baseDn);
}
