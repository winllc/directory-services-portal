import { z } from 'zod';
import { isValidDn } from '@dsp/shared';
import { isValidFilter } from '../ldap/filter';

export const attrName = z
  .string()
  .trim()
  .min(1)
  .max(128)
  .regex(/^([A-Za-z][A-Za-z0-9-]*|\d+(\.\d+)+)(;[A-Za-z0-9-]+)*$/, 'Invalid attribute name');

export const dnString = z.string().trim().min(1).max(1024).refine(isValidDn, 'Invalid DN');
export const filterString = z.string().trim().max(4096).refine((f) => f === '' || isValidFilter(f), 'Invalid LDAP filter');

const optionItem = z.object({ value: z.string().trim().min(1).max(1024), label: z.string().trim().max(256).optional() });

const dropdown = z.discriminatedUnion('type', [
  z.object({ type: z.literal('static'), options: z.array(optionItem).max(1000) }),
  z.object({
    type: z.literal('ldap'),
    baseDn: dnString,
    scope: z.enum(['one', 'sub']),
    filter: filterString,
    valueAttribute: z.union([z.literal('dn'), attrName]),
    labelAttribute: attrName,
  }),
]);

export const formFieldSchema = z.object({
  id: z.string().trim().min(1).max(64),
  attribute: attrName,
  label: z.string().trim().min(1).max(128),
  helpText: z.string().max(500).optional(),
  widget: z.enum(['text', 'textarea', 'dropdown']),
  multiValued: z.boolean(),
  required: z.boolean(),
  readOnly: z.boolean(),
  selfEditable: z.boolean(),
  format: z.enum(['plain', 'email', 'phone', 'url', 'number', 'dn']).optional(),
  pattern: z.string().max(500).optional(),
  maxLength: z.number().int().positive().max(65536).optional(),
  placeholder: z.string().max(200).optional(),
  dropdown: dropdown.optional(),
  allowCustomValues: z.boolean().optional(),
  section: z.string().max(100).optional(),
  defaultValues: z.array(z.string().max(1024)).max(100).optional(),
});

export const formInputSchema = z.object({
  name: z.string().trim().min(1).max(128),
  description: z.string().max(1000).optional(),
  objectClasses: z.array(attrName).min(1).max(50),
  rdnAttribute: attrName,
  fields: z.array(formFieldSchema).min(1).max(200),
});

const selfMatch = z.discriminatedUnion('type', [
  z.object({ type: z.literal('none') }),
  z.object({ type: z.literal('dn') }),
  z.object({
    type: z.literal('attribute'),
    entryAttribute: attrName,
    userAttribute: z.union([z.literal('dn'), attrName]),
  }),
]);

export const definitionInputSchema = z.object({
  name: z.string().trim().min(1).max(128),
  slug: z
    .string()
    .trim()
    .min(1)
    .max(64)
    .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Use lower-case letters, digits and dashes'),
  description: z.string().max(1000).optional(),
  formId: z.string().min(1),
  baseDn: dnString,
  scope: z.enum(['one', 'sub']),
  filter: filterString.optional(),
  mode: z.enum(['readwrite', 'readonly']),
  everyoneCanRead: z.boolean(),
  listAttributes: z.array(attrName).min(1).max(20),
  searchAttributes: z.array(attrName).max(20),
  titleAttribute: attrName,
  createContainers: z.array(dnString).max(50),
  selfMatch,
  icon: z.string().max(32).optional(),
});

export const grantInputSchema = z.object({
  definitionId: z.string().min(1),
  subjectType: z.enum(['user', 'group']),
  subject: z.string().trim().min(1).max(1024),
  subjectLabel: z.string().max(256).optional(),
  access: z.enum(['read', 'write']),
});

const oid = z
  .string()
  .trim()
  .regex(/^([0-9]+(\.[0-9]+)+|[A-Za-z][A-Za-z0-9-]*)$/, 'Invalid OID');
const descr = z
  .string()
  .trim()
  .regex(/^[A-Za-z][A-Za-z0-9-]*$/, 'Invalid name');

export const attributeTypeSchema = z.object({
  oid,
  names: z.array(descr).min(1).max(10),
  desc: z.string().max(1000).optional(),
  sup: z.string().optional(),
  equality: z.string().optional(),
  ordering: z.string().optional(),
  substr: z.string().optional(),
  syntax: z.string().optional(),
  singleValue: z.boolean(),
  collective: z.boolean().optional(),
  noUserModification: z.boolean(),
  obsolete: z.boolean().optional(),
  usage: z.enum(['userApplications', 'directoryOperation', 'distributedOperation', 'dSAOperation']).optional(),
  displayName: z.string().max(128).optional(),
});

export const objectClassSchema = z.object({
  oid,
  names: z.array(descr).min(1).max(10),
  desc: z.string().max(1000).optional(),
  sup: z.array(oid).max(10),
  kind: z.enum(['STRUCTURAL', 'AUXILIARY', 'ABSTRACT']),
  must: z.array(oid).max(200),
  may: z.array(oid).max(500),
  obsolete: z.boolean().optional(),
  displayName: z.string().max(128).optional(),
});

export const listQuerySchema = z.object({
  q: z.string().max(256).optional(),
  page: z.coerce.number().int().min(1).max(10000).optional(),
  pageSize: z.coerce.number().int().min(1).max(200).optional(),
  sort: z.union([z.literal('dn'), attrName]).optional(),
  order: z.enum(['asc', 'desc']).optional(),
});

export const valuesBody = z.record(z.string(), z.union([z.string(), z.array(z.string()), z.null()]));
