import type {
  AttributeTypeDef,
  FormDefinition,
  ObjectClassDef,
  SchemaSnapshot,
} from './types';

/** Fast, case-insensitive lookups over a schema snapshot. */
export class SchemaIndex {
  private readonly attrs = new Map<string, AttributeTypeDef>();
  private readonly classes = new Map<string, ObjectClassDef>();

  constructor(readonly schema: SchemaSnapshot) {
    for (const a of schema.attributeTypes) {
      this.attrs.set(a.oid.toLowerCase(), a);
      for (const n of a.names) this.attrs.set(n.toLowerCase(), a);
    }
    for (const c of schema.objectClasses) {
      this.classes.set(c.oid.toLowerCase(), c);
      for (const n of c.names) this.classes.set(n.toLowerCase(), c);
    }
  }

  attribute(name: string): AttributeTypeDef | undefined {
    return this.attrs.get(name.toLowerCase());
  }

  objectClass(name: string): ObjectClassDef | undefined {
    return this.classes.get(name.toLowerCase());
  }

  /** Primary name for an attribute (resolves aliases such as "surname" -> "sn"). */
  primaryAttributeName(name: string): string {
    return this.attribute(name)?.names[0] ?? name;
  }

  /** Follow SUP chains so inherited properties (e.g. SINGLE-VALUE via SUP) are honoured. */
  isSingleValue(name: string): boolean {
    const seen = new Set<string>();
    let at = this.attribute(name);
    while (at && !seen.has(at.oid)) {
      if (at.singleValue) return true;
      seen.add(at.oid);
      at = at.sup ? this.attribute(at.sup) : undefined;
    }
    return false;
  }

  /** Object classes including all superclasses (deduplicated, input order first). */
  expandClasses(names: string[]): ObjectClassDef[] {
    const out: ObjectClassDef[] = [];
    const seen = new Set<string>();
    const visit = (n: string) => {
      const c = this.objectClass(n);
      if (!c || seen.has(c.oid)) return;
      seen.add(c.oid);
      out.push(c);
      c.sup.forEach(visit);
    };
    names.forEach(visit);
    return out;
  }

  /** MUST and MAY attributes for a set of object classes (primary names, deduplicated). */
  classAttributes(names: string[]): { must: string[]; may: string[] } {
    const classes = this.expandClasses(names);
    const must = new Map<string, string>();
    const may = new Map<string, string>();
    for (const c of classes) {
      for (const a of c.must) {
        const p = this.primaryAttributeName(a);
        must.set(p.toLowerCase(), p);
      }
    }
    for (const c of classes) {
      for (const a of c.may) {
        const p = this.primaryAttributeName(a);
        if (!must.has(p.toLowerCase())) may.set(p.toLowerCase(), p);
      }
    }
    must.delete('objectclass');
    return { must: [...must.values()], may: [...may.values()] };
  }

  unknownClasses(names: string[]): string[] {
    return names.filter((n) => !this.objectClass(n));
  }
}

/** Merge custom schema elements over the server schema; custom wins on OID or name clash. */
export function mergeSchema(server: SchemaSnapshot | undefined, custom: { attributeTypes: AttributeTypeDef[]; objectClasses: ObjectClassDef[] }): SchemaSnapshot {
  const mergeList = <T extends { oid: string; names: string[] }>(base: T[], over: T[]): T[] => {
    const keys = (x: T) => [x.oid.toLowerCase(), ...x.names.map((n) => n.toLowerCase())];
    const overKeys = new Set(over.flatMap(keys));
    return [...base.filter((b) => !keys(b).some((k) => overKeys.has(k))), ...over];
  };
  return {
    attributeTypes: mergeList(server?.attributeTypes ?? [], custom.attributeTypes),
    objectClasses: mergeList(server?.objectClasses ?? [], custom.objectClasses),
    fetchedAt: server?.fetchedAt,
    subschemaDn: server?.subschemaDn,
  };
}

