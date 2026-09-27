/**
 * Types shared between the portal server and web client.
 */

// ---------------------------------------------------------------------------
// LDAP schema
// ---------------------------------------------------------------------------

/** Where a schema element came from. */
export type SchemaSource = 'server' | 'custom';

export interface AttributeTypeDef {
  oid: string;
  /** All NAMEs of the attribute type; the first is the primary name. */
  names: string[];
  desc?: string;
  sup?: string;
  equality?: string;
  ordering?: string;
  substr?: string;
  syntax?: string;
  singleValue: boolean;
  collective?: boolean;
  noUserModification: boolean;
  obsolete?: boolean;
  usage?: 'userApplications' | 'directoryOperation' | 'distributedOperation' | 'dSAOperation';
  source: SchemaSource;
  /** Portal-only display hint, never sent to the directory. */
  displayName?: string;
}

export type ObjectClassKind = 'STRUCTURAL' | 'AUXILIARY' | 'ABSTRACT';

export interface ObjectClassDef {
  oid: string;
  names: string[];
  desc?: string;
  sup: string[];
  kind: ObjectClassKind;
  must: string[];
  may: string[];
  obsolete?: boolean;
  source: SchemaSource;
  displayName?: string;
}

export interface SchemaSnapshot {
  attributeTypes: AttributeTypeDef[];
  objectClasses: ObjectClassDef[];
  /** ISO timestamp of the last successful pull from the directory, if any. */
  fetchedAt?: string;
  /** DN of the subschema subentry the server schema was read from. */
  subschemaDn?: string;
}

/** Portal-side overrides/extensions layered on top of the server schema. */
export interface CustomSchema {
  attributeTypes: AttributeTypeDef[];
  objectClasses: ObjectClassDef[];
}

// ---------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------

/** Free text (single line or multi-line) or drop down. */
export type FieldWidget = 'text' | 'textarea' | 'dropdown';

export type TextFormat = 'plain' | 'email' | 'phone' | 'url' | 'number' | 'dn';

export interface OptionItem {
  value: string;
  label?: string;
}

export type DropdownSource =
  | { type: 'static'; options: OptionItem[] }
  | {
      type: 'ldap';
      /** Search base; must be a valid DN. */
      baseDn: string;
      scope: 'one' | 'sub';
      filter: string;
      /** Attribute whose value is stored ("dn" means the entry DN). */
      valueAttribute: string;
      /** Attribute shown to the user. */
      labelAttribute: string;
    };

export interface FormField {
  id: string;
  /** LDAP attribute name. */
  attribute: string;
  label: string;
  helpText?: string;
  widget: FieldWidget;
  /** Whether the attribute accepts multiple values in this form. */
  multiValued: boolean;
  required: boolean;
  /** Displayed but never written through this form. */
  readOnly: boolean;
  /** When true, a user may edit this field on their own entry from the "My profile" page. */
  selfEditable: boolean;
  /** Free-text validation. */
  format?: TextFormat;
  pattern?: string;
  maxLength?: number;
  placeholder?: string;
  /** Drop-down configuration (required when widget === 'dropdown'). */
  dropdown?: DropdownSource;
  /** For drop downs: permit values that are not in the option list. */
  allowCustomValues?: boolean;
  /** Optional section heading used to group fields visually. */
  section?: string;
  defaultValues?: string[];
}

export interface FormDefinition {
  id: string;
  name: string;
  description?: string;
  /** Object classes written to new entries. */
  objectClasses: string[];
  /** Attribute used as the RDN when creating entries (must be a field). */
  rdnAttribute: string;
  fields: FormField[];
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// Directory definitions
// ---------------------------------------------------------------------------

export type DefinitionMode = 'readwrite' | 'readonly';

export type AccessLevel = 'none' | 'read' | 'write';

/**
 * How to locate the logged-in user's entry inside a definition.
 *  - `dn`: the entry DN equals the user's DN.
 *  - `attribute`: entry[entryAttribute] equals user[userAttribute]
 *    (userAttribute "dn" means the user's DN, which is useful for member/owner style links).
 */
export type SelfMatch =
  | { type: 'none' }
  | { type: 'dn' }
  | { type: 'attribute'; entryAttribute: string; userAttribute: string };

export interface DirectoryDefinition {
  id: string;
  name: string;
  slug: string;
  description?: string;
  formId: string;
  /** Namespace this definition is scoped to. All operations are confined below it. */
  baseDn: string;
  scope: 'one' | 'sub';
  /** Extra LDAP filter ANDed with the form's object classes. */
  filter?: string;
  /** `readonly` definitions act as white pages: nobody can write through them. */
  mode: DefinitionMode;
  /** Grants read access to every authenticated user (typical for white pages). */
  everyoneCanRead: boolean;
  /** Columns shown in list views. */
  listAttributes: string[];
  /** Attributes matched by the quick search box. */
  searchAttributes: string[];
  /** Attribute used as the entry's display title. */
  titleAttribute: string;
  /** Allowed parent containers for new entries (defaults to baseDn). */
  createContainers: string[];
  selfMatch: SelfMatch;
  icon?: string;
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

export type SubjectType = 'user' | 'group';

export interface PermissionGrant {
  id: string;
  definitionId: string;
  subjectType: SubjectType;
  /** Username for users, group DN for groups. */
  subject: string;
  /** Friendly name for display only. */
  subjectLabel?: string;
  access: Exclude<AccessLevel, 'none'>;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Directory entries & sessions
// ---------------------------------------------------------------------------

/** Attribute names are lower-cased; values are always arrays of strings. */
export type EntryAttributes = Record<string, string[]>;

export interface DirectoryEntry {
  dn: string;
  attributes: EntryAttributes;
}

export interface EntryListResponse {
  entries: DirectoryEntry[];
  total: number;
  page: number;
  pageSize: number;
  /** True when the directory size limit was hit and results are incomplete. */
  truncated: boolean;
}

export interface SessionUser {
  username: string;
  dn: string;
  displayName: string;
  mail?: string;
  groups: string[];
  isAdmin: boolean;
  /** How the user signed in. */
  authMethod?: 'password' | 'x509';
  /** Subject DN of the client certificate, for certificate sign-ins. */
  certificateSubject?: string;
}

/** Definition as seen by a specific user, including their effective access. */
export interface DefinitionSummary extends DirectoryDefinition {
  access: AccessLevel;
  formName?: string;
}

export interface SelfEntryResult {
  definition: DefinitionSummary;
  form: FormDefinition;
  entries: DirectoryEntry[];
  /** Fields the user may edit on their own entry. */
  editableFields: string[];
  error?: string;
}

export interface ServerInfo {
  mode: 'ldap' | 'memory';
  ldapUrl?: string;
  baseDn: string;
  connected: boolean;
  error?: string;
  version: string;
  /** Sign-in methods offered by the server. */
  auth?: { password: boolean; x509: boolean; x509AutoLogin: boolean };
}

export interface SubjectSearchResult {
  type: SubjectType;
  id: string;
  label: string;
  dn: string;
}

export interface ApiError {
  error: string;
  details?: Record<string, string>;
}
