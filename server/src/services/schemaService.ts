import type { AttributeTypeDef, ObjectClassDef, SchemaSnapshot } from '@dsp/shared';
import { mergeSchema, SchemaIndex } from '@dsp/shared';
import type { Directory } from '../ldap/directory';
import { parseSchemaText } from '../ldap/schemaParser';
import type { ConfigStore } from '../store/configStore';
import { badRequest, notFound } from '../errors';

const OID_OR_DESCR = /^([0-9]+(\.[0-9]+)+|[A-Za-z][A-Za-z0-9-]*)$/;
const DESCR = /^[A-Za-z][A-Za-z0-9-]*$/;

function keyClash<T extends { oid: string; names: string[] }>(list: T[], item: T): number {
  const keys = new Set([item.oid.toLowerCase(), ...item.names.map((n) => n.toLowerCase())]);
  return list.findIndex((x) => keys.has(x.oid.toLowerCase()) || x.names.some((n) => keys.has(n.toLowerCase())));
}

export class SchemaService {
  private merged?: { schema: SchemaSnapshot; index: SchemaIndex; stamp: unknown };

  constructor(
    private readonly directory: Directory,
    private readonly store: ConfigStore,
  ) {}

  /** Pull the schema from the directory's subschema subentry and cache it. */
  async refresh(): Promise<SchemaSnapshot> {
    const schema = await this.directory.readSchema();
    await this.store.update((d) => {
      d.schemaCache = schema;
    });
    return schema;
  }

  async serverSchema(): Promise<SchemaSnapshot | undefined> {
    if (!this.store.snapshot.schemaCache) {
      try {
        await this.refresh();
      } catch (err) {
        console.warn('[portal] could not read schema from directory:', (err as Error).message);
      }
    }
    return this.store.snapshot.schemaCache;
  }

  /** Server schema with custom definitions layered on top. Cached until the store changes. */
  async mergedIndex(): Promise<SchemaIndex> {
    await this.serverSchema();
    const snap = this.store.snapshot;
    if (this.merged?.stamp !== snap) {
      const schema = mergeSchema(snap.schemaCache, snap.customSchema);
      this.merged = { schema, index: new SchemaIndex(schema), stamp: snap };
    }
    return this.merged.index;
  }

  custom() {
    return this.store.snapshot.customSchema;
  }

  private validateElement(item: { oid: string; names: string[] }) {
    if (!OID_OR_DESCR.test(item.oid)) throw badRequest(`Invalid OID "${item.oid}"`);
    if (!item.names.length) throw badRequest('At least one NAME is required');
    for (const n of item.names) if (!DESCR.test(n)) throw badRequest(`Invalid name "${n}"`);
  }

  async upsertAttributeType(def: AttributeTypeDef, originalOid?: string): Promise<AttributeTypeDef> {
    this.validateElement(def);
    const item: AttributeTypeDef = { ...def, source: 'custom' };
    return this.store.update((d) => {
      const list = d.customSchema.attributeTypes;
      if (originalOid) {
        const i = list.findIndex((x) => x.oid === originalOid);
        if (i >= 0) list.splice(i, 1);
      }
      const clash = keyClash(list, item);
      if (clash >= 0) list[clash] = item;
      else list.push(item);
      return item;
    });
  }

  async upsertObjectClass(def: ObjectClassDef, originalOid?: string): Promise<ObjectClassDef> {
    this.validateElement(def);
    for (const a of [...def.must, ...def.may, ...def.sup]) {
      if (!OID_OR_DESCR.test(a)) throw badRequest(`Invalid attribute or class reference "${a}"`);
    }
    const item: ObjectClassDef = { ...def, source: 'custom' };
    return this.store.update((d) => {
      const list = d.customSchema.objectClasses;
      if (originalOid) {
        const i = list.findIndex((x) => x.oid === originalOid);
        if (i >= 0) list.splice(i, 1);
      }
      const clash = keyClash(list, item);
      if (clash >= 0) list[clash] = item;
      else list.push(item);
      return item;
    });
  }

  async deleteCustom(kind: 'attributeTypes' | 'objectClasses', oid: string): Promise<void> {
    await this.store.update((d) => {
      const list = d.customSchema[kind] as { oid: string }[];
      const i = list.findIndex((x) => x.oid === oid);
      if (i < 0) throw notFound('Custom schema element not found');
      list.splice(i, 1);
    });
  }

  /** Import schema text (OpenLDAP .schema, LDIF, or raw descriptions) as custom definitions. */
  async importText(text: string): Promise<{ attributeTypes: number; objectClasses: number; errors: string[] }> {
    const parsed = parseSchemaText(text, 'custom');
    if (!parsed.attributeTypes.length && !parsed.objectClasses.length) {
      throw badRequest(parsed.errors[0] ?? 'No attribute types or object classes found in the text');
    }
    await this.store.update((d) => {
      for (const at of parsed.attributeTypes) {
        const i = keyClash(d.customSchema.attributeTypes, at);
        if (i >= 0) d.customSchema.attributeTypes[i] = at;
        else d.customSchema.attributeTypes.push(at);
      }
      for (const oc of parsed.objectClasses) {
        const i = keyClash(d.customSchema.objectClasses, oc);
        if (i >= 0) d.customSchema.objectClasses[i] = oc;
        else d.customSchema.objectClasses.push(oc);
      }
    });
    return {
      attributeTypes: parsed.attributeTypes.length,
      objectClasses: parsed.objectClasses.length,
      errors: parsed.errors,
    };
  }
}