export interface FormLint {
  errors: string[];
  warnings: string[];
}

/** Structural checks for a form definition, optionally against a schema. */
export function lintForm(form: Pick<FormDefinition, 'fields' | 'objectClasses' | 'rdnAttribute' | 'name'>, index?: SchemaIndex): FormLint {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!form.name?.trim()) errors.push('Form name is required');
  if (!form.objectClasses.length) errors.push('At least one object class is required');
  if (!form.fields.length) errors.push('At least one field is required');

  const seen = new Set<string>();
  for (const f of form.fields) {
    const key = f.attribute.toLowerCase();
    if (!f.attribute.trim()) errors.push('Every field needs an attribute');
    if (seen.has(key)) errors.push(`Attribute "${f.attribute}" is used by more than one field`);
    seen.add(key);
    if (!f.label.trim()) errors.push(`Field "${f.attribute}" needs a label`);
    if (f.widget === 'dropdown') {
      if (!f.dropdown) errors.push(`Drop down "${f.label}" has no option source`);
      else if (f.dropdown.type === 'static' && !f.dropdown.options.length && !f.allowCustomValues) {
        errors.push(`Drop down "${f.label}" has no options`);
      } else if (f.dropdown.type === 'ldap') {
        if (!f.dropdown.baseDn.trim()) errors.push(`Drop down "${f.label}" needs a search base`);
        if (!f.dropdown.valueAttribute.trim()) errors.push(`Drop down "${f.label}" needs a value attribute`);
      }
    }
    if (f.pattern) {
      try {
        new RegExp(f.pattern);
      } catch {
        errors.push(`Field "${f.label}" has an invalid pattern`);
      }
    }
    if (index) {
      const at = index.attribute(f.attribute);
      if (!at) warnings.push(`Attribute "${f.attribute}" is not defined in the schema`);
      else {
        if (f.multiValued && index.isSingleValue(f.attribute)) {
          errors.push(`"${f.attribute}" is SINGLE-VALUE in the schema and cannot be multi-valued`);
        }
        if (at.noUserModification && !f.readOnly) {
          warnings.push(`"${f.attribute}" is NO-USER-MODIFICATION; consider making the field read only`);
        }
      }
    }
  }
  if (!form.rdnAttribute.trim()) errors.push('A naming (RDN) attribute is required');
  else if (!form.fields.some((f) => f.attribute.toLowerCase() === form.rdnAttribute.toLowerCase())) {
    errors.push(`The naming attribute "${form.rdnAttribute}" must be one of the form fields`);
  }

  if (index && form.objectClasses.length) {
    const unknown = index.unknownClasses(form.objectClasses);
    if (unknown.length) warnings.push(`Unknown object class(es): ${unknown.join(', ')}`);
    const classes = index.expandClasses(form.objectClasses);
    if (classes.length && !classes.some((c) => c.kind === 'STRUCTURAL')) {
      warnings.push('No STRUCTURAL object class selected; the directory will reject new entries');
    }
    const { must, may } = index.classAttributes(form.objectClasses);
    const missing = must.filter((m) => !seen.has(m.toLowerCase()) && !form.fields.some((f) => index.primaryAttributeName(f.attribute).toLowerCase() === m.toLowerCase()));
    if (missing.length) warnings.push(`Required by the object classes but not in the form: ${missing.join(', ')}`);
    const allowed = new Set([...must, ...may].map((a) => a.toLowerCase()));
    const extensible = classes.some((c) => c.names.some((n) => n.toLowerCase() === 'extensibleobject'));
    if (!extensible) {
      for (const f of form.fields) {
        const p = index.primaryAttributeName(f.attribute).toLowerCase();
        if (index.attribute(f.attribute) && !allowed.has(p)) {
          warnings.push(`"${f.attribute}" is not allowed by the selected object classes`);
        }
      }
    }
  }
  return { errors, warnings };
}
