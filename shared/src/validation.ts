import type { EntryAttributes, FormDefinition, FormField, OptionItem } from './types';
import { isValidDn } from './dn';

export type ValidationMode = 'create' | 'update' | 'self';

export interface ValidationOptions {
  mode: ValidationMode;
  /**
   * Allowed option values for LDAP-backed drop downs keyed by field id.
   * When omitted, LDAP-backed option membership is not checked.
   */
  resolvedOptions?: Record<string, OptionItem[]>;
}

export interface ValidationResult {
  /** Values to write, keyed by lower-cased attribute name. Only writable fields are present. */
  values: EntryAttributes;
  errors: Record<string, string>;
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE = /^\+?[0-9 ()./-]{3,}(\s*(x|ext\.?)\s*\d+)?$/i;
const NUMBER = /^-?\d+(\.\d+)?$/;

export function normalizeValues(raw: unknown): string[] {
  if (raw === undefined || raw === null) return [];
  const list = Array.isArray(raw) ? raw : [raw];
  const out: string[] = [];
  for (const v of list) {
    if (v === undefined || v === null) continue;
    const s = String(v).trim();
    if (s !== '' && !out.includes(s)) out.push(s);
  }
  return out;
}

/** Whether a field can be written in the given mode. */
export function isFieldWritable(form: FormDefinition, field: FormField, mode: ValidationMode): boolean {
  const isRdn = field.attribute.toLowerCase() === form.rdnAttribute.toLowerCase();
  if (mode === 'create') return !field.readOnly || isRdn;
  if (field.readOnly) return false;
  if (mode === 'self') return field.selfEditable;
  return true;
}

export function staticOptions(field: FormField): OptionItem[] | undefined {
  return field.dropdown?.type === 'static' ? field.dropdown.options : undefined;
}

export function validateFieldValues(
  field: FormField,
  values: string[],
  resolvedOptions?: OptionItem[],
): string | undefined {
  if (field.required && values.length === 0) return `${field.label} is required`;
  if (!field.multiValued && values.length > 1) return `${field.label} accepts a single value`;

  for (const v of values) {
    if (field.maxLength && v.length > field.maxLength) {
      return `${field.label} must be at most ${field.maxLength} characters`;
    }
    if (field.widget === 'dropdown') {
      const options = staticOptions(field) ?? resolvedOptions;
      if (options && !field.allowCustomValues && !options.some((o) => o.value === v)) {
        return `"${v}" is not an allowed value for ${field.label}`;
      }
      continue;
    }
    switch (field.format) {
      case 'email':
        if (!EMAIL.test(v)) return `"${v}" is not a valid e-mail address`;
        break;
      case 'phone':
        if (!PHONE.test(v)) return `"${v}" is not a valid phone number`;
        break;
      case 'number':
        if (!NUMBER.test(v)) return `"${v}" is not a number`;
        break;
      case 'url':
        try {
          new URL(v);
        } catch {
          return `"${v}" is not a valid URL`;
        }
        break;
      case 'dn':
        if (!isValidDn(v)) return `"${v}" is not a valid distinguished name`;
        break;
      default:
        break;
    }
    if (field.pattern) {
      let re: RegExp;
      try {
        re = new RegExp(`^(?:${field.pattern})$`);
      } catch {
        return `${field.label} has an invalid validation pattern`;
      }
      if (!re.test(v)) return `"${v}" does not match the required format for ${field.label}`;
    }
  }
  return undefined;
}

/**
 * Validate submitted values against a form. Unknown attributes and non-writable fields
 * are dropped. For updates, only attributes present in `input` are validated/returned,
 * which lets clients send partial updates.
 */
export function validateFormValues(
  form: FormDefinition,
  input: Record<string, unknown>,
  options: ValidationOptions,
): ValidationResult {
  const byAttr = new Map<string, unknown>();
  for (const [k, v] of Object.entries(input ?? {})) byAttr.set(k.toLowerCase(), v);

  const values: EntryAttributes = {};
  const errors: Record<string, string> = {};

  for (const field of form.fields) {
    const key = field.attribute.toLowerCase();
    if (!isFieldWritable(form, field, options.mode)) continue;
    const present = byAttr.has(key);
    if (!present && options.mode !== 'create') continue;

    const vals = normalizeValues(byAttr.get(key));
    const isRdn = key === form.rdnAttribute.toLowerCase();
    if (isRdn && options.mode === 'create' && vals.length === 0) {
      errors[key] = `${field.label} is required (it names the entry)`;
      continue;
    }
    if (isRdn && vals.length === 0 && present) {
      errors[key] = `${field.label} cannot be empty (it names the entry)`;
      continue;
    }
    const err = validateFieldValues(field, vals, options.resolvedOptions?.[field.id]);
    if (err) {
      errors[key] = err;
      continue;
    }
    values[key] = vals;
  }
  return { values, errors };
}

/** Case-insensitive attribute lookup on an entry. */
export function getAttr(attrs: EntryAttributes | undefined, name: string): string[] {
  if (!attrs) return [];
  return attrs[name.toLowerCase()] ?? [];
}

export function firstAttr(attrs: EntryAttributes | undefined, name: string): string | undefined {
  return getAttr(attrs, name)[0];
}
